"""Aster lifecycle and permissions for tools executed by the upstream CLI engines.

The engine owns schemas, file readers/editors, shell implementations and tool
results. This module checks the staged workspace, routes approval to Android,
and checkpoints changes; it does not replace upstream tool implementations.
"""
from pathlib import Path
import json
import uuid

READ = {'list_directory', 'read_file', 'glob', 'grep_search', 'read_many_files',
        'get_context_remaining', 'view_image', 'get_internal_docs', 'list_background_processes',
        'read_background_output', 'list_mcp_resources', 'read_mcp_resource',
        'tracker_get_task', 'tracker_list_tasks', 'tracker_visualize'}
WRITE = {'write_file', 'replace'}
NETWORK = {'web_fetch', 'google_web_search'}
LOCAL = {'write_todos', 'activate_skill', 'update_topic', 'tracker_create_task',
         'tracker_update_task', 'tracker_add_dependency'}
CONTROL = {'enter_plan_mode', 'exit_plan_mode', 'invoke_agent'}
COMMAND = {'run_shell_command'}
GEMINI_TOOLS = READ | WRITE | NETWORK | LOCAL | COMMAND | CONTROL | {'ask_user'}
PATH_FIELDS = {'file_path', 'dir_path', 'directory', 'path', 'absolute_path', 'workdir', 'cwd'}


def staged_path(session, value, *, folder=False):
    if not isinstance(value, str) or '\x00' in value:
        raise ValueError('Native tool path must be a string')
    root = session.stage.resolve()
    raw = Path(value)
    target = raw if raw.is_absolute() else root / raw
    if session.permission_mode == "native":
        return target.resolve()
    # Check lexical traversal and symlink components before resolving.
    if '..' in raw.parts:
        raise ValueError('Native tool path escapes the staged project')
    try:
        relative = target.relative_to(root)
    except ValueError:
        raise ValueError('Native tool path must stay in the staged project') from None
    cursor = root
    for part in relative.parts:
        if part in session.excluded or part.startswith('.aster-'):
            raise ValueError('Native tool path is excluded from this project')
        cursor = cursor / part
        if cursor.is_symlink():
            raise ValueError('Native tools cannot follow project symlinks')
    resolved = target.resolve()
    if resolved != root and root not in resolved.parents:
        raise ValueError('Native tool path escapes the staged project')
    if resolved == root and not folder:
        raise ValueError('Native file tool requires a file path')
    return resolved


def native_available(session, name, server=None):
    if session.permission_mode == "native" and not server:
        return True
    if server:
        enabled = str((session.request.get('configuration') or {}).get('mcpServers', '')).split(',')
        return (session.mode == 'execute' or session.permission_mode == 'native') and server in {item.strip() for item in enabled}
    if name not in GEMINI_TOOLS:
        return False
    if name in WRITE | COMMAND | CONTROL:
        return session.mode == 'execute'
    if name in {'write_todos'}:
        return session.mode in {'plan', 'execute'}
    if name == 'activate_skill':
        return bool((session.request.get('configuration') or {}).get('enabledSkills'))
    if name == 'google_web_search':
        return (session.request.get('configuration') or {}).get('protocol', 'gemini') == 'gemini'
    return True


