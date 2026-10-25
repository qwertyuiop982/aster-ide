"""Aster UI/Termux adapter for the upstream Codex app-server JSON-RPC API."""
from pathlib import Path
import contextlib
import json
import os
import select
import subprocess
import tempfile
import time

from agent_engines import codex_binary
from aster_tools import ProjectEnvironment, READ_TOOLS, TOOLS

NL = bytes([10])

def context_settings(configuration):
    window = configuration.get('contextWindow', 32768)
    if isinstance(window, bool) or not isinstance(window, int) or not 8192 <= window <= 1048576:
        raise ValueError('Codex contextWindow must be an integer from 8192 to 1048576')
    return {'model_context_window': window, 'model_auto_compact_token_limit': max(4096, window - 8192)}

class CodexTransport:
    def __init__(self, session, process, errors):
        self.session, self.process, self.errors = session, process, errors
        self.sequence = 0
        self.buffer = b''
        self.replies = {}
        self.thread_id = ''
        self.turn_id = ''
        self.summary = ''
        self.finished = False
        self.status = None
        self.native_completed = False
        self.pending_status = None
        self.pending_call = None
        self.stop_deadline = None
        self.interrupt_sent = False
        self.compaction_items = set()
        self.compactions = 0
        self.context_tokens = 0
        self.context_source = "unknown"
        self.context_window = int((session.request.get('configuration') or {}).get('contextWindow', 32768))
        self.messages = {}
        self.limit = max(1, min(int((session.request.get('configuration') or {}).get('maxSteps', 32)), 128))

    def send(self, message):
        self.process.stdin.write(json.dumps(message, ensure_ascii=False).encode() + NL)
        self.process.stdin.flush()

    def request(self, method, params, wait=True):
        self.sequence += 1
        identifier = self.sequence
        self.send({'id': identifier, 'method': method, 'params': params})
        if not wait:
            return identifier
        deadline = time.monotonic() + 90
        while identifier not in self.replies:
            if time.monotonic() > deadline:
                raise TimeoutError('Codex RPC timed out: ' + method)
            self.poll()
        response = self.replies.pop(identifier)
        if 'error' in response:
            raise RuntimeError('Codex: ' + str(response['error'].get('message', response['error']))[:1600])
        return response.get('result') or {}

    def interrupt(self):
        if self.turn_id and not self.interrupt_sent and not self.native_completed:
            self.interrupt_sent = True
            self.request('turn/interrupt', {'threadId': self.thread_id, 'turnId': self.turn_id}, wait=False)

    def emit_context(self):
        self.session.emit('context', tokens=self.context_tokens,
                          contextWindow=self.context_window, compactions=self.compactions, source=self.context_source)

    def poll(self, check_cancelled=True):
        if check_cancelled:
            self.session.check_cancelled()
        if self.stop_deadline is not None and time.monotonic() > self.stop_deadline:
            raise TimeoutError('Codex did not finish persisting the interrupted turn')
        ready, _, _ = select.select([self.process.stdout], [], [], 0.1)
        if not ready:
            if self.process.poll() is not None:
                self.errors.seek(max(0, os.fstat(self.errors.fileno()).st_size - 1600))
                detail = self.errors.read(1600).decode(errors='replace').strip()
                raise RuntimeError(f'Codex app-server exited (code {self.process.returncode})' + (': ' + detail if detail else ' without stderr'))
            return
        data = os.read(self.process.stdout.fileno(), 32768)
        if not data:
            if check_cancelled:
                self.session.check_cancelled()
            code = self.process.poll()
            if code is not None:
                self.errors.seek(max(0, os.fstat(self.errors.fileno()).st_size - 1600))
                detail = self.errors.read(1600).decode(errors='replace').strip()
                raise RuntimeError(f'Codex app-server disconnected (exit {code})' + (': ' + detail if detail else ''))
            raise RuntimeError('Codex app-server closed stdout while the process is still running')
        self.buffer += data
        if len(self.buffer) > 8 * 1024 * 1024:
            raise ValueError('Codex message exceeded 8 MiB')
        while NL in self.buffer:
            line, self.buffer = self.buffer.split(NL, 1)
            value = json.loads(line)
            if 'method' in value:
                if 'id' in value:
                    self.server_request(value)
                else:
                    self.notification(value['method'], value.get('params') or {})
            elif 'id' in value:
                self.replies[value['id']] = value

    def server_request(self, value):
        method, params = value['method'], value.get('params') or {}
        if method == 'item/tool/call':
            name = params.get('tool', '')
            name = 'mcp' if name == 'aster_mcp' else name
            valid = (bool(self.thread_id) and params.get('threadId') == self.thread_id
                     and bool(self.turn_id) and params.get('turnId') == self.turn_id
                     and params.get('namespace') == 'aster' and bool(params.get('callId')))
            if not valid:
                result = {'ok': False, 'error': 'Tool call does not belong to the active Aster thread/turn/namespace'}
            elif self.session.cancelled.is_set():
                result = {'ok': False, 'error': 'Task cancelled'}
            elif name not in self.session.allowed_tools:
                result = {'ok': False, 'error': 'Tool is not allowed in the current Aster mode'}
            elif self.finished or self.session.steps >= self.limit:
                result = {'ok': False, 'error': 'Task finished or step limit reached'}
            else:
                self.session.steps += 1
                self.session.emit('status', text='Codex 正在执行工具', step=self.session.steps)
                result = ProjectEnvironment(self.session).execute({'name': name, 'arguments': params.get('arguments')})
                if name not in READ_TOOLS and name != 'update_plan':
                    self.session.checkpoint_proposal()
                self.session.emit('tool_result', name=name, text=json.dumps(self.session.redact_event(result), ensure_ascii=False)[:12000])
            self.send({'id': value['id'], 'result': {'contentItems': [{'type': 'inputText', 'text': json.dumps(result, ensure_ascii=False)}], 'success': result.get('ok', True)}})
            if result.get('finished'):
                self.finished = True
                self.summary = result.get('summary', '')
            if valid and (self.finished or self.session.steps >= self.limit) and self.pending_status is None:
                # First return the host tool result; interrupt only after the core
                # acknowledges the completed tool item. Wait for turn/completed.
                self.pending_status = 'Submitted' if self.finished else 'LimitsExceeded'
                self.pending_call = params['callId']
                self.stop_deadline = time.monotonic() + 30
        elif method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval'):
            # Only host tools may access the staged project or Termux.
            self.send({'id': value['id'], 'result': {'decision': 'decline'}})
            self.session.emit('tool', name='permissions', text='已拒绝未经过 Aster 工具桥接的操作')
        elif method == 'item/tool/requestUserInput':
            self.send({'id': value['id'], 'result': {'answers': {}}})
            self.session.emit('message', role='assistant', text=chr(10).join(q.get('question', '') for q in params.get('questions', [])))
        else:
            self.send({'id': value['id'], 'error': {'code': -32601, 'message': 'Aster does not support this request: ' + method}})

    def notification(self, method, params):
        if self.thread_id and params.get('threadId', self.thread_id) != self.thread_id:
            return
        incoming_turn = params.get('turnId') or (params.get('turn') or {}).get('id')
        if self.turn_id and incoming_turn and incoming_turn != self.turn_id:
            return
        if method == 'turn/started':
            self.turn_id = (params.get('turn') or {}).get('id', self.turn_id)
        elif method == 'item/agentMessage/delta':
            identifier = str(params['itemId'])
            if identifier not in self.messages:
                self.messages[identifier] = ''
                self.session.emit('message_start', messageId=identifier)
            delta = str(params.get('delta', ''))
            self.messages[identifier] += delta
            self.session.emit('message_delta', messageId=identifier, text=delta)
        elif method == 'item/completed':
            item = params.get('item') or {}
            if item.get('type') == 'contextCompaction':
                identifier = str(item.get('id', ''))
                if identifier and identifier not in self.compaction_items:
                    self.compaction_items.add(identifier)
                    self.compactions += 1
                    self.context_source = "unknown"
                    self.emit_context()
            elif item.get('type') == 'dynamicToolCall' and str(item.get('id')) == self.pending_call:
                self.interrupt()
            elif item.get('type') == 'agentMessage':
                text = str(item.get('text', self.messages.get(str(item.get('id')), '')))
                self.session.emit('message_end', messageId=str(item['id']), text=text)
                if text:
                    self.summary = text
        elif method == 'turn/plan/updated':
            statuses = {'pending': 'pending', 'inProgress': 'in_progress', 'completed': 'completed'}
            self.session.tasks = [{'task': str(p.get('step', ''))[:200], 'status': statuses.get(p.get('status'), 'pending')} for p in params.get('plan', [])[:20]]
            self.session.emit('plan', tasks=self.session.tasks)
        elif method == 'thread/tokenUsage/updated':
            usage = params.get('tokenUsage') or {}
            self.session.tokens = int((usage.get('total') or {}).get('totalTokens', 0))
            self.context_tokens = int((usage.get('last') or {}).get('totalTokens', 0))
            self.context_window = usage.get('modelContextWindow') or self.context_window
            self.context_source = 'engine'
            self.emit_context()
        elif method == 'error':
            error = params.get('error') or {}
            self.session.emit('error', text=str(error.get('message', error))[:1600], recoverable=True)
        elif method == 'turn/completed':
            turn = params.get('turn') or {}
            self.native_completed = True
            self.stop_deadline = None
            native_status = turn.get('status')
            if native_status == 'failed' or turn.get('error'):
                self.status = 'Failed'
            elif self.pending_status and native_status in ('completed', 'interrupted'):
                self.status = self.pending_status
            elif native_status == 'completed':
                self.status = 'Submitted' if self.summary.strip() or self.session.request.get('compactOnly') else 'Failed'
            else:
                self.status = 'Cancelled' if native_status == 'interrupted' else 'Failed'
            if turn.get('error'):
                self.session.emit('error', text=str(turn['error'].get('message', turn['error']))[:1600], recoverable=True)


