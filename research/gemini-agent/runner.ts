// Aster host for the original Gemini CLI client, scheduler, recording and compression.
import { Config } from '../../vendor/gemini-cli/packages/core/src/config/config.js';
import { AuthType } from '../../vendor/gemini-cli/packages/core/src/core/contentGenerator.js';
import { LegacyAgentSession } from '../../vendor/gemini-cli/packages/core/src/agent/legacy-agent-session.js';
import { BaseDeclarativeTool, BaseToolInvocation, Kind } from '../../vendor/gemini-cli/packages/core/src/tools/tools.js';
import { ToolErrorType } from '../../vendor/gemini-cli/packages/core/src/tools/tool-error.js';
import { PolicyDecision } from '../../vendor/gemini-cli/packages/core/src/policy/types.js';
import { loadConversationRecord } from '../../vendor/gemini-cli/packages/core/src/services/chatRecordingService.js';
import { convertSessionToClientHistory } from '../../vendor/gemini-cli/packages/core/src/utils/sessionUtils.js';
import { tokenLimit } from '../../vendor/gemini-cli/packages/core/src/core/tokenLimits.js';
import { CompressionStatus } from '../../vendor/gemini-cli/packages/core/src/core/turn.js';
import { estimateTokenCountSync } from '../../vendor/gemini-cli/packages/core/src/utils/tokenCalculation.js';
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname } from 'node:path';
import { run, rpc, emit, output } from '../agent-engines/transport.mjs';

const ENGINE = 'Gemini CLI 0.64.0';