def before_native_tool(session, payload):
    name = payload.get('name')
    args = payload.get('arguments') or {}
    server = payload.get('server')
    if not isinstance(args, dict) or not native_available(session, name, server):
        raise ValueError('Native tool is not available in this mode: ' + str(name))
    session.check_cancelled()
    for key, value in args.items():
        if key in PATH_FIELDS and value:
            staged_path(session, value, folder=key in {'dir_path', 'directory', 'workdir', 'cwd'} or name in {'glob', 'grep_search', 'list_directory'})
        if session.permission_mode != 'native' and key in {'include', 'exclude'} and isinstance(value, list):
            for pattern in value:
                if not isinstance(pattern, str) or Path(pattern).is_absolute() or '..' in Path(pattern).parts:
                    raise ValueError('Native file patterns must stay in the staged project')
    if name in WRITE and session.permission_mode != "native":
        value = args.get('file_path') or args.get('path')
        path = staged_path(session, value)
        from aster_tools import ProjectEnvironment
        env = ProjectEnvironment(session)
        if name == 'write_file':
            content = args.get('content')
            env.validate_write(path, content)
            session.changes({str(path.relative_to(session.stage)): content.encode()})
        elif path.is_file():
            env.validate_write(path, env.source(path))
    if name == 'activate_skill':
        selected = (session.request.get('configuration') or {}).get('enabledSkills') or []
        if args.get('name') not in selected:
            raise ValueError('This project skill has not been enabled in Aster')
    session.consume_tool_budget()
    session.steps += 1
    session.emit('tool', name=name, text=json.dumps(session.redact_event(args), ensure_ascii=False)[:12000])
    if name in COMMAND:
        command = args.get('command')
        if not isinstance(command, str) or not command.strip():
            raise ValueError('Native shell command is empty')
        if not session.approve_command(command, 0 if args.get('is_background') else 120):
            session.emit('tool_result', name=name, text=json.dumps({'ok': False, 'error': 'Command was not approved'}))
            return {'ok': False, 'error': 'Command was not approved'}
    if server or name == 'invoke_agent':
        description = ('MCP ' + server + ' · ' + str(name)) if server else ('Gemini ' + name + ' · ' + json.dumps(session.redact_event(args), ensure_ascii=False)[:1000])
        if not session.approve_external(description):
            session.emit('tool_result', name=name, text=json.dumps({'ok': False, 'error': 'Operation was not approved'}))
            return {'ok': False, 'error': 'Operation was not approved'}
    call_id = uuid.uuid4().hex
    calls = getattr(session, '_native_calls', None)
    if calls is None:
        session._native_calls = calls = {}
    calls[call_id] = (name, args)
    return {'ok': True, 'callId': call_id, 'approvalGranted': bool(server or name in COMMAND or name == 'invoke_agent')}


def after_native_tool(session, payload):
    calls = getattr(session, '_native_calls', {})
    value = calls.pop(payload.get('callId'), None)
    if value is None:
        raise ValueError('Native tool completion does not match an active call')
    name, args = value
    failed = bool(payload.get('failed'))
    if name in WRITE | COMMAND:
        session.checkpoint_proposal()
    if name == 'write_todos' and not failed:
        statuses = {'pending': 'pending', 'in_progress': 'in_progress', 'completed': 'completed',
                    'cancelled': 'completed', 'blocked': 'pending'}
        session.tasks = [{'task': str(item.get('description', ''))[:200],
                          'status': statuses.get(item.get('status'), 'pending')}
                         for item in (args.get('todos') or [])[:20] if isinstance(item, dict)]
        session.emit('plan', tasks=session.tasks)
    if name.startswith('tracker_') and not failed and isinstance(payload.get('tasks'), list):
        statuses = {'open': 'pending', 'pending': 'pending', 'in_progress': 'in_progress', 'done': 'completed', 'completed': 'completed', 'closed': 'completed'}
        session.tasks = [{'task': str(item.get('title', ''))[:200], 'status': statuses.get(item.get('status'), 'pending')} for item in payload['tasks'][:20] if isinstance(item, dict)]
        session.emit('plan', tasks=session.tasks)
    from aster_diagnostics import clean_runtime_output
    summary = '用户交互已返回；回答不写入活动日志。' if name == 'ask_user' else clean_runtime_output(payload.get('summary', ''))
    session.emit('tool_result', name=name, text=json.dumps({'ok': not failed, 'native': True, 'output': str(summary)[:12000]}, ensure_ascii=False))
    return {'ok': True}


