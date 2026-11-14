package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	_ "github.com/lib/pq"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

//go:embed schema.sql
var schema string

const serviceVersion = "v1.06-aster"

const promptVersion = "aster-persona-2-tools"
const persona = "你是 Aster，Aster 中的开发助手。使用用户的语言，清楚、务实地帮助完成编程任务。区分建议、计划、真实工具执行和已经验证的结果；没有工具证据时，不声称已经修改文件、运行命令或操作设备。不伪造文件、命令输出或执行成功。身份名称是 Aster，服务型号是 Aster-free。"

var beijing = time.FixedZone("Asia/Shanghai", 8*3600)

type config struct {
	normalCounterMaxOutput                                                                                                                                                                                      int64
	goCounterMaxOutput                                                                                                                                                                                          int64
	adminUsername, adminPasswordHash, adminLinkHash                                                                                                                                                             string
	goReasoningEffort                                                                                                                                                                                           string
	db, secret, publicURL, resendKey, emailFrom, githubID, githubSecret, upstreamURL, upstreamKey, upstreamModel, counterURL, counterKey, normalURL, normalKey, normalModel, normalCounterURL, normalCounterKey string
	normalChatCount, goChatCount                                                                                                                                                                                bool
}
type app struct {
	db   *sql.DB
	cfg  config
	http *http.Client
}
type failure struct {
	status        int
	code, message string
}

