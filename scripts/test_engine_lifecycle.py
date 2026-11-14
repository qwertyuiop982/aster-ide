"""Resident engine protocol and lifecycle regressions; no provider credentials required.

Run with: python3 -B -m unittest discover -s scripts -p 'test_engine_lifecycle.py'
The transport integration cases require the application's Node.js 22.19+ runtime.
"""
from contextlib import ExitStack
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core/app/src/main/assets/bridge'))
import agent_engine_runtime as runtime
import agent_engines
import agent_runner


class LifecycleReservationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.session = self.session_for('conversation')

    def session_for(self, identifier, engine='pi'):
        return types.SimpleNamespace(
            base=self.root / 'runtime', workspace=self.root / 'project',
            engine_id=engine, session_id=identifier, check_cancelled=lambda: None,
        )

    def test_stop_blocks_new_warmups_until_existing_operation_finishes(self):
        entered = threading.Event()
        release = threading.Event()
        errors = []

        def stop():
            try:
                with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi', timeout=5):
                    entered.set()
                    if not release.wait(5):
                        raise TimeoutError('test did not release engine shutdown')
            except BaseException as error:
                errors.append(error)

        worker = threading.Thread(target=stop, daemon=True)
        other = self.session_for('queued-warmup')
        try:
            with runtime.engine_operation(self.session):
                worker.start()
                deadline = time.monotonic() + 3
                while True:
                    try:
                        with runtime.engine_operation(other):
                            pass
                    except ValueError as error:
                        self.assertIn('正在退出', str(error))
                        break
                    if time.monotonic() >= deadline:
                        self.fail('shutdown did not reserve the engine')
                    release.wait(0.01)
                self.assertFalse(entered.is_set(), 'shutdown passed an active operation')
            self.assertTrue(entered.wait(3), 'shutdown did not continue after the active operation')
            with self.assertRaisesRegex(ValueError, '正在退出'):
                with runtime.engine_operation(other):
                    self.fail('a queued warmup restarted an engine during shutdown')
            with runtime.engine_operation(self.session_for('other-engine', 'gemini')):
                pass
        finally:
            release.set()
            if worker.ident is not None:
                worker.join(6)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])
        with runtime.engine_operation(other):
            pass

    def test_queued_warmup_cannot_miss_a_completed_shutdown(self):
        queued = threading.Event()
        outcomes = []
        original_wait = runtime._CHANGED.wait

        def warmup():
            try:
                with runtime.engine_operation(self.session_for('conversation')):
                    outcomes.append('admitted')
            except BaseException as error:
                outcomes.append(error)

        worker = threading.Thread(target=warmup, daemon=True)

        def observed_wait(timeout=None):
            if threading.current_thread() is worker:
                queued.set()
            return original_wait(timeout)

        with patch.object(runtime._CHANGED, 'wait', side_effect=observed_wait):
            try:
                with ExitStack() as active:
                    active.enter_context(runtime.engine_operation(self.session))
                    worker.start()
                    self.assertTrue(queued.wait(3), 'warmup did not queue behind the active turn')
                    # Reproduce a waiter waking only after STOPPING was removed.
                    with runtime._CHANGED:
                        active.close()
                        with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi'):
                            pass
            finally:
                if worker.ident is not None:
                    worker.join(6)
        self.assertFalse(worker.is_alive())
        self.assertEqual(len(outcomes), 1)
        self.assertIsInstance(outcomes[0], ValueError)
        self.assertIn('正在退出', str(outcomes[0]))
        with runtime.engine_operation(self.session_for('conversation')):
            pass

    def test_shutdown_admission_remains_responsive_during_process_cleanup(self):
        finished = threading.Event()
        outcomes = []

        def warmup():
            try:
                with runtime.engine_operation(self.session_for('new-conversation')):
                    outcomes.append('admitted')
            except BaseException as error:
                outcomes.append(error)
            finally:
                finished.set()

        worker = threading.Thread(target=warmup, daemon=True)
        try:
            with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi'):
                with runtime._LOCK:
                    worker.start()
                    self.assertTrue(finished.wait(3), 'warmup waited for process cleanup instead of rejecting shutdown')
        finally:
            if worker.ident is not None:
                worker.join(6)
        self.assertFalse(worker.is_alive())
        self.assertEqual(len(outcomes), 1)
        self.assertIsInstance(outcomes[0], ValueError)
        self.assertIn('正在退出', str(outcomes[0]))

    def test_timeout_releases_shutdown_reservation(self):
        with runtime.engine_operation(self.session):
            with self.assertRaisesRegex(ValueError, '请先停止或等待'):
                with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi', timeout=0):
                    self.fail('shutdown passed an active operation')
        with runtime.engine_operation(self.session):
            pass
        with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi'):
            pass

    def test_cancelled_operation_does_not_reserve_conversation(self):
        def cancelled():
            raise agent_runner.UserInterruption('cancelled before startup')

        self.session.check_cancelled = cancelled
        with self.assertRaises(agent_runner.UserInterruption):
            with runtime.engine_operation(self.session):
                self.fail('cancelled operation started an engine')
        with runtime.engine_operation(self.session_for('conversation')):
            pass

    def test_shutdown_failure_releases_reservation(self):
        with self.assertRaisesRegex(RuntimeError, 'fixture cleanup failure'):
            with runtime.stopping_engine(self.session.base, self.session.workspace, 'pi'):
                raise RuntimeError('fixture cleanup failure')
        with runtime.engine_operation(self.session):
            pass


