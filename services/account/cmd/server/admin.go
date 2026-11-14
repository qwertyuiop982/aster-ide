package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/shopspring/decimal"
)

//go:embed adminui/*
var adminAssets embed.FS

const adminCookie = "__Secure-aster_admin"
const adminRoot = "/api/v1/admin/"

// PBKDF2-HMAC-SHA256, one 32-byte block. Compatible with hashlib.pbkdf2_hmac.
func adminDerive(password string, salt []byte, rounds int) []byte {
	mac := hmac.New(sha256.New, []byte(password))
	mac.Write(salt)
	mac.Write([]byte{0, 0, 0, 1})
	u := mac.Sum(nil)
	result := append([]byte(nil), u...)
	for i := 1; i < rounds; i++ {
		mac.Reset()
		mac.Write(u)
		u = mac.Sum(u[:0])
		for j := range result {
			result[j] ^= u[j]
		}
	}
	return result
}
func validAdminHash(encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	rounds, e := strconv.Atoi(parts[1])
	salt, e2 := hex.DecodeString(parts[2])
	hash, e3 := hex.DecodeString(parts[3])
	return e == nil && e2 == nil && e3 == nil && rounds >= 100000 && rounds <= 600000 && len(salt) >= 16 && len(salt) <= 32 && len(hash) == 32
}
func verifyAdminPassword(encoded, password string) bool {
	if !validAdminHash(encoded) || len(password) > 1024 {
		return false
	}
	parts := strings.Split(encoded, "$")
	rounds, _ := strconv.Atoi(parts[1])
	salt, _ := hex.DecodeString(parts[2])
	want, _ := hex.DecodeString(parts[3])
	return subtle.ConstantTimeCompare(adminDerive(password, salt, rounds), want) == 1
}
func (a *app) adminConfigured() bool {
	return a.cfg.adminUsername != "" && a.cfg.adminPasswordHash != "" && a.cfg.adminLinkHash != ""
}
func (a *app) adminVersion() string {
	return digest(a.cfg.adminUsername + "|" + a.cfg.adminPasswordHash + "|" + a.cfg.adminLinkHash)
}
func (a *app) adminCSRF(token string) string { return a.codeHash("admin-csrf|" + token) }
func adminHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow, noarchive")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
}
func (a *app) adminPage(w http.ResponseWriter, r *http.Request) {
	adminHeaders(w)
	if !a.adminConfigured() {
		http.NotFound(w, r)
		return
	}
	name, mime := "", ""
	switch r.URL.Path {
	case "/admin-console/":
		name, mime = "index.html", "text/html; charset=utf-8"
	case "/admin-console/site.css":
		name, mime = "site.css", "text/css; charset=utf-8"
	case "/admin-console/site.js":
		name, mime = "site.js", "application/javascript; charset=utf-8"
	default:
		http.NotFound(w, r)
		return
	}
	raw, e := adminAssets.ReadFile("adminui/" + name)
	if e != nil {
		http.Error(w, "Unavailable", 500)
		return
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
	_, _ = w.Write(raw)
}
func (a *app) adminRoute(login bool, handler func(http.ResponseWriter, *http.Request, string) error) http.HandlerFunc {
	return a.route(func(w http.ResponseWriter, r *http.Request) error {
		adminHeaders(w)
		if !a.adminConfigured() {
			return fail(404, "NOT_FOUND", "页面不存在。")
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		r = r.WithContext(ctx)
		if r.Method != http.MethodGet {
			if r.Header.Get("Origin") != a.cfg.publicURL {
				return fail(403, "ADMIN_ORIGIN", "请从管理面板提交操作。")
			}
			if strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
				return fail(415, "ADMIN_CONTENT_TYPE", "请使用 JSON 请求。")
			}
			r.Body = http.MaxBytesReader(w, r.Body, 8192)
		}
		if login {
			token := r.Header.Get("X-Aster-Admin-Token")
			if len(token) < 32 || len(token) > 128 || subtle.ConstantTimeCompare([]byte(digest(token)), []byte(a.cfg.adminLinkHash)) != 1 {
				return fail(401, "ADMIN_LOGIN_REQUIRED", "管理链接或登录凭据无效。")
			}
			return handler(w, r, "")
		}
		cookie, e := r.Cookie(adminCookie)
		if e != nil || len(cookie.Value) != 43 {
			return fail(401, "ADMIN_LOGIN_REQUIRED", "请先登录管理面板。")
		}
		if r.Method != http.MethodGet && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Aster-CSRF")), []byte(a.adminCSRF(cookie.Value))) != 1 {
			return fail(403, "ADMIN_CSRF", "会话校验失败，请刷新页面。")
		}
		var username string
		e = a.db.QueryRowContext(ctx, `SELECT username FROM admin_sessions WHERE token_hash=$1 AND credential_version=$2 AND expires_at>clock_timestamp()`, digest(cookie.Value), a.adminVersion()).Scan(&username)
		if errors.Is(e, sql.ErrNoRows) {
			return fail(401, "ADMIN_SESSION_EXPIRED", "管理员登录已过期。")
		}
		if e != nil {
			return e
		}
		return handler(w, r, username)
	})
}
func (a *app) registerAdmin(m *http.ServeMux) {
	m.HandleFunc("GET /admin-console/", a.adminPage)
	m.HandleFunc("POST /api/v1/admin/login", a.adminRoute(true, a.adminLogin))
	m.HandleFunc("GET /api/v1/admin/session", a.adminRoute(false, a.adminSession))
	m.HandleFunc("POST /api/v1/admin/logout", a.adminRoute(false, a.adminLogout))
	m.HandleFunc("GET /api/v1/admin/summary", a.adminRoute(false, a.adminSummary))
	m.HandleFunc("GET /api/v1/admin/users", a.adminRoute(false, a.adminUsers))
	m.HandleFunc("GET /api/v1/admin/users/{id}", a.adminRoute(false, a.adminUserDetail))
	m.HandleFunc("GET /api/v1/admin/adjustments", a.adminRoute(false, a.adminAudit))
	m.HandleFunc("POST /api/v1/admin/users/{id}/adjustments", a.adminRoute(false, a.adminAdjust))
}
func (a *app) adminLogin(w http.ResponseWriter, r *http.Request, _ string) error {
	if e := a.rate(r, "admin-login", 20); e != nil {
		return e
	}
	var input struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if e := decode(w, r, &input); e != nil {
		return e
	}
	passwordOK := verifyAdminPassword(a.cfg.adminPasswordHash, input.Password)
	if subtle.ConstantTimeCompare([]byte(digest(input.Username)), []byte(digest(a.cfg.adminUsername))) != 1 || !passwordOK {
		return fail(401, "ADMIN_LOGIN_REQUIRED", "管理链接或登录凭据无效。")
	}
	token := randomToken()
	expires := time.Now().Add(8 * time.Hour)
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), `DELETE FROM admin_sessions WHERE expires_at<clock_timestamp() OR credential_version<>$1`, a.adminVersion()); e != nil {
		return e
	}
	// Re-authentication rotates a browser's old session without revoking other devices.
	if old, e2 := r.Cookie(adminCookie); e2 == nil {
		if _, e = tx.ExecContext(r.Context(), `DELETE FROM admin_sessions WHERE token_hash=$1`, digest(old.Value)); e != nil {
			return e
		}
	}
	if _, e = tx.ExecContext(r.Context(), `INSERT INTO admin_sessions(token_hash,username,credential_version,expires_at) VALUES($1,$2,$3,$4)`, digest(token), a.cfg.adminUsername, a.adminVersion(), expires); e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: token, Path: adminRoot, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 8 * 3600, Expires: expires})
	writeJSON(w, 200, map[string]any{"username": a.cfg.adminUsername, "csrf": a.adminCSRF(token), "expires_at": expires.Format(time.RFC3339)})
	return nil
}
func (a *app) adminSession(w http.ResponseWriter, r *http.Request, username string) error {
	cookie, _ := r.Cookie(adminCookie)
	writeJSON(w, 200, map[string]any{"username": username, "csrf": a.adminCSRF(cookie.Value)})
	return nil
}
func (a *app) adminLogout(w http.ResponseWriter, r *http.Request, _ string) error {
	cookie, _ := r.Cookie(adminCookie)
	if _, e := a.db.ExecContext(r.Context(), `DELETE FROM admin_sessions WHERE token_hash=$1`, digest(cookie.Value)); e != nil {
		return e
	}
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: "", Path: adminRoot, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	writeJSON(w, 200, map[string]bool{"ok": true})
	return nil
}
func adminTotal(raw string) string {
	number, e := decimal.NewFromString(raw)
	if e != nil {
		return "0.000"
	}
	return number.Div(decimal.NewFromInt(1000)).StringFixed(3)
}
func signedPoints(units int64) string {
	if units < 0 {
		return "-" + points(-units)
	}
	return points(units)
}
func (a *app) adminSummary(w http.ResponseWriter, r *http.Request, _ string) error {
	var count int64
	var balance, reserved, today string
	e := a.db.QueryRowContext(r.Context(), `SELECT count(*),COALESCE(sum(balance_units),0)::text,COALESCE(sum(reserved_units),0)::text FROM users`).Scan(&count, &balance, &reserved)
	if e != nil {
		return e
	}
	e = a.db.QueryRowContext(r.Context(), `SELECT COALESCE(sum(-delta_units),0)::text FROM ledger WHERE kind='usage' AND created_at>=((clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::date AT TIME ZONE 'Asia/Shanghai')`).Scan(&today)
	if e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"users": count, "balance": adminTotal(balance), "reserved": adminTotal(reserved), "today_usage": adminTotal(today), "timezone": "Asia/Shanghai"})
	return nil
}

