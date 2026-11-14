"""Provider continuation errors exercised through the real Codex adapter."""
from contextlib import contextmanager
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'core/app/src/main/assets/bridge')]
from scripts.test_codex_native import Session
import agent_codex as codex
import agent_codex_runtime as runtime

ERROR = 'stream disconnected before completion: The upstream could not validate encrypted continuation context. Rebuild the conversation context in the client.'

class RecoveryTests(unittest.TestCase):
    def run_case(self, *, repeat=False, resume_error=False, immediate=False, other=False, prepare_first=False):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        session = Session(root)
        session.base = root
        session.session_id = 'recovery'
        session.session_file = root / 'recovery.jsonl'
        session.engine_info = {'name': 'Codex'}
        session.project_instructions = lambda: 'project rules'
        session.task_mode_prompt = lambda: 'task rules'
        config = {'model': 'fixture', 'endpoint': 'https://fixture.invalid/v1', 'contextWindow': 32768}
        session.request.update(configuration=config, task='Finish the remaining checks', history=[
            {'role': 'user', 'content': 'Keep the exported filename stable'},
            {'role': 'assistant', 'content': 'The export was created'},
            {'type': 'reasoning', 'encrypted_content': 'OPAQUE-DO-NOT-REPLAY'}])
        pointer = root / 'recovery.codex-native.json'
        pointer.write_text(json.dumps({'threadId': 'old', 'compactions': 3}))
        calls = []
        class Wire(codex.CodexTransport):
            attempts = 0
            def request(self, method, params, wait=True):
                calls.append((method, params))
                if method == 'thread/resume' and resume_error:
                    raise RuntimeError(ERROR)
                if method == 'thread/start':
                    return {'thread': {'id': 'fresh'}}
                if method == 'turn/start':
                    self.attempts += 1
                    if immediate and self.attempts == 1:
                        raise RuntimeError(ERROR)
                    self.native_completed = False
                    return {'turn': {'id': 'turn-' + str(self.attempts)}}
                return {}
            def poll(self, **kwargs):
                fail = repeat or (self.attempts == 1 and not resume_error)
                params = {'threadId': self.thread_id, 'turnId': self.turn_id}
                if fail:
                    # A non-idempotent tool has already run. The retry must only
                    # send a new model turn, never execute this call again.
                    target = session.stage / 'already-executed.txt'
                    target.write_text(target.read_text() + 'x' if target.exists() else 'x')
                    self.notification('item/completed', {**params, 'item': {'id': 'done', 'type': 'commandExecution', 'command': 'append once', 'status': 'completed'}})
                    self.notification('item/agentMessage/delta', {**params, 'itemId': 'answer', 'delta': 'The existing command finished'})
                    self.notification('turn/completed', {'threadId': self.thread_id, 'turn': {'id': self.turn_id, 'status': 'failed', 'error': {'message': 'rate limit' if other else ERROR}}})
                else:
                    self.notification('item/completed', {**params, 'item': {'id': 'final', 'type': 'agentMessage', 'text': 'Checks finished'}})
                    self.notification('turn/completed', {'threadId': self.thread_id, 'turn': {'id': self.turn_id, 'status': 'completed'}})
        wire = Wire(session, types.SimpleNamespace(stdin=io.BytesIO()), io.BytesIO())
        self.addCleanup(wire.close_interactions)
        @contextmanager
        def server(*args):
            yield wire
        with patch.object(codex, 'codex_binary', return_value=Path('/fixture/codex')), patch.object(runtime, 'native_server', server):
            if prepare_first:
                ready = codex.run_codex(session, config, prepare_only=True)
                self.assertEqual(ready['status'], 'Ready')
                self.assertFalse(ready['modelRequested'])
                self.assertFalse(any(method in ('thread/start', 'thread/resume', 'turn/start') for method, _ in calls))
                self.assertEqual(json.loads(pointer.read_text()), {'threadId': 'old', 'compactions': 3})
            result = codex.run_codex(session, config)
        return result, calls, session, json.loads(pointer.read_text())

    def test_failed_stream_rebuilds_once_with_visible_context_and_preserved_files(self):
        result, calls, session, saved = self.run_case()
        self.assertEqual(result['status'], 'Submitted')
        turns = [data for method, data in calls if method == 'turn/start']
        self.assertEqual([turn['threadId'] for turn in turns], ['old', 'fresh'])
        text = turns[1]['input'][0]['text']
        for expected in ['Keep the exported filename stable', 'The existing command finished', 'append once', 'Finish the remaining checks']:
            self.assertIn(expected, text)
        self.assertNotIn('OPAQUE-DO-NOT-REPLAY', text)
        self.assertEqual((session.stage / 'already-executed.txt').read_text(), 'x')
        self.assertEqual(saved['threadId'], 'fresh')
        self.assertFalse(saved['rebuildContext'])

    def test_repeated_provider_rejection_stops_after_one_rebuild(self):
        result, calls, _, _ = self.run_case(repeat=True)
        self.assertEqual(result['status'], 'Failed')
        self.assertEqual(sum(method == 'thread/start' for method, _ in calls), 1)
        self.assertEqual(sum(method == 'turn/start' for method, _ in calls), 2)

    def test_unrelated_failure_does_not_rebuild(self):
        result, calls, _, saved = self.run_case(other=True)
        self.assertEqual(result['status'], 'Failed')
        self.assertFalse(any(method == 'thread/start' for method, _ in calls))
        self.assertEqual(saved['threadId'], 'old')

    def test_resume_failure_restores_context_in_first_turn(self):
        result, calls, _, _ = self.run_case(resume_error=True)
        self.assertEqual(result['status'], 'Submitted')
        turns = [data for method, data in calls if method == 'turn/start']
        self.assertEqual(len(turns), 1)
        self.assertIn('Keep the exported filename stable', turns[0]['input'][0]['text'])

    def test_prewarm_preserves_saved_thread_and_first_turn_recovers(self):
        result, calls, _, saved = self.run_case(resume_error=True, prepare_first=True)
        self.assertEqual(result['status'], 'Submitted')
        self.assertEqual(saved['threadId'], 'fresh')
        self.assertFalse(saved['rebuildContext'])
        turns = [data for method, data in calls if method == 'turn/start']
        self.assertEqual(len(turns), 1)
        self.assertIn('Keep the exported filename stable', turns[0]['input'][0]['text'])

    def test_immediate_turn_rpc_rejection_is_recoverable(self):
        result, calls, _, _ = self.run_case(immediate=True)
        self.assertEqual(result['status'], 'Submitted')
        self.assertEqual(sum(method == 'turn/start' for method, _ in calls), 2)

if __name__ == '__main__':
    unittest.main()
