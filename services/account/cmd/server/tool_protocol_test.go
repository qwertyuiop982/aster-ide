package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

func protocolFixture() string {
	return asterToolProtocolPrefix + "\n可用工具：" + `[{"type":"function","function":{"name":"read_file","parameters":{"type":"object"}}},{"type":"function","function":{"name":"finish","parameters":{"type":"object"}}}]`
}

func TestToolProtocolScopeAndValidation(t *testing.T) {
	if detectToolProtocol(chatInput{Messages: []message{{Role: "user", Content: "hello"}}}) != nil {
		t.Fatal("plain chat changed")
	}
	if detectToolProtocol(chatInput{Messages: []message{{Role: "user", Content: "请总结完整会话"}}}) != nil {
		t.Fatal("compaction changed")
	}
	p := detectToolProtocol(chatInput{Messages: []message{{Role: "user", Content: protocolFixture()}, {Role: "user", Content: "工具结果 read_file：read"}}})
	if p == nil || !p.observed || !strings.Contains(p.instructions(), "tool_calls") {
		t.Fatal("protocol not detected")
	}
	for _, s := range []string{
		`{"content":"读取","tool_calls":[{"name":"read_file","arguments":{"path":"README.md"}}]}`,
		`{"content":"结果","tool_calls":[{"name":"finish","arguments":{"summary":"已读取，未运行测试。"}}]}`,
	} {
		if err := p.validate(s); err != nil {
			t.Fatal(err)
		}
	}
	for _, s := range []string{
		"read_file path README.md", "```json\n{}\n```", `{"content":"结束","tool_calls":[]}`,
		`{"content":"结束","tool_calls":null}`, `{"content":{},"tool_calls":[]}`,
		`{"content":"调用","tool_calls":[{"name":"unknown","arguments":{}}]}`,
		`{"content":"调用","tool_calls":[{"name":"read_file","arguments":"README.md"}]}`,
		`{"content":"完成","tool_calls":[{"name":"finish","arguments":{}}]}`,
	} {
		if p.validate(s) == nil {
			t.Fatal("invalid protocol accepted", s)
		}
	}
}

func TestToolProtocolRepairSettlement(t *testing.T) {
	for _, mode := range []string{"repair", "always-invalid"} {
		t.Run(mode, func(t *testing.T) {
			db := testDB(t)
			a := &app{db: db, cfg: config{normalURL: "https://private.invalid/v1", normalKey: "fake", normalModel: "fake", normalChatCount: true}}
			tx, err := db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			uid, err := identityAccount(context.Background(), tx, "email", mode+"@example.invalid", "protocol")
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
			replies, calls := 0, 0
			a.http = &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				var p map[string]any
				if err = json.NewDecoder(r.Body).Decode(&p); err != nil {
					t.Fatal(err)
				}
				format, _ := p["response_format"].(map[string]any)
				if format["type"] != "json_object" {
					t.Fatal("JSON mode absent")
				}
				messages, _ := p["messages"].([]any)
				if !strings.Contains(messages[0].(map[string]any)["content"].(string), "每次响应必须") {
					t.Fatal("server instructions absent")
				}
				input, output, text := int64(100), int64(1), "x"
				if p["max_tokens"] != float64(1) {
					replies++
					input, output = 140, 20
					text = "read_file 参数：README.md"
					if replies == 2 && mode == "repair" {
						text = `{"content":"读取说明","tool_calls":[{"name":"read_file","arguments":{"path":"README.md"}}]}`
						input, output = 180, 50
					}
				}
				encoded, _ := json.Marshal(text)
				return response(fmt.Sprintf(`{"choices":[{"message":{"content":%s},"finish_reason":"stop"}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}`, encoded, input, output)), nil
			})}
			encoded, _ := json.Marshal(chatInput{RequestID: "protocol-live-check", Messages: []message{{Role: "user", Content: protocolFixture()}, {Role: "user", Content: "读取 README.md"}}})
			reply := direct(a, "POST", "/api/v1/free/chat", string(encoded), token)
			want := 200
			if mode == "always-invalid" {
				want = 502
			}
			if reply.Code != want || calls != 3 {
				t.Fatal(reply.Code, reply.Body.String(), calls)
			}
			var balance, reserved, charged int64
			if err = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &reserved); err != nil {
				t.Fatal(err)
			}
			if err = db.QueryRow(`SELECT charged_units FROM usage_requests WHERE user_id=$1`, uid).Scan(&charged); err != nil {
				t.Fatal(err)
			}
			if reserved != 0 {
				t.Fatal("hold leaked")
			}
			expected := int64(0)
			if mode == "repair" {
				expected = 230
			}
			if charged != expected || balance != 1000000-expected {
				t.Fatal("invalid completion charged", charged, balance)
			}
			if mode == "repair" {
				cached := direct(a, "POST", "/api/v1/free/chat", string(encoded), token)
				if cached.Code != 200 || calls != 3 {
					t.Fatal("replay repeated provider work")
				}
			}
		})
	}
}