def native_rpc(session, payload):
    if payload.get('phase') == 'before':
        return before_native_tool(session, payload)
    if payload.get('phase') == 'after':
        return after_native_tool(session, payload)
    if payload.get('phase') == 'confirm':
        name = str(payload.get('name', ''))
        if not native_available(session, name, payload.get('server')):
            raise ValueError('This tool cannot request confirmation in the current mode')
        description = 'Gemini ' + name + '\n' + str(payload.get('description', ''))[:1000]
        for key in ('systemMessage', 'command', 'fileDiff', 'additionalPermissions', 'prompt', 'urls'):
            if payload.get(key):
                description += '\n' + key + ': ' + json.dumps(session.redact_event(payload[key]), ensure_ascii=False)[:14000]
        accepted = session.approve_command(str(payload.get('command') or description), 0) if name in COMMAND or payload.get('command') else session.approve_external(description)
        return {'ok': accepted}
    if payload.get('phase') == 'ask_user':
        from agent_interactions import request_questions
        return request_questions(session, payload.get('questions'))
    if payload.get('phase') == 'plan_review':
        if not native_available(session, 'exit_plan_mode'):
            raise ValueError('当前模式不能确认执行计划')
        plan = staged_path(session, payload.get('planPath'))
        if not plan.is_file() or plan.stat().st_size > 1024 * 1024:
            raise ValueError('待确认计划文件不存在或过大')
        with plan.open(encoding='utf-8') as source:
            text = source.read(18001)
        if len(text) > 18000:
            text = text[:18000] + '\n（计划较长；请在项目中查看完整文件：' + str(plan.relative_to(session.stage)) + '）'
        from agent_interactions import request_questions
        result = request_questions(session, [
            {'id': 'decision', 'header': '计划确认', 'question': text + '\n是否批准这个计划？此操作不代表授予命令或额外权限。', 'options': ['批准计划', '退回修改'], 'allowCustom': False},
            {'id': 'feedback', 'header': '修改意见', 'question': '需要补充或调整的内容', 'options': [], 'required': False}
        ])
        return {'ok': True, 'dismissed': result['dismissed'],
                'approved': not result['dismissed'] and result['answers'].get('decision') == ['批准计划'],
                'feedback': '\n'.join(result['answers'].get('feedback') or [])}
    if payload.get('phase') == 'mcp_elicitation':
        server = str(payload.get('serverName') or '')
        if not native_available(session, '', server):
            return {'action': 'decline', 'content': None}
        from agent_interactions import mcp_elicitation
        return mcp_elicitation(session, payload)
    raise ValueError('Unknown native tool lifecycle phase')


RUNTIME_ENV_KEYS = ('SHELL', 'PREFIX', 'HOME', 'PATH', 'TMPDIR', 'LD_LIBRARY_PATH',
                    'SSL_CERT_FILE', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'JAVA_HOME')


def sync_mcp_configuration(session, configuration):
    """Merge enabled UI definitions into the project without dropping other entries."""
    import os
    import re
    import tempfile
    raw = configuration.get('mcpDefinitions')
    if raw is None:
        return
    if not isinstance(raw, dict):
        raise ValueError('MCP 配置必须是 JSON 对象')
    raw = raw.get('mcpServers', raw)
    if not isinstance(raw, dict):
        raise ValueError('MCP 配置缺少 mcpServers 对象')
    selected = {name.strip() for name in str(configuration.get('mcpServers', '') or '').split(',') if name.strip()}
    if len(selected) > 16 or any(not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name) for name in selected):
        raise ValueError('MCP 服务器名称无效或超过 16 个')
    updates = {name: raw[name] for name in selected if name in raw}
    if not updates:
        return
    if any(not isinstance(value, dict) for value in updates.values()):
        raise ValueError('MCP 服务器定义必须是 JSON 对象')
    workspace = session.workspace.resolve()
    source = workspace / '.aster/mcp.json'
    if source.is_symlink() or workspace not in source.resolve().parents:
        raise ValueError('MCP 配置必须位于当前项目内，不能使用外部符号链接')
    document = {}
    if source.exists():
        if not source.is_file() or source.stat().st_size > 32000:
            raise ValueError('现有 .aster/mcp.json 不是有效文件或超过 32 KB')
        try:
            document = json.loads(source.read_text(encoding='utf-8'))
        except (ValueError, UnicodeError) as error:
            raise ValueError('现有 .aster/mcp.json 无法解析；请修复后重试') from error
        if not isinstance(document, dict) or not isinstance(document.get('mcpServers', {}), dict):
            raise ValueError('现有 .aster/mcp.json 必须包含 mcpServers 对象')
    entries = dict(document.get('mcpServers', {}))
    if all(entries.get(name) == value for name, value in updates.items()):
        return
    entries.update(updates)
    document['mcpServers'] = entries
    payload = json.dumps(document, ensure_ascii=False, indent=2) + '\n'
    if len(payload.encode('utf-8')) > 32000:
        raise ValueError('合并后的 .aster/mcp.json 超过 32 KB')
    source.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=source.parent, prefix='.mcp-', suffix='.tmp', delete=False) as target:
            temporary = target.name
            target.write(payload)
        if source.exists():
            os.chmod(temporary, source.stat().st_mode & 0o777)
        os.replace(temporary, source)
        temporary = None
    finally:
        if temporary is not None:
            os.unlink(temporary)


