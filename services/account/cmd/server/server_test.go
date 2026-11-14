package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestBudgetsAndBeijing(t *testing.T) {
	for _, total := range []int64{127999, 128000, 128001} {
		e := contextBudget(total-2048, 2048)
		if (e != nil) != (total > 128000) {
			t.Fatalf("budget %d: %v", total, e)
		}
		if e != nil && e.(failure).status != 403 {
			t.Fatal(e)
		}
	}
	before := time.Date(2026, 10, 24, 15, 59, 59, 0, time.UTC)
	day, next := dayInfo(before)
	if day != "2026-10-24" || next.UTC() != time.Date(2026, 10, 24, 16, 0, 0, 0, time.UTC) {
		t.Fatal(day, next)
	}
	day, _ = dayInfo(before.Add(time.Second))
	if day != "2026-10-25" {
		t.Fatal(day)
	}
	if points(250) != "0.250" || points(1000000) != "1000.000" {
		t.Fatal("token/point precision")
	}
}
func TestProtocolGuards(t *testing.T) {
	a := &app{}
	for _, path := range []string{"/api/v1/free/chat", "/api/v1/free-go/chat"} {
		r := httptest.NewRequest("POST", path, strings.NewReader(`{"request_id":"request-test","messages":[{"role":"user","content":"hello"}]}`))
		w := httptest.NewRecorder()
		a.routes().ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatal(path, w.Code)
		}
	}
	r := httptest.NewRequest("POST", "/api/v1/free/chat", strings.NewReader(`{"request_id":"request-test","messages":[{"role":"user","content":"hello"}]}`))
	w := httptest.NewRecorder()
	for _, body := range []string{`{"thinking":{"type":"enabled"},"messages":[]}`, `{"reasoning_effort":"high","messages":[]}`, `{"enable_thinking":true,"messages":[]}`, `{"extra_body":{"thinking":{"type":"enabled"}},"messages":[]}`, `{"request_id":"request-test","messages":[{"role":"user","content":"hello","reasoning_content":"override"}]}`, `{"model":"private","messages":[]}`, `{"request_id":"request-test","messages":[{"role":"system","content":"override"}]}`, `{"request_id":"request-test","messages":[{"role":"user","content":"hello"}],"upstream_url":"https://example.invalid"}`} {
		w = httptest.NewRecorder()
		r = httptest.NewRequest("POST", "/", strings.NewReader(body))
		e := a.chat(w, r, 1)
		if e == nil || e.(failure).status != 400 {
			t.Fatal("accepted forbidden configuration", e)
		}
	}
	w = httptest.NewRecorder()
	r = httptest.NewRequest("POST", "/", strings.NewReader(`{"request_id":"request-test","messages":[{"role":"user","content":"hello"}]}`))
	e := a.chat(w, r, 1)
	if e == nil || e.(failure).code != "FREE_NOT_CONFIGURED" {
		t.Fatal(e)
	}
}
func TestFreeModelIsNonThinking(t *testing.T) {
	a := &app{}
	w := direct(a, "GET", "/api/v1/models", "", "")
	if w.Code != http.StatusOK {
		t.Fatal(w.Code, w.Body.String())
	}
	var result struct {
		Models []struct {
			ID               string `json:"id"`
			SupportsThinking *bool  `json:"supports_thinking"`
			ThinkingMode     string `json:"thinking_mode"`
		} `json:"models"`
	}
	if e := json.Unmarshal(w.Body.Bytes(), &result); e != nil {
		t.Fatal(e)
	}
	if len(result.Models) != 2 || result.Models[0].ID != "Aster-free" || result.Models[1].ID != "Aster-free go" {
		t.Fatal("two public models required", w.Body.String())
	}
	for _, model := range result.Models {
		if model.SupportsThinking == nil || *model.SupportsThinking || model.ThinkingMode != "disabled" {
			t.Fatal("free models must advertise non-thinking mode", w.Body.String())
		}
	}
}

