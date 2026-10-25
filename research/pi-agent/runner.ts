/* Product host for the unmodified Pi agent loop, session tree and compaction.
 * All project and model I/O crosses the authenticated Aster Python bridge. */
import { Agent } from '@earendil-works/pi-agent-core';
import { AssistantMessageEventStream, getCurrentSystemPrompt, getCurrentTools, Type } from '@earendil-works/pi-ai';
import { SessionManager } from '../../vendor/pi/packages/coding-agent/src/core/session-manager.ts';
import { convertToLlm } from '../../vendor/pi/packages/coding-agent/src/core/messages.ts';
import { prepareCompaction, compact, estimateContextTokens } from '../../vendor/pi/packages/coding-agent/src/core/compaction/compaction.ts';
import { createInterface } from 'node:readline';
import { dirname } from 'node:path';

export const ENGINE = 'Pi Coding Agent 1.3.0';
const pending = new Map();
let sequence = 0;
let begin;
const started = new Promise(resolve => { begin = resolve; });
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
function output(value) { process.stdout.write(JSON.stringify(value) + '\n'); }
function rpc(method, payload) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    output({ type: 'request', id, method, payload });
  });
}
input.on('line', line => {
  try {
    const value = JSON.parse(line);
    if (value.type === 'start') { begin(value); return; }
    const waiter = pending.get(value.id);
    if (!waiter) return;
    pending.delete(value.id);
    if (value.error) waiter.reject(new Error(value.error)); else waiter.resolve(value.result);
  } catch (error) {
    for (const waiter of pending.values()) waiter.reject(error);
    pending.clear();
  }
});
input.on('close', () => {
  for (const waiter of pending.values()) waiter.reject(new Error('Agent transport closed'));
  pending.clear();
});
const usage = () => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } });
function assistant(model, text = '') {
  return { role: 'assistant', content: text ? [{ type: 'text', text }] : [], api: model.api,
    provider: model.provider, model: model.id, usage: usage(), stopReason: 'stop', timestamp: Date.now() };
}
function asText(content) {
  return typeof content === 'string' ? content : (content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
}
function toOpenAI(context) {
  const messages = [];
  const system = getCurrentSystemPrompt(context.messages);
  if (system) messages.push({ role: 'system', content: system });
  for (const message of context.messages) {
    if (message.role === 'user') messages.push({ role: 'user', content: asText(message.content) });
    else if (message.role === 'assistant') {
      const calls = message.content.filter(c => c.type === 'toolCall').map(c => ({ id: c.id, type: 'function',
        function: { name: c.name, arguments: JSON.stringify(c.arguments) } }));
      messages.push({ role: 'assistant', content: asText(message.content), ...(calls.length ? { tool_calls: calls } : {}) });
    } else if (message.role === 'toolResult') {
      messages.push({ role: 'tool', tool_call_id: message.toolCallId, content: asText(message.content) });
    }
  }
  return messages;
}

async function main() {
  const config = await started;
  const freeMode = config.freeMode === true;
  const maxSteps = freeMode ? 64 : Math.max(1, Math.min(config.maxSteps || 32, 128));
  const maxToolCalls = freeMode ? 64 : Infinity;
  let toolCalls = 0;
  const thinkingLevel = config.thinkingLevel && config.thinkingLevel !== 'auto' ? config.thinkingLevel : 'off';
  const contextWindow = Math.max(8192, Math.min(config.contextWindow || 32768, 1048576));
  const model = { id: config.model || 'aster', name: config.model || 'Aster', api: 'openai-completions',
    provider: 'aster', baseUrl: '', reasoning: thinkingLevel !== 'off', input: ['text'],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow, maxTokens: config.maxTokens || 4096 };
  const manager = SessionManager.open(config.sessionFile, dirname(config.sessionFile), config.workspace);
  let steps = 0;
  let status = 'Submitted';
  let summary = '';
  let finished = false;
  let compacting = false;
  let compactions = manager.getBranch().filter(e => e.type === 'compaction').length;
  const reserveTokens = Math.max(model.maxTokens, Math.min(8192, Math.max(2048, Math.floor(contextWindow / 4))));
  if (reserveTokens > contextWindow - 1024) throw new Error("Output budget must leave at least 1024 tokens for input");
  const settings = { enabled: true, reserveTokens, keepRecentTokens: Math.min(12000, Math.floor((contextWindow - reserveTokens) / 2)) };

  // Restore old Aster text transcripts once. New sessions persist full tool exchanges.
  if (!manager.buildSessionContext().messages.some(m => m.role === 'user')) {
    for (const item of config.history || []) {
      if (item.role === 'user') manager.appendMessage({ role: 'user', content: item.content, timestamp: Date.now() });
      else if (item.role === 'assistant') manager.appendMessage(assistant(model, item.content));
    }
  }
  // A killed process may leave a completed assistant tool call without its result.
  // Complete that exchange before the provider sees the resumed transcript.
  const restored = manager.buildSessionContext().messages;
  const unresolved = new Map();
  for (const message of restored) {
    if (message.role === 'assistant') for (const call of message.content.filter(c => c.type === 'toolCall')) unresolved.set(call.id, call.name);
    else if (message.role === 'toolResult') unresolved.delete(message.toolCallId);
  }
  for (const [id, name] of unresolved) manager.appendMessage({ role: 'toolResult', toolCallId: id, toolName: name,
    content: [{ type: 'text', text: 'Previous execution was interrupted; inspect project state before retrying.' }], isError: true, timestamp: Date.now() });

  const tools = (config.tools || []).map(item => {
    const fn = item.function;
    return { name: fn.name, label: fn.name, description: fn.description, parameters: Type.Unsafe(fn.parameters),
      execute: async (id, args) => {
        if (finished) return { content: [{ type: 'text', text: 'Task already finished.' }], details: { ok: false } };
        toolCalls++;
        const result = await rpc('tool', { id, name: fn.name, arguments: args });
        if (result.finished) { finished = true; summary = result.summary || ''; }
        return { content: [{ type: 'text', text: JSON.stringify(result) }], details: result,
          terminate: finished || toolCalls >= maxToolCalls };
      } };
  });
  const currentMessages = manager.buildSessionContext().messages;
  const allowed = new Set(config.allowedTools || tools.map(t => t.name));
  const declarations = tools.filter(t => allowed.has(t.name)).map(({ name, description, parameters }) => ({ name, description, parameters }));
  manager.appendMessage({ role: 'system', content: '', sections: { aster: config.system }, timestamp: Date.now(),
    toolsRemoved: getCurrentTools(convertToLlm(currentMessages)).map(t => ({ name: t.name })), toolsAdded: declarations });

  function streamFn(_model, context, options) {
    const stream = new AssistantMessageEventStream();
    void (async () => {
      const message = assistant(model);
      stream.push({ type: 'start', partial: message });
      try {
        if (options?.signal?.aborted) throw new Error('Agent cancelled');
        const result = await rpc('model', { messages: toOpenAI(context), step: compacting ? steps : ++steps,
          purpose: compacting ? 'compaction' : 'task', reasoning: options?.reasoning, tools: compacting ? [] : getCurrentTools(context.messages).map(t => t.name) });
        if (result.content) message.content.push({ type: 'text', text: result.content });
        for (const call of result.tool_calls || []) {
          message.content.push({ type: 'toolCall', id: call.id, name: call.function.name, arguments: JSON.parse(call.function.arguments) });
        }
        const reported = result.usage || {};
        message.usage = { ...usage(), input: reported.prompt_tokens || 0, output: reported.completion_tokens || 0,
          totalTokens: reported.total_tokens || 0 };
        message.stopReason = message.content.some(c => c.type === 'toolCall') ? 'toolUse' : 'stop';
        stream.push({ type: 'done', reason: message.stopReason, message });
      } catch (error) {
        message.stopReason = options?.signal?.aborted ? 'aborted' : 'error';
        message.errorMessage = String(error.message || error);
        stream.push({ type: 'error', reason: message.stopReason, error: message });
      }
      stream.end();
    })();
    return stream;
  }

  async function compactContext(force = false, signal) {
    const messages = convertToLlm(manager.buildSessionContext().messages);
    const tokens = estimateContextTokens(messages).tokens;
    output({ type: 'event', kind: 'context', tokens, contextWindow, compactions, source: 'hybrid' });
    if (!force && tokens < contextWindow - settings.reserveTokens) return false;
    const preparation = prepareCompaction(manager.getBranch(), settings);
    if (!preparation) return false;
    output({ type: 'event', kind: 'status', text: '正在压缩上下文', step: steps });
    compacting = true;
    try {
      const result = await compact(preparation, model, undefined, undefined,
        'Preserve user constraints, decisions, exact paths, outstanding tasks and command results. Distinguish proposed edits from applied edits. Answer in the user language.',
        signal, 'off', streamFn, undefined, { maxRetries: 1 });
      manager.appendCompaction(result.summary, result.firstKeptEntryId, result.tokensBefore, result.details, false, result.usage);
      compactions++;
      output({ type: 'event', kind: 'context', tokens: estimateContextTokens(convertToLlm(manager.buildSessionContext().messages)).tokens, contextWindow, compactions, source: 'hybrid' });
      return true;
    } finally { compacting = false; }
  }

  const agent = new Agent({
    initialState: { messages: manager.buildSessionContext().messages, tools: tools.filter(t => allowed.has(t.name)), model, thinkingLevel },
    convertToLlm, streamFn, sessionId: manager.getSessionId(), toolExecution: 'sequential',
    beforeToolCall: async ({ toolCall, args }) => {
      if (finished || toolCalls >= maxToolCalls) return { block: true, terminate: true, reason: finished ? "任务已完成" : "已达到每轮任务 64 次工具调用上限" };
      if (args && typeof args === 'object' && '_invalid_arguments' in args) return { block: true, reason: '模型工具参数格式无效，请重新提供 JSON 对象' };
      if (!allowed.has(toolCall.name)) return { block: true, reason: '当前模式不允许此工具；问答和规划模式不能修改文件或运行命令' };
    },
    afterToolCall: async ({ result }) => ({ isError: result.details?.ok === false }),
    prepareRequest: async (request, signal) => {
      if (await compactContext(false, signal)) return { context: { ...request.context, messages: manager.buildSessionContext().messages } };
    },
    finishTurn: async ({ message }) => {
      if (finished) return { action: 'end' };
      if (message.stopReason === 'error' || message.stopReason === 'aborted') return;
      const calls = message.content.some(c => c.type === 'toolCall');
      if (!calls) { summary = asText(message.content); return { action: 'end' }; }
      if (toolCalls >= maxToolCalls || steps >= maxSteps) {
        status = 'LimitsExceeded';
        summary = freeMode ? '已达到每轮任务 64 次工具调用或模型轮次上限，现有修改可以预览；发送消息可继续。' : '已达到步骤上限，现有修改可以预览；发送消息可继续。';
        return { action: 'end' };
      }
      return { action: 'continue' };
    },
  });
  agent.subscribe(event => {
    if (event.type === 'message_end') manager.appendMessage(event.message);
  });
  if (config.compactOnly) {
    const changed = await compactContext(true);
    summary = changed ? '上下文已压缩，完整会话记录仍保留。' : '当前上下文较短，无需压缩。';
  } else {
    await agent.prompt(config.task);
    if (agent.state.errorMessage) throw new Error(agent.state.errorMessage);
    const last = agent.state.messages.filter(m => m.role === 'assistant').at(-1);
    if (last?.stopReason === 'error') throw new Error(last.errorMessage || 'Model request failed');
  }
  output({ type: 'event', kind: 'context', tokens: estimateContextTokens(convertToLlm(manager.buildSessionContext().messages)).tokens, contextWindow, compactions, source: 'hybrid' });
  output({ type: 'result', engine: ENGINE, status, summary, steps, toolCalls });
}
main().catch(error => {
  output({ type: 'error', error: String(error.message || error) });
  process.exitCode = 1;
}).finally(() => input.close());
