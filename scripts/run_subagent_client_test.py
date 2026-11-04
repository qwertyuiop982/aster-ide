"""Compile the real Kotlin coordinator and exercise it against real Python/Pi tool loops.

Run directly with python3 scripts/run_subagent_client_test.py. Requires the cached
Gradle Kotlin compiler, Node 22.19+, and ~/.cache/aster-jvm-tests/json-20240303.jar.
The only substitutes are Android's connection entry point and a local model HTTP fixture.
"""
import concurrent.futures
import contextlib
import http.server
import hashlib
import json
import os
from pathlib import Path
import re
import socketserver
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'core/app/src/main/assets/bridge'
sys.path.insert(0, str(BRIDGE))
import agent_runner as runner


def main():
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    def jar(group, name, version):
        return next((cache / group / name / version).glob('*/*.jar'))
    compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.0')
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.0')
    coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler_path = [compiler, stdlib, coroutines, annotations,
        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.1.0'),
        jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330')]
    json_jar = Path.home() / '.cache/aster-jvm-tests/json-20240303.jar'
    assert json_jar.is_file(), 'Cache org.json:json:20240303 from Maven Central before running'
    classpath = os.pathsep.join(map(str, [stdlib, coroutines, annotations, json_jar]))
    requests, child_requests, active_counts, provider_errors = [], [], [], []
    fixture_lock = threading.Lock()
    with tempfile.TemporaryDirectory(prefix='aster-client-subagent-') as folder:
        base = Path(folder)
        source = (ROOT / 'core/app/src/main/java/dev/aster/ide/runtime/BridgeClient.kt').read_text()
        codec = source[source.index('class BridgeConnection('):source.index('class BridgeClient(')]
        codec_file = base / 'ConnectionCodec.kt'
        codec_file.write_text('package dev.aster.ide.runtime\nimport java.net.Socket\nimport java.io.*\nimport org.json.JSONObject\n' + codec)
        agent = ROOT / 'core/app/src/main/java/dev/aster/ide/agent'
        sources = [agent / name for name in ['AgentSubagents.kt', 'AgentEngine.kt', 'AgentModels.kt', 'AgentContextPolicy.kt', 'AgentThinkingPolicy.kt', 'AgentDiagnostics.kt', 'AgentNativeQuestions.kt']]
        sources += list((ROOT / 'scripts/subagent_client').glob('*.kt')) + [codec_file]
        digest = hashlib.sha256(b''.join(path.read_bytes() for path in sources)).hexdigest()
        output = Path.home() / '.cache/aster-jvm-tests' / ('coordinator-' + digest + '.jar')
        output.parent.mkdir(parents=True, exist_ok=True)
        command = ['java', '-Xmx512m', '-cp', os.pathsep.join(map(str, compiler_path)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(output), *map(str, sources)]
        if not output.is_file():
            compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
            if compiled.returncode != 0:
                output.unlink(missing_ok=True)
                raise AssertionError(compiled.stdout + compiled.stderr)
        workspace = base / 'project'
        workspace.mkdir()
        (workspace / 'shared.txt').write_text('shared-value')
        skill = workspace / '.aster/skills/fixture/SKILL.md'
        skill.parent.mkdir(parents=True)
        skill.write_text('Read shared.txt and keep each task result in its own file.')
        environment = dict(os.environ)
        parent = runner.AgentSession('parent', workspace, base, {'engineId': 'pi', 'sessionId': 'main-conversation', 'mode': 'execute',
            'history': [{'role': 'user', 'content': 'MAIN-PRIVATE-CONTEXT'}], 'configuration': {'model': 'main'}}, environment, lambda event: None)
        parent.prepare()
        parent.deadline = time.monotonic() + 120
        runner.SESSIONS['parent'] = parent
        class ModelHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                with fixture_lock:
                    requests.append(body)
                wire = json.dumps(body)
                try:
                    assert 'MAIN-PRIVATE-CONTEXT' not in wire
                    assert all(tool['function']['name'] != 'subagent' for tool in body.get('tools', []))
                    if 'fail-provider' in wire:
                        self.send_response(400)
                        self.end_headers()
                        self.wfile.write(b'{"error":{"message":"fixture-child-secret fixture provider failure"}}')
                        return
                    if 'slow-cancel' in wire:
                        time.sleep(2)
                    match = re.search(r'task-(\d)', wire)
                    task = match.group(1) if match else 'cancel'
                    tools = [message for message in body['messages'] if message.get('role') == 'tool']
                    if not tools:
                        name, arguments = 'read_file', {'path': 'shared.txt'}
                    elif len(tools) == 1:
                        assert 'shared-value' in json.dumps(tools)
                        name, arguments = 'write_file', {'path': 'result-' + task + '.txt', 'content': 'done ' + task}
                    else:
                        name, arguments = 'finish', {'summary': 'completed task ' + task}
                    time.sleep(.1)
                    value = {'id': 'fixture', 'model': body['model'], 'choices': [{'message': {'role': 'assistant', 'content': None,
                        'tool_calls': [{'id': 'call-' + str(len(tools)), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(arguments)}}]},
                        'finish_reason': 'tool_calls'}], 'usage': {'prompt_tokens': 32, 'completion_tokens': 16}}
                    encoded = json.dumps(value).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(encoded)))
                    self.end_headers()
                    with contextlib.suppress(BrokenPipeError):
                        self.wfile.write(encoded)
                except Exception as error:
                    provider_errors.append(str(error))
                    self.send_response(500)
                    self.end_headers()
        model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), ModelHandler)
        class BridgeHandler(socketserver.StreamRequestHandler):
            def handle(self):
                request = json.loads(self.rfile.readline())
                def send(value):
                    if value.get('ok') is True and value.get('job'):
                        with fixture_lock:
                            active_counts.append(len(parent.children))
                    self.wfile.write((json.dumps(value) + '\n').encode())
                    self.wfile.flush()
                try:
                    if request['op'] == 'agent':
                        with fixture_lock:
                            child_requests.append(request)
                        runner.handle(workspace, base, request, environment, send, self.connection)
                    else:
                        accepted = runner.control(request, cancel=request['op'] == 'agent_cancel')
                        send({'ok': True, 'accepted': accepted})
                except Exception as error:
                    with contextlib.suppress(OSError):
                        send({'ok': False, 'error': str(error)})
        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True
        bridge = Server(('127.0.0.1', 0), BridgeHandler)
        workers = [threading.Thread(target=server.serve_forever, daemon=True) for server in (model, bridge)]
        for worker in workers:
            worker.start()
        try:
            executed = subprocess.run(['java', '-Xmx256m', '-cp', str(output) + os.pathsep + classpath,
                'dev.aster.ide.agent.CoordinatorTestKt', str(bridge.server_address[1]), 'http://127.0.0.1:' + str(model.server_port)],
                capture_output=True, text=True, timeout=75)
            assert executed.returncode == 0, executed.stdout + executed.stderr
            assert not provider_errors, provider_errors
            assert len(child_requests) == 6, len(child_requests)
            assert max(active_counts) == 2, active_counts
            assert {request['configuration']['model'] for request in child_requests} == {'fixture-primary', 'fixture-secondary'}
            for i in range(4):
                request = next(value for value in child_requests if value['task'].startswith('task-' + str(i)))
                assert request['systemPrompt'].startswith('SYSTEM-' + str(i))
                assert request['history'] == [] and request['context'] == ''
                assert request['configuration']['thinkingLevel'] == 'low'
                assert request['configuration']['mcpServers'] == 'terminal'
                assert request['configuration']['enabledSkills'] == ['fixture']
                assert (parent.stage / ('result-' + str(i) + '.txt')).read_text() == 'done ' + str(i)
                assert not (workspace / ('result-' + str(i) + '.txt')).exists()
            end = time.monotonic() + 8
            while parent.children and time.monotonic() < end:
                time.sleep(.05)
            assert not parent.children, 'Child runtimes did not close after completion/cancellation'
            receipt = json.loads(executed.stdout.strip().splitlines()[-1])
            receipt.update(real_python_runtime=True, real_shipped_pi_loop=True, max_observed_children=max(active_counts),
                child_sessions=len(child_requests), provider_requests=len(requests), provider='local HTTP protocol fixture',
                native_android_ui_tested=False)
            destination = ROOT / 'docs/subagent-client-verification.json'
            destination.write_text(json.dumps(receipt, indent=2) + '\n')
            print(json.dumps(receipt))
        finally:
            parent.cancel()
            for server in (bridge, model):
                server.shutdown()
                server.server_close()
            for worker in workers:
                worker.join()
            if parent.stage_lock:
                parent.stage_lock.close()
            runner.SESSIONS.clear()


if __name__ == '__main__':
    main()