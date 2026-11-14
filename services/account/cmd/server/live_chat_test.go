package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"testing"
)

// Explicit live verification uses only the dedicated test database and short
// Chat completions. Credentials are inherited from a root-only server process.
func TestLiveChatGateway(t *testing.T) {
	if os.Getenv("ASTER_LIVE_CHAT_SMOKE") != "1" {
		t.Skip("live upstream smoke is opt-in")
	}
	db := testDB(t)
	cfg := loadConfig()
	cfg.normalChatCount = true
	cfg.goChatCount = true
	a := &app{db: db, cfg: cfg, http: &http.Client{}}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	uid, err := identityAccount(context.Background(), tx, "email", "live-chat-smoke@example.invalid", "live smoke")
	if err != nil {
		t.Fatal(err)
	}
	token, err := session(context.Background(), tx, uid)
	if err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	calls := 0
	a.http.Transport = transportFunc(func(r *http.Request) (*http.Response, error) { calls++; return http.DefaultTransport.RoundTrip(r) })
	onlyFree := os.Getenv("ASTER_LIVE_CHAT_ONLY_FREE") == "1"
	for i, tier := range []struct {
		path, label string
		rate        int64
	}{{"/api/v1/free/chat", "Aster-free", 1}, {"/api/v1/free-go/chat", "Aster-free go", 5}} {
		if onlyFree && i > 0 {
			t.Log("go smoke omitted; provider verification recorded separately")
			continue
		}
		var before, after, held int64
		if err = db.QueryRow(`SELECT balance_units FROM users WHERE id=$1`, uid).Scan(&before); err != nil {
			t.Fatal(err)
		}
		body := fmt.Sprintf(`{"request_id":"live-chat-%d","messages":[{"role":"user","content":"请只回复连接成功，不调用工具。"}]}`, i)
		reply := direct(a, "POST", tier.path, body, token)
		if reply.Code != 200 {
			t.Fatal(tier.label, reply.Code, reply.Body.String())
		}
		var result struct {
			Model string `json:"model"`
			Usage struct {
				Total  int64  `json:"total_tokens"`
				Points string `json:"points"`
			} `json:"usage"`
		}
		if err = json.Unmarshal(reply.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if result.Model != tier.label || result.Usage.Total < 1 || result.Usage.Points != points(result.Usage.Total*tier.rate) {
			t.Fatal("live pricing mismatch")
		}
		if err = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&after, &held); err != nil {
			t.Fatal(err)
		}
		if before-after != result.Usage.Total*tier.rate || held != 0 {
			t.Fatal("live wallet settlement mismatch")
		}
		for _, secret := range []string{cfg.normalKey, cfg.upstreamKey, cfg.normalModel, cfg.upstreamModel} {
			if secret != "" && strings.Contains(reply.Body.String(), secret) {
				t.Fatal("private provider configuration leaked")
			}
		}
		beforeReplay := calls
		cached := direct(a, "POST", tier.path, body, token)
		if cached.Code != 200 || calls != beforeReplay || !sameJSON(cached.Body.Bytes(), reply.Body.Bytes()) {
			t.Fatal("live replay not cached")
		}
		t.Logf("%s: real token usage %d, points %s, hold released, replay cached", tier.label, result.Usage.Total, result.Usage.Points)
	}
	expectedCalls := 4
	if onlyFree {
		expectedCalls = 2
	}
	if calls != expectedCalls {
		t.Fatal("expected one count probe and one formal completion per verified tier", calls)
	}
}
