"""Aster UI/Termux adapter for the upstream Codex app-server JSON-RPC API."""
from pathlib import Path
import contextlib
from concurrent.futures import ThreadPoolExecutor
import threading
import json
import os
import select
import time

from agent_engines import codex_binary

NL = bytes([10])

NATIVE_ITEM_TYPES = {
    'commandExecution', 'fileChange', 'webSearch', 'imageView',
    'mcpToolCall', 'collabAgentToolCall', 'dynamicToolCall', 'functionCallOutput',
}
MCP_RESOURCE_TOOLS = {'list_mcp_resources', 'list_mcp_resource_templates', 'read_mcp_resource'}


def native_item_name(item):
    kind = str(item.get('type', ''))
    if kind == 'commandExecution':
        return 'unified_exec'
    if kind == 'fileChange':
        return 'apply_patch'
    if kind == 'imageView':
        return 'view_image'
    if kind == 'webSearch':
        return 'web_search'
    if kind == 'collabAgentToolCall':
        tool = str(item.get('tool') or '')
        return {'spawnAgent': 'spawn_agent', 'sendInput': 'send_input', 'resumeAgent': 'resume_agent',
                'wait': 'wait_agent', 'closeAgent': 'close_agent', 'sendMessage': 'send_message',
                'followupTask': 'followup_task', 'interruptAgent': 'interrupt_agent', 'listAgents': 'list_agents'}.get(tool, tool or 'multi_agents')
    if kind == 'dynamicToolCall':
        name = str(item.get('tool') or '')
        return 'tool_search' if name in {'tool_search', 'tool_search_tool'} else name or 'dynamic_tool'
    if kind == 'functionCallOutput':
        return str(item.get('name') or 'codex_tool')
    if kind == 'mcpToolCall':
        return 'mcp_resource' if str(item.get('tool') or '') in MCP_RESOURCE_TOOLS else 'mcp'
    return kind or 'codex_tool'


def _toml_string(value):
    return json.dumps(str(value), ensure_ascii=False)


def _toml_string_map(values):
    if not isinstance(values, dict):
        return '{}'
    pairs = []
    for key, value in values.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise ValueError('MCP 配置必须使用字符串键和值')
        pairs.append(f'{_toml_string(key)} = {_toml_string(value)}')
    return '{ ' + ', '.join(pairs) + ' }'


def write_native_mcp_config(session, home, configuration):
    target = home / 'config.toml'
    from agent_native import load_native_mcp_servers
    servers = load_native_mcp_servers(session, configuration)
    from agent_skills import load_project_skills
    catalog, selected = load_project_skills(session.stage, configuration.get('enabledSkills') or [])
    enabled_skills = {name for name, _, _ in selected}
    skill_root = home / 'skills'
    skill_root.mkdir(exist_ok=True)
    for link in skill_root.iterdir():
        if link.is_symlink() and link.name not in enabled_skills and session.stage.resolve() in link.resolve().parents:
            link.unlink()
    for name, path, _ in selected:
        link = skill_root / name
        source = (session.stage / path).parent.resolve()
        if link.is_symlink():
            link.unlink()
        elif link.exists():
            raise ValueError('Native skill path already exists: ' + name)
        link.symlink_to(source, target_is_directory=True)
    if not servers and not catalog:
        target.unlink(missing_ok=True)
        return 0
    lines = []
    if catalog:
        rules = ['{ name = ' + _toml_string(name) + ', enabled = ' + str(name in enabled_skills).lower() + ' }' for name in catalog]
        lines += ['[skills]', 'include_instructions = true', 'config = [' + ', '.join(rules) + ']', '']
    for name, config in servers.items():
        lines += [f'[mcp_servers.{_toml_string(name)}]', 'environment_id = "local"', 'enabled = true']
        if config['type'] == 'stdio':
            lines += ['command = ' + _toml_string(config['command']),
                      'args = [' + ', '.join(_toml_string(item) for item in config['args']) + ']',
                      'env = ' + _toml_string_map(config['env']),
                      'cwd = ' + _toml_string(config['cwd'])]
        else:
            lines += ['url = ' + _toml_string(config['url'])]
            if config['headers']:
                lines.append('http_headers = ' + _toml_string_map(config['headers']))
        lines.append('')
    temporary = target.with_suffix('.tmp')
    temporary.write_text('\n'.join(lines), encoding='utf-8')
    temporary.chmod(0o600)
    temporary.replace(target)
    return len(servers)


def context_settings(configuration):
    window = configuration.get('contextWindow', 32768)
    if isinstance(window, bool) or not isinstance(window, int) or not 8192 <= window <= 1048576:
        raise ValueError('Codex contextWindow must be an integer from 8192 to 1048576')
    return {'model_context_window': window, 'model_auto_compact_token_limit': max(4096, window - 8192)}

