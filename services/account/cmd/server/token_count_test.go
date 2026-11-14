package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"testing"
)

func sameJSON(first, second []byte) bool {
	var left, right any
	return json.Unmarshal(first, &left) == nil && json.Unmarshal(second, &right) == nil && reflect.DeepEqual(left, right)
}

func TestChatUsageCounterGuards(t *testing.T) {
	for _, test := range []struct {
		name, body string
		want       int64
	}{
		{"valid", `{"choices":[{"message":{"content":"x"}}],"usage":{"prompt_tokens":34,"completion_tokens":2}}`, 34},
		{"missing-input", `{"choices":[{}],"usage":{"completion_tokens":1}}`, 0},
		{"missing-output", `{"choices":[{}],"usage":{"prompt_tokens":34}}`, 0},
		{"unbounded-output", `{"choices":[{}],"usage":{"prompt_tokens":34,"completion_tokens":100}}`, 0},
		{"reasoning-text", `{"choices":[{"message":{"reasoning_content":"private"}}],"usage":{"prompt_tokens":34,"completion_tokens":1}}`, 0},
		{"reasoning-tokens", `{"choices":[{}],"usage":{"prompt_tokens":34,"completion_tokens":1,"completion_tokens_details":{"reasoning_tokens":1}}}`, 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			tier := freeTier{name: "Aster-free", endpoint: "https://chat.invalid/v1", key: "fake", model: "private-model", unitsPerToken: 1, chatCount: true}
			payload := map[string]any{"model": tier.model, "max_tokens": int64(2048), "stream": false, "thinking": map[string]string{"type": "disabled"}, "reasoning_effort": "none", "messages": []message{{Role: "user", Content: "hello"}}}
			a := &app{http: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				var got map[string]any
				if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
					t.Fatal(err)
				}
				if got["max_tokens"] != float64(1) || got["model"] != tier.model || got["stream"] != false || got["reasoning_effort"] != "none" || r.URL.Path != "/v1/chat/completions" {
					t.Fatal("probe contract changed", got)
				}
				return response(test.body), nil
			})}}
			got, err := a.countInput(context.Background(), tier, payload)
			if (err == nil) != (test.want > 0) || got != test.want {
				t.Fatal(got, err)
			}
			if payload["max_tokens"] != int64(2048) {
				t.Fatal("probe changed formal reply budget")
			}
		})
	}
}

func TestChatUsageCounterBilling(t *testing.T) {
	db := testDB(t)
	a := &app{db: db, cfg: config{normalURL: "https://normal.invalid/v1", normalKey: "normal-key", normalModel: "normal-model", normalChatCount: true, upstreamURL: "https://go.invalid/v1", upstreamKey: "go-key", upstreamModel: "go-model", goChatCount: true}}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	uid, err := identityAccount(context.Background(), tx, "email", "chat-probe@example.invalid", "probe user")
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
	probes, answers, mode := 0, 0, "normal"
	var probeMessages string
	a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		var payload map[string]any
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		messages, _ := json.Marshal(payload["messages"])
		thinking, _ := payload["thinking"].(map[string]any)
		if thinking["type"] != "disabled" || payload["reasoning_effort"] != "none" {
			t.Fatal("thinking enabled")
		}
		input, output, reasoning := 200, 50, ""
		if payload["max_tokens"] == float64(1) {
			probes++
			probeMessages = string(messages)
			output = 2
			if mode == "oversize" {
				input = 125953
			}
			if mode == "reasoning" {
				reasoning = "private"
			}
		} else {
			answers++
			if string(messages) != probeMessages {
				t.Fatal("formal prompt differs from count probe")
			}
			if mode == "drift" {
				input = 201
			}
		}
		return response(fmt.Sprintf(`{"choices":[{"message":{"content":"OK","reasoning_content":%q},"finish_reason":"stop"}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}`, reasoning, input, output)), nil
	})}
	body := func(id string) string {
		return fmt.Sprintf(`{"request_id":%q,"messages":[{"role":"user","content":"hello"}]}`, id)
	}
	balance := func() (int64, int64) {
		var b, r int64
		if err := db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&b, &r); err != nil {
			t.Fatal(err)
		}
		return b, r
	}
	for _, tier := range []struct{ path, id, price string }{{"/api/v1/free/chat", "chat-normal-1", "0.250"}, {"/api/v1/free-go/chat", "chat-go-1", "1.250"}} {
		reply := direct(a, "POST", tier.path, body(tier.id), token)
		if reply.Code != 200 || !strings.Contains(reply.Body.String(), `"points":"`+tier.price+`"`) {
			t.Fatal(reply.Code, reply.Body.String())
		}
		cached := direct(a, "POST", tier.path, body(tier.id), token)
		if cached.Code != 200 || !sameJSON(cached.Body.Bytes(), reply.Body.Bytes()) {
			t.Fatal("cache failed")
		}
	}
	if probes != 2 || answers != 2 {
		t.Fatal("replays incurred extra upstream work", probes, answers)
	}
	before, reserved := balance()
	if before != 998500 || reserved != 0 {
		t.Fatal("probe usage billed", before, reserved)
	}
	crossed := direct(a, "POST", "/api/v1/free-go/chat", body("chat-normal-1"), token)
	if crossed.Code != 409 || probes != 2 {
		t.Fatal("cross-tier replay caused a probe")
	}
	for _, bad := range []struct {
		mode, code string
		status     int
	}{{"oversize", "CONTEXT_LIMIT_EXCEEDED", 403}, {"reasoning", "TOKEN_COUNT_UNAVAILABLE", 503}} {
		mode = bad.mode
		previousAnswers := answers
		reply := direct(a, "POST", "/api/v1/free-go/chat", body("chat-bad-"+bad.mode), token)
		if reply.Code != bad.status || !strings.Contains(reply.Body.String(), bad.code) {
			t.Fatal(reply.Code, reply.Body.String())
		}
		b, r := balance()
		if b != before || r != 0 {
			t.Fatal("failed probe or mismatch charged", b, r)
		}
		if mode != "drift" && answers != previousAnswers {
			t.Fatal("invalid count reached formal completion")
		}
	}
	if _, err = db.Exec(`UPDATE users SET balance_units=0 WHERE id=$1`, uid); err != nil {
		t.Fatal(err)
	}
	beforeProbe := probes
	reply := direct(a, "POST", "/api/v1/free-go/chat", body("chat-no-credit"), token)
	if reply.Code != 403 || probes != beforeProbe {
		t.Fatal("insufficient wallet spent probe calls")
	}
}
