"""Native Codex host boundaries: approval routing, cancellation and process reuse."""
from contextlib import nullcontext
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core/app/src/main/assets/bridge'))
import agent_codex as codex
import agent_codex_runtime as runtime
from agent_native import load_native_mcp_servers


class Session:
    def __init__(self, root):
        self.stage = root / 'stage'
        self.workspace = root / 'project'
        self.stage.mkdir(exist_ok=True)
        self.workspace.mkdir(exist_ok=True)
        self.environment = dict(os.environ, PREFIX='/fixture/termux', SHELL='/bin/bash')
        self.request = {'configuration': {'contextWindow': 32768, 'maxSteps': 2}}
        self.mode = 'execute'
        self.permission_mode = 'review'
        self.native_commands_unchecked = False
        self.excluded = set()
        self.steps = self.tokens = self.checkpoints = 0
        self.events = []
        self.commands = []
        self.external = []
        self.engine_process = None
        self.tasks = []
        self.accept = True

    def command_shell(self): return '/bin/bash'
    def check_cancelled(self): pass
    def interaction_scope(self, cancelled): return nullcontext()
    def consume_tool_budget(self): pass
    def redact_event(self, value): return value
    def emit(self, kind, **data): self.events.append((kind, data))
    def checkpoint_proposal(self): self.checkpoints += 1
    def approve_command(self, command, timeout, *, description=None):
        self.commands.append(command)
        return self.accept
    def approve_external(self, description):
        self.external.append(description)
        return self.accept
    def client_tool(self, name, args):
        return {'ok': True, 'answers': {q['id']: ['yes'] for q in args['questions']}}


class NativeTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.session = Session(self.root)
        self.process = types.SimpleNamespace(stdin=io.BytesIO())
        self.wire = codex.CodexTransport(self.session, self.process, io.BytesIO())
        self.wire.thread_id, self.wire.turn_id = 'parent', 'turn'

    def tearDown(self):
        self.wire.close_interactions()
        runtime.close_all()
        self.temp.cleanup()

    def request(self, method, **params):
        self.process.stdin.seek(0)
        self.process.stdin.truncate()
        self.wire.server_request({'id': 7, 'method': method, 'params': {'threadId': 'parent', 'turnId': 'turn', **params}})
        pending = self.wire.server_requests.get(7)
        if pending is not None:
            pending[1].result(timeout=5)
            self.wire.drain_interactions()
        return json.loads(self.process.stdin.getvalue())

    def test_ultra_preserves_native_proactive_mode(self):
        self.assertEqual(codex.thinking_settings({'thinkingLevel': 'ultra', 'thinkingMode': 'strength'}), {'model_reasoning_effort': 'ultra'})
        self.assertEqual(codex.thinking_settings({'thinkingLevel': 'max', 'thinkingMode': 'strength'}), {'model_reasoning_effort': 'max'})

    def test_parent_spawn_call_has_stable_transcript_id(self):
        item = {'id': 'spawn-1', 'type': 'collabAgentToolCall', 'tool': 'spawnAgent', 'receiverThreadIds': ['child']}
        self.wire.notification('item/started', {'threadId': 'parent', 'turnId': 'turn', 'item': item})
        self.wire.notification('item/completed', {'threadId': 'parent', 'turnId': 'turn', 'item': {**item, 'status': 'completed'}})
        tools = [data for kind, data in self.session.events if kind in ('tool', 'tool_result')]
        self.assertEqual([entry['callId'] for entry in tools], ['spawn-1', 'spawn-1'])
        self.assertEqual([entry['name'] for entry in tools], ['spawn_agent', 'spawn_agent'])
        self.assertIn('child', self.wire.child_threads)

    def test_native_process_restarts_when_collaboration_contract_changes(self):
        home = self.root / 'contract-native'
        home.mkdir()
        code = 'import sys,json\nfor line in sys.stdin:\n v=json.loads(line);print(json.dumps({"id":v["id"],"result":{}}),flush=True)'
        command = [sys.executable, '-u', '-c', code]
        self.session.request['configuration']['collaborationMode'] = 'assist'
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            first_pid = wire.process.pid
            wire.request('ping', {})
        self.session.request['configuration']['collaborationMode'] = 'team'
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            self.assertNotEqual(first_pid, wire.process.pid)
            team_pid = wire.process.pid
            wire.request('ping', {})
        self.session.request['task'] = 'a new user turn must not restart the server'
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            self.assertEqual(team_pid, wire.process.pid)
        self.session.request['systemPrompt'] = 'new developer contract'
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            self.assertNotEqual(team_pid, wire.process.pid)
            wire.request('ping', {})

    def test_parent_completion_keeps_active_child_event_pump_alive(self):
        self.wire.register_child('child')
        self.wire.notification('turn/started', {'threadId': 'child', 'turn': {'id': 'child-turn'}})
        self.wire.summary = 'Delegated work is running'
        self.wire.notification('turn/completed', {'threadId': 'parent', 'turn': {'id': 'turn', 'status': 'completed'}})
        self.assertTrue(self.wire.native_completed)
        self.assertTrue(self.wire.has_active_children())
        self.wire.notification('item/completed', {'threadId': 'child', 'turnId': 'child-turn', 'item': {'id': 'late-answer', 'type': 'agentMessage', 'text': 'child actual result'}})
        self.wire.notification('turn/completed', {'threadId': 'child', 'turn': {'id': 'child-turn', 'status': 'completed'}})
        self.assertFalse(self.wire.has_active_children())
        self.assertTrue(any(kind == 'subagent_event' and data['event'].get('text') == 'child actual result' for kind, data in self.session.events))

    def test_native_role_contracts_are_in_generated_launch_and_thread_request(self):
        from unittest.mock import patch
        from contextlib import contextmanager
        session = self.session
        session.base = self.root
        session.session_id = 'contracts'
        session.session_file = self.root / 'contracts.jsonl'
        session.engine_info = {'name': 'Codex'}
        session.project_instructions = lambda: 'fixture project instructions'
        session.task_mode_prompt = lambda: 'fixture task mode'
        config = {'model': 'gpt-6.1-sol', 'endpoint': 'https://fixture.invalid/v1', 'collaborationMode': 'extreme', 'subagentsUnlimited': True, 'thinkingLevel': 'ultra', 'thinkingMode': 'strength'}
        session.request['configuration'] = config
        captured = {}
        class Wire:
            initialized = False
            compactions = 0
            thread_id = ''
            def send(self, value): pass
            def request(self, method, params):
                captured[method] = params
                return {'thread': {'id': 'fixture-thread'}}
        @contextmanager
        def fake_server(session, home, command, environment, transport):
            captured['command'] = command
            yield Wire()
        with patch.object(codex, 'codex_binary', return_value=Path('/fixture/codex')), patch.object(runtime, 'native_server', fake_server):
            codex.run_codex(session, config, prepare_only=True)
        command = captured['command']
        overrides = dict(value.split('=', 1) for index, value in enumerate(command) if index and command[index - 1] == '-c')
        self.assertEqual(json.loads(overrides['model_reasoning_effort']), 'ultra')
        self.assertIn('EXTREME ROOT CONTRACT', json.loads(overrides['features.multi_agent_v2.root_agent_usage_hint_text']))
        self.assertIn('EXTREME CHILD CONTRACT', json.loads(overrides['features.multi_agent_v2.subagent_developer_instructions']))
        self.assertNotIn('EXTREME ROOT CONTRACT', json.loads(overrides['features.multi_agent_v2.subagent_developer_instructions']))
        self.assertIn('EXTREME ROOT CONTRACT', captured['thread/start']['developerInstructions'])

    def test_real_v2_activity_registers_child_and_shows_spawn_tool_call(self):
        self.wire.notification('item/agentMessage/delta', {'threadId': 'v2-child', 'itemId': 'answer', 'delta': 'actual child output'})
        activity = {'type': 'subAgentActivity', 'id': 'spawn-v2', 'kind': 'started', 'agentThreadId': 'v2-child', 'agentPath': '/root/project_structure', 'model': 'fixture'}
        self.wire.notification('item/started', {'threadId': 'parent', 'turnId': 'turn', 'item': activity})
        self.assertIn('v2-child', self.wire.child_threads)
        events = [data for kind, data in self.session.events if kind == 'subagent_event']
        self.assertTrue(any(data['event'].get('text') == 'actual child output' for data in events))
        tools = [data for kind, data in self.session.events if kind == 'tool']
        self.assertEqual(len(tools), 1)
        self.assertEqual((tools[0]['callId'], tools[0]['name']), ('spawn-v2', 'spawn_agent'))
        self.wire.notification('item/completed', {'threadId': 'v2-child', 'item': {'type': 'subAgentActivity', 'id': 'grand-spawn', 'kind': 'started', 'agentThreadId': 'v2-grandchild', 'agentPath': '/root/project_structure/docs'}})
        self.assertIn('v2-grandchild', self.wire.child_threads)
        self.wire.notification('item/completed', {'threadId': 'parent', 'turnId': 'old-turn', 'item': {**activity, 'kind': 'completed'}})
        self.assertIn('v2-child', self.wire.child_completed)
        tools = [(kind, data.get('callId'), data.get('name'), data.get('status'))
                 for kind, data in self.session.events if kind in ('tool', 'tool_result')]
        self.assertEqual(tools, [
            ('tool', 'spawn-v2', 'spawn_agent', 'running'),
            ('tool_result', 'spawn-v2', 'spawn_agent', 'completed'),
        ])

    def test_context_settings_rejects_invalid_window(self):
        self.assertEqual(codex.context_settings({})['model_context_window'], 32768)
        for value in (True, 8191, 1048577, '32768'):
            with self.assertRaises(ValueError): codex.context_settings({'contextWindow': value})

    def test_dynamic_aster_namespace_is_not_executed(self):
        response = self.request('item/tool/call', tool='read_file', arguments={'path': 'private'})
        self.assertEqual(response['error']['code'], -32601)
        self.assertFalse(self.session.commands)

    def test_command_and_stdin_approvals_and_stale_turn(self):
        response = self.request('item/commandExecution/requestApproval', command='printf ready', cwd=str(self.session.stage))
        self.assertEqual(response['result']['decision'], 'accept')
        response = self.request('item/commandExecution/requestApproval', kind='writeStdin', itemId='terminal-1')
        self.assertEqual(response['result']['decision'], 'accept')
        response = self.request('item/commandExecution/requestApproval', turnId='stale', command='bad')
        self.assertEqual(response['error']['code'], -32000)
        self.assertNotIn('result', response)
        self.assertEqual(len(self.session.commands), 2)

    def test_permissions_decline_outside_project_and_read_only(self):
        response = self.request('item/permissions/requestApproval', permissions={'fileSystem': {'write': [str(self.session.stage / 'new')]}})
        self.assertIn('fileSystem', response['result']['permissions'])
        response = self.request('item/permissions/requestApproval', permissions={'fileSystem': {'write': ['/outside']}})
        self.assertEqual(response['error']['code'], -32000)
        self.assertNotIn('result', response)
        self.assertEqual(len(self.session.external), 1)
        self.session.mode = 'ask'
        response = self.request('item/fileChange/requestApproval')
        self.assertEqual(response['error']['code'], -32000)
        self.assertNotIn('result', response)
        self.assertEqual(len(self.session.external), 1)

    def test_explicit_user_decline_is_a_decision_not_an_approval_error(self):
        self.session.accept = False
        response = self.request('item/commandExecution/requestApproval', command='printf denied', cwd=str(self.session.stage))
        self.assertEqual(response['result']['decision'], 'decline')
        self.assertNotIn('error', response)
        self.assertEqual(self.session.commands, ['printf denied'])

    def test_native_child_approvals_do_not_complete_parent_turn(self):
        self.wire.notification('thread/started', {'thread': {'id': 'child', 'parentThreadId': 'parent'}})
        self.wire.notification('turn/started', {'threadId': 'child', 'turn': {'id': 'child-turn'}})
        response = self.request('item/commandExecution/requestApproval', threadId='child', turnId='child-turn', command='printf child')
        self.assertEqual(response['result']['decision'], 'accept')
        self.wire.notification('item/completed', {'threadId': 'child', 'turnId': 'child-turn', 'item': {'type': 'agentMessage', 'id': 'message', 'text': 'child result'}})
        self.wire.notification('turn/completed', {'threadId': 'child', 'turn': {'id': 'child-turn', 'status': 'completed'}})
        self.assertFalse(self.wire.native_completed)
        self.assertEqual(self.wire.summary, '')

    def test_spawn_source_child_messages_are_forwarded(self):
        self.wire.notification('thread/started', {'thread': {'id': 'child', 'source': {'subAgent': {'thread_spawn': {'parent_thread_id': 'parent'}}}}})
        self.wire.notification('item/agentMessage/delta', {'threadId': 'child', 'itemId': 'answer', 'delta': 'hello'})
        events = [data for kind, data in self.session.events if kind == 'subagent_event']
        self.assertEqual(events[-1]['event']['text'], 'hello')
        self.assertEqual(events[-1]['subagentId'], 'child')
        self.assertEqual(self.wire.summary, '')
    def test_child_tool_start_output_and_completion_are_forwarded(self):
        self.wire.notification('thread/started', {'thread': {'id': 'child', 'parentThreadId': 'parent'}})
        self.wire.notification('turn/started', {'threadId': 'child', 'turn': {'id': 'child-turn'}})
        self.wire.notification('item/started', {'threadId': 'child', 'turnId': 'child-turn', 'item': {'id': 'call-1', 'type': 'commandExecution', 'command': 'printf child'}})
        self.wire.notification('item/commandExecution/outputDelta', {'threadId': 'child', 'turnId': 'child-turn', 'itemId': 'call-1', 'delta': 'child output'})
        self.wire.notification('item/completed', {'threadId': 'child', 'turnId': 'child-turn', 'item': {'id': 'call-1', 'type': 'commandExecution', 'status': 'completed', 'command': 'printf child', 'aggregatedOutput': 'child output'}})
        events = [data['event'] for kind, data in self.session.events if kind == 'subagent_event']
        self.assertEqual([event['kind'] for event in events[-3:]], ['tool', 'tool_delta', 'tool_result'])
        self.assertEqual(events[-1]['callId'], 'call-1')
        self.assertIn('child output', events[-1]['text'])

    def test_early_child_events_replay_only_after_parentage_confirmed(self):
        self.wire.notification('item/started', {'threadId': 'late-child', 'item': {'id': 'early-tool', 'type': 'commandExecution'}})
        self.assertFalse(any(kind == 'subagent_event' for kind, _ in self.session.events))
        self.wire.notification('thread/started', {'thread': {'id': 'late-child', 'parentThreadId': 'parent'}})
        events = [data for kind, data in self.session.events if kind == 'subagent_event']
        self.assertEqual(events[-1]['subagentId'], 'late-child')
        self.assertEqual(events[-1]['event']['callId'], 'early-tool')
        self.assertEqual(events[-1]['event']['kind'], 'tool')
        self.assertNotIn('late-child', self.wire.pending_child_events)

    def test_nested_native_receiver_replays_early_tool_events(self):
        self.wire.register_child('child')
        self.wire.notification('item/started', {'threadId': 'grandchild', 'item': {'id': 'nested-tool', 'type': 'commandExecution'}})
        self.wire.notification('item/completed', {'threadId': 'child', 'item': {'id': 'spawn', 'type': 'collabAgentToolCall', 'receiverThreadIds': ['grandchild']}})
        events = [data for kind, data in self.session.events if kind == 'subagent_event' and data['subagentId'] == 'grandchild']
        self.assertEqual(events[-1]['event']['callId'], 'nested-tool')
        self.assertIn('grandchild', self.wire.child_threads)

    def test_user_input_routes_original_ids_and_answers(self):
        response = self.request('item/tool/requestUserInput', questions=[{'id': 'choice', 'question': 'Continue?'}])
        self.assertEqual(response['result']['answers'], {'choice': {'answers': ['yes']}})
        self.assertEqual([kind for kind, _ in self.session.events], ['tool', 'tool_result'])

    def test_old_step_setting_does_not_interrupt_native_turn(self):
        self.session.steps = 200
        self.wire.notification('item/started', {'threadId': 'parent', 'turnId': 'turn', 'item': {'id': 'exec', 'type': 'commandExecution'}})
        self.assertEqual(self.session.steps, 201)
        self.assertIsNone(self.wire.pending_status)
        self.assertFalse(self.wire.interrupt_sent)
        self.assertNotIn(b'turn/interrupt', self.process.stdin.getvalue())

    def test_shell_changes_checkpoint_native_proposal(self):
        self.wire.notification('item/completed', {'threadId': 'parent', 'turnId': 'turn', 'item': {'id': 'cmd', 'type': 'commandExecution', 'status': 'completed'}})
        self.assertEqual(self.session.checkpoints, 1)

    def test_selected_mcp_config_uses_staged_cwd_and_termux_environment(self):
        folder = self.session.workspace / '.aster'
        folder.mkdir()
        (folder / 'mcp.json').write_text(json.dumps({'mcpServers': {
            'local': {'command': 'node', 'args': ['server.mjs']},
            'remote': {'url': 'https://example.test/mcp', 'headerEnv': {'Authorization': 'MCP_TEST_TOKEN'}}}}))
        self.session.environment['MCP_TEST_TOKEN'] = 'fixture-secret'
        config = {'mcpServers': 'local,remote'}
        selected = load_native_mcp_servers(self.session, config)
        self.assertEqual(selected['local']['cwd'], str(self.session.stage))
        self.assertEqual(selected['local']['env']['PREFIX'], '/fixture/termux')
        self.assertEqual(selected['local']['env']['SHELL'], '/bin/bash')
        self.assertIn('fixture-secret', self.session.mcp_secret_values)
        home = self.root / 'home'
        home.mkdir()
        self.assertEqual(codex.write_native_mcp_config(self.session, home, config), 2)
        import tomllib
        document = tomllib.loads((home / 'config.toml').read_text())
        self.assertEqual(document['mcp_servers']['remote']['http_headers']['Authorization'], 'fixture-secret')
        self.assertEqual((home / 'config.toml').stat().st_mode & 0o777, 0o600)
        codex.write_native_mcp_config(self.session, home, {})
        self.assertFalse((home / 'config.toml').exists())

    def test_native_process_state_survives_turns_and_resets_after_failure(self):
        home = self.root / 'native'
        home.mkdir()
        code = 'import sys,json\nn=0\nfor line in sys.stdin:\n v=json.loads(line);n+=1;print(json.dumps({"id":v["id"],"result":{"counter":n}}),flush=True)'
        command = [sys.executable, '-u', '-c', code]
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            pid = wire.process.pid
            self.assertEqual(wire.request('ping', {})['counter'], 1)
        self.assertIsNone(self.session.engine_process)
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            self.assertEqual(wire.process.pid, pid)
            self.assertEqual(wire.request('ping', {})['counter'], 2)
        with self.assertRaisesRegex(ValueError, 'broken'):
            with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport):
                raise ValueError('broken')
        with runtime.native_server(self.session, home, command, os.environ.copy(), codex.CodexTransport) as wire:
            self.assertNotEqual(wire.process.pid, pid)
            self.assertEqual(wire.request('ping', {})['counter'], 1)


if __name__ == '__main__':
    unittest.main()