func (e failure) Error() string                   { return e.code }
func fail(status int, code, message string) error { return failure{status, code, message} }
func randomToken() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func digest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func challenge(s string) string {
	h := sha256.Sum256([]byte(s))
	return base64.RawURLEncoding.EncodeToString(h[:])
}
func (a *app) codeHash(s string) string {
	h := hmac.New(sha256.New, []byte(a.cfg.secret))
	h.Write([]byte(s))
	return hex.EncodeToString(h.Sum(nil))
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func writeError(w http.ResponseWriter, e error) {
	var f failure
	if !errors.As(e, &f) {
		f = failure{500, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"}
	}
	writeJSON(w, f.status, map[string]any{"error": map[string]any{"code": f.code, "message": f.message}})
}
func decode(w http.ResponseWriter, r *http.Request, out any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 4<<20)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if e := d.Decode(out); e != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(e, &tooLarge) {
			return fail(413, "BODY_TOO_LARGE", "请求体过大。")
		}
		return fail(400, "INVALID_REQUEST", "请求参数无效。")
	}
	if d.Decode(new(any)) != io.EOF {
		return fail(400, "INVALID_REQUEST", "请求必须是单个 JSON 对象。")
	}
	return nil
}
func (a *app) remoteJSON(ctx context.Context, method, target string, body any, headers map[string]string, out any) (int, error) {
	var reader io.Reader
	if body != nil {
		b, e := json.Marshal(body)
		if e != nil {
			return 0, e
		}
		reader = strings.NewReader(string(b))
	}
	req, e := http.NewRequestWithContext(ctx, method, target, reader)
	if e != nil {
		return 0, e
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Aster-account-service/1.0.1")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	response, e := a.http.Do(req)
	if e != nil {
		return 0, e
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
		return response.StatusCode, fmt.Errorf("remote status %d", response.StatusCode)
	}
	if out == nil {
		return response.StatusCode, nil
	}
	e = json.NewDecoder(io.LimitReader(response.Body, 8<<20)).Decode(out)
	return response.StatusCode, e
}
func (a *app) route(handler func(http.ResponseWriter, *http.Request) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		defer func() {
			if recover() != nil {
				writeError(w, fail(500, "INTERNAL_ERROR", "服务暂时不可用。"))
			}
		}()
		if e := handler(w, r); e != nil {
			writeError(w, e)
		}
	}
}
func (a *app) authenticated(handler func(http.ResponseWriter, *http.Request, int64) error) http.HandlerFunc {
	return a.route(func(w http.ResponseWriter, r *http.Request) error {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if token == "" || token == r.Header.Get("Authorization") {
			return fail(401, "LOGIN_REQUIRED", "请先登录。")
		}
		var id int64
		e := a.db.QueryRowContext(r.Context(), `SELECT s.user_id FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=$1 AND s.expires_at>clock_timestamp() AND u.status='active'`, digest(token)).Scan(&id)
		if errors.Is(e, sql.ErrNoRows) {
			return fail(401, "SESSION_EXPIRED", "登录已过期，请重新登录。")
		}
		if e != nil {
			return e
		}
		return handler(w, r, id)
	})
}
func (a *app) available() map[string]bool {
	return map[string]bool{"email_login": a.cfg.resendKey != "" && a.cfg.emailFrom != "", "github_login": a.cfg.githubSecret != "", "free": a.tier(false).available(), "free_go": a.tier(true).available()}
}
func (a *app) routes() http.Handler {
	m := http.NewServeMux()
	a.registerAdmin(m)
	m.HandleFunc("GET /api/v1/health", a.route(func(w http.ResponseWriter, r *http.Request) error {
		if e := a.db.PingContext(r.Context()); e != nil {
			return e
		}
		writeJSON(w, 200, map[string]any{"status": "ok", "version": serviceVersion, "capabilities": a.available()})
		return nil
	}))
	m.HandleFunc("GET /api/v1/models", a.route(func(w http.ResponseWriter, r *http.Request) error {
		writeJSON(w, 200, map[string]any{"models": []any{
			map[string]any{"id": "Aster-free", "requires_login": true, "configurable": false, "custom_prompt": false, "context_limit_tokens": 128000, "supports_thinking": false, "thinking_mode": freeThinkingMode, "points_per_1000_tokens": freeUnitsPerToken, "available": a.available()["free"]},
			map[string]any{"id": "Aster-free go", "requires_login": true, "configurable": false, "custom_prompt": false, "context_limit_tokens": 128000, "supports_thinking": a.tier(true).fixedReasoning, "thinking_mode": a.tier(true).thinkingMode(), "reasoning_in_output_tokens": a.tier(true).fixedReasoning, "points_per_1000_tokens": goUnitsPerToken, "available": a.available()["free_go"]},
		}})
		return nil
	}))
	m.HandleFunc("POST /api/v1/auth/email/start", a.route(a.emailStart))
	m.HandleFunc("POST /api/v1/auth/email/verify", a.route(a.emailVerify))
	m.HandleFunc("POST /api/v1/auth/github/start", a.route(a.githubStart))
	m.HandleFunc("GET /api/v1/auth/github/callback", a.route(a.githubCallback))
	m.HandleFunc("POST /api/v1/auth/exchange", a.route(a.exchange))
	m.HandleFunc("GET /api/v1/account/me", a.authenticated(a.me))
	m.HandleFunc("GET /api/v1/wallet", a.authenticated(a.wallet))
	m.HandleFunc("GET /api/v1/wallet/ledger", a.authenticated(a.history))
	m.HandleFunc("POST /api/v1/checkins", a.authenticated(a.checkin))
	m.HandleFunc("POST /api/v1/free/chat", a.authenticated(a.chat))
	m.HandleFunc("POST /api/v1/free-go/chat", a.authenticated(func(w http.ResponseWriter, r *http.Request, id int64) error { return a.chatForTier(w, r, id, true) }))
	m.HandleFunc("POST /api/v1/auth/logout", a.authenticated(a.logout))
	m.HandleFunc("GET /.well-known/assetlinks.json", a.route(a.appLinks))
	m.HandleFunc("GET /auth/complete", a.route(a.authComplete))
	return m
}
func loadConfig() config {
	cfg := config{db: os.Getenv("ASTER_DATABASE_URL"), secret: os.Getenv("ASTER_SESSION_SECRET"), publicURL: os.Getenv("ASTER_PUBLIC_URL"), resendKey: os.Getenv("RESEND_API_KEY"), emailFrom: os.Getenv("ASTER_EMAIL_FROM"), githubID: os.Getenv("GITHUB_CLIENT_ID"), githubSecret: os.Getenv("GITHUB_CLIENT_SECRET"), upstreamURL: os.Getenv("ASTER_FREE_UPSTREAM_URL"), upstreamKey: os.Getenv("ASTER_FREE_UPSTREAM_KEY"), upstreamModel: os.Getenv("ASTER_FREE_UPSTREAM_MODEL"), counterURL: os.Getenv("ASTER_TOKEN_COUNTER_URL"), counterKey: os.Getenv("ASTER_TOKEN_COUNTER_KEY"), normalURL: os.Getenv("ASTER_FREE_NORMAL_URL"), normalKey: os.Getenv("ASTER_FREE_NORMAL_KEY"), normalModel: os.Getenv("ASTER_FREE_NORMAL_MODEL"), normalCounterURL: os.Getenv("ASTER_FREE_NORMAL_COUNTER_URL"), normalCounterKey: os.Getenv("ASTER_FREE_NORMAL_COUNTER_KEY")}
	loadAdminConfig(&cfg)
	cfg.normalChatCount = os.Getenv("ASTER_FREE_NORMAL_COUNTER_MODE") == "chat_usage"
	cfg.goChatCount = os.Getenv("ASTER_GO_COUNTER_MODE") == "chat_usage"
	cfg.goReasoningEffort = os.Getenv("ASTER_GO_REASONING_EFFORT")
	cfg.goCounterMaxOutput, _ = strconv.ParseInt(os.Getenv("ASTER_GO_COUNTER_MAX_OUTPUT_TOKENS"), 10, 64)
	cfg.normalCounterMaxOutput, _ = strconv.ParseInt(os.Getenv("ASTER_FREE_NORMAL_COUNTER_MAX_OUTPUT_TOKENS"), 10, 64)
	return cfg
}
func main() {
	cfg := loadConfig()
	if e := validateAdminConfig(cfg); e != nil {
		log.Fatal(e)
	}
	if cfg.db == "" || len(cfg.secret) < 32 {
		log.Fatal("Database and session secret configuration required")
	}
	if cfg.publicURL == "" {
		cfg.publicURL = "https://asteride.cn"
	}
	if cfg.githubID == "" {
		cfg.githubID = "Iv23li1mKOY52dmNsqAV"
	}
	for _, address := range []string{cfg.publicURL, cfg.upstreamURL, cfg.counterURL, cfg.normalURL, cfg.normalCounterURL} {
		if address == "" {
			continue
		}
		u, e := url.Parse(address)
		if e != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Host == "" || !(u.Scheme == "https" || u.Scheme == "http" && (u.Hostname() == "localhost" || u.Hostname() == "127.0.0.1")) {
			log.Fatal("Invalid service URL configuration")
		}
	}
	db, e := sql.Open("postgres", cfg.db)
	if e != nil {
		log.Fatal("Database setup failed")
	}
	defer db.Close()
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if e = db.PingContext(ctx); e != nil {
		log.Fatal("Database unavailable")
	}
	if _, e = db.ExecContext(ctx, schema); e != nil {
		log.Fatal("Database migration failed")
	}
	a := &app{db: db, cfg: cfg, http: &http.Client{Timeout: 180 * time.Second, CheckRedirect: func(r *http.Request, v []*http.Request) error { return http.ErrUseLastResponse }}}
	maintenanceContext, stopMaintenance := context.WithCancel(context.Background())
	defer stopMaintenance()
	go a.maintenance(maintenanceContext)
	server := &http.Server{Addr: "127.0.0.1:8787", Handler: a.routes(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 200 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 << 10, BaseContext: func(net.Listener) context.Context { return context.Background() }}
	done := make(chan os.Signal, 1)
	signal.Notify(done, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-done
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()
	log.Print("Aster service listening on loopback:8787")
	if e := server.ListenAndServe(); e != nil && !errors.Is(e, http.ErrServerClosed) {
		log.Fatal("HTTP service stopped")
	}
}