def load_native_mcp_servers(session, configuration):
    """Translate selected project MCP entries for the upstream registries."""
    import re
    from urllib.parse import urlsplit
    enabled = {item.strip() for item in str(configuration.get('mcpServers', '') or '').split(',') if item.strip()}
    if not enabled:
        return {}
    if len(enabled) > 16:
        raise ValueError('最多启用 16 个 MCP 服务器')
    source = session.workspace / '.aster/mcp.json'
    if not source.is_file() or source.is_symlink() or session.workspace not in source.resolve().parents or source.stat().st_size > 32000:
        raise ValueError('已启用 MCP，但项目缺少有效的 .aster/mcp.json')
    document = json.loads(source.read_text(encoding='utf-8'))
    entries = document.get('mcpServers') if isinstance(document, dict) else None
    if not isinstance(entries, dict):
        raise ValueError('.aster/mcp.json 缺少 mcpServers 对象')
    result, secrets = {}, []
    def string_map(value, label):
        if not isinstance(value, dict) or any(not isinstance(k, str) or not isinstance(v, str) or '\x00' in k or '\x00' in v for k, v in value.items()):
            raise ValueError('MCP ' + label + ' 必须使用字符串键和值')
        return dict(value)
    for name in sorted(enabled):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name):
            raise ValueError('MCP 服务器名称无效：' + name)
        config = entries.get(name)
        if not isinstance(config, dict):
            raise ValueError('MCP 服务器配置无效：' + name)
        transport = config.get('type', 'stdio' if 'command' in config else 'http')
        if transport == 'stdio':
            command = config.get('command')
            args = config.get('args', [])
            if not isinstance(command, str) or not command.strip() or '\x00' in command:
                raise ValueError('MCP stdio command 无效：' + name)
            if not isinstance(args, list) or any(not isinstance(item, str) or '\x00' in item for item in args):
                raise ValueError('MCP args 无效：' + name)
            env = {key: session.environment[key] for key in RUNTIME_ENV_KEYS if session.environment.get(key)}
            env['SHELL'] = session.command_shell()
            extra = string_map(config.get('env', {}), 'env')
            env.update(extra)
            secrets.extend(value for key, value in extra.items() if any(word in key.upper() for word in ('KEY', 'TOKEN', 'SECRET', 'PASSWORD')))
            result[name] = {'type': 'stdio', 'command': command, 'args': args, 'env': env,
                            'cwd': str(staged_path(session, config.get('cwd') or str(session.stage), folder=True))}
        elif transport == 'http':
            url = config.get('url')
            if not isinstance(url, str):
                raise ValueError('MCP HTTP URL 无效：' + name)
            parsed = urlsplit(url)
            if parsed.scheme not in ('https', 'http') or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
                raise ValueError('MCP HTTP URL 无效：' + name)
            headers = string_map(config.get('headers', {}), 'headers')
            for header, variable in string_map(config.get('headerEnv', {}), 'headerEnv').items():
                value = session.environment.get(variable)
                if not value:
                    raise ValueError('缺少 MCP 请求头环境变量：' + variable)
                headers[header] = value
            if len(headers) > 16 or any(not re.fullmatch(r'[A-Za-z0-9-]{1,80}', k) or len(v) > 8192 or '\r' in v or '\n' in v for k, v in headers.items()):
                raise ValueError('MCP 请求头无效')
            if headers and parsed.scheme != 'https' and parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
                raise ValueError('含凭据的 MCP 连接需要 HTTPS')
            result[name] = {'type': 'http', 'url': url, 'headers': headers}
            secrets.extend(headers.values())
        else:
            raise ValueError('不支持的 MCP 传输类型：' + str(transport))
    session.mcp_secret_values = list(set(getattr(session, 'mcp_secret_values', []) + [v for v in secrets if len(v) >= 8]))
    return result
