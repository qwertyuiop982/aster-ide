"""Bounded newline JSON-RPC transport for approved project MCP processes."""
import json
import os
import select
import signal
import subprocess
import tempfile
import time
import uuid


class StdioMcpClient:
    def __init__(self, config, session):
        command = config.get('command')
        args = config.get('args', [])
        if not isinstance(command, str) or not command or '\x00' in command:
            raise ValueError('MCP command 必须是非空字符串')
        if not isinstance(args, list) or len(args) > 64 or any(not isinstance(arg, str) or '\x00' in arg for arg in args):
            raise ValueError('MCP args 必须是字符串数组，最多64项')
        variables = config.get('env', {})
        if not isinstance(variables, dict) or any(not isinstance(k, str) or not isinstance(v, str) or not k or '=' in k or '\x00' in k + v for k, v in variables.items()):
            raise ValueError('MCP env 必须是字符串映射')
        environment = dict(session.environment)
        environment.update(variables)
        # Model credentials are never implicitly forwarded to project processes.
        for key in ('ASTER_MODEL_API_KEY', 'ANTHROPIC_API_KEY', 'OPENAI_API_KEY', 'GEMINI_API_KEY'):
            if key not in variables:
                environment.pop(key, None)
        self.session = session
        self.buffer = b''
        self.errors = tempfile.TemporaryFile()
        self.process = None
        try:
            self.process = subprocess.Popen([command, *args], cwd=session.stage, env=environment,
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=self.errors, start_new_session=True, bufsize=0)
        except BaseException:
            self.errors.close()
            raise

    def request(self, method, params=None, notification=False):
        self.session.check_cancelled()
        identifier = uuid.uuid4().hex
        value = {'jsonrpc': '2.0', 'method': method, 'params': params or {}}
        if not notification:
            value['id'] = identifier
        wire = json.dumps(value).encode() + b'\n'
        if len(wire) > 1024 * 1024:
            raise ValueError('MCP 请求超过 1 MiB')
        self.process.stdin.write(wire)
        if notification:
            return {}
        deadline = time.monotonic() + 15
        received = 0
        while time.monotonic() < deadline:
            self.session.check_cancelled()
            if b'\n' not in self.buffer:
                ready, _, _ = select.select([self.process.stdout], [], [], 0.1)
                if not ready:
                    continue
                data = os.read(self.process.stdout.fileno(), 65536)
                if not data:
                    raise RuntimeError('MCP stdio 进程断开，exit=' + str(self.process.poll()))
                received += len(data)
                self.buffer += data
                if received > 1024 * 1024:
                    raise ValueError('MCP 响应超过 1 MiB')
                continue
            line, self.buffer = self.buffer.split(b'\n', 1)
            if not line.strip():
                continue
            reply = json.loads(line)
            if not isinstance(reply, dict) or reply.get('jsonrpc') != '2.0':
                raise ValueError('MCP stdio 响应格式无效')
            if 'method' in reply:
                if 'id' in reply:
                    rejected = {'jsonrpc': '2.0', 'id': reply['id'], 'error': {'code': -32601, 'message': 'Client requests are not supported'}}
                    self.process.stdin.write(json.dumps(rejected).encode() + b'\n')
                continue
            if reply.get('id') != identifier:
                raise ValueError('MCP 响应 ID 不匹配')
            if reply.get('error'):
                raise ValueError('MCP 调用失败：' + str(reply['error'])[:800])
            return reply.get('result', {})
        raise TimeoutError('MCP stdio 请求超时：' + method)

    def initialize(self):
        result = self.request('initialize', {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 'aster', 'version': '1.0.1'}})
        if result.get('protocolVersion') != '2025-03-26':
            raise ValueError('MCP 服务器未协商支持的协议版本')
        self.request('notifications/initialized', notification=True)

    def close(self):
        if self.process is not None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                self.process.wait(timeout=2)
            self.process.stdin.close()
            self.process.stdout.close()
        self.errors.close()
