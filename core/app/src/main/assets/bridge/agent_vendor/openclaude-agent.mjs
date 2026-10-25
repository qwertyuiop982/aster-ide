// research/openclaude-agent/runner.mjs
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";

// research/agent-engines/transport.mjs
import { createInterface } from "node:readline";
var pending = /* @__PURE__ */ new Map();
var sequence = 0;
var begin;
var started = new Promise((resolve) => {
  begin = resolve;
});
var input = createInterface({ input: process.stdin, crlfDelay: Infinity });
function output(value) {
  process.stdout.write(JSON.stringify(value) + String.fromCharCode(10));
}
function emit(kind, values = {}) {
  output({ type: "event", kind, ...values });
}
function rpc(method, payload) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    output({ type: "request", id, method, payload });
  });
}
input.on("line", (line) => {
  try {
    const value = JSON.parse(line);
    if (value.type === "start") {
      begin(value);
      return;
    }
    const waiter = pending.get(value.id);
    if (!waiter) return;
    pending.delete(value.id);
    if (value.error) waiter.reject(new Error(value.error));
    else waiter.resolve(value.result);
  } catch (error) {
    for (const waiter of pending.values()) waiter.reject(error);
    pending.clear();
  }
});
input.on("close", () => {
  for (const waiter of pending.values()) waiter.reject(new Error("Aster transport closed"));
  pending.clear();
});
async function run(main) {
  try {
    await main(await started);
  } catch (error) {
    output({ type: "error", error: String(error.message || error) });
    process.exitCode = 1;
  } finally {
    input.close();
    process.stdout.write("", () => process.exit(process.exitCode || 0));
  }
}

