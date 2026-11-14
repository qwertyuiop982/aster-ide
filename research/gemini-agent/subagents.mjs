import { randomUUID } from 'node:crypto';

// Observe the upstream invocation's progress; execution and approvals stay native.
export function subagentReporter(emit, params, model) {
  const id = 'gemini-' + randomUUID();
  const label = 'Gemini · ' + (params.agent_name || 'Agent');
  const seen = new Map();
  let started = false, ended = false, lastProgress;
  const send = event => emit('subagent_event', { subagentId: id, label, event });
  function start() {
    if (started) return;
    started = true;
    send({ kind: 'started', prompt: String(params.prompt || ''), model });
  }
  function update(progress) {
    if (!progress?.isSubagentProgress || ended) return;
    start(); lastProgress = progress;
    for (const item of progress.recentActivity || []) {
      const key = item.id;
      const fingerprint = JSON.stringify([item.status, item.content, item.args]);
      if (!key || seen.get(key) === fingerprint) continue;
      const fresh = !seen.has(key);
      seen.set(key, fingerprint);
      if (seen.size > 256) seen.delete(seen.keys().next().value);
      if (item.type === 'tool_call') {
        const detail = { callId: key, name: item.content || 'Gemini tool', text: String(item.args || item.description || '') };
        if (fresh) send({ ...detail, kind: 'tool', status: 'running' });
        if (item.status !== 'running') send({ ...detail, kind: 'tool_result', status: item.status === 'completed' ? 'completed' : 'failed' });
      } else if (item.status === 'error') {
        send({ kind: 'error', text: String(item.content || '子代理执行失败') });
      } else {
        send({ kind: 'status', text: String(item.content || 'Gemini 子代理正在工作') });
      }
    }
  }
  function finish(result, cancelled = false) {
    if (ended) return;
    start(); update(result?.returnDisplay);
    const progress = lastProgress;
    const summary = String(progress?.result || '');
    if (summary) send({ kind: 'message_end', messageId: id + '-result', text: summary });
    const reason = progress?.terminateReason;
    const status = cancelled || progress?.state === 'cancelled' || reason === 'ABORTED' ? 'Cancelled'
      : reason === 'TIMEOUT' ? 'TimeExceeded'
      : reason === 'MAX_TURNS' ? 'LimitsExceeded'
      : result?.error || progress?.state === 'error' || reason?.startsWith('ERROR') ? 'Failed' : 'Submitted';
    if (result?.error) send({ kind: 'error', text: String(result.error.message || 'Gemini 子代理执行失败') });
    send({ kind: 'result', status, summary });
    ended = true;
  }
  return { start, update, finish };
}

// The bridge owns explicit task deadlines and cancellation. Do not add Gemini's
// built-in ten-minute child quota, or schedule Infinity (Node treats it as 1 ms).
export class TaskLifetime {
  constructor() { this.controller = new AbortController(); }
  get signal() { return this.controller.signal; }
  pause() {}
  resume() {}
  abort(reason) { this.controller.abort(reason); }
}