type adminUser struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Status    string `json:"status"`
	Providers string `json:"providers"`
	Balance   string `json:"balance"`
	Available string `json:"available"`
	Reserved  string `json:"reserved"`
	CreatedAt string `json:"created_at"`
}

const adminUserColumns = `u.id,u.display_name,u.status,u.balance_units,u.reserved_units,u.created_at,COALESCE((SELECT string_agg(i.provider,', ' ORDER BY i.provider) FROM identities i WHERE i.user_id=u.id),'')`

type adminScanner interface{ Scan(...any) error }

func scanAdminUser(row adminScanner) (adminUser, error) {
	var user adminUser
	var balance, reserved int64
	var at time.Time
	e := row.Scan(&user.ID, &user.Name, &user.Status, &balance, &reserved, &at, &user.Providers)
	user.Balance, user.Available, user.Reserved = points(balance), points(balance-reserved), points(reserved)
	user.CreatedAt = at.In(beijing).Format(time.RFC3339)
	return user, e
}
func adminID(r *http.Request) (int64, error) {
	id, e := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if e != nil || id < 1 {
		return 0, fail(400, "INVALID_ACCOUNT", "账号编号无效。")
	}
	return id, nil
}
func (a *app) adminUsers(w http.ResponseWriter, r *http.Request, _ string) error {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if utf8.RuneCountInString(q) > 100 {
		return fail(400, "INVALID_SEARCH", "搜索内容过长。")
	}
	page := 1
	if raw := r.URL.Query().Get("page"); raw != "" {
		var e error
		page, e = strconv.Atoi(raw)
		if e != nil || page < 1 || page > 1000000 {
			return fail(400, "INVALID_PAGE", "页码无效。")
		}
	}
	search := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q) + "%"
	const where = ` WHERE ($1='' OR u.id::text=$1 OR u.display_name ILIKE $2 OR EXISTS(SELECT 1 FROM identities i WHERE i.user_id=u.id AND i.subject ILIKE $2))`
	var total int64
	if e := a.db.QueryRowContext(r.Context(), `SELECT count(*) FROM users u`+where, q, search).Scan(&total); e != nil {
		return e
	}
	rows, e := a.db.QueryContext(r.Context(), `SELECT `+adminUserColumns+` FROM users u`+where+` ORDER BY u.id DESC LIMIT 25 OFFSET $3`, q, search, (page-1)*25)
	if e != nil {
		return e
	}
	defer rows.Close()
	users := make([]adminUser, 0)
	for rows.Next() {
		user, e := scanAdminUser(rows)
		if e != nil {
			return e
		}
		users = append(users, user)
	}
	if e = rows.Err(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"items": users, "total": total, "page": page, "page_size": 25})
	return nil
}
func (a *app) adminUserDetail(w http.ResponseWriter, r *http.Request, _ string) error {
	id, e := adminID(r)
	if e != nil {
		return e
	}
	user, e := scanAdminUser(a.db.QueryRowContext(r.Context(), `SELECT `+adminUserColumns+` FROM users u WHERE u.id=$1`, id))
	if errors.Is(e, sql.ErrNoRows) {
		return fail(404, "ACCOUNT_NOT_FOUND", "账号不存在。")
	}
	if e != nil {
		return e
	}
	rows, e := a.db.QueryContext(r.Context(), `SELECT l.id,l.kind,l.reference,l.delta_units,l.created_at,COALESCE(aa.reason,''),COALESCE(aa.admin_name,'') FROM ledger l LEFT JOIN admin_adjustments aa ON l.kind='admin_adjustment' AND aa.request_id=l.reference AND aa.user_id=l.user_id WHERE l.user_id=$1 ORDER BY l.id DESC LIMIT 100`, id)
	if e != nil {
		return e
	}
	defer rows.Close()
	items := make([]any, 0)
	for rows.Next() {
		var ledgerID, delta int64
		var kind, ref, reason, actor string
		var at time.Time
		if e = rows.Scan(&ledgerID, &kind, &ref, &delta, &at, &reason, &actor); e != nil {
			return e
		}
		items = append(items, map[string]any{"id": ledgerID, "kind": kind, "reference": ref, "points": signedPoints(delta), "reason": reason, "admin": actor, "created_at": at.In(beijing).Format(time.RFC3339)})
	}
	if e = rows.Err(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"user": user, "ledger": items})
	return nil
}