// research/openclaude-agent/runner.mjs
await run(async (start) => {
  process.env.OPENCLAUDE_CONFIG_DIR = start.engineHome;
  process.env.ANTHROPIC_BASE_URL = start.configuration.endpoint;
  process.env.ANTHROPIC_API_KEY = start.configuration.apiKey || "";
  process.env.DISABLE_TELEMETRY = "1";
  process.env.DISABLE_ERROR_REPORTING = "1";
  process.env.CLAUDE_CODE_MAX_OUTPUT_TOKENS = String(start.maxTokens);
  const thinking = start.thinking || { level: "auto" };
  const explicitMode = thinking.mode && thinking.mode !== "legacy";
  const effort = explicitMode && thinking.mode !== "strength" ? "unset" : thinking.effort;
  const fixedBudget = thinking.mode === "budget";
  const forceThinkingEffort = thinking.mode === "strength";
  if (forceThinkingEffort) process.env.ASTER_FORCE_THINKING_EFFORT = "1";
  else delete process.env.ASTER_FORCE_THINKING_EFFORT;
  if (fixedBudget) process.env.CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING = "1";
  else delete process.env.CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING;
  if (effort) process.env.CLAUDE_CODE_EFFORT_LEVEL = effort;
  else delete process.env.CLAUDE_CODE_EFFORT_LEVEL;
  const sdk = await import(pathToFileURL(start.sdkPath).href);
  if (typeof sdk.query !== "function" || typeof sdk.createSdkMcpServer !== "function" || typeof sdk.tool !== "function") {
    throw new Error("OpenClaude 0.33.0 SDK exports are unavailable");
  }
  if (start.compactOnly) throw new Error("OpenClaude manual compaction is not verified");
  if (typeof sdk.asterSetContextWindow !== "function" || typeof sdk.asterGetContextWindow !== "function") {
    throw new Error("Local OpenClaude SDK is missing the public context-window adapter; rebuild the local-only SDK");
  }
  const budget = sdk.asterSetContextWindow(start.model, start.contextWindow);
  if (!budget.ok) throw new Error(budget.error);
  const effectiveWindow = sdk.asterGetContextWindow(start.model);
  if (effectiveWindow !== start.contextWindow) throw new Error("OpenClaude did not accept the requested context window");
  const abortController = new AbortController();
  const allowed = new Set(start.allowedTools);
  let steps = 0, tokens = 0, compactions = 0;
  let summary = "", status = "Failed", messageId = "", text = "";
  let finished = false;
  let toolQueue = Promise.resolve();
  const limit = () => {
    if (steps >= start.maxSteps) {
      status = "LimitsExceeded";
      abortController.abort();
    }
  };
  const tools = start.tools.filter((item) => allowed.has(item.function.name)).map((item) => {
    const fn = item.function;
    return sdk.tool(fn.name, fn.description, fn.parameters, async (args) => {
      const perform = async () => {
        if (finished || steps >= start.maxSteps) return { isError: true, content: [{ type: "text", text: "Task finished or step limit reached" }] };
        steps++;
        const result = await rpc("tool", { name: fn.name, arguments: args });
        if (result.finished) {
          finished = true;
          summary = result.summary || "";
        }
        return { isError: result.ok === false, content: [{ type: "text", text: JSON.stringify(result) }] };
      };
      const next = toolQueue.then(perform);
      toolQueue = next.catch(() => void 0);
      return next;
    });
  });
  const query = sdk.query({
    prompt: start.compactOnly ? "/compact" : start.task,
    options: {
      cwd: start.workspace,
      model: start.model,
      sessionId: start.sessionId,
      abortController,
      settingSources: [],
      hooks: {},
      agents: {},
      permissionMode: "default",
      includePartialMessages: true,
      systemPrompt: start.system,
      maxTurns: start.maxSteps,
      env: {
        OPENCLAUDE_CONFIG_DIR: start.engineHome,
        ANTHROPIC_BASE_URL: start.configuration.endpoint,
        ANTHROPIC_API_KEY: start.configuration.apiKey || "",
        CLAUDE_CODE_MAX_OUTPUT_TOKENS: String(start.maxTokens),
        CLAUDE_CODE_EFFORT_LEVEL: effort,
        CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING: fixedBudget ? "1" : void 0,
        ASTER_FORCE_THINKING_EFFORT: forceThinkingEffort ? "1" : void 0,
        DISABLE_TELEMETRY: "1",
        DISABLE_ERROR_REPORTING: "1"
      },
      mcpServers: { aster: sdk.createSdkMcpServer({ type: "sdk", name: "aster", tools }) },
      canUseTool: async (name, input2) => allowed.has(name.replace(/^mcp__aster__/, "")) && name.startsWith("mcp__aster__") ? { behavior: "allow", updatedInput: input2 } : { behavior: "deny", message: "Use Aster project and Termux tools only." }
    }
  });
  try {
    if (Object.hasOwn(thinking, "budgetTokens")) {
      if (typeof query.setMaxThinkingTokens !== "function") throw new Error("SDK does not expose thinking budget control");
      query.setMaxThinkingTokens(thinking.budgetTokens);
    }
    for await (const event of query) {
      if (event.type === "stream_event") {
        const value = event.event || {};
        if (value.type === "message_start") {
          messageId = value.message?.id || randomUUID();
          text = "";
          emit("message_start", { messageId });
        } else if (value.type === "content_block_delta" && value.delta?.type === "text_delta") {
          if (!messageId) {
            messageId = randomUUID();
            emit("message_start", { messageId });
          }
          text += value.delta.text;
          emit("message_delta", { messageId, text: value.delta.text });
        }
      } else if (event.type === "assistant") {
        const usage = event.message?.usage;
        if (usage) {
          const count = (usage.input_tokens || 0) + (usage.cache_read_input_tokens || 0) + (usage.cache_creation_input_tokens || 0) + (usage.output_tokens || 0);
          emit("context", { tokens: count, contextWindow: effectiveWindow, compactions, source: "engine" });
        }
        const complete = (event.message?.content || []).filter((part) => part.type === "text").map((part) => part.text).join(String.fromCharCode(10));
        if (complete) {
          emit("message_end", { messageId: messageId || event.uuid || randomUUID(), text: complete });
          summary = complete;
        }
        messageId = "";
        text = "";
      } else if (event.type === "system" && event.subtype === "compact_boundary") {
        compactions++;
        emit("context", { tokens: 0, contextWindow: effectiveWindow, compactions, source: "unknown" });
      } else if (event.type === "result") {
        status = event.is_error ? "Failed" : "Submitted";
        summary = event.result || summary;
        tokens = (event.usage?.input_tokens || 0) + (event.usage?.output_tokens || 0);
      }
      limit();
    }
    if (finished && status !== "Failed") status = "Submitted";
    output({ type: "result", engine: "OpenClaude 0.33.0", status, summary, tokens, steps });
  } finally {
    query.close();
  }
});