def thinking_settings(configuration):
    from agent_thinking import resolve_thinking
    thinking = resolve_thinking('codex', configuration)
    return {'model_reasoning_effort': thinking['effort']} if 'effort' in thinking else {}


def run_codex(session, configuration):
    from agent_runner import kill_group, SYSTEM, MODE_PROMPTS, UserInterruption, TimeExceeded
    binary = codex_binary(session.base, session.environment)
    if binary is None:
        raise ValueError('Android codex-app-server is not installed')
    endpoint = str(configuration.get('endpoint', '')).rstrip('/')
    from urllib.parse import urlsplit
    parsed = urlsplit(endpoint)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('Invalid Codex Responses API endpoint')
    home = session.base / 'agent-native/codex'
    home.mkdir(parents=True, exist_ok=True)
    environment = dict(session.environment, CODEX_HOME=str(home), ASTER_MODEL_API_KEY=str(configuration.get('apiKey', '')))
    prefix = environment.get('PREFIX')
    if prefix:
        libraries = str(Path(prefix) / 'lib')
        search = [p for p in environment.get('LD_LIBRARY_PATH', '').split(os.pathsep) if p]
        environment['LD_LIBRARY_PATH'] = os.pathsep.join([libraries] + [p for p in search if p != libraries])
        certificates = Path(prefix) / 'etc/tls/cert.pem'
        if certificates.is_file():
            # Covers both the native-tls and rustls clients in upstream Codex.
            environment.setdefault('SSL_CERT_FILE', str(certificates))
    environment.pop('NODE_OPTIONS', None)
    settings = {
        'model': str(configuration['model']), 'model_provider': 'aster',
        'model_providers.aster.name': 'Aster', 'model_providers.aster.base_url': endpoint,
        'model_providers.aster.env_key': 'ASTER_MODEL_API_KEY', 'model_providers.aster.wire_api': 'responses',
        'model_providers.aster.supports_websockets': False,
        **context_settings(configuration), **thinking_settings(configuration),
        'web_search': 'disabled', 'features.multi_agent': False, 'features.code_mode': True, 'features.code_mode_only': False,
        'features.code_mode_host': True, 'features.code_mode_prewarm': False,
        'features.shell_tool': False, 'features.hooks': False, 'shell_environment_policy.inherit': 'all',
        'shell_environment_policy.set': {'SHELL': session.command_shell()},
    }
    command = [str(binary), '--listen', 'stdio://']
    for name, value in settings.items():
        if isinstance(value, dict):
            encoded = '{ ' + ', '.join(json.dumps(k) + ' = ' + json.dumps(v) for k, v in value.items()) + ' }'
        else:
            encoded = json.dumps(value)
        command += ['-c', name + '=' + encoded]
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, cwd=session.stage, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, start_new_session=True)
        session.engine_process = process
        wire = CodexTransport(session, process, errors)
        pointer = session.session_file.with_suffix('.codex.json')
        try:
            # app-server is an in-process child of the pinned native CLI. Identify
            # the real host rather than claiming to be a desktop Codex client.
            wire.request('initialize', {'clientInfo': {'name': 'codex-tui', 'title': 'Codex TUI', 'version': '0.163.0'}, 'capabilities': {'experimentalApi': True}})
            wire.send({'method': 'initialized', 'params': {}})
            developer = SYSTEM + chr(10) + MODE_PROMPTS[session.mode] + chr(10) + session.project_instructions()
            developer += chr(10) + 'Aster owns project and Termux access. Use only the supplied aster namespace tools. Changes are staged until the user applies the preview. Allowed tools for this turn: ' + ', '.join('aster_mcp' if name == 'mcp' else name for name in sorted(session.allowed_tools))
            developer += chr(10) + 'User-enabled MCP servers (aster_mcp tool): ' + str(configuration.get('mcpServers', '') or 'none')
            options = {'model': configuration['model'], 'modelProvider': 'aster', 'cwd': str(session.stage),
                       'approvalPolicy': 'untrusted', 'sandbox': 'read-only', 'developerInstructions': developer}
            if pointer.exists():
                saved = json.loads(pointer.read_text())
                wire.thread_id = saved['threadId']
                wire.compactions = saved.get('compactions', 0)
                wire.request('thread/resume', {**options, 'threadId': wire.thread_id})
            else:
                # Native dynamic tool definitions persist with the thread. Register
                # a stable namespace so Ask -> Execute can reuse its full history;
                # the host checks the current mode on every request.
                tools = [{'type': 'function', 'name': ('aster_mcp' if t['function']['name'] == 'mcp' else t['function']['name']), 'description': t['function']['description'], 'inputSchema': t['function']['parameters']} for t in TOOLS]
                namespace = {'type': 'namespace', 'name': 'aster', 'description': 'Aster project and Termux tools, subject to the current task mode.', 'tools': tools}
                result = wire.request('thread/start', {**options, 'dynamicTools': [namespace], 'environments': []})
                wire.thread_id = result['thread']['id']
            def save_pointer():
                temp = pointer.with_suffix('.tmp')
                temp.write_text(json.dumps({'threadId': wire.thread_id, 'compactions': wire.compactions}))
                temp.replace(pointer)
            save_pointer()
            if session.request.get('compactOnly'):
                before = wire.compactions
                wire.request('thread/compact/start', {'threadId': wire.thread_id})
                while not wire.native_completed:
                    wire.poll()
                wire.status = 'Submitted' if wire.status == 'Submitted' and wire.compactions > before else 'Failed'
                wire.summary = 'Codex 上下文已压缩' if wire.status == 'Submitted' else 'Codex 上下文压缩未完成'
            else:
                task = str(session.request.get('task', '')) + chr(10) + 'Editor context: ' + str(session.request.get('context', ''))
                effort = thinking_settings(configuration).get('model_reasoning_effort')
                response = wire.request('turn/start', {'threadId': wire.thread_id, 'input': [{'type': 'text', 'text': task}], 'environments': [],
                    **({'effort': effort} if effort is not None else {})})
                wire.turn_id = response['turn']['id']
                while not wire.native_completed:
                    wire.poll()
            save_pointer()
            return {'engine': session.engine_info['name'], 'status': wire.status, 'summary': wire.summary, 'steps': session.steps}
        except (UserInterruption, TimeExceeded):
            # Let the native core close the interrupted turn before force cleanup.
            wire.interrupt()
            deadline = time.monotonic() + 0.6
            with contextlib.suppress(OSError, RuntimeError, TimeoutError):
                while wire.turn_id and not wire.native_completed and time.monotonic() < deadline:
                    wire.poll(check_cancelled=False)
            raise
        finally:
            if wire.thread_id:
                with contextlib.suppress(OSError):
                    temporary = pointer.with_suffix('.tmp')
                    temporary.write_text(json.dumps({'threadId': wire.thread_id, 'compactions': wire.compactions}))
                    temporary.replace(pointer)
            kill_group(process)
            session.engine_process = None
            process.stdin.close()
            process.stdout.close()