class ResidentTransportTest(unittest.TestCase):
    def setUp(self):
        status = agent_engines.node_runtime(os.environ.copy())
        if not status['ready']:
            self.skipTest(status['reason'])
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.addCleanup(runtime.close_all)
        self.root = Path(temporary.name)
        self.base = self.root / 'runtime'
        self.workspace = self.root / 'project'
        self.workspace.mkdir()
        self.vendor = self.root / 'vendor'
        self.vendor.mkdir()
        self.events = []
        transport = (ROOT / 'research/agent-engines/transport.mjs').as_uri()
        for engine in ('pi', 'gemini', 'openclaude'):
            info = agent_engines.engine_info(engine)
            script = (
                'import { writeFileSync } from "node:fs";\n'
                'import { setTimeout as delay } from "node:timers/promises";\n'
                f'import {{ run, output, emit }} from {json.dumps(transport)};\n'
                f'const engine = {json.dumps(info["name"])};\n'
                f'const cleaned = {json.dumps(str(self.root / (engine + ".cleaned")))};\n'
                f'const disposed = {json.dumps(str(self.root / (engine + ".disposed")))};\n'
                'await run(async start => {\n'
                '  const task = start.task.split("\\n", 1)[0];\n'
                '  if (task === "idle-first") output({ type: "idle" });\n'
                '  const status = start.prepareOnly ? "Ready" : "Submitted";\n'
                '  const modelRequested = task === "bad-warmup";\n'
                '  output({ type: "result", engine, status, modelRequested, pid: process.pid, summary: task });\n'
                '  await delay(30);\n'
                '  if (task === "late-event") emit("status", { text: "unexpected late work" });\n'
                '  if (task === "late-error") throw new Error("cleanup failed after result");\n'
                '  writeFileSync(cleaned, task);\n'
                '}, { persistent: true, dispose: () => writeFileSync(disposed, "disposed") });\n'
            )
            (self.vendor / info['bundle']).write_text(script, encoding='utf-8')
        self.vendor_patch = patch.object(agent_runner, 'VENDOR', self.vendor)
        self.vendor_patch.start()
        self.addCleanup(self.vendor_patch.stop)

    def session_for(self, engine, identifier='conversation'):
        configuration = {
            'model': 'fixture-model', 'endpoint': 'https://fixture.invalid/v1',
            'thinkingMode': 'auto', 'permissionMode': 'native',
        }
        request = {'engineId': engine, 'sessionId': identifier, 'configuration': configuration}
        return agent_runner.AgentSession(
            'job-' + engine + '-' + identifier, self.workspace, self.base,
            request, os.environ.copy(), self.events.append,
        )

    def turn(self, session, task, *, prepare_only=False):
        session.request['task'] = task
        session.deadline = time.monotonic() + 5
        with runtime.engine_operation(session):
            return session.run_graph(None, session.request['configuration'], prepare_only=prepare_only)

    def test_warmup_and_back_to_back_turns_share_process_and_wait_for_cleanup(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                session = self.session_for(engine)
                ready = self.turn(session, 'warmup', prepare_only=True)
                self.assertEqual(ready['status'], 'Ready')
                self.assertIs(ready['modelRequested'], False)
                first = self.turn(session, 'first message')
                cleaned = self.root / (engine + '.cleaned')
                self.assertTrue(cleaned.is_file(), 'the host returned before turn cleanup')
                self.assertEqual(cleaned.read_text(), 'first message')
                second = self.turn(session, 'second message')
                self.assertEqual(first['status'], 'Submitted')
                self.assertEqual(second['summary'], 'second message')
                self.assertEqual(cleaned.read_text(), 'second message')
                self.assertEqual(ready['pid'], first['pid'])
                self.assertEqual(first['pid'], second['pid'])

    def test_model_change_restarts_resident_process(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                session = self.session_for(engine)
                first = self.turn(session, 'first model')
                session.request['configuration']['model'] = 'different-model'
                second = self.turn(session, 'changed model')
                self.assertNotEqual(first['pid'], second['pid'])

    def test_stopping_one_engine_closes_all_its_conversations_and_runs_dispose(self):
        first_session = self.session_for('pi', 'one')
        second_session = self.session_for('pi', 'two')
        other_session = self.session_for('gemini', 'three')
        first = self.turn(first_session, 'first')
        second = self.turn(second_session, 'second')
        other = self.turn(other_session, 'other engine')
        self.assertNotEqual(first['pid'], second['pid'])
        with runtime.stopping_engine(self.base, self.workspace, 'pi'):
            self.assertEqual(runtime.close_scope(self.base, self.workspace, 'pi'), 2)
        self.assertEqual((self.root / 'pi.disposed').read_text(), 'disposed')
        self.assertNotEqual(self.turn(first_session, 'restarted')['pid'], first['pid'])
        self.assertNotEqual(self.turn(second_session, 'also restarted')['pid'], second['pid'])
        self.assertEqual(self.turn(other_session, 'still running')['pid'], other['pid'])

    def test_idle_before_result_is_rejected(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                with self.assertRaisesRegex(ValueError, '结果之前'):
                    self.turn(self.session_for(engine), 'idle-first')

    def test_event_after_result_is_rejected(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                with self.assertRaisesRegex(ValueError, '结果之后'):
                    self.turn(self.session_for(engine), 'late-event')

    def test_cleanup_error_after_result_is_reported(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                with self.assertRaisesRegex(RuntimeError, 'cleanup failed after result'):
                    self.turn(self.session_for(engine), 'late-error')

    def test_warmup_requires_explicit_no_model_request_confirmation(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                with self.assertRaisesRegex(ValueError, '未完成本地预热'):
                    self.turn(self.session_for(engine), 'bad-warmup', prepare_only=True)

    def test_nonpersistent_engine_returns_result_without_waiting_for_idle(self):
        result = self.turn(self.session_for('openclaude'), 'single turn')
        self.assertEqual(result['status'], 'Submitted')
        self.assertEqual(result['summary'], 'single turn')


if __name__ == '__main__':
    unittest.main()
