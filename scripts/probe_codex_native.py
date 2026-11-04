"""Exercise the actual Android Codex app-server against a local Responses API."""
import base64
import gzip
import http.server
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = Path(os.environ.get('ASTER_PROBE_BRIDGE', str(ROOT / 'core/app/src/main/assets/bridge')))
sys.path.insert(0, str(BRIDGE))
import agent_runner as runner
import agent_codex
import agent_codex_runtime


def main():
    requests, events, failures, exposed = [], [], [], set()
    round_index = [0]
    with tempfile.TemporaryDirectory(prefix='aster-native-codex-') as folder:
        base = Path(folder)
        project = base / 'project'
        project.mkdir()
        (project / 'seed.txt').write_text('codex-native-seed')
        (project / 'image.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='))
        def tools(body):
            result = {}
            def collect(items, namespace=None):
                for spec in items:
                    if spec.get('type') == 'namespace':
                        collect(spec.get('tools', []), spec.get('name'))
                    elif spec.get('name'):
                        result[spec['name']] = (namespace, spec)
                    elif spec.get('type') == 'tool_search':
                        result['tool_search'] = (None, spec)
            collect(body.get('tools', []))
            exposed.update(result)
            return result
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{"data":[]}')
            def do_POST(self):
                raw = self.rfile.read(int(self.headers['Content-Length']))
                if self.headers.get('Content-Encoding') == 'gzip': raw = gzip.decompress(raw)
                body = json.loads(raw)
                requests.append(body)
                try:
                    available = tools(body)
                    index = round_index[0]
                    if index == 0 and len(requests) == 1:
                        self.send_response(503)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(b'{"error":{"message":"Codex native retry fixture"}}')
                        return
                    actions = [
                        ('exec_command', {'cmd': 'cat seed.txt; printf x >> shell-once.txt', 'yield_time_ms': 1000, 'max_output_tokens': 500}),
                        ('apply_patch', '*** Begin Patch\n*** Add File: codex-created.txt\n+codex-native-written\n*** End Patch'),
                        ('update_plan', {'plan': [{'step': 'Native Codex fixture', 'status': 'completed'}]}),
                        ('view_image', {'path': str(session.stage / 'image.png')}),
                        ('request_user_input', {'questions': [{'id': 'choice', 'header': 'Native', 'question': 'Choose fixture', 'options': [{'label': 'fixture-answer', 'description': 'Use fixture'}, {'label': 'other', 'description': 'Other'}]}]}),
                        ('request_permissions', {'reason': 'Native fixture permission', 'permissions': {'network': {'enabled': True}}}),
                        ('get_context_remaining', {}),
                        ('exec', 'console.log("native-js:" + (6 * 7))'),
                    ]
                    if index < len(actions):
                        name, arguments = actions[index]
                        if name not in available:
                            failures.append('missing native tool: ' + name)
                            # Continue exposing the full registry on old installed binaries.
                            round_index[0] += 1
                            name, arguments = ('exec_command', {'cmd': 'printf missing-tool', 'yield_time_ms': 1000})
                        namespace, spec = available[name]
                        item = {'id': 'fc_' + str(index), 'call_id': 'call_' + str(index), 'name': name, 'status': 'completed'}
                        if namespace: item['namespace'] = namespace
                        if spec.get('type') == 'custom':
                            item.update(type='custom_tool_call', input=arguments)
                        else:
                            item.update(type='function_call', arguments=json.dumps(arguments))
                        round_index[0] = index + 1
                    else:
                        item = {'id': 'msg_native', 'type': 'message', 'role': 'assistant', 'phase': 'final_answer', 'content': [{'type': 'output_text', 'text': 'Codex native pipeline passed'}]}
                    stream = [
                        {'type': 'response.created', 'response': {'id': 'resp_' + str(index)}},
                        {'type': 'response.output_item.added', 'output_index': 0, 'item': item},
                        {'type': 'response.output_item.done', 'output_index': 0, 'item': item},
                        {'type': 'response.completed', 'response': {'id': 'resp_' + str(index), 'status': 'completed', 'output': [item], 'usage': {'input_tokens': 64, 'output_tokens': 16, 'total_tokens': 80}, 'end_turn': item['type'] == 'message'}},
                    ]
                    encoded = ''.join('data: ' + json.dumps(value) + '\n\n' for value in stream).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.send_header('Content-Length', str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)
                except Exception as error:
                    failures.append(type(error).__name__ + ': ' + str(error))
                    self.send_response(500)
                    self.end_headers()
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        config = {'model': os.environ.get('ASTER_PROBE_MODEL', 'native-fixture'), 'endpoint': 'http://127.0.0.1:' + str(server.server_port) + '/v1',
                  'apiKey': 'fixture-local', 'contextWindow': 131072, 'maxTokens': 4096, 'maxSteps': 24, 'webSearch': 'disabled'}
        request = {'engineId': 'codex', 'sessionId': 'native-fixture', 'mode': 'execute', 'task': 'Exercise native tools and complete.', 'configuration': config}
        session = runner.AgentSession('native-fixture', project, base, request, dict(os.environ), events.append)
        session.approve_command = lambda *args: True
        session.approve_external = lambda *args: True
        session.client_tool = lambda name, args: {'ok': True, 'answers': {q['id']: ['fixture-answer'] for q in args['questions']}}
        session.deadline = time.monotonic() + 160
        try:
            session.prepare()
            result = agent_codex.run_codex(session, config)
            print(json.dumps({'result': result, 'requests': len(requests), 'nativeTools': sorted(exposed), 'failures': failures,
                              'toolEvents': [event for event in events if event.get('kind') in ('error', 'tool_result')][-12:]}, ensure_ascii=False))
            assert result['status'] == 'Submitted', result
            assert not failures, failures
            assert (session.stage / 'shell-once.txt').read_text() == 'x'
            assert (session.stage / 'codex-created.txt').read_text() == 'codex-native-written\n'
            assert not (project / 'codex-created.txt').exists()
            wire = json.dumps(requests, ensure_ascii=False)
            for expected in ('codex-native-seed', 'fixture-answer', 'native-js:42'):
                assert expected in wire, ('missing tool response', expected)
            assert not any(spec.get('name') == 'aster' for body in requests for spec in body.get('tools', []))
            print(json.dumps({'status': 'passed', 'engine': result['engine'], 'nativeToolCount': len(exposed), 'requests': len(requests), 'retry503': True, 'shellExecutedOnce': True, 'nativePatch': True, 'nativeImage': True, 'nativeQuestion': True, 'nativePermissions': True, 'nativeContext': True, 'nativeJavaScript': True}))
        finally:
            agent_codex_runtime.close_all()
            server.shutdown()
            server.server_close()
            if session.stage_lock: session.stage_lock.close()


if __name__ == '__main__': main()