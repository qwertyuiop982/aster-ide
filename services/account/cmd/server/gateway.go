package main

import (
	"context"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"time"
)

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}
type chatInput struct {
	RequestID string    `json:"request_id"`
	Messages  []message `json:"messages"`
}

const freeUnitsPerToken = 1
const goUnitsPerToken = 5

const freeThinkingMode = "disabled"
const freeReasoningEffort = "none"

type freeTier struct {
	name, endpoint, key, model, counterURL, counterKey string
	unitsPerToken                                      int64
	probeMaxOutput                                     int64
	chatCount                                          bool
	fixedReasoning                                     bool
}

func (a *app) tier(goMode bool) freeTier {
	if goMode {
		return freeTier{name: "Aster-free go", endpoint: a.cfg.upstreamURL, key: a.cfg.upstreamKey, model: a.cfg.upstreamModel, counterURL: a.cfg.counterURL, counterKey: a.cfg.counterKey, unitsPerToken: goUnitsPerToken, probeMaxOutput: a.cfg.goCounterMaxOutput, chatCount: a.cfg.goChatCount, fixedReasoning: a.cfg.goReasoningEffort == "low"}
	}
	return freeTier{name: "Aster-free", endpoint: a.cfg.normalURL, key: a.cfg.normalKey, model: a.cfg.normalModel, counterURL: a.cfg.normalCounterURL, counterKey: a.cfg.normalCounterKey, unitsPerToken: freeUnitsPerToken, probeMaxOutput: a.cfg.normalCounterMaxOutput, chatCount: a.cfg.normalChatCount}
}