class CodexTransport:
    def __init__(self, session, process, errors):
        self.session, self.process, self.errors = session, process, errors
        self.initialized = False
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
        self.child_threads = set()
        self.pending_child_events = {}
        self.child_turns = {}
        self.child_completed = set()
        self.send_lock = threading.RLock()
        self.reply_context = threading.local()
        self.interactions = ThreadPoolExecutor(max_workers=1, thread_name_prefix='Aster-Codex-UI')
        self.server_requests = {}
        self.async_questions = []
        self.question_items = set()
        self.native_items = {}
        self.native_outputs = {}
        self.native_started = set()
        self.messages = {}

    def begin_turn(self, session):
        self.session = session
        self.turn_id = ''
        self.summary = ''
        self.status = None
        self.finished = False
        self.native_completed = False
        self.pending_status = None
        self.pending_call = None
        self.stop_deadline = None
        self.interrupt_sent = False
        self.messages.clear()
        self.question_items.clear()
        self.native_started.clear()
        self.native_items.clear()
        self.native_outputs.clear()

    def send(self, message):
        cancelled = getattr(self.reply_context, 'cancelled', None)
        if cancelled is not None and cancelled.is_set() and 'method' not in message:
            return
        with self.send_lock:
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

    def active_request(self, params, *, optional_turn=False):
        thread = params.get('threadId')
        turn = params.get('turnId')
        expected = self.turn_id if thread == self.thread_id else self.child_turns.get(thread)
        known = bool(self.thread_id) and (thread == self.thread_id or thread in self.child_threads)
        return known and ((optional_turn and turn is None) or (bool(expected) and turn == expected))

    def mcp_elicitation(self, params):
        if getattr(self, 'preparing', False) or not self.active_request(params, optional_turn=True):
            return {'action': 'cancel', 'content': None}
        from agent_interactions import mcp_elicitation
        return mcp_elicitation(self.session, params)

    def has_interactions(self):
        return bool(self.server_requests or self.async_questions)

    def close_interactions(self):
        for cancelled, future in self.server_requests.values():
            cancelled.set()
            future.cancel()
        for entry in self.async_questions:
            entry['cancelled'].set()
            entry['future'].cancel()
        self.interactions.shutdown(wait=False, cancel_futures=True)

    def server_request(self, value):
        if value['method'] == 'currentTime/read':
            self.send({'id': value['id'], 'result': {'currentTimeAt': int(time.time())}})
            return
        # Never stop reading native events while Android is waiting for a person.
        # In particular serverRequest/resolved must dismiss an auto-resolved form.
        if len(self.server_requests) >= 32:
            self.send({'id': value['id'], 'error': {'code': -32000, 'message': 'Too many pending interactive requests'}})
            return
        cancelled = threading.Event()
        def handle():
            with self.session.interaction_scope(cancelled):
                self.reply_context.cancelled = cancelled
                try:
                    if not cancelled.is_set():
                        self._server_request(value)
                except (ValueError, TypeError, KeyError) as error:
                    self.send({'id': value['id'], 'error': {'code': -32602, 'message': str(error)[:1000]}})
                finally:
                    self.reply_context.cancelled = None
        self.server_requests[value['id']] = (cancelled, self.interactions.submit(handle))

    def queue_questions(self, item, thread_id):
        key = (thread_id, str(item.get('id', '')))
        if key in self.question_items:
            return
        if len(self.async_questions) >= 32:
            raise ValueError('Codex pending question limit reached')
        self.question_items.add(key)
        from agent_interactions import normalize_questions, request_questions
        questions = normalize_questions(item['questions'])
        cancelled = threading.Event()
        self.session.emit('tool', name='request_user_input_async', text=json.dumps({'questions': questions}, ensure_ascii=False)[:12000])
        def ask():
            with self.session.interaction_scope(cancelled):
                return request_questions(self.session, questions)
        self.async_questions.append({'thread': thread_id, 'item': key[1], 'questions': questions,
                                     'cancelled': cancelled, 'future': self.interactions.submit(ask)})

    def start_followup(self, thread_id, text):
        if thread_id == self.thread_id:
            self.native_completed = False
            self.turn_id = ''
            self.summary = ''
            self.status = None
        else:
            self.child_turns.pop(thread_id, None)
            self.child_completed.discard(thread_id)
        result = self.request('turn/start', {'threadId': thread_id, 'input': [{'type': 'text', 'text': text}]})
        if thread_id == self.thread_id:
            self.turn_id = result['turn']['id']
        else:
            self.child_turns[thread_id] = result['turn']['id']

    def drain_interactions(self):
        if self.pending_status or self.native_completed and self.status in ('Failed', 'Cancelled'):
            for entry in self.async_questions:
                entry['cancelled'].set()
        for identifier, (cancelled, future) in list(self.server_requests.items()):
            if future.done():
                self.server_requests.pop(identifier, None)
                if not future.cancelled():
                    future.result()
        for entry in list(self.async_questions):
            if not entry['future'].done():
                continue
            self.async_questions.remove(entry)
            result = entry['future'].result()
            if self.pending_status or self.native_completed and self.status in ('Failed', 'Cancelled'):
                self.session.emit('tool_result', name='request_user_input_async', text=json.dumps({'ok': False, 'error': 'Task limit reached before answer delivery'}))
                continue
            self.session.emit('tool_result', name='request_user_input_async', text=json.dumps({
                'ok': True, 'dismissed': result.get('dismissed', False), 'answered': list((result.get('answers') or {}).keys())}))
            lines = ['User response to question item ' + entry['item'] + ':']
            if result.get('dismissed'):
                lines.append('The user cancelled or timed out. No answer or permission was granted; do not assume consent.')
            else:
                for question in entry['questions']:
                    lines.append(question['question'] + '\n' + '\n'.join(result['answers'].get(question['id']) or []))
            lines.append('Continue the task using this response. This is not authorization for commands or extra permissions.')
            text = '\n'.join(lines)
            thread_id = entry['thread']
            active = self.turn_id if thread_id == self.thread_id and not self.native_completed else self.child_turns.get(thread_id) if thread_id not in self.child_completed else None
            if active:
                try:
                    self.request('turn/steer', {'threadId': thread_id, 'expectedTurnId': active, 'input': [{'type': 'text', 'text': text}]})
                except RuntimeError as error:
                    # A native async turn may finish while the user is answering.
                    detail = str(error).lower()
                    ended = self.native_completed if thread_id == self.thread_id else thread_id in self.child_completed
                    if not ended and not any(marker in detail for marker in ('no active turn', 'turn mismatch', 'turn id mismatch', 'not active', 'expected turn')):
                        raise
                    self.start_followup(thread_id, text)
            else:
                self.start_followup(thread_id, text)
            if thread_id != self.thread_id and self.native_completed:
                self.start_followup(self.thread_id, 'The user has responded to questions from child thread ' + thread_id + '. Continue coordinating the task and wait for the child as needed. No extra permissions were granted.')

    def validate_permissions(self, permissions):
        if not isinstance(permissions, dict):
            raise ValueError('Invalid native permission profile')
        from agent_native import staged_path
        filesystem = permissions.get('fileSystem') or permissions.get('file_system') or {}
        for key in ('read', 'write'):
            for path in filesystem.get(key) or []:
                staged_path(self.session, path, folder=True)
        for entry in filesystem.get('entries') or []:
            path = entry.get('path') if isinstance(entry, dict) else None
            if isinstance(path, dict):
                if path.get('type') != 'path':
                    raise ValueError('Only project paths can be granted on Android')
                path = path.get('path')
            staged_path(self.session, path, folder=True)

    def approval_error(self, identifier, reason):
        message = '执行审批未完成（未收到用户拒绝）：' + str(reason)[:1000]
        self.session.emit('error', text=message, recoverable=True)
        self.send({'id': identifier, 'error': {'code': -32000, 'message': message}})

    def _server_request(self, value):
        method, params = value['method'], value.get('params') or {}
        if method == 'item/tool/call':
            self.send({'id': value['id'], 'error': {
                'code': -32601,
                'message': 'Codex dynamic Aster tools are disabled; use the native Codex tool registry',
            }})
        elif method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval'):
            valid = self.active_request(params) and not self.pending_status
            if not valid:
                self.approval_error(value['id'], '审批请求已过期或不属于当前回合，未向用户显示窗口。')
                return
            if self.session.mode != 'execute' and self.session.permission_mode != 'native':
                self.approval_error(value['id'], '当前工作模式不允许执行操作，未向用户显示窗口。')
                return
            accepted = False
            try:
                from agent_native import staged_path
                if valid and method == 'item/commandExecution/requestApproval':
                    staged_path(self.session, params.get('cwd') or str(self.session.stage), folder=True)
                    command = params.get('command')
                    details = []
                    if params.get('reason'):
                        details.append(str(params['reason'])[:2000])
                    if params.get('networkApprovalContext'):
                        details.append('网络请求：' + json.dumps(params['networkApprovalContext'], ensure_ascii=False)[:8000])
                    if params.get('additionalPermissions'):
                        self.validate_permissions(params['additionalPermissions'])
                        details.append('额外权限：' + json.dumps(params['additionalPermissions'], ensure_ascii=False)[:12000])
                    if params.get('kind') == 'writeStdin':
                        command = 'Codex 终端输入 · ' + str(command or params.get('itemId', ''))[:1600]
                    if isinstance(command, str) and command.strip():
                        accepted = self.session.approve_command(command, 0, description=command + ('\n\n' + '\n'.join(details) if details else ''))
                    elif params.get('networkApprovalContext'):
                        accepted = self.session.approve_external('Codex 网络访问确认\n' + '\n'.join(details))
                    else:
                        raise ValueError('审批请求缺少有效命令或网络操作')
                elif valid:
                    root = params.get('grantRoot') or params.get('cwd') or str(self.session.stage)
                    staged_path(self.session, root, folder=True)
                    description = 'Codex 文件变更请求'
                    if params.get('reason'):
                        description += ' · ' + str(params['reason'])[:800]
                    accepted = self.session.approve_external(description)
            except (ValueError, TypeError, TimeoutError) as error:
                self.approval_error(value['id'], str(error))
                return
            self.send({'id': value['id'], 'result': {'decision': 'accept' if accepted else 'decline'}})
        elif method == 'item/permissions/requestApproval':
            valid = self.active_request(params) and not self.pending_status
            if not valid:
                self.approval_error(value['id'], '审批请求已过期或不属于当前回合，未向用户显示窗口。')
                return
            if self.session.mode != 'execute' and self.session.permission_mode != 'native':
                self.approval_error(value['id'], '当前工作模式不允许执行操作，未向用户显示窗口。')
                return
            accepted = False
            permissions = params.get('permissions') if isinstance(params.get('permissions'), dict) else {}
            try:
                from agent_native import staged_path
                self.validate_permissions(permissions)
                if valid:
                    description = 'Codex 请求额外权限\n' + json.dumps(permissions, ensure_ascii=False)[:12000]
                    if params.get('reason'):
                        description += ' · ' + str(params['reason'])[:800]
                    accepted = self.session.approve_external(description)
            except (ValueError, TypeError, TimeoutError) as error:
                self.approval_error(value['id'], str(error))
                return
            self.send({'id': value['id'], 'result': {
                'permissions': permissions if accepted else {}, 'scope': 'turn',
            }})
        elif method == 'mcpServer/elicitation/request':
            self.send({'id': value['id'], 'result': self.mcp_elicitation(params)})
        elif method == 'item/tool/requestUserInput':
            valid = self.active_request(params)
            answers = {}
            if valid:
                questions = params.get('questions') or []
                self.session.emit('tool', name='request_user_input', text=json.dumps({'questions': questions}, ensure_ascii=False)[:12000])
                from agent_interactions import request_questions
                result = request_questions(self.session, questions)
                for key, values in (result.get('answers') or {}).items():
                    answers[str(key)] = {'answers': values if isinstance(values, list) else [str(values)]}
                self.session.emit('tool_result', name='request_user_input', text=json.dumps({'ok': True, 'dismissed': result.get('dismissed', False), 'answered': list(answers)}))
            self.send({'id': value['id'], 'result': {'answers': answers}})
        else:
            self.send({'id': value['id'], 'error': {'code': -32601, 'message': 'Aster does not support this request: ' + method}})

    def register_child(self, identifier, *, label='', prompt='', model=''):
        identifier = str(identifier or '')
        if not identifier or identifier == self.thread_id:
            return
        fresh = identifier not in self.child_threads
        self.child_threads.add(identifier)
        if fresh:
            self.session.emit('subagent_event', subagentId=identifier,
                              label=label or 'Codex · ' + identifier[-8:],
                              event={'kind': 'started', 'prompt': prompt, 'model': model})
        for method, params in self.pending_child_events.pop(identifier, []):
            self.notification(method, params)

    def has_active_children(self):
        return any(identifier not in self.child_completed for identifier in self.child_turns)
    def notification(self, method, params):
        if method == 'serverRequest/resolved':
            pending = self.server_requests.get(params.get('requestId'))
            if pending:
                pending[0].set()
            return
        if method == 'thread/started':
            thread = params.get('thread') or {}
            source = thread.get('source') or {}
            subagent = source.get('subAgent') if isinstance(source, dict) else None
            spawn = subagent.get('thread_spawn') if isinstance(subagent, dict) else None
            parent = thread.get('parentThreadId') or (spawn.get('parent_thread_id') if isinstance(spawn, dict) else None)
            if parent and (parent == self.thread_id or parent in self.child_threads):
                identifier = thread.get('id')
                if identifier:
                    self.register_child(identifier, label=str(thread.get('agentNickname') or ''), model=str(thread.get('model') or ''))
            return
        activity = params.get('item') or {}
        is_subagent_activity = method in ('item/started', 'item/completed') and activity.get('type') == 'subAgentActivity'
        thread_id = params.get('threadId', self.thread_id)
        child = thread_id in self.child_threads
        if is_subagent_activity:
            target = str(activity.get('agentThreadId') or '')
            action = activity.get('kind')
            if not target:
                return
            self.register_child(target, label=str(activity.get('agentPath') or ''), model=str(activity.get('model') or ''))
            label = str(activity.get('agentPath') or 'Codex · ' + target[-8:])
            call_id = str(activity.get('id') or target)
            if action == 'started':
                event = {'callId': call_id, 'name': 'spawn_agent', 'status': 'running',
                         'text': json.dumps(self.session.redact_event(activity), ensure_ascii=False)}
                if child:
                    event['kind'] = 'tool'
                    self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event=event)
                else:
                    self.session.emit('tool', **event)
            elif action in ('completed', 'interrupted'):
                self.child_completed.add(target)
                result = {'callId': call_id, 'name': 'spawn_agent',
                          'status': 'completed' if action == 'completed' else 'failed',
                          'text': json.dumps(self.session.redact_event(activity), ensure_ascii=False)}
                if child:
                    result['kind'] = 'tool_result'
                    self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event=result)
                else:
                    self.session.emit('tool_result', **result)
                self.session.emit('subagent_event', subagentId=target, label=label,
                                  event={'kind': 'result', 'status': 'completed' if action == 'completed' else 'interrupted'})
            return
        if self.thread_id and thread_id != self.thread_id and not child:
            if thread_id and method.startswith(('item/', 'turn/')):
                if thread_id in self.pending_child_events or len(self.pending_child_events) < 128:
                    pending = self.pending_child_events.setdefault(thread_id, [])
                    if len(pending) < 256:
                        pending.append((method, params))
            return
        incoming_turn = params.get('turnId') or (params.get('turn') or {}).get('id')
        expected = self.child_turns.get(thread_id) if child else self.turn_id
        if expected and incoming_turn and incoming_turn != expected and method != 'turn/started':
            return
        if child and method in ('thread/closed', 'thread/archived'):
            self.child_completed.add(thread_id)
            self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event={'kind': 'result', 'status': 'interrupted'})
            return
        if child and method == 'turn/started':
            self.child_turns[thread_id] = incoming_turn
            self.child_completed.discard(thread_id)
            self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event={'kind': 'started'})
            return
        if child and method in ('turn/completed', 'item/agentMessage/delta', 'turn/plan/updated', 'thread/tokenUsage/updated'):
            if method == 'turn/completed':
                self.child_completed.add(thread_id)
                self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event={'kind': 'result', 'status': str((params.get('turn') or {}).get('status', ''))})
            elif method == 'item/agentMessage/delta':
                self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event={'kind': 'message_delta', 'messageId': str(params.get('itemId', '')), 'text': str(params.get('delta', ''))})
            return
        if child and method in ('item/started', 'item/completed', 'item/commandExecution/outputDelta'):
            item = params.get('item') or {}
            kind = item.get('type')
            child_event = None
            if kind == 'agentMessage':
                if method == 'item/completed':
                    if item.get('questions'):
                        self.queue_questions(item, thread_id)
                    child_event = {'kind': 'message_end', 'messageId': str(item.get('id', '')), 'text': str(item.get('text', ''))}
                elif item.get('id'):
                    child_event = {'kind': 'message_start', 'messageId': str(item['id'])}
            elif kind == 'userMessage' and method == 'item/completed':
                text = '\n'.join(str(part.get('text', '')) for part in item.get('content', []) if isinstance(part, dict))
                child_event = {'kind': 'message', 'messageId': str(item.get('id', '')), 'role': 'user', 'text': text}
            elif method == 'item/commandExecution/outputDelta' and params.get('itemId'):
                child_event = {'kind': 'tool_delta', 'callId': str(params.get('itemId')), 'name': 'commandExecution', 'text': str(params.get('delta', ''))}
            elif kind in NATIVE_ITEM_TYPES:
                if kind == 'collabAgentToolCall':
                    for receiver in item.get('receiverThreadIds', []):
                        self.register_child(receiver, prompt=str(item.get('prompt', '')))

                child_event = {'kind': 'tool_result' if method == 'item/completed' else 'tool', 'callId': str(item.get('id', '')), 'name': native_item_name(item), 'status': 'running' if method == 'item/started' else 'failed' if item.get('status') in ('failed', 'declined') else 'completed', 'text': json.dumps(self.session.redact_event(item), ensure_ascii=False)[:16000]}
            if child_event:
                self.session.emit('subagent_event', subagentId=str(thread_id), label='Codex · ' + str(thread_id)[-8:], event=child_event)
            return
        if method == 'item/started':
            item = params.get('item') or {}
            identifier = str(item.get('id', ''))
            kind = item.get('type')
            if identifier and kind in NATIVE_ITEM_TYPES and identifier not in self.native_started:
                self.native_started.add(identifier)
                self.native_items[identifier] = item
                self.session.consume_tool_budget()
                self.session.steps += 1
                self.session.emit('tool', callId=identifier, name=native_item_name(item), text=json.dumps(self.session.redact_event(item), ensure_ascii=False)[:12000])
        elif method == 'item/commandExecution/outputDelta':
            identifier = str(params.get('itemId', ''))
            self.native_outputs[identifier] = (self.native_outputs.get(identifier, '') + str(params.get('delta', '')))[-24000:]
        elif method == 'turn/started':
            self.turn_id = (params.get('turn') or {}).get('id', self.turn_id)
            self.native_completed = False
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
            kind = item.get('type')
            if kind in NATIVE_ITEM_TYPES:
                identifier = str(item.get('id', ''))
                name = native_item_name(item)
                if identifier and identifier not in self.native_started:
                    self.native_started.add(identifier)
                    self.session.consume_tool_budget()
                    self.session.steps += 1
                    self.session.emit('tool', callId=identifier, name=name, text=json.dumps({'native': True, 'name': name}, ensure_ascii=False))
                if kind == 'collabAgentToolCall':
                    for receiver in item.get('receiverThreadIds', []):
                        if receiver:
                            identifier_child = str(receiver)
                            self.register_child(identifier_child, prompt=str(item.get('prompt', '')))
                if kind in ('fileChange', 'commandExecution'):
                    self.session.checkpoint_proposal()
                result = {'ok': item.get('status') not in ('failed', 'declined', 'interrupted'), 'native': True,
                          'item': item, 'output': self.native_outputs.pop(identifier, '')}
                self.session.emit('tool_result', callId=identifier, name=name, text=json.dumps(self.session.redact_event(result), ensure_ascii=False)[:24000])
                self.native_items.pop(identifier, None)
            elif kind == 'contextCompaction' and not child:
                identifier = str(item.get('id', ''))
                if identifier and identifier not in self.compaction_items:
                    self.compaction_items.add(identifier)
                    self.compactions += 1
                    self.context_source = 'unknown'
                    self.emit_context()
            elif kind == 'agentMessage':
                if item.get('questions'):
                    self.queue_questions(item, thread_id)
                if child:
                    if item.get('delivery') == 'async' and item.get('text'):
                        self.session.emit('subagent_status', text='Codex 子代理消息 · ' + str(item['text'])[:8000])
                    return
                text = str(item.get('text', self.messages.get(str(item.get('id')), '')))
                self.session.emit('message_end', messageId=str(item['id']), text=text)
                if text and not item.get('questions'):
                    self.summary = text
        elif method == 'turn/plan/updated':
            statuses = {'pending': 'pending', 'inProgress': 'in_progress', 'in_progress': 'in_progress', 'completed': 'completed'}
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
    # Ultra is a native coordination mode, not merely the provider's max effort.
    if configuration.get('thinkingLevel') == 'ultra' and configuration.get('thinkingMode', 'legacy') in ('legacy', 'strength'):
        return {'model_reasoning_effort': 'ultra'}
    return {'model_reasoning_effort': thinking['effort']} if 'effort' in thinking else {}


