package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

func TestFormalUsageDriftAndReplay(t *testing.T) {
	for _, tc := range []struct {
		name    string
		actual  int64
		balance int64
		want    int
	}{
		{"less-than-probe", 180, 1000000, 200},
		{"more-than-probe", 240, 1000000, 200},
		{"above-original-hold", 2500, 1000000, 200},
		{"above-hold-insufficient", 2500, 2250, 403},
	} {
		t.Run(tc.name, func(t *testing.T) {
			db := testDB(t)
			a := &app{db: db, cfg: config{normalURL: "https://private.invalid/v1", normalKey: "fake", normalModel: "fake", normalChatCount: true}}
			tx, err := db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			uid, err := identityAccount(context.Background(), tx, "email", tc.name+"@example.invalid", "drift")
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
			if _, err = db.Exec(`UPDATE users SET balance_units=$1 WHERE id=$2`, tc.balance, uid); err != nil {
				t.Fatal(err)
			}
			calls := 0
			a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				var p map[string]any
				if err = json.NewDecoder(r.Body).Decode(&p); err != nil {
					t.Fatal(err)
				}
				input, output := tc.actual, int64(10)
				if p["max_tokens"] == float64(1) {
					input, output = 200, 1
				}
				return response(fmt.Sprintf(`{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}`, input, output)), nil
			})}
			body := `{"request_id":"actual-usage-check","messages":[{"role":"user","content":"hello"}]}`
			reply := direct(a, "POST", "/api/v1/free/chat", body, token)
			if reply.Code != tc.want {
				t.Fatal(reply.Code, reply.Body.String())
			}
			var balance, held, charged int64
			var status string
			if err = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &held); err != nil {
				t.Fatal(err)
			}
			if err = db.QueryRow(`SELECT status,charged_units FROM usage_requests WHERE user_id=$1`, uid).Scan(&status, &charged); err != nil {
				t.Fatal(err)
			}
			if held != 0 {
				t.Fatal("reservation leaked", held)
			}
			if tc.want == 200 {
				if charged != tc.actual+10 || balance != tc.balance-charged || status != "completed" {
					t.Fatal("formal usage not settled", charged, balance, status)
				}
				cached := direct(a, "POST", "/api/v1/free/chat", body, token)
				if cached.Code != 200 || calls != 2 || !sameJSON(cached.Body.Bytes(), reply.Body.Bytes()) {
					t.Fatal("replay was billed")
				}
				if !strings.Contains(reply.Body.String(), fmt.Sprintf(`"input_tokens":%d`, tc.actual)) {
					t.Fatal(reply.Body.String())
				}
			} else if charged != 0 || balance != tc.balance || status != "failed" {
				t.Fatal("failed request charged", charged, balance, status)
			}
		})
	}
}
