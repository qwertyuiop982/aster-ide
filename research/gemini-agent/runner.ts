// Aster host for the original Gemini CLI client, scheduler, recording and compression.
import { Config } from '../../vendor/gemini-cli/packages/core/src/config/config.js';
import { AuthType } from '../../vendor/gemini-cli/packages/core/src/core/contentGenerator.js';
import { LegacyAgentSession } from '../../vendor/gemini-cli/packages/core/src/agent/legacy-agent-session.js';
import { BaseDeclarativeTool, BaseToolInvocation, Kind, ToolConfirmationOutcome } from '../../vendor/gemini-cli/packages/core/src/tools/tools.js';
import { ActivateSkillTool } from '../../vendor/gemini-cli/packages/core/src/tools/activate-skill.js';
import { loadSkillsFromDir } from '../../vendor/gemini-cli/packages/core/src/skills/skillLoader.js';
import { ToolErrorType } from '../../vendor/gemini-cli/packages/core/src/tools/tool-error.js';
import { ApprovalMode, PolicyDecision } from '../../vendor/gemini-cli/packages/core/src/policy/types.js';
import { loadConversationRecord } from '../../vendor/gemini-cli/packages/core/src/services/chatRecordingService.js';
import { convertSessionToClientHistory } from '../../vendor/gemini-cli/packages/core/src/utils/sessionUtils.js';
import { tokenLimit } from '../../vendor/gemini-cli/packages/core/src/core/tokenLimits.js';
import { CompressionStatus } from '../../vendor/gemini-cli/packages/core/src/core/turn.js';
import { estimateTokenCountSync } from '../../vendor/gemini-cli/packages/core/src/utils/tokenCalculation.js';
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { run, rpc, emit, output } from '../agent-engines/transport.mjs';

const ENGINE = 'Gemini CLI 0.64.0';

