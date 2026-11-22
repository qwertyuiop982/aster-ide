"""Expose Aster's Android tools to Codex through its native stdio MCP registry."""
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import sys


SERVER_NAME = 'aster_android'
TOOLS = ('web_webview_search', 'browser', 'desktop')
PROTOCOLS = {'2025-03-26', '2025-06-18', '2025-11-25'}
CALL_TIMEOUT = 175
MAX_REQUEST = 64 * 1024
MAX_RESPONSE = 2 * 1024 * 1024 + 1024
_KEY = secrets.token_bytes(32)


def _definitions(automation):
    vendor = str(Path(__file__).parent / 'agent_vendor')
    if vendor not in sys.path:
        sys.path.insert(0, vendor)
    from aster_tools import TOOLS as schemas
    return {fn['name']: {'name': fn['name'], 'description': fn['description'], 'inputSchema': fn['parameters']}
            for item in schemas for fn in [item['function']]
            if fn['name'] in (TOOLS if automation else TOOLS[:1])}


def _session_key(session):
    scope = f'{session.session_directory}/{session.session_id}'.encode()
    return hmac.new(_KEY, scope, 'sha256').hexdigest()


def server_config(session):
    port = getattr(session, 'android_mcp_port', None)
    if not port:
        return None
    # The capability stays stable for a warm Codex conversation and expires with
    # this bridge process. It grants only the Android tools of that active turn.
    environment = {name: session.environment[name] for name in
                   ('PATH', 'HOME', 'PREFIX', 'TMPDIR', 'LD_LIBRARY_PATH', 'LD_PRELOAD', 'TERMUX__PREFIX')
                   if name in session.environment}
    environment.update(ASTER_ANDROID_MCP_PORT=str(port),
                       ASTER_ANDROID_MCP_CAPABILITY=_session_key(session),
                       ASTER_ANDROID_MCP_AUTOMATION=str(int(session.mode == 'execute')))
    return {'type': 'stdio', 'command': sys.executable, 'args': ['-I', '-B', str(Path(__file__).resolve())],
            'env': environment, 'cwd': str(session.stage), 'tool_timeout_sec': CALL_TIMEOUT + 5}


def call_active_session(request):
    from agent_runner import SESSIONS, SESSIONS_LOCK, TimeExceeded, UserInterruption

    capability = request.get('capability')
    rejected = {'ok': False, 'error': 'No active Codex turn for this Android tool'}
    if not isinstance(capability, str) or len(capability) != 64 or not capability.isascii():
        return rejected
    with SESSIONS_LOCK:
        session = next((item for item in SESSIONS.values()
                        if getattr(item, 'android_mcp_active', False)
                        and hmac.compare_digest(capability, _session_key(item))), None)
    if session is None:
        return rejected
    name, arguments = request.get('name'), request.get('arguments')
    allowed = TOOLS if session.mode == 'execute' else TOOLS[:1]
    if name not in allowed or not isinstance(arguments, dict):
        return {'ok': False, 'error': 'Invalid or unavailable Android tool'}
    arguments = dict(arguments)
    if name == TOOLS[0]:
        arguments = {key: value for key, value in arguments.items() if key in ('query', 'type', 'maxResults')}
    try:
        with session.client_tool_lock:
            if not session.android_mcp_active:
                return rejected
            session.check_cancelled()
            session.consume_tool_budget()
            result = session.client_tool(name, arguments)
        return session.redact_event(result)
    except (UserInterruption, TimeExceeded):
        return rejected
    except (ValueError, RuntimeError, OSError) as error:
        return {'ok': False, 'error': session.redact_event(str(error)[:1000])}


def _bridge_call(port, capability, name, arguments):
    request = {'op': 'android_mcp_call', 'capability': capability, 'name': name, 'arguments': arguments}
    wire = json.dumps(request, ensure_ascii=False).encode('utf-8') + b'\n'
    if len(wire) > MAX_REQUEST:
        raise ValueError('Android tool arguments are too large')
    with socket.create_connection(('127.0.0.1', port), timeout=CALL_TIMEOUT) as connection:
        connection.sendall(wire)
        with connection.makefile('rb') as response:
            data = response.readline(MAX_RESPONSE + 1)
    if not data.endswith(b'\n') or len(data) > MAX_RESPONSE:
        raise ValueError('Incomplete Android tool result')
    result = json.loads(data)
    if not isinstance(result, dict):
        raise ValueError('Invalid Android tool result')
    return result


def serve(source, output, definitions, call):
    while line := source.readline(MAX_REQUEST + 1):
        if len(line) > MAX_REQUEST or not line.endswith(b'\n'):
            return
        try:
            request = json.loads(line)
        except (UnicodeError, ValueError):
            continue
        if not isinstance(request, dict) or 'id' not in request:
            continue
        method = request.get('method')
        params = request.get('params', {})
        reply = {'jsonrpc': '2.0', 'id': request['id']}
        if request.get('jsonrpc') != '2.0' or not isinstance(params, dict):
            reply['error'] = {'code': -32600, 'message': 'Invalid MCP request'}
        elif method == 'initialize':
            version = params.get('protocolVersion')
            reply['result'] = {'protocolVersion': version if isinstance(version, str) and version in PROTOCOLS else '2025-11-25',
                               'capabilities': {'tools': {}},
                               'serverInfo': {'name': SERVER_NAME, 'version': '1'}}
        elif method == 'ping':
            reply['result'] = {}
        elif method == 'tools/list':
            reply['result'] = {'tools': list(definitions.values())}
        elif method == 'tools/call':
            name, arguments = params.get('name'), params.get('arguments', {})
            if not isinstance(name, str) or name not in definitions or not isinstance(arguments, dict):
                reply['error'] = {'code': -32602, 'message': 'Invalid or unavailable Android tool'}
            else:
                try:
                    result = call(name, arguments)
                except (OSError, ValueError):
                    result = {'ok': False, 'error': 'Android tool bridge did not respond'}
                reply['result'] = {'isError': result.get('ok') is False,
                                   'content': [{'type': 'text', 'text': json.dumps(result, ensure_ascii=False)}]}
        else:
            reply['error'] = {'code': -32601, 'message': 'Unsupported MCP method'}
        output.write(json.dumps(reply, ensure_ascii=False).encode('utf-8') + b'\n')
        output.flush()


if __name__ == '__main__':
    port = int(os.environ['ASTER_ANDROID_MCP_PORT'])
    capability = os.environ['ASTER_ANDROID_MCP_CAPABILITY']
    definitions = _definitions(os.environ.get('ASTER_ANDROID_MCP_AUTOMATION') == '1')
    serve(sys.stdin.buffer, sys.stdout.buffer, definitions,
          lambda name, args: _bridge_call(port, capability, name, args))
