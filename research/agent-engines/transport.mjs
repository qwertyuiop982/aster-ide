import { createInterface } from 'node:readline';
const pending = new Map();
let sequence = 0;
let begin;
export const started = new Promise(resolve => { begin = resolve; });
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
export function output(value) { process.stdout.write(JSON.stringify(value) + String.fromCharCode(10)); }
export function emit(kind, values = {}) { output({ type: 'event', kind, ...values }); }
export function rpc(method, payload) {
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
  for (const waiter of pending.values()) waiter.reject(new Error('Aster transport closed'));
  pending.clear();
});
export async function run(main) {
  try { await main(await started); }
  catch (error) { output({ type: 'error', error: String(error.message || error) }); process.exitCode = 1; }
  finally {
    input.close();
    process.stdout.write('', () => process.exit(process.exitCode || 0));
  }
}