def ultra_collaboration_settings(configuration):
    if configuration.get('collaborationMode', 'extreme') != 'extreme' or configuration.get('thinkingLevel') != 'ultra' or configuration.get('thinkingMode', 'legacy') not in ('legacy', 'strength'):
        return {}
    # Native TOML exposes positive integer capacities, not a nullable/unlimited value.
    # Use its largest signed wire integer so no practical application quota is imposed.
    # The native limiter increments counters on demand; this never preallocates threads.
    return {'agents.max_concurrent_threads_per_session': (1 << 63) - 1,
            'features.multi_agent_v2.max_concurrent_threads_per_session': (1 << 63) - 1,
            'agents.default_subagent_reasoning_effort': 'max'}


def run_codex(session, configuration, *, prepare_only=False):
    from agent_runner import SYSTEM, MODE_PROMPTS, UserInterruption, TimeExceeded
    from agent_codex_runtime import native_server
    binary = codex_binary(session.base, session.environment)
    if binary is None:
        raise ValueError('Android codex-app-server is not installed')
    endpoint = str(configuration.get('endpoint', '')).rstrip('/')
    from urllib.parse import urlsplit
    parsed = urlsplit(endpoint)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('Invalid Codex Responses API endpoint')
    home = session.base / 'agent-native/codex' / session.session_id
    home.mkdir(parents=True, exist_ok=True)
    write_native_mcp_config(session, home, configuration)
    # The upstream catalog setting also supports custom provider model slugs.
    template = Path(__file__).parent / 'agent_vendor/codex-model-template.json'
    metadata = json.loads(template.read_text(encoding='utf-8'))
    metadata.update(slug=str(configuration['model']), display_name=str(configuration['model']),
                    context_window=context_settings(configuration)['model_context_window'],
                    max_context_window=context_settings(configuration)['model_context_window'])
    if configuration.get('thinkingLevel') == 'ultra':
        metadata['supported_reasoning_levels'] = list(metadata.get('supported_reasoning_levels') or [])
        if not any(level.get('effort') == 'max' for level in metadata['supported_reasoning_levels']):
            metadata['supported_reasoning_levels'].append({'effort': 'max', 'description': 'Ultra'})
    catalog = home / 'models.json'
    catalog.write_text(json.dumps({'models': [metadata]}, ensure_ascii=False), encoding='utf-8')
    catalog.chmod(0o600)
    environment = dict(session.environment, CODEX_HOME=str(home), ASTER_MODEL_API_KEY=str(configuration.get('apiKey', '')))
    prefix = environment.get('PREFIX')
    shell = session.command_shell()
    environment['SHELL'] = shell
    if prefix:
        libraries = str(Path(prefix) / 'lib')
        search = [p for p in environment.get('LD_LIBRARY_PATH', '').split(os.pathsep) if p]
        environment['LD_LIBRARY_PATH'] = os.pathsep.join([libraries] + [p for p in search if p != libraries])
        certificates = Path(prefix) / 'etc/tls/cert.pem'
        if certificates.is_file():
            # Covers both the native-tls and rustls clients in upstream Codex.
            environment.setdefault('SSL_CERT_FILE', str(certificates))
    environment.pop('NODE_OPTIONS', None)
    shell_environment = {key: environment[key] for key in (
        'SHELL', 'PREFIX', 'HOME', 'PATH', 'TMPDIR', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'JAVA_HOME'
    ) if environment.get(key)}
    settings = {
        'model': str(configuration['model']), 'model_provider': 'aster',
        'model_catalog_json': str(catalog),
        'model_providers.aster.name': 'Aster', 'model_providers.aster.base_url': endpoint,
        'model_providers.aster.env_key': 'ASTER_MODEL_API_KEY', 'model_providers.aster.wire_api': 'responses',
        'model_providers.aster.supports_websockets': False,
        'model_providers.aster.request_max_retries': 2,
        'model_providers.aster.stream_max_retries': 2,
        **context_settings(configuration), **thinking_settings(configuration), **ultra_collaboration_settings(configuration),
        'web_search': str(configuration.get('webSearch') or 'cached'),
        'features.multi_agent': True, 'features.multi_agent_v2': True,
        'features.multi_agent_v2_dynamic_tools': True,
        'features.code_mode': True, 'features.code_mode_only': False,
        'features.code_mode_tool_search': True, 'features.code_mode_host': True,
        'features.code_mode_interrupt': True, 'features.code_mode_prewarm': False,
        'features.unified_exec': True, 'features.unified_exec_tty': True,
        'features.stable_environment_tools': True, 'features.shell_tool': True,
        'features.view_image': True, 'features.exec_permission_approvals': True,
        'features.write_stdin_approval': True, 'features.request_permissions_tool': True,
        'features.token_budget': True, 'features.context_management': True,
        'features.send_message_to_user_async': True,
        'features.mcp_2026_07_28': True, 'features.deferred_tool_world_state': True,
        'tools.update_plan.enabled': True,
        'tools.experimental_request_user_input.enabled': True,
        'features.hooks': False,
        'shell_environment_policy.inherit': 'all', 'shell_environment_policy.set': shell_environment,
    }
    from agent_task_mode import collaboration_prompt
    from types import SimpleNamespace
    root_contract = collaboration_prompt(session)
    child_contract = collaboration_prompt(SimpleNamespace(request={**session.request, 'isSubagent': True}))
    if configuration.get('collaborationMode', 'assist') in ('team', 'extreme'):
        # Override native explicit-request-only hints at their real role-specific source.
        settings['features.multi_agent_v2.root_agent_usage_hint_text'] = root_contract
        settings['features.multi_agent_v2.subagent_usage_hint_text'] = child_contract
        settings['features.multi_agent_v2.subagent_developer_instructions'] = child_contract + '\nYou are an execution child, not the root. Work in the inherited cwd using native tools, preserve user permissions, and return actual evidence.'
        settings['features.multi_agent_v2.multi_agent_mode_hint_text'] = (
            'Delegation is mandatory for this task. The root orchestrates and reports only; '
            'all repository inspection, shell execution, edits and tests belong to child agents. '
            'Use spawn_agent and wait_agent; a user does not need to explicitly ask again.')
    command = [str(binary), '--listen', 'stdio://']
    for name, value in settings.items():
        if isinstance(value, dict):
            encoded = '{ ' + ', '.join(json.dumps(k) + ' = ' + json.dumps(v) for k, v in value.items()) + ' }'
        else:
            encoded = json.dumps(value)
        command += ['-c', name + '=' + encoded]
    with native_server(session, home, command, environment, CodexTransport) as wire:
        wire.preparing = prepare_only
        pointer = session.session_file.with_suffix('.codex-native.json')
        try:
            # app-server is an in-process child of the pinned native CLI. Identify
            # the real host rather than claiming to be a desktop Codex client.
            if not wire.initialized:
                wire.request('initialize', {'clientInfo': {'name': 'aster', 'title': 'Aster Codex', 'version': '1.0.5'}, 'capabilities': {'experimentalApi': True}})
                wire.send({'method': 'initialized', 'params': {}})
                wire.initialized = True
            developer = str(session.request.get("systemPrompt") or SYSTEM) + chr(10) + MODE_PROMPTS[session.mode] + chr(10) + session.project_instructions()
            developer += chr(10) + 'Use the native Codex tool registry for every operation. Prefer unified_exec/exec_command and write_stdin for Android-compatible shell work, apply_patch for file changes, update_plan for plans, view_image for images, request_user_input and request_permissions for interaction, tool_search for deferred tools, get_context_remaining and new_context for context management, native MCP resource tools, and native multi-agent tools. Do not call an Aster dynamic namespace. All edits are in the staged cwd and remain proposals until the user applies the preview. The shell is Termux bash on Android; use the supplied local environment, cwd and runtime workspace roots.'
            developer += chr(10) + 'User-enabled MCP servers are loaded by the native Codex MCP registry when configured in the project; use native mcp_resource tools and native approvals.'
            if session.request.get('isSubagent'):
                developer += chr(10) + 'You are a native Codex collaboration child with an independent context. Complete the assigned prompt using the shared project and inherited permissions.'
            from agent_task_mode import collaboration_prompt
            collaboration = collaboration_prompt(session)
            developer += chr(10) + 'Working directory (absolute path): ' + str(session.stage)
            developer += chr(10) + 'The pinned Codex release removed the legacy js_repl name; use its native code mode exec/wait surface when JavaScript evaluation is needed. list_dir is provided through native unified_exec directory commands, and shell is exposed as native exec_command/unified_exec.'
            developer += chr(10) + 'Keep working until the task is complete or a concrete blocker is returned. Do not end the turn merely because one command or patch completed.'
            from agent_permissions import permission_prompt
            developer = permission_prompt(session, developer)
            developer += chr(10) + session.task_mode_prompt()
            if collaboration:
                developer += chr(10) + collaboration
            if ultra_collaboration_settings(configuration):
                developer += chr(10) + 'Ultra mode is enabled: preserve native Ultra coordination (the provider resolves its inference effort to Max). There is no application quota on subagent count or concurrency; derive the number of children from useful independent tasks and available resources.'
            options = {'model': configuration['model'], 'modelProvider': 'aster', 'cwd': str(session.stage),
                       'approvalPolicy': 'never' if session.permission_mode == 'native' and session.native_commands_unchecked else 'untrusted', 'sandbox': 'danger-full-access' if session.permission_mode == 'native' else ('workspace-write' if session.mode == 'execute' else 'read-only'),
                       'developerInstructions': developer,
                       'environments': [{'environmentId': 'local', 'cwd': str(session.stage), 'runtimeWorkspaceRoots': [str(session.stage)]}]}
            if pointer.exists():
                saved = json.loads(pointer.read_text())
                wire.thread_id = saved['threadId']
                wire.compactions = saved.get('compactions', 0)
                if getattr(wire, 'thread_options', None) != options:
                    wire.request('thread/resume', {**options, 'threadId': wire.thread_id})
            else:
                result = wire.request('thread/start', {**options})
                wire.thread_id = result['thread']['id']
            wire.thread_options = options
            def save_pointer():
                temp = pointer.with_suffix('.tmp')
                temp.write_text(json.dumps({'threadId': wire.thread_id, 'compactions': wire.compactions}))
                temp.replace(pointer)
            save_pointer()
            if prepare_only:
                return {'engine': session.engine_info['name'], 'status': 'Ready', 'modelRequested': False}
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
                response = wire.request('turn/start', {'threadId': wire.thread_id, 'input': [{'type': 'text', 'text': task}], 'environments': options['environments'],
                    **({'effort': effort} if effort is not None else {})})
                wire.turn_id = response['turn']['id']
                while not wire.native_completed or wire.has_interactions() or wire.has_active_children():
                    wire.poll()
                    wire.drain_interactions()
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
