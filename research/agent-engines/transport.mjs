import { createInterface } from 'node:readline';

const pending = new Map();
const starts = [];
let sequence = 0;
let firstStart;
let waiting;
let closed = false;
let busy = false;
export const started = new Promise(resolve => { firstStart = resolve; });
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

export function output(value) { process.stdout.write(JSON.stringify(value) + '\n'); }
export function emit(kind, values = {}) { output({ type: 'event', kind, ...values }); }
export function rpc(method, payload) {
  if (closed) return Promise.reject(new Error('Aster transport closed'));
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    output({ type: 'request', id, method, payload });
  });
}
function rejectPending(error) {
  for (const waiter of pending.values()) waiter.reject(error);
  pending.clear();
}
function nextStart() {
  if (starts.length) return Promise.resolve(starts.shift());
  if (closed) return Promise.resolve(null);
  return new Promise((resolve, reject) => { waiting = { resolve, reject }; });
}
input.on('line', line => {
  try {
    const value = JSON.parse(line);
    if (value.type === 'start') {
      if (busy || starts.length) throw new Error('Aster engine already has an active turn');
      firstStart(value);
      if (waiting) { const waiter = waiting; waiting = undefined; waiter.resolve(value); }
      else starts.push(value);
      return;
    }
    if (value.type !== 'response') throw new Error('Invalid Aster transport message');
    const waiter = pending.get(value.id);
    if (!waiter) return;
    pending.delete(value.id);
    if (value.error) waiter.reject(new Error(value.error)); else waiter.resolve(value.result);
  } catch (error) {
    rejectPending(error);
    if (waiting) { const waiter = waiting; waiting = undefined; waiter.reject(error); }
    input.close();
  }
});
input.on('close', () => {
  closed = true;
  rejectPending(new Error('Aster transport closed'));
  if (waiting) { const waiter = waiting; waiting = undefined; waiter.resolve(null); }
});

export async function run(main, { persistent = false, dispose } = {}) {
  try {
    do {
      const start = await nextStart();
      if (!start) break;
      busy = true;
      await main(start);
      if (pending.size) throw new Error('Aster turn ended with unfinished host requests');
      busy = false;
      // The host releases this turn only after all work and RPCs have settled.
      if (persistent && !closed) output({ type: 'idle' });
    } while (persistent && !closed);
  } catch (error) {
    output({ type: 'error', error: String(error.message || error) });
    process.exitCode = 1;
  } finally {
    try { await dispose?.(); }
    catch (error) {
      output({ type: 'error', error: String(error.message || error) });
      process.exitCode = 1;
    }
    input.close();
    process.stdout.write('', () => process.exit(process.exitCode || 0));
  }
}
