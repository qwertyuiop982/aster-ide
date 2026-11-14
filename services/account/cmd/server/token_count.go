package main

import (
	"context"
	"net/http"
	"strings"
	"time"
)

// Chat providers can tokenize Messages and Responses differently. This optional
// counter observes the real Chat prompt usage on a bounded one-token completion.
// Probe usage is service overhead and is never charged to the user's wallet.
// The actual completion must report the same prompt count or no credits settle.
func (a *app) countInput(ctx context.Context, tier freeTier, payload map[string]any) (int64, error) {
	unavailable := fail(http.StatusServiceUnavailable, "TOKEN_COUNT_UNAVAILABLE", "暂时无法可靠计算 Chat 上下文长度，本次不扣积分。")
	limit := 15 * time.Second
	if tier.chatCount {
		limit = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, limit)
	defer cancel()
	if !tier.chatCount {
		var counted struct {
			Input *int64 `json:"input_tokens"`
			Exact bool   `json:"exact"`
		}
		_, err := a.remoteJSON(ctx, "POST", tier.counterURL, payload, map[string]string{"Authorization": "Bearer " + tier.counterKey}, &counted)
		if err != nil || counted.Input == nil || !counted.Exact || *counted.Input < 1 {
			return 0, unavailable
		}
		return *counted.Input, nil
	}
	probe := make(map[string]any, len(payload))
	for key, value := range payload {
		probe[key] = value
	}
	probe["max_tokens"] = 1
	// Some adapters ignore a one-token request. Permit a per-tier,
	// operator-selected ceiling, never more than the formal output budget.
	// Invalid/unset settings retain the eight-token limit. Reasoning guards
	// below still apply independently of this completion-token allowance.
	maxProbeOutput := int64(8)
	if tier.probeMaxOutput >= 8 && tier.probeMaxOutput <= 2048 {
		maxProbeOutput = tier.probeMaxOutput
	}
	var result struct {
		Choices []struct {
			Message struct {
				Reasoning string `json:"reasoning_content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			Input   *int64 `json:"prompt_tokens"`
			Output  *int64 `json:"completion_tokens"`
			Details struct {
				Reasoning int64 `json:"reasoning_tokens"`
			} `json:"completion_tokens_details"`
		} `json:"usage"`
	}
	_, err := a.remoteJSON(ctx, "POST", strings.TrimRight(tier.endpoint, "/")+"/chat/completions", probe, map[string]string{"Authorization": "Bearer " + tier.key}, &result)
	if err != nil || len(result.Choices) == 0 || result.Usage.Input == nil || *result.Usage.Input < 1 || result.Usage.Output == nil || *result.Usage.Output < 0 || *result.Usage.Output > maxProbeOutput || (result.Usage.Details.Reasoning < 0 || result.Usage.Details.Reasoning > *result.Usage.Output || (!tier.fixedReasoning && result.Usage.Details.Reasoning != 0)) {
		return 0, unavailable
	}
	// Count probe cost is service overhead. Only the formal completion is
	// charged; its prompt count must still exactly match this observation.
	for _, choice := range result.Choices {
		if !tier.fixedReasoning && strings.TrimSpace(choice.Message.Reasoning) != "" {
			return 0, unavailable
		}
	}
	return *result.Usage.Input, nil
}