await run(async start => {
  const options = start.configuration;
  const protocol = options.protocol || 'gemini';
  if (!['gemini', 'openai'].includes(protocol)) throw new Error('Unknown Gemini API protocol');
  process.env.ASTER_GEMINI_PROTOCOL = protocol;
  process.env.GEMINI_CLI_HOME = start.engineHome;
  const termuxPrefix = process.env.PREFIX;
  if (termuxPrefix) {
    process.env.PATH = `${termuxPrefix}/bin:${process.env.PATH || ''}`;
    process.env.SHELL = `${termuxPrefix}/bin/bash`;
  }
  process.env.ASTER_GEMINI_CONTEXT_WINDOW = String(Math.max(8192, Math.min(Number(start.contextWindow) || 32768, 1048576)));
  const contextWindow = tokenLimit(options.model);
  const maxOutputTokens = Math.max(64, Math.min(Number(start.maxTokens) || 4096, 65536));
  if (maxOutputTokens > contextWindow - 1024) throw new Error(`Output budget exceeds effective Gemini window ${contextWindow}; leave at least 1024 input tokens`);
  const reserve = Math.max(2048, maxOutputTokens);
  const thinking = start.thinking?.gemini;
  process.env.ASTER_GEMINI_OPENAI_THINKING = JSON.stringify(start.thinking || {});
  const generationBudget = { generateContentConfig: { maxOutputTokens,
    ...(thinking ? { thinkingConfig: { thinkingBudget: undefined, thinkingLevel: undefined, ...thinking } } : {}) } };
  const nativePermissions = start.nativePermissions === true;
  const approvalMode = nativePermissions && start.commandsUnchecked === true ? ApprovalMode.YOLO : ApprovalMode.DEFAULT;
  const config = new Config({
    approvalMode, trustedFolder: nativePermissions ? true : undefined,
    sandbox: nativePermissions ? { enabled: false } : undefined,
    sessionId: start.sessionId, clientName: 'aster', targetDir: start.stage, cwd: start.stage,
    debugMode: false, model: options.model, userMemory: start.system, coreTools: start.nativeTools, useWriteTodos: true,
    enableHooks: false, mcpEnabled: true, extensionsEnabled: false, enableAgents: true,
    mcpServers: Object.fromEntries(Object.entries(start.nativeMcpServers || {}).map(([name, entry]: [string, any]) => {
      const { type, ...server } = entry;
      return [name, type === 'http' ? { ...server, type: 'http' } : server];
    })),
    plan: true, tracker: true, planSettings: { directory: join(start.stage, '.aster/plans'), modelRouting: false },
    skillsSupport: Boolean(options.enabledSkills?.length), adminSkillsEnabled: Boolean(options.enabledSkills?.length), enableInteractiveShell: false,
    interactive: false, usageStatisticsEnabled: false, telemetry: { enabled: false },
    checkpointing: false, includeDirectoryTree: true, skipNextSpeakerCheck: true,
    disableModelRouterForAuth: [AuthType.GATEWAY, AuthType.USE_GEMINI],
    maxSessionTurns: start.freeMode ? 64 : -1, maxAttempts: 3, retryFetchErrors: true,
    compressionThreshold: Math.min(0.8, 1 - reserve / contextWindow),
    contextManagement: { enabled: false },
    modelConfigServiceConfig: { customOverrides: [
      { match: { overrideScope: 'core' }, modelConfig: generationBudget },
      { match: { model: options.model }, modelConfig: generationBudget },
    ] },
    fileFiltering: { enableFileWatcher: false },
    policyEngineConfig: { defaultDecision: PolicyDecision.ALLOW, approvalMode },
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
    if (options.enabledSkills?.length) {
      const selected = new Set(options.enabledSkills);
      const manager = config.getSkillManager();
      manager.addSkills(await loadSkillsFromDir(join(start.stage, '.aster/skills')));
      manager.filterSkills(skill => selected.has(skill.name));
      if (manager.getSkills().length) registry.registerTool(new ActivateSkillTool(config, config.messageBus));
    }
    // Preserve the actual upstream CLI tool instances and their original schemas.
    // Aster wraps only permission, UI and staged-change lifecycle boundaries.
    const allowedNative = new Set(start.nativeTools || []);
    const summarize = result => JSON.stringify(result?.returnDisplay ?? result?.llmContent ?? '', (key, value) =>
      key === 'data' && typeof value === 'string' && value.length > 2048 ? `[binary ${value.length} characters]` : value).slice(0, 12000);
    for (const nativeTool of registry.getAllTools()) {
      const server = (nativeTool as any).serverName;
      if (!allowedNative.has(nativeTool.name) && !(server && start.nativeMcpServers?.[server])) { registry.unregisterTool(nativeTool.name); continue; }
      const originalBuild = nativeTool.build;
      nativeTool.build = function(params, ...rest) {
        const invocation = originalBuild.call(this, params, ...rest);
        const execute = invocation.execute.bind(invocation);
        const confirm = invocation.shouldConfirmExecute.bind(invocation);
        let ticket;
        let admission;
        let confirmation;
        let confirmed = false;
        let completed = false;
        const admit = async () => {
          if (!admission) admission = rpc('native_tool', { phase: 'before', name: nativeTool.name, server, arguments: params });
          ticket = await admission;
          if (!ticket?.ok) throw new Error(ticket?.error || 'Native tool was not approved');
          return ticket;
        };
        const complete = async result => {
          if (completed || !ticket?.callId) return;
          completed = true;
          const tasks = nativeTool.name.startsWith('tracker_') ? await config.getTrackerService().listTasks() : undefined;
          await rpc('native_tool', { phase: 'after', callId: ticket.callId, failed: Boolean(result?.error), summary: summarize(result), tasks });
        };
        const failure = error => ({ llmContent: String(error), returnDisplay: String(error), error: { type: ToolErrorType.EXECUTION_FAILED, message: String(error) } });
        if (nativeTool.name === 'exit_plan_mode') {
          // Tighten native ALLOW to ASK, never override native DENY. Keep original
          // plan-path/content validation and onConfirm as the sole state owners.
          const policy = (invocation as any).getMessageBusDecision.bind(invocation);
          (invocation as any).getMessageBusDecision = async (...args) => {
            const decision = await policy(...args);
            return decision === 'allow' ? 'ask_user' : decision;
          };
        }
        const performConfirmation = async signal => {
          await admit();
          const details = await confirm(signal);
          if (details && details.type === 'ask_user') {
            const answer = await rpc('native_tool', { phase: 'ask_user', questions: details.questions.map((question, index) => ({ ...question, id: String(index) })) });
            const answers = Object.fromEntries(Object.entries(answer.answers || {}).map(([id, values]) => [id, Array.isArray(values) ? values.join(', ') : String(values)]));
            await details.onConfirm(answer.dismissed ? ToolConfirmationOutcome.Cancel : ToolConfirmationOutcome.ProceedOnce, answer.dismissed ? undefined : { answers });
          } else if (details && details.type === 'exit_plan_mode') {
            const review = await rpc('native_tool', { phase: 'plan_review', planPath: details.planPath });
            await details.onConfirm(review.dismissed ? ToolConfirmationOutcome.Cancel : ToolConfirmationOutcome.ProceedOnce,
              { approved: Boolean(review.approved), feedback: review.feedback || '' });
          } else if (details) {
            const alreadyApproved = ticket.approvalGranted && ['exec', 'mcp'].includes(details.type);
            const approval = alreadyApproved ? { ok: true } : await rpc('native_tool', { phase: 'confirm', name: nativeTool.name, server,
              description: details.title || nativeTool.name, systemMessage: details.systemMessage,
              command: details.command, fileDiff: details.fileDiff, additionalPermissions: details.additionalPermissions,
              prompt: details.prompt, urls: details.urls });
            await details.onConfirm(approval.ok ? ToolConfirmationOutcome.ProceedOnce : ToolConfirmationOutcome.Cancel);
            if (!approval.ok) throw new Error('User declined the tool confirmation');
          }
          confirmed = true;
          return false;
        };
        invocation.shouldConfirmExecute = async signal => {
          if (!confirmation) confirmation = performConfirmation(signal).catch(async error => {
            await complete(failure(error));
            throw error;
          });
          return confirmation;
        };
        invocation.execute = async (...executeArgs) => {
          let result;
          try {
            await admit();
            if (!confirmed) await invocation.shouldConfirmExecute(executeArgs[0]?.abortSignal);
            result = await execute(...executeArgs);
          } catch (error) {
            result = failure(error);
          }
          await complete(result);
          return result;
        };
        return invocation;
      };
    }
    for (const item of start.tools) {
      if (start.allowedTools.includes(item.function.name) && !registry.getAllTools().some(tool => tool.name === item.function.name)) registry.registerTool(new BridgeTool(item.function));
    }
    emit('status', { text: 'Gemini CLI 原生工具已加载：' + registry.getAllTools().filter(tool => allowedNative.has(tool.name)).map(tool => tool.name).join(', '), step: 0 });
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