func (t freeTier) thinkingMode() string {
	if t.fixedReasoning {
		return "fixed_low"
	}
	return freeThinkingMode
}
func (t freeTier) reasoningEffort() string {
	if t.fixedReasoning {
		return "low"
	}
	return freeReasoningEffort
}
func (t freeTier) available() bool {
	return t.endpoint != "" && t.key != "" && t.model != "" && (t.counterURL != "" || t.chatCount)
}

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{8,80}$`)

func validateMessages(in chatInput) error {
	if !requestIDPattern.MatchString(in.RequestID) || len(in.Messages) == 0 || len(in.Messages) > 300 {
		return fail(400, "INVALID_REQUEST", "请求编号或消息数量无效。")
	}
	for _, m := range in.Messages {
		if (m.Role != "user" && m.Role != "assistant") || len(m.Content) == 0 {
			return fail(400, "CUSTOM_PROMPT_NOT_ALLOWED", "free 仅接受用户和助手消息，系统提示词由服务端维护。")
		}
	}
	if in.Messages[len(in.Messages)-1].Role != "user" {
		return fail(400, "INVALID_REQUEST", "最后一条消息必须是用户任务。")
	}
	return nil
}
func contextBudget(input, output int64) error {
	if input < 1 || output < 1 {
		return fail(503, "TOKEN_COUNT_UNAVAILABLE", "暂时无法可靠计算上下文长度。")
	}
	if input > 128000 || output > 128000 || input+output > 128000 {
		return fail(403, "CONTEXT_LIMIT_EXCEEDED", "上下文预算超过 128000 token，请缩短消息历史。")
	}
	return nil
}
func (a *app) chat(w http.ResponseWriter, r *http.Request, id int64) error {
	return a.chatForTier(w, r, id, false)
}
func (a *app) chatForTier(w http.ResponseWriter, r *http.Request, id int64, goMode bool) error {
	tier := a.tier(goMode)
	var in chatInput
	if e := decode(w, r, &in); e != nil {
		return e
	}
	if e := validateMessages(in); e != nil {
		return e
	}
	if !tier.available() {
		return fail(503, "FREE_NOT_CONFIGURED", tier.name+" 尚未配置完成，请使用自定义 API。")
	}
	prompt := persona
	if goMode {
		prompt = strings.Replace(persona, "Aster-free", tier.name, 1)
	}
	protocol := detectToolProtocol(in)
	if protocol != nil {
		prompt += protocol.instructions()
	}
	messages := append([]message{{Role: "system", Content: prompt}}, in.Messages...)
	const outputBudget int64 = 2048
	payload := map[string]any{"model": tier.model, "messages": messages, "stream": false, "max_tokens": outputBudget, "reasoning_effort": tier.reasoningEffort()}
	if !tier.fixedReasoning {
		payload["thinking"] = map[string]string{"type": freeThinkingMode}
	} else {
		// Some Chat adapters add a few accounting tokens to the requested cap.
		// Keep that small overhead inside the wallet's fixed 2048-token budget.
		payload["max_tokens"] = outputBudget - 8
	}
	if protocol != nil {
		payload["response_format"] = map[string]string{"type": "json_object"}
	}
	canonicalInput := map[string]any{"messages": in.Messages, "tier": tier.name, "units_per_token": tier.unitsPerToken, "prompt_version": promptVersion, "thinking_mode": tier.thinkingMode(), "reasoning_effort": tier.reasoningEffort()}
	canonicalInput["provider_model"] = tier.model
	canonical, _ := json.Marshal(canonicalInput)
	fingerprint := digest(string(canonical))
	cached, e := a.walletPreflight(r.Context(), id, in.RequestID, fingerprint, (outputBudget+1)*tier.unitsPerToken)
	if e != nil {
		return e
	}
	if len(cached) > 0 {
		writeJSON(w, 200, cached)
		return nil
	}
	counted, e := a.countInput(r.Context(), tier, payload)
	if e != nil {
		return e
	}
	if e = contextBudget(counted, outputBudget); e != nil {
		return e
	}
	budgetUnits := (counted + outputBudget) * tier.unitsPerToken
	cached, e = a.reserve(r.Context(), id, in.RequestID, fingerprint, budgetUnits)
	if e != nil {
		return e
	}
	if len(cached) > 0 {
		writeJSON(w, 200, cached)
		return nil
	}
	// Once accepted, a short independent context allows actual usage to settle even if the phone disconnects.
	requestLimit := 180 * time.Second
	if tier.chatCount {
		requestLimit = 150 * time.Second
	}
	upstreamCtx, cancelUpstream := context.WithTimeout(context.Background(), requestLimit)
	defer cancelUpstream()
	var upstream struct {
		Choices []struct {
			Message struct {
				message
				ReasoningContent string `json:"reasoning_content"`
			} `json:"message"`
			Finish string `json:"finish_reason"`
		} `json:"choices"`
		Usage struct {
			Input         *int64 `json:"prompt_tokens"`
			Output        *int64 `json:"completion_tokens"`
			OutputDetails struct {
				ReasoningTokens int64 `json:"reasoning_tokens"`
			} `json:"completion_tokens_details"`
		} `json:"usage"`
	}
	status, e := a.remoteJSON(upstreamCtx, "POST", strings.TrimRight(tier.endpoint, "/")+"/chat/completions", payload, map[string]string{"Authorization": "Bearer " + tier.key}, &upstream)
	protocolInvalid := false
	if protocol != nil && e == nil {
		for attempt := 0; attempt < 2; attempt++ {
			candidate := ""
			if len(upstream.Choices) > 0 {
				candidate = upstream.Choices[0].Message.Content
			}
			if protocol.validate(candidate) == nil {
				break
			}
			if attempt == 1 {
				protocolInvalid = true
				break
			}
			repaired := make(map[string]any, len(payload))
			for key, value := range payload {
				repaired[key] = value
			}
			repaired["messages"] = toolRepairMessages(messages, candidate)
			upstream.Choices = nil
			upstream.Usage.Input = nil
			upstream.Usage.Output = nil
			upstream.Usage.OutputDetails.ReasoningTokens = 0
			status, e = a.remoteJSON(upstreamCtx, "POST", strings.TrimRight(tier.endpoint, "/")+"/chat/completions", repaired, map[string]string{"Authorization": "Bearer " + tier.key}, &upstream)
			if e != nil {
				break
			}
		}
	}
	settleCtx, cancelSettle := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelSettle()
	if e != nil {
		known := status >= 400 && status < 500
		if settlementErr := a.settleTier(settleCtx, id, in.RequestID, 0, 0, nil, known, tier); settlementErr != nil {
			return settlementErr
		}
		return fail(502, "MODEL_SERVICE_UNAVAILABLE", "Aster-free 暂时不可用，请稍后重试。")
	}
	if protocolInvalid {
		if e = a.settleTier(settleCtx, id, in.RequestID, 0, 0, nil, true, tier); e != nil {
			return e
		}
		return fail(502, "TOOL_PROTOCOL_UNAVAILABLE", "模型未返回完整的工具 JSON，本次不扣积分，请重试。")
	}
	hasReasoning := upstream.Usage.OutputDetails.ReasoningTokens > 0
	for _, choice := range upstream.Choices {
		hasReasoning = hasReasoning || strings.TrimSpace(choice.Message.ReasoningContent) != ""
	}
	if hasReasoning && !tier.fixedReasoning {
		if e = a.settleTier(settleCtx, id, in.RequestID, 0, 0, nil, true, tier); e != nil {
			return e
		}
		return fail(502, "THINKING_MODE_UNAVAILABLE", "模型服务未遵守无思考设置，本次不扣积分，请稍后重试。")
	}
	if upstream.Usage.Input == nil || upstream.Usage.Output == nil || len(upstream.Choices) == 0 {
		if e = a.settleTier(settleCtx, id, in.RequestID, 0, 0, nil, false, tier); e != nil {
			return e
		}
		return fail(502, "USAGE_UNAVAILABLE", "请求用量待核对，本次预占暂时保留。")
	}
	input, output := *upstream.Usage.Input, *upstream.Usage.Output
	// Chat usage probes are admission estimates: load-balanced upstreams can
	// report different prompt counts for the same messages. Bill only formal
	// response usage; dedicated exact counters retain their strict contract.
	if input != counted && !tier.chatCount {
		if e = a.settleTier(settleCtx, id, in.RequestID, 0, 0, nil, true, tier); e != nil {
			return e
		}
		return fail(502, "TOKEN_COUNT_MISMATCH", "Chat 输入用量与预检不一致，本次不扣积分，请稍后重试。")
	}
	if input < 1 || output < 0 || output > outputBudget || input > 128000 || input+output > 128000 || upstream.Usage.OutputDetails.ReasoningTokens < 0 || upstream.Usage.OutputDetails.ReasoningTokens > output {
		if e = a.settleTier(settleCtx, id, in.RequestID, input, output, nil, false, tier); e != nil {
			return e
		}
		return fail(502, "USAGE_RECONCILIATION_REQUIRED", "请求用量待核对，请稍后重试。")
	}
	finish := upstream.Choices[0].Finish
	if finish != "length" && finish != "stop" && finish != "content_filter" {
		finish = "stop"
	}
	normalized := map[string]any{"id": "aster-" + randomToken(), "model": tier.name, "choices": []any{map[string]any{"index": 0, "message": message{Role: "assistant", Content: upstream.Choices[0].Message.Content}, "finish_reason": finish}}, "usage": map[string]any{"input_tokens": input, "output_tokens": output, "total_tokens": input + output, "points": points((input + output) * tier.unitsPerToken)}}
	if e = a.settleTier(settleCtx, id, in.RequestID, input, output, normalized, true, tier); e != nil {
		return e
	}
	writeJSON(w, 200, normalized)
	return nil
}
