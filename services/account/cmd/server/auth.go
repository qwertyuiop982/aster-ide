package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/mail"
	"net/url"
	"regexp"
	"strings"
)

var pkcePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

func (a *app) rate(r *http.Request, kind string, limit int) error {
	ip := r.Header.Get("X-Real-IP")
	if ip == "" {
		ip, _, _ = net.SplitHostPort(r.RemoteAddr)
	}
	key := a.codeHash(kind + "|" + ip)
	var used int
	e := a.db.QueryRowContext(r.Context(), `INSERT INTO auth_limits(bucket,started_at,used) VALUES($1,clock_timestamp(),1) ON CONFLICT(bucket) DO UPDATE SET started_at=CASE WHEN auth_limits.started_at<clock_timestamp()-interval '1 hour' THEN clock_timestamp() ELSE auth_limits.started_at END, used=CASE WHEN auth_limits.started_at<clock_timestamp()-interval '1 hour' THEN 1 ELSE auth_limits.used+1 END RETURNING used`, key).Scan(&used)
	if e != nil {
		return e
	}
	if used > limit {
		return fail(429, "RATE_LIMITED", "请求过于频繁，请稍后重试。")
	}
	return nil
}
func identityAccount(ctx context.Context, tx *sql.Tx, provider, subject, name string) (int64, error) {
	if _, e := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, provider+":"+subject); e != nil {
		return 0, e
	}
	var id int64
	e := tx.QueryRowContext(ctx, `SELECT i.user_id FROM identities i JOIN users u ON u.id=i.user_id WHERE provider=$1 AND subject=$2 AND u.status='active'`, provider, subject).Scan(&id)
	if e == nil {
		return id, nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return 0, e
	}
	var exists bool
	if e = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM identities WHERE provider=$1 AND subject=$2)`, provider, subject).Scan(&exists); e != nil {
		return 0, e
	}
	if exists {
		return 0, fail(403, "ACCOUNT_DISABLED", "账户已停用。")
	}
	if e = tx.QueryRowContext(ctx, `INSERT INTO users(display_name) VALUES($1) RETURNING id`, name).Scan(&id); e != nil {
		return 0, e
	}
	if _, e = tx.ExecContext(ctx, `INSERT INTO identities(provider,subject,user_id) VALUES($1,$2,$3)`, provider, subject, id); e != nil {
		return 0, e
	}
	_, e = tx.ExecContext(ctx, `INSERT INTO ledger(user_id,kind,reference,delta_units) VALUES($1,'registration','welcome',1000000)`, id)
	return id, e
}
func session(ctx context.Context, tx *sql.Tx, id int64) (string, error) {
	t := randomToken()
	_, e := tx.ExecContext(ctx, `INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,clock_timestamp()+interval '30 days')`, digest(t), id)
	return t, e
}
func (a *app) emailStart(w http.ResponseWriter, r *http.Request) error {
	if !a.available()["email_login"] {
		return fail(503, "EMAIL_LOGIN_NOT_CONFIGURED", "邮箱登录尚未配置，请稍后再试。")
	}
	if e := a.rate(r, "email-start", 20); e != nil {
		return e
	}
	var in struct {
		Email string `json:"email"`
	}
	if e := decode(w, r, &in); e != nil {
		return e
	}
	in.Email = strings.ToLower(strings.TrimSpace(in.Email))
	address, e := mail.ParseAddress(in.Email)
	if e != nil || address.Address != in.Email || len(in.Email) > 254 {
		return fail(400, "INVALID_EMAIL", "请输入有效邮箱。")
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), `SELECT pg_advisory_xact_lock(hashtextextended($1,1))`, in.Email); e != nil {
		return e
	}
	var count int
	if e = tx.QueryRowContext(r.Context(), `SELECT count(*) FROM challenges WHERE email=$1 AND created_at>clock_timestamp()-interval '60 seconds'`, in.Email).Scan(&count); e != nil {
		return e
	}
	if count > 0 {
		return fail(429, "RESEND_COOLDOWN", "请在 60 秒后重发。")
	}
	if e = tx.QueryRowContext(r.Context(), `SELECT count(*) FROM challenges WHERE email=$1 AND created_at>clock_timestamp()-interval '1 day'`, in.Email).Scan(&count); e != nil {
		return e
	}
	if count >= 20 {
		return fail(429, "DAILY_EMAIL_LIMIT", "今日验证码发送次数已达上限。")
	}
	n, e := rand.Int(rand.Reader, big.NewInt(1000000))
	if e != nil {
		return e
	}
	code := fmt.Sprintf("%06d", n.Int64())
	id := randomToken()
	if _, e = tx.ExecContext(r.Context(), `UPDATE challenges SET consumed=true WHERE email=$1 AND NOT consumed`, in.Email); e != nil {
		return e
	}
	if _, e = tx.ExecContext(r.Context(), `INSERT INTO challenges(id,email,code_hash,expires_at) VALUES($1,$2,$3,clock_timestamp()+interval '5 minutes')`, id, in.Email, a.codeHash(id+"|"+code)); e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	var reply struct {
		ID string `json:"id"`
	}
	_, e = a.remoteJSON(r.Context(), "POST", "https://api.resend.com/emails", map[string]any{"from": a.cfg.emailFrom, "to": []string{in.Email}, "subject": "Aster 登录验证码", "text": "你的 Aster 登录验证码是 " + code + "。5 分钟内有效，请勿透露给他人。"}, map[string]string{"Authorization": "Bearer " + a.cfg.resendKey, "Idempotency-Key": "aster-login/" + id}, &reply)
	if e != nil {
		_, _ = a.db.ExecContext(context.Background(), `UPDATE challenges SET consumed=true WHERE id=$1`, id)
		return fail(503, "EMAIL_SEND_FAILED", "验证码发送失败，请稍后重试。")
	}
	writeJSON(w, 202, map[string]any{"challenge_id": id, "expires_in": 300, "resend_after": 60})
	return nil
}
func (a *app) emailVerify(w http.ResponseWriter, r *http.Request) error {
	if e := a.rate(r, "email-verify", 100); e != nil {
		return e
	}
	var in struct {
		ID   string `json:"challenge_id"`
		Code string `json:"code"`
	}
	if e := decode(w, r, &in); e != nil {
		return e
	}
	if len(in.ID) > 100 || len(in.Code) != 6 {
		return fail(400, "INVALID_CODE", "验证码无效或已过期。")
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var email, hash string
	var attempts int
	e = tx.QueryRowContext(r.Context(), `SELECT email,code_hash,attempts FROM challenges WHERE id=$1 AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE`, in.ID).Scan(&email, &hash, &attempts)
	if errors.Is(e, sql.ErrNoRows) {
		return fail(400, "INVALID_CODE", "验证码无效或已过期。")
	}
	if e != nil {
		return e
	}
	if attempts >= 5 {
		return fail(429, "CODE_ATTEMPTS_EXCEEDED", "验证码尝试次数已达上限，请重新获取。")
	}
	if !hmac.Equal([]byte(hash), []byte(a.codeHash(in.ID+"|"+in.Code))) {
		if _, e = tx.ExecContext(r.Context(), `UPDATE challenges SET attempts=attempts+1 WHERE id=$1`, in.ID); e != nil {
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
		return fail(400, "INVALID_CODE", "验证码无效或已过期。")
	}
	if _, e = tx.ExecContext(r.Context(), `UPDATE challenges SET consumed=true WHERE id=$1`, in.ID); e != nil {
		return e
	}
	id, e := identityAccount(r.Context(), tx, "email", email, email)
	if e != nil {
		return e
	}
	token, e := session(r.Context(), tx, id)
	if e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"access_token": token, "token_type": "Bearer", "expires_in": 2592000})
	return nil
}
func (a *app) githubStart(w http.ResponseWriter, r *http.Request) error {
	if !a.available()["github_login"] {
		return fail(503, "GITHUB_LOGIN_NOT_CONFIGURED", "GitHub 登录尚未配置。")
	}
	if e := a.rate(r, "github-start", 50); e != nil {
		return e
	}
	var in struct {
		Challenge string `json:"code_challenge"`
	}
	if e := decode(w, r, &in); e != nil {
		return e
	}
	if !pkcePattern.MatchString(in.Challenge) {
		return fail(400, "INVALID_PKCE", "登录参数无效。")
	}
	state, verifier := randomToken(), randomToken()
	_, e := a.db.ExecContext(r.Context(), `INSERT INTO oauth_transactions(state_hash,mobile_challenge,upstream_verifier,expires_at) VALUES($1,$2,$3,clock_timestamp()+interval '10 minutes')`, digest(state), in.Challenge, verifier)
	if e != nil {
		return e
	}
	q := url.Values{"client_id": {a.cfg.githubID}, "redirect_uri": {a.cfg.publicURL + "/api/v1/auth/github/callback"}, "state": {state}, "code_challenge": {challenge(verifier)}, "code_challenge_method": {"S256"}}
	writeJSON(w, 200, map[string]any{"authorize_url": "https://github.com/login/oauth/authorize?" + q.Encode()})
	return nil
}
func (a *app) githubCallback(w http.ResponseWriter, r *http.Request) error {
	if !a.available()["github_login"] {
		return fail(503, "GITHUB_LOGIN_NOT_CONFIGURED", "GitHub 登录尚未配置。")
	}
	state, code := r.URL.Query().Get("state"), r.URL.Query().Get("code")
	if state == "" || code == "" || len(state) > 100 || len(code) > 500 {
		return fail(400, "INVALID_OAUTH_CALLBACK", "GitHub 授权未完成，请重新登录。")
	}
	var verifier string
	e := a.db.QueryRowContext(r.Context(), `WITH candidate AS (SELECT state_hash,upstream_verifier FROM oauth_transactions WHERE state_hash=$1 AND expires_at>clock_timestamp() AND upstream_verifier<>'' AND NOT consumed FOR UPDATE) UPDATE oauth_transactions o SET upstream_verifier='' FROM candidate c WHERE o.state_hash=c.state_hash RETURNING c.upstream_verifier`, digest(state)).Scan(&verifier)
	// Capture the verifier before consuming the callback, atomically under a row lock.
	if e == nil && verifier == "" {
		return fail(400, "INVALID_OAUTH_STATE", "登录已过期，请重新开始。")
	}
	if errors.Is(e, sql.ErrNoRows) {
		return fail(400, "INVALID_OAUTH_STATE", "登录已过期，请重新开始。")
	}
	if e != nil {
		return e
	}
	var token struct {
		AccessToken string `json:"access_token"`
		Error       string `json:"error"`
	}
	_, e = a.remoteJSON(r.Context(), "POST", "https://github.com/login/oauth/access_token", map[string]string{"client_id": a.cfg.githubID, "client_secret": a.cfg.githubSecret, "code": code, "redirect_uri": a.cfg.publicURL + "/api/v1/auth/github/callback", "code_verifier": verifier}, nil, &token)
	if e != nil || token.AccessToken == "" || token.Error != "" {
		return fail(400, "GITHUB_AUTH_FAILED", "GitHub 授权未完成，请重新登录。")
	}
	var user struct {
		ID    int64  `json:"id"`
		Login string `json:"login"`
	}
	_, e = a.remoteJSON(r.Context(), "GET", "https://api.github.com/user", nil, map[string]string{"Authorization": "Bearer " + token.AccessToken, "X-GitHub-Api-Version": "2022-11-28"}, &user)
	if e != nil || user.ID <= 0 {
		return fail(502, "GITHUB_IDENTITY_FAILED", "暂时无法验证 GitHub 身份。")
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	id, e := identityAccount(r.Context(), tx, "github", fmt.Sprint(user.ID), user.Login)
	if e != nil {
		return e
	}
	ticket := randomToken()
	_, e = tx.ExecContext(r.Context(), `UPDATE oauth_transactions SET ticket_hash=$1,user_id=$2,expires_at=clock_timestamp()+interval '90 seconds' WHERE state_hash=$3`, digest(ticket), id, digest(state))
	if e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	http.Redirect(w, r, a.cfg.publicURL+"/auth/complete?code="+url.QueryEscape(ticket), http.StatusSeeOther)
	return nil
}
func (a *app) exchange(w http.ResponseWriter, r *http.Request) error {
	if e := a.rate(r, "oauth-exchange", 100); e != nil {
		return e
	}
	var in struct {
		Code     string `json:"code"`
		Verifier string `json:"code_verifier"`
	}
	if e := decode(w, r, &in); e != nil {
		return e
	}
	if len(in.Code) > 100 || len(in.Verifier) < 43 || len(in.Verifier) > 128 {
		return fail(400, "INVALID_EXCHANGE", "登录参数无效。")
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var id int64
	var expected string
	e = tx.QueryRowContext(r.Context(), `SELECT user_id,mobile_challenge FROM oauth_transactions WHERE ticket_hash=$1 AND expires_at>clock_timestamp() AND NOT consumed FOR UPDATE`, digest(in.Code)).Scan(&id, &expected)
	if errors.Is(e, sql.ErrNoRows) {
		return fail(400, "INVALID_EXCHANGE", "登录已过期，请重新开始。")
	}
	if e != nil {
		return e
	}
	if !hmac.Equal([]byte(expected), []byte(challenge(in.Verifier))) {
		return fail(400, "INVALID_EXCHANGE", "登录验证失败。")
	}
	if _, e = tx.ExecContext(r.Context(), `UPDATE oauth_transactions SET consumed=true WHERE ticket_hash=$1`, digest(in.Code)); e != nil {
		return e
	}
	token, e := session(r.Context(), tx, id)
	if e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"access_token": token, "token_type": "Bearer", "expires_in": 2592000})
	return nil
}
func (a *app) me(w http.ResponseWriter, r *http.Request, id int64) error {
	var name string
	if e := a.db.QueryRowContext(r.Context(), `SELECT display_name FROM users WHERE id=$1`, id).Scan(&name); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"id": id, "display_name": name, "logged_in": true})
	return nil
}
func (a *app) logout(w http.ResponseWriter, r *http.Request, id int64) error {
	_, e := a.db.ExecContext(r.Context(), `DELETE FROM sessions WHERE token_hash=$1 AND user_id=$2`, digest(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")), id)
	if e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"logged_in": false})
	return nil
}