var adminAmountPattern = regexp.MustCompile(`^[+-]?(0|[1-9][0-9]{0,6})(\.[0-9]{1,3})?$`)
var adminRequestPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,80}$`)

func parseAdminAmount(value string) (int64, error) {
	if !adminAmountPattern.MatchString(value) {
		return 0, fail(400, "INVALID_POINTS", "积分最多保留三位小数，不支持科学计数法。")
	}
	number, e := decimal.NewFromString(value)
	if e != nil {
		return 0, fail(400, "INVALID_POINTS", "积分数值无效。")
	}
	units := number.Mul(decimal.NewFromInt(1000)).IntPart()
	if units == 0 || units > 1000000000 || units < -1000000000 {
		return 0, fail(400, "INVALID_POINTS", "单次调整须大于 0 且不超过 1,000,000 积分。")
	}
	return units, nil
}
func (a *app) adminAdjust(w http.ResponseWriter, r *http.Request, username string) error {
	id, e := adminID(r)
	if e != nil {
		return e
	}
	var input struct {
		RequestID string `json:"request_id"`
		Points    string `json:"points"`
		Reason    string `json:"reason"`
	}
	if e = decode(w, r, &input); e != nil {
		return e
	}
	if !adminRequestPattern.MatchString(input.RequestID) {
		return fail(400, "INVALID_REQUEST_ID", "操作编号无效，请刷新后重试。")
	}
	delta, e := parseAdminAmount(input.Points)
	if e != nil {
		return e
	}
	input.Reason = strings.TrimSpace(input.Reason)
	if n := utf8.RuneCountInString(input.Reason); n < 2 || n > 200 {
		return fail(400, "INVALID_REASON", "请填写 2 至 200 字的调整原因。")
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "admin-adjustment:"+input.RequestID); e != nil {
		return e
	}
	var oldID, oldDelta, after, held int64
	var oldReason, oldAdmin string
	e = tx.QueryRowContext(r.Context(), `SELECT user_id,delta_units,reason,admin_name,balance_after,reserved_after FROM admin_adjustments WHERE request_id=$1`, input.RequestID).Scan(&oldID, &oldDelta, &oldReason, &oldAdmin, &after, &held)
	if e == nil {
		if oldID != id || oldDelta != delta || oldReason != input.Reason || oldAdmin != username {
			return fail(409, "ADJUSTMENT_CONFLICT", "该操作编号已用于其他调整，请重新打开调整窗口。")
		}
		writeJSON(w, 200, map[string]any{"request_id": input.RequestID, "user_id": id, "points": signedPoints(delta), "balance": points(after), "reserved": points(held), "replayed": true})
		return nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	var balance, reserved int64
	e = tx.QueryRowContext(r.Context(), `SELECT balance_units,reserved_units FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&balance, &reserved)
	if errors.Is(e, sql.ErrNoRows) {
		return fail(404, "ACCOUNT_NOT_FOUND", "账号不存在。")
	}
	if e != nil {
		return e
	}
	if delta > 0 && balance > math.MaxInt64-delta {
		return fail(409, "BALANCE_LIMIT", "积分余额超出范围。")
	}
	after = balance + delta
	if after < reserved {
		return fail(409, "RESERVED_CREDITS", "可用积分不足，不能扣减正在使用的预占积分。")
	}
	if _, e = tx.ExecContext(r.Context(), `UPDATE users SET balance_units=$1 WHERE id=$2`, after, id); e != nil {
		return e
	}
	if _, e = tx.ExecContext(r.Context(), `INSERT INTO admin_adjustments(request_id,user_id,admin_name,delta_units,balance_after,reserved_after,reason) VALUES($1,$2,$3,$4,$5,$6,$7)`, input.RequestID, id, username, delta, after, reserved, input.Reason); e != nil {
		return e
	}
	if _, e = tx.ExecContext(r.Context(), `INSERT INTO ledger(user_id,kind,reference,delta_units) VALUES($1,'admin_adjustment',$2,$3)`, id, input.RequestID, delta); e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"request_id": input.RequestID, "user_id": id, "points": signedPoints(delta), "balance": points(after), "reserved": points(reserved), "replayed": false})
	return nil
}
func (a *app) adminAudit(w http.ResponseWriter, r *http.Request, _ string) error {
	rows, e := a.db.QueryContext(r.Context(), `SELECT aa.request_id,aa.user_id,u.display_name,aa.admin_name,aa.delta_units,aa.balance_after,aa.reason,aa.created_at FROM admin_adjustments aa JOIN users u ON u.id=aa.user_id ORDER BY aa.created_at DESC,aa.request_id DESC LIMIT 100`)
	if e != nil {
		return e
	}
	defer rows.Close()
	items := make([]any, 0)
	for rows.Next() {
		var ref, name, actor, reason string
		var id, delta, balance int64
		var at time.Time
		if e = rows.Scan(&ref, &id, &name, &actor, &delta, &balance, &reason, &at); e != nil {
			return e
		}
		items = append(items, map[string]any{"request_id": ref, "user_id": id, "name": name, "admin": actor, "points": signedPoints(delta), "balance": points(balance), "reason": reason, "created_at": at.In(beijing).Format(time.RFC3339)})
	}
	if e = rows.Err(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"items": items, "limit": 100})
	return nil
}

func loadAdminConfig(cfg *config) {
	cfg.adminUsername = os.Getenv("ASTER_ADMIN_USERNAME")
	cfg.adminPasswordHash = os.Getenv("ASTER_ADMIN_PASSWORD_HASH")
	cfg.adminLinkHash = os.Getenv("ASTER_ADMIN_LINK_TOKEN_HASH")
}
func validateAdminConfig(cfg config) error {
	if cfg.adminUsername == "" && cfg.adminPasswordHash == "" && cfg.adminLinkHash == "" {
		return nil
	}
	link, e := hex.DecodeString(cfg.adminLinkHash)
	if cfg.adminUsername == "" || len(cfg.adminUsername) > 80 || !validAdminHash(cfg.adminPasswordHash) || e != nil || len(link) != 32 {
		return fmt.Errorf("incomplete or invalid administrator configuration")
	}
	return nil
}