await run(async start => {
  const options = start.configuration;
  const protocol = options.protocol || 'gemini';
  if (!['gemini', 'openai'].includes(protocol)) throw new Error('Unknown Gemini API protocol');
  process.env.ASTER_GEMINI_PROTOCOL = protocol;
  process.env.GEMINI_CLI_HOME = start.engineHome;
  process.env.ASTER_GEMINI_CONTEXT_WINDOW = String(Math.max(8192, Math.min(Number(start.contextWindow) || 32768, 1048576)));
  const contextWindow = tokenLimit(options.model);
  const maxOutputTokens = Math.max(64, Math.min(Number(start.maxTokens) || 4096, 65536));
  if (maxOutputTokens > contextWindow - 1024) throw new Error(`Output budget exceeds effective Gemini window ${contextWindow}; leave at least 1024 input tokens`);
  const reserve = Math.max(2048, maxOutputTokens);
  const thinking = start.thinking?.gemini;
  process.env.ASTER_GEMINI_OPENAI_THINKING = JSON.stringify(start.thinking || {});
  const generationBudget = { generateContentConfig: { maxOutputTokens,
    ...(thinking ? { thinkingConfig: { thinkingBudget: undefined, thinkingLevel: undefined, ...thinking } } : {}) } };
  const config = new Config({
    sessionId: start.sessionId, clientName: 'aster', targetDir: start.workspace, cwd: start.workspace,
    debugMode: false, model: options.model, userMemory: start.system, coreTools: [],
    enableHooks: false, mcpEnabled: false, extensionsEnabled: false, enableAgents: false,
    skillsSupport: false, adminSkillsEnabled: false, enableInteractiveShell: false,
    interactive: false, usageStatisticsEnabled: false, telemetry: { enabled: false },
    checkpointing: false, includeDirectoryTree: false, skipNextSpeakerCheck: true,
    disableModelRouterForAuth: [AuthType.GATEWAY, AuthType.USE_GEMINI],
    maxSessionTurns: start.maxSteps, maxAttempts: 2,
    compressionThreshold: Math.min(0.8, 1 - reserve / contextWindow),
    contextManagement: { enabled: false },
    modelConfigServiceConfig: { customOverrides: [
      { match: { overrideScope: 'core' }, modelConfig: generationBudget },
      { match: { model: options.model }, modelConfig: generationBudget },
    ] },
    fileFiltering: { enableFileWatcher: false },
    policyEngineConfig: { defaultDecision: PolicyDecision.ALLOW },
  });
  const pointer = start.sessionFile + '.native.json';
  const saved = existsSync(pointer) ? JSON.parse(readFileSync(pointer, 'utf8')) : null;
  let summary = '';
  let finished = false;
  let compactions = saved?.compactions || 0;
  let tokens = 0;
  let steps = 0;
  let status = 'Failed';
  let nativeEnded = false;
  let messageId = '';
  let messageText = '';
  let toolQueue = Promise.resolve();

  function closeMessage() {
    if (messageId) emit('message_end', { messageId, text: messageText });
    if (messageText.trim()) summary = messageText;
    messageId = ''; messageText = '';
  }
  class Invocation extends BaseToolInvocation {
    constructor(params, name) { super(params, config.messageBus, name, name); }
    getDescription() { return this._toolName || 'Project tool'; }
    async shouldConfirmExecute() { return false; }
    async execute() {
      const perform = async () => {
        if (finished) return { llmContent: 'Task already completed', returnDisplay: '' };
        const args = { ...this.params };
        delete args.wait_for_previous;
        const result = await rpc('tool', { name: this._toolName, arguments: args });
        if (result.finished) { finished = true; summary = result.summary || ''; }
        const text = JSON.stringify(result);
        return { llmContent: text, returnDisplay: text,
          ...(result.finished ? { error: { type: ToolErrorType.STOP_EXECUTION, message: summary || 'Completed' } } :
             result.ok === false ? { error: { type: ToolErrorType.EXECUTION_FAILED, message: result.error || text } } : {}) };
      };
      const pending = toolQueue.then(perform);
      toolQueue = pending.catch(() => undefined);
      return pending;
    }
  }
  class BridgeTool extends BaseDeclarativeTool {
    constructor(fn) { super(fn.name, fn.name, fn.description, Kind.Other, fn.parameters, config.messageBus); }
    createInvocation(params) { return new Invocation(params, this.name); }
  }
  function checkpoint() {
    const file = config.getGeminiClient().getChatRecordingService()?.getConversationFilePath();
    if (!file) return;
    writeFileSync(pointer + '.tmp', JSON.stringify({ file, compactions }), { mode: 0o600 });
    renameSync(pointer + '.tmp', pointer);
  }
  function contextUsage() {
    const history = config.getGeminiClient().getHistory();
    const count = estimateTokenCountSync(history);
    emit('context', { tokens: count, contextWindow, compactions, source: 'estimate' });
  }
  try {
    await config.refreshAuth(AuthType.GATEWAY, options.apiKey || '', options.endpoint);
    await config.initialize();
    const registry = config.getToolRegistry();
    for (const existing of registry.getAllTools()) registry.unregisterTool(existing.name);
    for (const item of start.tools) {
      if (start.allowedTools.includes(item.function.name)) registry.registerTool(new BridgeTool(item.function));
    }
    const client = config.getGeminiClient();
    // The upstream AgentProtocol intentionally drops ChatCompressed events. Decorate
    // its original compression API for UI accounting; the native algorithm and
    // recording service remain the only owners of compression/history.
    const compress = client.tryCompressChat.bind(client);
    client.tryCompressChat = async (...args) => {
      const info = await compress(...args);
      if (info.compressionStatus === CompressionStatus.COMPRESSED || info.compressionStatus === CompressionStatus.CONTENT_TRUNCATED) {
        compactions++; checkpoint(); contextUsage();
      }
      return info;
    };
    await client.setTools();
    if (saved) {
      const conversation = await loadConversationRecord(saved.file);
      if (!conversation || conversation.sessionId !== start.sessionId) throw new Error('Gemini session cannot be restored');
      await client.resumeChat(convertSessionToClientHistory(conversation.messages), { conversation, filePath: saved.file });
      client.discardTrailingUnansweredToolCallTurn();
    }
    if (start.compactOnly) {
      status = 'Submitted';
      emit('status', { text: '正在压缩 Gemini 上下文', step: 0 });
      const info = await client.tryCompressChat(start.sessionId, true);
      if (info.compressionStatus === CompressionStatus.COMPRESSED || info.compressionStatus === CompressionStatus.CONTENT_TRUNCATED) summary = 'Gemini 上下文已压缩';
      else if (info.compressionStatus === CompressionStatus.NOOP) summary = '当前上下文无需压缩';
      else throw new Error('Gemini context compression failed: ' + info.compressionStatus);
    } else {
      const session = new LegacyAgentSession({ config, promptId: start.sessionId });
      for await (const event of session.sendStream({ message: { content: [{ type: 'text', text: start.task }] } })) {
        if (event.type === 'message' && event.role === 'agent') {
          const text = event.content.filter(part => part.type === 'text').map(part => part.text).join('');
          if (text) {
            if (!messageId) { messageId = randomUUID(); emit('message_start', { messageId }); }
            messageText += text; emit('message_delta', { messageId, text });
          }
        } else if (event.type === 'tool_request') {
          closeMessage(); emit('status', { text: 'Gemini 正在执行工具', step: steps });
        } else if (event.type === 'usage') {
          steps++;
          emit('status', { text: 'Gemini 正在思考', step: steps });
          tokens += (event.inputTokens || 0) + (event.outputTokens || 0);
          emit('usage', { tokens });

        } else if (event.type === 'error') {
          emit('error', { text: event.message, recoverable: true });
          if (event.fatal) status = 'Failed';
        } else if (event.type === 'agent_end') {
          nativeEnded = true;
          if (event.reason === 'completed') status = 'Submitted';
          else if (event.reason === 'max_turns') status = 'LimitsExceeded';
          else if (event.reason === 'aborted') status = 'Cancelled';
          else if (event.reason !== 'completed') status = 'Failed';
        }
        checkpoint();
      }
      closeMessage();
      if (!nativeEnded || (status === 'Submitted' && !summary.trim() && !finished)) {
        status = 'Failed';
        emit('error', { text: 'Gemini 未返回完整的原生结束事件或有效回复；请重试。', recoverable: true });
      }
    }
    checkpoint(); contextUsage();
    output({ type: 'result', engine: ENGINE, status, summary, steps, tokens });
  } finally { await config.dispose(); }
});
