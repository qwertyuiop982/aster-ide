"""Regression checks for the shipped Pi host's unlimited loop and checkpoints."""
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'core/app/src/main/assets/bridge'
sys.path[:0] = [str(BRIDGE / 'agent_vendor'), str(BRIDGE)]
from aster_model import OpenAIModel

READ = {'type': 'function', 'function': {'name': 'read_file', 'description': 'Read a file',
        'parameters': {'type': 'object', 'properties': {'path': {'type': 'string'}}, 'required': ['path']}}}


@unittest.skipUnless(shutil.which('node'), 'Node is required for the shipped Pi engine')
class PiContextTests(unittest.TestCase):
    def run_engine(self, folder, reply, **options):
        engine = subprocess.Popen([shutil.which('node'), str(BRIDGE / 'agent_vendor/pi-agent.mjs')],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, bufsize=1)
        incoming = queue.Queue()
        def reader():
            for line in engine.stdout:
                incoming.put(line)
            incoming.put('')
        threading.Thread(target=reader, daemon=True).start()
        def send(value):
            engine.stdin.write(json.dumps(value) + '\n')
            engine.stdin.flush()
        send({'type': 'start', 'system': 'Help with the current project.', 'task': 'Complete the project task.',
              'tools': [READ], 'allowedTools': ['read_file'], 'sessionId': 'checkpoint-fixture',
              'stage': folder, 'workspace': folder, 'sessionFile': str(Path(folder) / 'session.jsonl'),
              'model': 'fixture', 'thinkingLevel': 'off', 'maxSteps': 2, 'maxTokens': 64,
              'contextWindow': 8192, **options})
        packets = []
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    line = incoming.get(timeout=0.1)
                except queue.Empty:
                    continue
                self.assertTrue(line, 'Engine closed before its final result')
                packet = json.loads(line)
                packets.append(packet)
                if packet['type'] == 'error':
                    self.fail(packet['error'])
                if packet['type'] == 'result':
                    return packet, packets
                if packet['type'] == 'request':
                    send({'type': 'response', 'id': packet['id'], 'result': reply(packet)})
            self.fail('Engine did not finish within the fixture deadline')
        finally:
            engine.stdin.close()
            if engine.poll() is None:
                engine.terminate()
            try:
                engine.wait(3)
            except subprocess.TimeoutExpired:
                engine.kill()
                engine.wait()
            engine.stdout.close()
            engine.stderr.close()

    def test_paid_loop_passes_128_calls_and_ignores_old_step_setting(self):
        calls = []
        def reply(packet):
            if packet['method'] == 'tool':
                calls.append(packet['payload']['name'])
                return {'ok': True, 'content': 'observed'}
            self.assertEqual(packet['payload']['purpose'], 'task')
            if len(calls) >= 132:
                return {'content': 'done'}
            return {'content': '', 'tool_calls': [{'id': 'call-' + str(len(calls)), 'type': 'function',
                    'function': {'name': 'read_file', 'arguments': '{"path":"file.txt"}'}}]}
        with tempfile.TemporaryDirectory() as folder:
            result, _ = self.run_engine(folder, reply, contextWindow=1048576)
        self.assertEqual(result['status'], 'Submitted')
        self.assertEqual(result['toolCalls'], 132)
        self.assertEqual(result['steps'], 133)

    def test_compaction_has_its_own_budget_and_invalidates_retained_usage(self):
        ordinary = []
        summaries = []
        def reply(packet):
            if packet['method'] == 'tool':
                return {'ok': True, 'content': 'last tool result'}
            payload = packet['payload']
            if payload['purpose'] == 'compaction':
                summaries.append(payload)
                self.assertEqual(payload['tools'], [])
                self.assertGreater(payload['maxTokens'], 64)
                return {'content': 'Checkpoint: user wants the current project completed. Preserve exact files and constraints.'}
            ordinary.append(payload)
            if len(ordinary) == 1:
                # The last assistant remains in the retained tail with old high usage.
                return {'content': '', 'usage': {'prompt_tokens': 7000, 'completion_tokens': 10, 'total_tokens': 7010},
                        'tool_calls': [{'id': 'last-call', 'type': 'function',
                                        'function': {'name': 'read_file', 'arguments': '{"path":"last.txt"}'}}]}
            return {'content': 'done', 'usage': {'prompt_tokens': 1200, 'completion_tokens': 10, 'total_tokens': 1210}}
        history = []
        for i in range(12):
            history += [{'role': 'user', 'content': 'request-' + str(i) + ': ' + 'goal ' * 40},
                        {'role': 'assistant', 'content': 'progress-' + str(i) + ': ' + 'work ' * 220}]
        with tempfile.TemporaryDirectory() as folder:
            result, packets = self.run_engine(folder, reply, history=history)
            self.assertEqual(result['status'], 'Submitted')
            entries = [json.loads(line) for line in (Path(folder) / 'session.jsonl').read_text().splitlines()]
            self.assertEqual(sum(e['type'] == 'compaction' for e in entries), 1)
            _, resumed = self.run_engine(folder, lambda _: self.fail('Fresh checkpoint must not call a model'), compactOnly=True)
        self.assertTrue(summaries)
        contexts = [p for p in packets if p.get('kind') == 'context']
        checkpoint = next(p for p in contexts if p['compactions'] == 1)
        self.assertLess(checkpoint['tokens'], 6144)
        self.assertEqual(resumed[-1]['summary'], '当前上下文较短，无需压缩。')


class CompactionAdapterTests(unittest.TestCase):
    def test_summary_budget_reaches_api_without_appearing_as_assistant_reply(self):
        class Session:
            tools = []
            tokens = 0
            events = []
            def check_cancelled(self):
                pass
            def emit(self, kind, **value):
                self.events.append((kind, value))
        session = Session()
        model = OpenAIModel({'endpoint': 'https://fixture.invalid/v1', 'model': 'fixture', 'maxTokens': 64}, session)
        requests = []
        def response(payload):
            requests.append(payload)
            return {'choices': [{'message': {'content': 'valid checkpoint'}, 'finish_reason': 'stop'}]}
        model.request = response
        model.query([{'role': 'user', 'content': 'summarize'}], purpose='compaction', tools=[], maxTokens=1228)
        self.assertEqual(requests[0]['max_tokens'], 1228)
        self.assertFalse(requests[0]['stream'])
        self.assertNotIn('tools', requests[0])
        self.assertFalse(session.events)


if __name__ == '__main__':
    unittest.main()