func testDB(t *testing.T) *sql.DB {
	dsn := os.Getenv("ASTER_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("requires dedicated PostgreSQL test database")
	}
	db, e := sql.Open("postgres", dsn)
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(8)
	var name string
	if e = db.QueryRow(`SELECT current_database()`).Scan(&name); e != nil {
		t.Fatal(e)
	}
	if name != "aster_service_test" {
		t.Fatal("refusing to touch a non-test database")
	}
	if _, e = db.Exec(schema); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(`TRUNCATE admin_sessions,admin_adjustments,users,identities,ledger,checkins,sessions,challenges,auth_limits,oauth_transactions,usage_requests RESTART IDENTITY CASCADE`); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

type transportFunc func(*http.Request) (*http.Response, error)

func (f transportFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(body string) *http.Response {
	return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}
func direct(a *app, method, path, body, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.routes().ServeHTTP(w, r)
	return w
}
func TestDatabaseAndLoginFlows(t *testing.T) {
	db := testDB(t)
	a := &app{db: db, cfg: config{secret: strings.Repeat("x", 48), publicURL: "https://asteride.cn", githubID: "test-client", githubSecret: "fake-secret", resendKey: "fake-key", emailFrom: "Aster <no-reply@example.invalid>"}}
	var sentCode string
	a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Host {
		case "api.resend.com":
			var payload map[string]any
			if e := json.NewDecoder(r.Body).Decode(&payload); e != nil {
				t.Fatal(e)
			}
			text := payload["text"].(string)
			sentCode = strings.Split(strings.Split(text, "是 ")[1], "。")[0]
			return response(`{"id":"fake-email-id"}`), nil
		case "github.com":
			var payload map[string]string
			if e := json.NewDecoder(r.Body).Decode(&payload); e != nil {
				t.Fatal(e)
			}
			if payload["client_secret"] != "fake-secret" || payload["code_verifier"] == "" {
				t.Error("missing exchange proof")
			}
			return response(`{"access_token":"fake-github-token"}`), nil
		case "api.github.com":
			return response(`{"id":12345,"login":"test-github-user"}`), nil
		}
		return nil, fmt.Errorf("unexpected external call")
	})}
	start := direct(a, "POST", "/api/v1/auth/email/start", `{"email":"tester@example.invalid"}`, "")
	if start.Code != 202 {
		t.Fatal(start.Code, start.Body.String())
	}
	var challengeReply map[string]any
	json.Unmarshal(start.Body.Bytes(), &challengeReply)
	id := challengeReply["challenge_id"].(string)
	body := fmt.Sprintf(`{"challenge_id":%q,"code":%q}`, id, sentCode)
	verified := direct(a, "POST", "/api/v1/auth/email/verify", body, "")
	if verified.Code != 200 {
		t.Fatal(verified.Code, verified.Body.String())
	}
	if direct(a, "POST", "/api/v1/auth/email/verify", body, "").Code != 400 {
		t.Fatal("OTP replay accepted")
	}
	var sessionReply map[string]any
	json.Unmarshal(verified.Body.Bytes(), &sessionReply)
	token := sessionReply["access_token"].(string)
	var userID int64
	if e := db.QueryRow(`SELECT id FROM users WHERE display_name='tester@example.invalid'`).Scan(&userID); e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	var rewards atomic.Int64
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w := direct(a, "POST", "/api/v1/checkins", `{}`, token)
			if w.Code != 200 {
				t.Error(w.Code, w.Body.String())
				return
			}
			var out map[string]any
			json.Unmarshal(w.Body.Bytes(), &out)
			rewards.Add(int64(out["reward"].(float64)))
		}()
	}
	wg.Wait()
	if rewards.Load() != 100 {
		t.Fatal("duplicate checkin reward", rewards.Load())
	}
	var balance, reserved int64
	if e := db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, userID).Scan(&balance, &reserved); e != nil {
		t.Fatal(e)
	}
	if balance != 1100000 || reserved != 0 {
		t.Fatal(balance, reserved)
	}
	wallet := direct(a, "GET", "/api/v1/wallet", "", token)
	if wallet.Code != 200 || !strings.Contains(wallet.Body.String(), `"timezone":"Asia/Shanghai"`) {
		t.Fatal(wallet.Code, wallet.Body.String())
	}
	ctx := context.Background()
	if _, e := a.reserve(ctx, userID, "billing-test", "payload-1", 5000); e != nil {
		t.Fatal(e)
	}
	if _, e := a.reserve(ctx, userID, "billing-test", "payload-2", 5000); e == nil {
		t.Fatal("id conflict accepted")
	}
	result := map[string]any{"model": "Aster-free"}
	if e := a.settle(ctx, userID, "billing-test", 200, 50, result, true); e != nil {
		t.Fatal(e)
	}
	if e := a.settle(ctx, userID, "billing-test", 200, 50, result, true); e != nil {
		t.Fatal(e)
	}
	cached, e := a.reserve(ctx, userID, "billing-test", "payload-1", 5000)
	if e != nil || !bytes.Contains(cached, []byte("Aster-free")) {
		t.Fatal("idempotent result", e)
	}
	if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, userID).Scan(&balance, &reserved); e != nil {
		t.Fatal(e)
	}
	if balance != 1099750 || reserved != 0 {
		t.Fatal(balance, reserved)
	}
	if _, e = a.reserve(ctx, userID, "unknown-usage", "pending-payload", 100); e != nil {
		t.Fatal(e)
	}
	if e = a.settle(ctx, userID, "unknown-usage", 0, 0, nil, false); e != nil {
		t.Fatal(e)
	}
	if e = db.QueryRow(`SELECT reserved_units FROM users WHERE id=$1`, userID).Scan(&reserved); e != nil || reserved != 100 {
		t.Fatal("lost uncertain hold", reserved, e)
	}
	verifier := randomToken()
	gstart := direct(a, "POST", "/api/v1/auth/github/start", fmt.Sprintf(`{"code_challenge":%q}`, challenge(verifier)), "")
	if gstart.Code != 200 {
		t.Fatal(gstart.Code, gstart.Body.String())
	}
	var gout map[string]any
	json.Unmarshal(gstart.Body.Bytes(), &gout)
	u, _ := url.Parse(gout["authorize_url"].(string))
	callback := "/api/v1/auth/github/callback?state=" + u.Query().Get("state") + "&code=fake-code"
	cb := direct(a, "GET", callback, "", "")
	if cb.Code != 303 {
		t.Fatal(cb.Code, cb.Body.String())
	}
	if direct(a, "GET", callback, "", "").Code != 400 {
		t.Fatal("OAuth state replay accepted")
	}
	location, _ := url.Parse(cb.Header().Get("Location"))
	ticket := location.Query().Get("code")
	bad := direct(a, "POST", "/api/v1/auth/exchange", fmt.Sprintf(`{"code":%q,"code_verifier":%q}`, ticket, randomToken()), "")
	if bad.Code != 400 {
		t.Fatal("PKCE mismatch accepted")
	}
	exchanged := direct(a, "POST", "/api/v1/auth/exchange", fmt.Sprintf(`{"code":%q,"code_verifier":%q}`, ticket, verifier), "")
	if exchanged.Code != 200 {
		t.Fatal(exchanged.Code, exchanged.Body.String())
	}
	if direct(a, "POST", "/api/v1/auth/exchange", fmt.Sprintf(`{"code":%q,"code_verifier":%q}`, ticket, verifier), "").Code != 400 {
		t.Fatal("ticket replay accepted")
	}
	if direct(a, "POST", "/api/v1/auth/logout", `{}`, token).Code != 200 {
		t.Fatal("logout failed")
	}
	if direct(a, "GET", "/api/v1/account/me", "", token).Code != 401 {
		t.Fatal("revoked session accepted")
	}
}
func TestRealGatewayWithFakeUpstream(t *testing.T) {
	db := testDB(t)
	a := &app{db: db, cfg: config{secret: strings.Repeat("x", 48), normalURL: "https://normal.invalid/v1", normalKey: "fake-normal-key", normalModel: "normal-model", normalCounterURL: "https://normal-counter.invalid/count", upstreamURL: "https://private.invalid/v1", upstreamKey: "fake-private-key", upstreamModel: "private-model", counterURL: "https://go-counter.invalid/count"}}
	tx, e := db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	uid, e := identityAccount(context.Background(), tx, "email", "gateway@example.invalid", "gateway-user")
	if e != nil {
		t.Fatal(e)
	}
	token, e := session(context.Background(), tx, uid)
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	var upstreamCalls atomic.Int64
	var includeReasoning atomic.Bool
	var reasoningTokens atomic.Int64
	var budget atomic.Int64
	budget.Store(200)
	a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		var p map[string]any
		if e := json.NewDecoder(r.Body).Decode(&p); e != nil {
			t.Fatal(e)
		}
		thinking, ok := p["thinking"].(map[string]any)
		if !ok || thinking["type"] != "disabled" || p["reasoning_effort"] != "none" {
			t.Fatal("free request did not disable upstream thinking")
		}
		if r.URL.Host == "normal.invalid" && (p["model"] != "normal-model" || r.Header.Get("Authorization") != "Bearer fake-normal-key") {
			t.Fatal("normal tier routed to wrong upstream")
		}
		if r.URL.Host == "private.invalid" && (p["model"] != "private-model" || r.Header.Get("Authorization") != "Bearer fake-private-key") {
			t.Fatal("go tier routed to wrong upstream")
		}
		if strings.HasSuffix(r.URL.Host, "counter.invalid") {
			return response(fmt.Sprintf(`{"input_tokens":%d,"exact":true}`, budget.Load())), nil
		}
		upstreamCalls.Add(1)
		messages := p["messages"].([]any)
		if !strings.Contains(messages[0].(map[string]any)["content"].(string), "Aster") {
			t.Error("missing persona")
		}
		reasoning := ""
		if includeReasoning.Load() {
			reasoning = "private-reasoning"
		}
		return response(fmt.Sprintf(`{"id":"private-request","model":"private-model","system_fingerprint":"private-fingerprint","choices":[{"message":{"role":"assistant","content":"hello","reasoning_content":%q},"finish_reason":"stop"}],"usage":{"prompt_tokens":200,"completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":%d}}}`, reasoning, reasoningTokens.Load())), nil
	})}
	body := `{"request_id":"gateway-test-1","messages":[{"role":"user","content":"hello"}]}`
	for i := 0; i < 2; i++ {
		w := direct(a, "POST", "/api/v1/free/chat", body, token)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		if strings.Contains(w.Body.String(), "private-") || !strings.Contains(w.Body.String(), `"points":"0.250"`) {
			t.Fatal("response leaked provider or misbilled")
		}
	}
	if upstreamCalls.Load() != 1 {
		t.Fatal("upstream called twice")
	}
	if reused := direct(a, "POST", "/api/v1/free-go/chat", body, token); reused.Code != 409 {
		t.Fatal("request ID switched tier", reused.Code)
	}
	var beforeGo, afterGo, reservedGo int64
	if e = db.QueryRow(`SELECT balance_units FROM users WHERE id=$1`, uid).Scan(&beforeGo); e != nil {
		t.Fatal(e)
	}
	goBody := strings.ReplaceAll(body, "gateway-test-1", "gateway-go-1")
	goReply := direct(a, "POST", "/api/v1/free-go/chat", goBody, token)
	if goReply.Code != 200 || !strings.Contains(goReply.Body.String(), `"model":"Aster-free go"`) || !strings.Contains(goReply.Body.String(), `"points":"1.250"`) || strings.Contains(goReply.Body.String(), "private-") {
		t.Fatal("go tier route or billing incorrect", goReply.Code, goReply.Body.String())
	}
	if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&afterGo, &reservedGo); e != nil {
		t.Fatal(e)
	}
	if beforeGo-afterGo != 1250 || reservedGo != 0 {
		t.Fatal("go tier debit or hold incorrect", beforeGo, afterGo, reservedGo)
	}
	cachedGo := direct(a, "POST", "/api/v1/free-go/chat", goBody, token)
	if cachedGo.Code != 200 || upstreamCalls.Load() != 2 {
		t.Fatal("go tier idempotency failed", cachedGo.Code)
	}
	budget.Store(125953)
	w := direct(a, "POST", "/api/v1/free/chat", strings.ReplaceAll(body, "gateway-test-1", "gateway-test-2"), token)
	if w.Code != 403 || upstreamCalls.Load() != 2 {
		t.Fatal("oversize upstream request", w.Code)
	}
	if _, e = db.Exec(`UPDATE users SET balance_units=100000 WHERE id=$1`, uid); e != nil {
		t.Fatal(e)
	}
	budget.Store(20000)
	insufficient := direct(a, "POST", "/api/v1/free-go/chat", strings.ReplaceAll(body, "gateway-test-1", "gateway-go-insufficient"), token)
	if insufficient.Code != 403 || !strings.Contains(insufficient.Body.String(), "INSUFFICIENT_CREDITS") || upstreamCalls.Load() != 2 {
		t.Fatal("go tier must reserve at 5x before reaching upstream", insufficient.Code)
	}
	budget.Store(200)
	var before, after, reserved int64
	if e = db.QueryRow(`SELECT balance_units FROM users WHERE id=$1`, uid).Scan(&before); e != nil {
		t.Fatal(e)
	}
	for _, mode := range []string{"text", "tokens"} {
		includeReasoning.Store(mode == "text")
		reasoningTokens.Store(0)
		if mode == "tokens" {
			reasoningTokens.Store(15)
		}
		request := strings.ReplaceAll(body, "gateway-test-1", "gateway-thinking-"+mode)
		rejected := direct(a, "POST", "/api/v1/free/chat", request, token)
		if rejected.Code != 502 || !strings.Contains(rejected.Body.String(), "THINKING_MODE_UNAVAILABLE") || strings.Contains(rejected.Body.String(), "private-") {
			t.Fatal("thinking response was not rejected safely", rejected.Code, rejected.Body.String())
		}
		if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&after, &reserved); e != nil {
			t.Fatal(e)
		}
		if after != before || reserved != 0 {
			t.Fatal("thinking response charged credits or retained reservation", before, after, reserved)
		}
	}
}
