"""Test Android tool discovery and approvals without a device or model request."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
import tomllib
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core/app/src/main/assets/bridge'))
import agent_android_mcp as android
from agent_codex import CodexTransport, write_native_mcp_config
from agent_codex_runtime import native_server, close_all
from agent_mcp_stdio import StdioMcpClient
from agent_runner import AgentSession, SESSIONS
from aster_tools import ProjectEnvironment, WEBVIEW_SEARCH
from bridge import Handler, Server


class AndroidClientToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.project = self.base / 'workspace'
        self.project.mkdir()
        self.events = []
        self.approve = True

    def session(self, mode='execute', engine='codex'):
        def on_event(event):
            self.events.append(event)
            if event['kind'] == 'approval':
                session.decide(event['requestId'], self.approve)
            elif event['kind'] == 'client_tool':
                session.accept_client_result(event['requestId'], {'ok': True, 'arguments': event['arguments']})
        session = AgentSession('test-job', self.project, self.base,
                               {'mode': mode, 'engineId': engine}, os.environ.copy(), on_event)
        session.stage.mkdir(parents=True)
        return session

    @contextmanager
    def serving(self, session):
        with Server(('127.0.0.1', 0), Handler) as server, patch.dict(SESSIONS, {session.identifier: session}):
            session.android_mcp_port = server.server_address[1]
            session.android_mcp_active = True
            worker = threading.Thread(target=server.serve_forever)
            worker.start()
            try:
                yield
            finally:
                server.shutdown()
                worker.join()

    def test_shared_search_is_available_in_pi_and_gemini_ask_mode(self):
        for engine in ('pi', 'gemini'):
            with self.subTest(engine=engine):
                session = self.session(mode='ask', engine=engine)
                args = {'query': 'web rendering', 'type': 'image', 'maxResults': 3}
                environment = ProjectEnvironment(session)
                self.assertIn(WEBVIEW_SEARCH, {item['function']['name'] for item in session.tools})
                self.assertEqual(environment.execute({'name': WEBVIEW_SEARCH, 'arguments': args})['arguments'], args)
                self.assertFalse(environment.execute({'name': 'desktop', 'arguments': {'action': 'status'}})['ok'])

    @unittest.skipUnless(shutil.which('node'), 'Node is needed to exercise the OpenClaude adapter')
    def test_openclaude_adapter_exposes_and_calls_search_in_ask_mode(self):
        session = self.session(mode='ask', engine='openclaude')
        args = {'query': 'rendered image', 'type': 'image', 'maxResults': 2}
        sdk = self.base / 'agent-engines/openclaude/sdk.mjs'
        sdk.parent.mkdir(parents=True)
        sdk.write_text("""
let contextWindow;
export const asterSetContextWindow = (_model, value) => { contextWindow = value; return { ok: true }; };
export const asterGetContextWindow = () => contextWindow;
export const tool = (name, _description, schema, call) => ({ name, schema, call });
export const createSdkMcpServer = (server) => server;
export function query({ options }) {
    const stream = (async function* () {
        const tools = options.mcpServers.aster.tools;
        const search = tools.find(({ name }) => name === 'web_webview_search');
        if (!search || !search.schema.required.includes('query') ||
            tools.some(({ name }) => ['desktop', 'browser'].includes(name))) {
            throw new Error('Unexpected OpenClaude tools in ask mode');
        }
        const args = { query: 'rendered image', type: 'image', maxResults: 2 };
        const permission = await options.canUseTool('mcp__aster__web_webview_search', args);
        if (permission.behavior !== 'allow') throw new Error('Search was not approved by the SDK adapter');
        const result = await search.call(permission.updatedInput);
        if (result.isError) throw new Error(result.content[0].text);
        yield { type: 'result', result: result.content[0].text };
    })();
    return Object.assign(stream, { close() {} });
}
""", encoding='utf-8')
        session.deadline = time.monotonic() + 30
        result = session.run_graph(None, {'model': 'fixture', 'endpoint': 'https://fixture.invalid/v1'})
        self.assertEqual(result['status'], 'Submitted')
        self.assertEqual(json.loads(result['summary'])['arguments'], args)
        self.assertTrue(any(event.get('name') == WEBVIEW_SEARCH and event['kind'] == 'client_tool'
                            for event in self.events))

    def test_native_stdio_calls_same_android_bridge_and_honors_approval(self):
        session = self.session()
        with self.serving(session):
            config = android.server_config(session)
            client = StdioMcpClient(config, session)
            try:
                client.initialize()
                tools = client.request('tools/list')['tools']
                self.assertEqual({tool['name'] for tool in tools}, set(android.TOOLS))
                args = {'query': 'dynamic page', 'type': 'image', 'maxResults': 2}
                request = {'name': WEBVIEW_SEARCH, 'arguments': args}
                reply = client.request('tools/call', request)
                self.assertFalse(reply['isError'])
                self.assertEqual(json.loads(reply['content'][0]['text'])['arguments'], args)
                self.approve = False
                self.events.clear()
                denial = client.request('tools/call', request)
                self.assertTrue(denial['isError'])
                self.assertFalse(any(event['kind'] == 'client_tool' for event in self.events))
                session.android_mcp_active = False
                self.assertTrue(client.request('tools/call', request)['isError'])
            finally:
                client.close()

    def test_read_only_mcp_leaves_desktop_and_browser_unavailable(self):
        session = self.session(mode='plan')
        session.android_mcp_port = 1024
        session.android_mcp_active = True
        with patch.dict(SESSIONS, {session.identifier: session}):
            request = {'name': 'desktop', 'arguments': {'action': 'status'}}
            self.assertFalse(android.call_active_session({**request, 'capability': android._session_key(session)})['ok'])
            self.assertFalse(android.call_active_session({**request, 'capability': '0' * 64})['ok'])
            self.assertEqual(self.events, [])
        self.assertEqual(set(android._definitions(False)), {WEBVIEW_SEARCH})

    @unittest.skipUnless(os.environ.get('ASTER_TEST_CODEX_APP_SERVER'), 'set ASTER_TEST_CODEX_APP_SERVER for native discovery')
    def test_pinned_codex_discovers_builtin_mcp(self):
        session = self.session()
        home = self.base / 'codex'
        home.mkdir()
        self.addCleanup(close_all)
        with self.serving(session):
            self.assertEqual(write_native_mcp_config(session, home, {}), 1)
            config = tomllib.loads((home / 'config.toml').read_text())
            self.assertEqual(config['mcp_servers'][android.SERVER_NAME]['environment_id'], 'local')
            environment = dict(session.environment, CODEX_HOME=str(home))
            command = [os.environ['ASTER_TEST_CODEX_APP_SERVER'], '--listen', 'stdio://',
                       '-c', 'features.mcp_2026_07_28=true']
            with native_server(session, home, command, environment, CodexTransport) as wire:
                wire.request('initialize', {'clientInfo': {'name': 'aster-test', 'version': '1'},
                                            'capabilities': {'experimentalApi': True}})
                wire.send({'method': 'initialized', 'params': {}})
                servers = wire.request('mcpServerStatus/list', {})['data']
                server = next(item for item in servers if item['name'] == android.SERVER_NAME)
                self.assertEqual(set(server['tools']), set(android.TOOLS))


if __name__ == '__main__':
    unittest.main()
