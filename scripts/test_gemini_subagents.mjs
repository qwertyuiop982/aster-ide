import assert from 'node:assert/strict';
import { test } from 'node:test';
import { subagentReporter, TaskLifetime } from '../research/gemini-agent/subagents.mjs';

test('parallel same-name Gemini children stay separate and progress does not duplicate tools', () => {
  const events = [];
  const emit = (kind, data) => events.push({ kind, ...data });
  const a = subagentReporter(emit, { agent_name: 'investigator', prompt: 'task a' }, 'model');
  const b = subagentReporter(emit, { agent_name: 'investigator', prompt: 'task b' }, 'model');
  a.start(); b.start();
  const update = { isSubagentProgress: true, recentActivity: [{ id: 'read', type: 'tool_call', content: 'read_file', args: '{"path":"seed"}', status: 'running' }], state: 'running' };
  a.update(update); a.update(update);
  a.update({ ...update, recentActivity: [{ ...update.recentActivity[0], status: 'completed' }] });
  const final = { returnDisplay: { ...update, recentActivity: [], state: 'completed', terminateReason: 'GOAL', result: 'actual native result' } };
  a.finish(final); a.finish(final);
  b.finish({ error: { message: 'cancelled' } }, true);
  assert.equal(new Set(events.map(e => e.subagentId)).size, 2);
  assert.equal(events.filter(e => e.event.kind === 'tool').length, 1);
  assert.equal(events.filter(e => e.event.kind === 'tool_result').length, 1);
  assert.deepEqual(events.filter(e => e.event.kind === 'result').map(e => e.event.status), ['Submitted', 'Cancelled']);
  assert.equal(events.find(e => e.event.kind === 'message_end').event.text, 'actual native result');
});

test('native termination reasons never become false success', () => {
  for (const [terminateReason, status] of [['MAX_TURNS','LimitsExceeded'], ['TIMEOUT','TimeExceeded'], ['ERROR','Failed'], ['ABORTED','Cancelled']]) {
    const events = [];
    subagentReporter((_, e) => events.push(e.event), {}, '').finish({ returnDisplay: { isSubagentProgress: true, recentActivity: [], state: 'completed', terminateReason } });
    assert.equal(events.at(-1).status, status);
  }
});

test('unlimited native lifetime schedules no timer and parent abort still propagates', () => {
  const original = globalThis.setTimeout;
  globalThis.setTimeout = () => { throw new Error('unexpected wall-clock quota'); };
  try {
    const lifetime = new TaskLifetime();
    lifetime.pause(); lifetime.resume();
    const parent = new AbortController();
    const signal = AbortSignal.any([parent.signal, lifetime.signal]);
    assert.equal(signal.aborted, false);
    parent.abort('user stopped task');
    assert.equal(signal.aborted, true);
    assert.equal(signal.reason, 'user stopped task');
    lifetime.abort();
  } finally { globalThis.setTimeout = original; }
});