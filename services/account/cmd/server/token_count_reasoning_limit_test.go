package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
)

func TestReasoningProbeOutputCeiling(t *testing.T) {
	for _, tt := range []struct {
		name                       string
		fixed                      bool
		ceiling, output, reasoning int64
		wantOK                     bool
	}{
		{"new-provider-reasoning", true, 2048, 27, 25, true},
		{"at-ceiling", true, 2048, 2048, 2047, true},
		{"over-ceiling", true, 2048, 2049, 1, false},
		{"default-stays-eight", true, 0, 27, 25, false},
		{"normal-tier-stays-eight", false, 0, 27, 0, false},
		{"normal-adapter-accounting", false, 2048, 35, 0, true},
		{"normal-reasoning-still-forbidden", false, 2048, 35, 1, false},
		{"invalid-ceiling-stays-eight", true, 999999, 27, 0, false},
		{"negative-ceiling-stays-eight", true, -1, 27, 0, false},
		{"invalid-reasoning", true, 2048, 27, 28, false},
		{"negative-output", true, 2048, -1, 0, false},
	} {
		t.Run(tt.name, func(t *testing.T) {
			tier := freeTier{endpoint: "https://chat.invalid/v1", key: "fake", model: "model", chatCount: true, fixedReasoning: tt.fixed, probeMaxOutput: tt.ceiling}
			payload := map[string]any{"model": "model", "max_tokens": int64(2040), "messages": []message{{Role: "user", Content: "hello"}}, "reasoning_effort": "low", "stream": false}
			a := &app{http: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				var got map[string]any
				if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
					t.Fatal(err)
				}
				if got["max_tokens"] != float64(1) {
					t.Fatal("probe request grew", got)
				}
				return response(fmt.Sprintf(`{"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":111,"completion_tokens":%d,"completion_tokens_details":{"reasoning_tokens":%d}}}`, tt.output, tt.reasoning)), nil
			})}}
			count, err := a.countInput(context.Background(), tier, payload)
			if (err == nil) != tt.wantOK {
				t.Fatalf("count=%d error=%v wantOK=%v", count, err, tt.wantOK)
			}
			if tt.wantOK && count != 111 {
				t.Fatal(count)
			}
			if payload["max_tokens"] != int64(2040) {
				t.Fatal("formal budget mutated")
			}
		})
	}
}

func TestReasoningProbeConfiguration(t *testing.T) {
	t.Setenv("ASTER_GO_COUNTER_MAX_OUTPUT_TOKENS", "2048")
	t.Setenv("ASTER_GO_REASONING_EFFORT", "low")
	t.Setenv("ASTER_FREE_NORMAL_COUNTER_MAX_OUTPUT_TOKENS", "64")
	a := &app{cfg: loadConfig()}
	if a.tier(true).probeMaxOutput != 2048 || !a.tier(true).fixedReasoning {
		t.Fatal("Go configuration not applied")
	}
	if a.tier(false).probeMaxOutput != 64 {
		t.Fatal("Go limit affected normal free")
	}
}
