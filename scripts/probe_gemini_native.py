"""Run the shipped Gemini CLI loop against a local model and real native tools."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = Path(os.environ.get('ASTER_PROBE_BRIDGE', str(ROOT / 'core/app/src/main/assets/bridge')))
sys.path.insert(0, str(BRIDGE))
import agent_runner as runner

MCP = '''import json,sys
for line in sys.stdin:
 try:
  v=json.loads(line); m=v.get('method'); p=v.get('params',{}); r={}
  if m=='initialize': r={'protocolVersion':p['protocolVersion'],'capabilities':{'tools':{},'resources':{}},'serverInfo':{'name':'native-fixture','version':'1.0'}}
  elif m=='tools/list': r={'tools':[{'name':'echo','description':'Echo through native MCP','inputSchema':{'type':'object','properties':{'value':{'type':'string'}},'required':['value']}}]}
  elif m=='tools/call': r={'content':[{'type':'text','text':'native-mcp:'+p['arguments']['value']}]}
  elif m=='resources/list': r={'resources':[{'uri':'fixture://seed','name':'Seed','mimeType':'text/plain'}]}
  elif m=='resources/read': r={'contents':[{'uri':p['uri'],'mimeType':'text/plain','text':'native-resource'}]}
  elif m=='resources/templates/list': r={'resourceTemplates':[]}
  if 'id' in v: print(json.dumps({'jsonrpc':'2.0','id':v['id'],'result':r}),flush=True)
 except Exception as e:
  print(json.dumps({'jsonrpc':'2.0','id':v.get('id'),'error':{'code':-32603,'message':str(e)}}),flush=True)
'''


def main():
    requests, failures, events = [], [], []
    progress = [0]
    with tempfile.TemporaryDirectory(prefix='aster-native-gemini-') as folder:
        base = Path(folder)
        project = base / 'project'
        project.mkdir()
        (project / 'seed.txt').write_text('native-seed')
        (project / 'fixture_mcp.py').write_text(MCP)
        (project / '.aster/skills/fixture-skill').mkdir(parents=True)
        (project / '.aster/skills/fixture-skill/SKILL.md').write_text('---\nname: fixture-skill\ndescription: Native skill roundtrip fixture\n---\nUse the native skill instructions.\n')
        (project / '.aster/mcp.json').write_text(json.dumps({'mcpServers': {'fixture': {'command': sys.executable, 'args': ['-u', 'fixture_mcp.py']}}}))
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                try:
                    names = {item['function']['name']: item['function'] for item in body.get('tools', [])}
                    if len(requests) == 1:
                        self.send_response(503)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(b'{"error":{"message":"native retry fixture"}}')
                        return
                    index = progress[0]
                    path = lambda name: str(session.stage / name)
                    echo = next((name for name in names if 'echo' in name and 'fixture' in name), None)
                    actions = [
                        ('read_file', {'file_path': path('seed.txt')}),
                        ('write_file', {'file_path': path('created.txt'), 'content': 'native-written\n'}),
                        ('run_shell_command', {'command': 'printf native-shell; printf x >> shell-once.txt', 'description': 'Native shell round trip'}),
                        ('ask_user', {'questions': [{'question': 'Choose the fixture value', 'header': 'Native UI', 'type': 'choice', 'options': [{'label': 'fixture-answer', 'description': 'Use fixture value'}]}]}),
                        ('tracker_create_task', {'title': 'Native fixture', 'description': 'Verify native task tracking round trip', 'type': 'task'}),
                        (echo, {'value': 'roundtrip'}),
                        ('activate_skill', {'name': 'fixture-skill'}),
                    ]
                    if index < len(actions):
                        name, args = actions[index]
                        assert name in names, ('missing tool', name, sorted(names))
                        message = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'native-' + str(index), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}
                        progress[0] += 1
                        reason = 'tool_calls'
                    else:
                        message, reason = {'role': 'assistant', 'content': 'Native pipeline passed'}, 'stop'
                    result = {'id': 'fixture', 'model': body['model'], 'choices': [{'message': message, 'finish_reason': reason}], 'usage': {'prompt_tokens': 64, 'completion_tokens': 16, 'total_tokens': 80}}
                    encoded = json.dumps(result).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)
                except Exception as error:
                    failures.append(str(error))
                    self.send_response(500)
                    self.end_headers()
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        request = {'engineId': 'gemini', 'sessionId': 'native-fixture', 'mode': 'execute', 'task': 'Exercise native tools and complete the fixture.',
                   'configuration': {'model': 'native-fixture', 'endpoint': 'http://127.0.0.1:' + str(server.server_port) + '/v1',
                                     'apiKey': 'fixture-local', 'protocol': 'openai', 'contextWindow': 131072, 'maxTokens': 4096, 'maxSteps': 20, 'mcpServers': 'fixture', 'enabledSkills': ['fixture-skill']}}
        session = runner.AgentSession('native-fixture', project, base, request, dict(os.environ), events.append)
        session.approve_command = lambda *args: True
        session.approve_external = lambda *args: True
        session.client_tool = lambda name, args: {'ok': True, 'answers': {str(q['id']): ['fixture-answer'] for q in args['questions']}}
        session.deadline = time.monotonic() + 110
        try:
            session.prepare()
            result = session.run_graph(None, request['configuration'])
            assert not failures, failures
            assert result['status'] == 'Submitted', result
            assert (session.stage / 'created.txt').read_text() == 'native-written\n'
            assert (session.stage / 'shell-once.txt').read_text() == 'x'
            assert not (project / 'created.txt').exists()
            wire = json.dumps(requests, ensure_ascii=False)
            for text in ('native-seed', 'native-shell', 'fixture-answer', 'native-mcp:roundtrip'):
                assert text in wire, ('missing native result', text)
            native = sorted(item['function']['name'] for item in requests[-1].get('tools', []))
            assert 'invoke_agent' in native, native
            assert 'tracker_create_task' in native, native
            assert 'enter_plan_mode' in native, native
            assert not {'terminal', 'subagent', 'run_command', 'finish', 'mcp'} & set(native), native
            failed = [event for event in events if event.get('kind') == 'tool_result' and json.loads(event['text']).get('ok') is False]
            assert not failed, failed
            receipt = {'engine': result['engine'], 'status': result['status'], 'requests': len(requests), 'retry503': True,
                       'realNativeTools': native, 'fileProposal': True, 'shellExecutedOnce': True, 'nativeUserInput': True, 'nativeMcpRoundTrip': True}
            print(json.dumps(receipt, ensure_ascii=False))
        finally:
            server.shutdown()
            server.server_close()
            if session.stage_lock: session.stage_lock.close()


if __name__ == '__main__': main()