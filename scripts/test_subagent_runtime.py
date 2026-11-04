"""Runtime contracts for client-owned children and their shared project environment."""
import concurrent.futures
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'core/app/src/main/assets/bridge'
sys.path.insert(0, str(BRIDGE))
import agent_runner as runner
from aster_tools import ProjectEnvironment
from aster_terminal import session_scope as scope_for


class SubagentRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.workspace = self.base / 'project'
        self.workspace.mkdir()
        (self.workspace / 'code.txt').write_text('baseline')
        self.events = []
        self.environment = dict(os.environ)
        self.parent = runner.AgentSession('parent', self.workspace, self.base,
            {'sessionId': 'conversation', 'engineId': 'pi', 'mode': 'execute', 'history': [{'role': 'user', 'content': 'private main context'}],
             'configuration': {'endpoint': 'https://fixture.invalid', 'model': 'main', 'apiKey': 'main-test-secret'}}, self.environment, self.events.append)
        self.parent.prepare()
        self.parent.deadline = time.monotonic() + 300
        with runner.SESSIONS_LOCK:
            self.assertFalse(runner.SESSIONS)
            runner.SESSIONS['parent'] = self.parent
        self.addCleanup(self.cleanup_sessions)

    def cleanup_sessions(self):
        self.parent.cancel()
        if self.parent.stage_lock:
            self.parent.stage_lock.close()
        with runner.SESSIONS_LOCK:
            runner.SESSIONS.clear()

    def request(self, identifier='child', **extra):
        return {'job': identifier, 'sessionId': identifier, 'engineId': 'pi', 'isSubagent': True, 'parentJob': 'parent',
                'mode': 'execute', 'history': [], 'context': '', 'task': 'Read the project', 'systemPrompt': 'Focused reviewer',
                'configuration': {'endpoint': 'https://fixture.invalid', 'model': 'child-model', 'apiKey': 'child-test-secret'}, **extra}

    def child(self, request=None, events=None):
        runner.handle(self.workspace, self.base, request or self.request(), self.environment, events.append if events is not None else self.events.append)

    def test_child_shares_stage_tools_locks_and_terminal_scope_but_has_independent_context(self):
        children = []
        def run(child):
            children.append(child)
            self.assertIs(child.root_session, self.parent)
            self.assertEqual(child.stage, self.parent.stage)
            self.assertIs(child.stage_tool_lock, self.parent.stage_tool_lock)
            self.assertIs(child.client_tool_lock, self.parent.client_tool_lock)
            self.assertEqual(child.allowed_tools, self.parent.allowed_tools - {'subagent'})
            self.assertNotEqual(child.session_file, self.parent.session_file)
            self.assertEqual(child.request['history'], [])
            self.assertEqual(child.request['systemPrompt'], 'Focused reviewer')
            self.assertEqual(scope_for(child), scope_for(self.parent))
            child.prepare()
            env = ProjectEnvironment(child)
            self.assertEqual(env.execute({'name': 'read_file', 'arguments': {'path': 'code.txt'}})['text'], '1: baseline')
            self.assertTrue(env.execute({'name': 'write_file', 'arguments': {'path': 'child.txt', 'content': 'shared edit'}})['ok'])
            self.assertFalse(env.execute({'name': 'subagent', 'arguments': {'action': 'models'}})['ok'])
        with patch.object(runner.AgentSession, 'run', run):
            self.child()
        self.assertEqual((self.parent.stage / 'child.txt').read_text(), 'shared edit')
        self.assertFalse((self.workspace / 'child.txt').exists())
        self.assertEqual(set(runner.SESSIONS), {'parent'})
        self.assertFalse(self.parent.children)

    def test_rejects_missing_wrong_workspace_nested_and_escalated_parent(self):
        with patch.object(runner.AgentSession, 'run') as run:
            with self.assertRaises(ValueError):
                self.child(self.request(parentJob='missing'))
            with self.assertRaises(ValueError):
                self.child(self.request(mode='ask'))
            other = self.base / 'other'
            other.mkdir()
            with self.assertRaises(ValueError):
                runner.handle(other, self.base, self.request(), self.environment, self.events.append)
            self.parent.root_session = object()
            with self.assertRaises(ValueError):
                self.child()
            self.parent.root_session = self.parent
            self.parent.cancelled.set()
            with self.assertRaises(ValueError):
                self.child()
            self.parent.cancelled.clear()
            run.assert_not_called()

    def test_read_only_parent_children_inherit_read_only_tools(self):
        self.parent.mode = 'ask'
        self.parent.allowed_tools = runner.READ_TOOLS
        def run(child):
            self.assertNotIn('write_file', child.allowed_tools)
            self.assertNotIn('terminal', child.allowed_tools)
            self.assertNotIn('subagent', child.allowed_tools)
            self.assertFalse(ProjectEnvironment(child).execute({'name': 'write_file', 'arguments': {'path': 'denied', 'content': 'x'}})['ok'])
        with patch.object(runner.AgentSession, 'run', run):
            self.child(self.request(mode='ask'))

    def test_two_simultaneous_children_reject_a_third_and_cleanup(self):
        started = threading.Barrier(3)
        release = threading.Event()
        def run(child):
            started.wait(timeout=5)
            release.wait(timeout=5)
        with patch.object(runner.AgentSession, 'run', run), concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            active = [pool.submit(self.child, self.request('child-' + str(i))) for i in range(2)]
            started.wait(timeout=5)
            try:
                with self.assertRaisesRegex(ValueError, '2 个子代理'):
                    self.child(self.request('third'))
            finally:
                release.set()
            for future in active:
                future.result(timeout=5)
        self.assertFalse(self.parent.children)
        self.assertEqual(set(runner.SESSIONS), {'parent'})

    def test_child_approval_routes_to_parent_and_redacts_child_key(self):
        approvals = []
        self.parent.approve_command = lambda command, timeout, description="": approvals.append((description or command, timeout)) or True
        self.parent.approve_external = lambda description: approvals.append(description) or False
        def run(child):
            self.assertTrue(child.approve_command('echo child-test-secret', 300))
            self.assertFalse(child.approve_external('network child-test-secret'))
        with patch.object(runner.AgentSession, 'run', run):
            self.child()
        self.assertIn('child-model', str(approvals))
        self.assertNotIn('child-test-secret', str(approvals))
        self.assertIn('300', str(approvals))

    def test_family_free_budget_is_shared_atomically(self):
        self.parent.request['configuration']['freeMode'] = True
        def run(child):
            self.parent.family_tool_calls = 62
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                futures = [pool.submit((child if i % 2 else self.parent).consume_tool_budget) for i in range(8)]
                results = [f.exception() for f in futures]
            self.assertEqual(sum(value is None for value in results), 2)
            self.assertEqual(self.parent.family_tool_calls, 64)
            self.assertTrue(all(isinstance(value, ValueError) for value in results if value is not None))
        with patch.object(runner.AgentSession, 'run', run):
            self.child()

    def test_cancel_parent_reaches_child_and_control_never_crosses_workspace(self):
        def run(child):
            request = {'job': child.identifier, 'workspace': 'other', 'op': 'agent_cancel'}
            self.assertFalse(runner.control(request, cancel=True))
            request['workspace'] = self.workspace.name
            self.assertTrue(runner.control(request, cancel=True))
            with self.assertRaises(runner.UserInterruption):
                child.check_cancelled()
            child.cancelled.clear()
            self.parent.cancel()
            self.assertTrue(child.cancelled.is_set())
        with patch.object(runner.AgentSession, 'run', run):
            self.child()

    def test_main_delegation_waits_for_client_result_without_backend_orchestration(self):
        arguments = {'action': 'models'}
        def send(event):
            if event.get('kind') == 'client_tool':
                self.assertEqual(event['name'], 'subagent')
                self.assertTrue(self.parent.accept_client_result(event['requestId'], {'ok': True, 'models': [{'model_id': 'current'}]}))
        self.parent.send = send
        result = ProjectEnvironment(self.parent).execute({'name': 'subagent', 'arguments': arguments})
        self.assertEqual(result['models'][0]['model_id'], 'current')
        self.assertFalse(self.parent.children)
        self.assertFalse((BRIDGE / 'agent_vendor/aster_subagents.py').exists())


if __name__ == '__main__':
    unittest.main()