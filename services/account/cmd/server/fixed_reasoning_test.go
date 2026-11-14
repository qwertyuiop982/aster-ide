package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

func TestFixedReasoningProbeGuards(t *testing.T) {
	for _, tc := range []struct {
		name, usage string
		valid       bool
	}{
		{"bounded", `"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":1}`, true},
		{"negative", `"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":-1}`, false},
		{"exceeds-output", `"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":5}`, false},
		{"unbounded", `"completion_tokens":9,"completion_tokens_details":{"reasoning_tokens":1}`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			tier := freeTier{endpoint: "https://private.invalid/v1", key: "fake", model: "private", chatCount: true, fixedReasoning: true}
			a := &app{http: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				var payload map[string]any
				if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
					t.Fatal(err)
				}
				if payload["reasoning_effort"] != "low" || payload["thinking"] != nil || payload["max_tokens"] != float64(1) {
					t.Fatal("incorrect fixed probe options")
				}
				return response(`{"choices":[{"message":{"reasoning_content":"internal"}}],"usage":{"prompt_tokens":182,` + tc.usage + `}}`), nil
			})}}
			_, err := a.countInput(context.Background(), tier, map[string]any{"reasoning_effort": "low"})
			if (err == nil) != tc.valid {
				t.Fatal("incorrect probe validation", err)
			}
		})
	}
}

func TestFixedGoReasoningIsPrivateAndBilledOnce(t *testing.T) {
	db := testDB(t)
	a := &app{db: db, cfg: config{upstreamURL: "https://private.invalid/v1", upstreamKey: "private-key", upstreamModel: "private-model", goChatCount: true, goReasoningEffort: "low", goCounterMaxOutput: 2048}}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	uid, err := identityAccount(context.Background(), tx, "email", "fixed-reasoning@example.invalid", "reasoning smoke")
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
	a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		var payload map[string]any
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		if payload["reasoning_effort"] != "low" || payload["thinking"] != nil {
			t.Fatal("Go options not fixed by server")
		}
		output, reasoning := int64(30), int64(20)
		if payload["max_tokens"] == float64(1) {
			output, reasoning = 27, 25
		} else if payload["max_tokens"] != float64(2040) {
			t.Fatal("output accounting margin missing")
		}
		return response(fmt.Sprintf(`{"choices":[{"message":{"content":"final answer","reasoning_content":"private thoughts"},"finish_reason":"stop"}],"usage":{"prompt_tokens":182,"completion_tokens":%d,"completion_tokens_details":{"reasoning_tokens":%d}}}`, output, reasoning)), nil
	})}
	body := `{"request_id":"fixed-go-chat-01","messages":[{"role":"user","content":"hello"}]}`
	reply := direct(a, "POST", "/api/v1/free-go/chat", body, token)
	if reply.Code != 200 || !strings.Contains(reply.Body.String(), `"points":"1.060"`) {
		t.Fatal(reply.Code, reply.Body.String())
	}
	for _, private := range []string{"private thoughts", "reasoning_content", "private-model", "private-key"} {
		if strings.Contains(reply.Body.String(), private) {
			t.Fatal("private data in reply")
		}
	}
	var balance, held int64
	if err = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &held); err != nil {
		t.Fatal(err)
	}
	if balance != 1000000-(182+30)*5 || held != 0 {
		t.Fatal("reasoning counted twice or hold not released", balance, held)
	}
	cached := direct(a, "POST", "/api/v1/free-go/chat", body, token)
	if cached.Code != 200 || calls != 2 || !sameJSON(reply.Body.Bytes(), cached.Body.Bytes()) {
		t.Fatal("cached request repeated provider work")
	}
	catalog := direct(a, "GET", "/api/v1/models", "", "")
	if !strings.Contains(catalog.Body.String(), `"thinking_mode":"fixed_low"`) || !strings.Contains(catalog.Body.String(), `"reasoning_in_output_tokens":true`) {
		t.Fatal("catalog hides fixed reasoning policy")
	}
	// Changing the private model must not replay a reply from the previous model.
	a.cfg.upstreamModel = "another-private-model"
	changed := direct(a, "POST", "/api/v1/free-go/chat", body, token)
	if changed.Code != 409 || calls != 2 {
		t.Fatal("model switch reused an old completion")
	}
}
