#!/usr/bin/env python3
"""Authenticated remote MCP facade for Aster's shared conversation PTY engine."""
from __future__ import annotations
import asyncio
import hmac
import os
from pathlib import Path
import re
import shutil
import sys
import threading
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
LIB = HERE / 'lib'
if not LIB.is_dir():
    LIB = HERE.parents[1] / 'core/app/src/main/assets/bridge/agent_vendor'
sys.path.insert(0, str(LIB))
from aster_terminal import TERMINALS
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from starlette.responses import JSONResponse
import uvicorn

ROOT = Path(os.environ.get('ASTER_TERMINAL_ROOT', '/var/lib/aster-terminal-mcp')).resolve()
ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
TOKEN = os.environ.get('ASTER_TERMINAL_TOKEN', '')
if len(TOKEN) < 32:
    raise RuntimeError('ASTER_TERMINAL_TOKEN must contain at least 32 characters')
PORT = int(os.environ.get('ASTER_TERMINAL_PORT', '8893'))
SHELL = shutil.which('bash')
if not SHELL:
    raise RuntimeError('bash is required')
HOSTS = ['asteride.cn', '127.0.0.1:*', 'localhost:*', '[::1]:*']
ORIGINS = ['https://asteride.cn', 'http://127.0.0.1:*', 'http://localhost:*']
mcp = FastMCP('Aster Persistent Terminal', json_response=True, stateless_http=True,
    host='127.0.0.1', port=PORT, streamable_http_path='/terminal-mcp',
    instructions='Real interactive PTYs. Use a stable conversation_id for the same conversation. Open multiple terminals; keep returned terminal_id. exec submits once and returns recent console history, including prior commands. read waits without replaying or killing a command. write is the interactive fallback: literal text, Enter, or key plus modifiers [ctrl, alt, shift]. Sessions keep cwd, variables and pending input until closed or this service stops. After restart, history is readable but old inputs are never replayed. Commands execute on the remote MCP server, not on the phone.',
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
        allowed_hosts=HOSTS, allowed_origins=ORIGINS, max_request_body_size=65536))


def make_session(conversation_id):
    if not isinstance(conversation_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', conversation_id):
        raise ValueError('conversation_id must be 1–80 letters, digits, hyphens or underscores')
    workspace = ROOT / 'workspaces' / conversation_id
    workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
    if ROOT not in workspace.resolve().parents:
        raise ValueError('Workspace is outside the terminal service root')
    home = ROOT / 'home'
    home.mkdir(exist_ok=True, mode=0o700)
    environment = {key:value for key,value in os.environ.items() if not key.startswith('ASTER_TERMINAL_') and key not in ('NOTIFY_SOCKET','CREDENTIALS_DIRECTORY','INVOCATION_ID','JOURNAL_STREAM')}
    environment.update(HOME=str(home), LANG='C.UTF-8', TERM='xterm-256color')
    return SimpleNamespace(base=ROOT, workspace=workspace.resolve(), stage=workspace.resolve(),
        engine_id='pi', session_id=conversation_id, environment=environment, excluded=set(),
        cancelled=threading.Event(), active_terminal=None, check_cancelled=lambda:None,
        command_shell=lambda:SHELL, approve_command=lambda command,timeout:True)


async def execute(conversation_id, action, **arguments):
    session = make_session(conversation_id)
    return await asyncio.to_thread(TERMINALS.execute, session, {'action':action, **arguments})


@mcp.tool()
async def terminal_open(conversation_id: str, name: str='shell', cwd: str='.', rows: int=24, columns: int=80) -> dict:
    """Open an independent persistent interactive PTY. Retain its terminal_id for subsequent calls/turns."""
    return await execute(conversation_id, 'open', name=name, cwd=cwd, rows=rows, columns=columns)


@mcp.tool()
async def terminal_exec(conversation_id: str, terminal_id: str, command: str, wait_ms: int=1000) -> dict:
    """Submit a command plus Enter once. Return recent console history including prior commands. wait_ms (0–30000) does not limit or terminate the command; use read to continue watching."""
    return await execute(conversation_id, 'exec', terminal_id=terminal_id, command=command, wait_ms=wait_ms)


@mcp.tool()
async def terminal_write(conversation_id: str, terminal_id: str, text: str='', key: str='', modifiers: list[str] | None=None, wait_ms: int=500) -> dict:
    """Interactive fallback: type literal text, press Enter/Tab/Escape/arrows/F1–F12, or send a combination using key and modifiers [ctrl,alt,shift]. Examples: key=c, modifiers=[ctrl]; key=x, modifiers=[ctrl,alt]. Text is sent before the key. Return recent console history."""
    return await execute(conversation_id, 'write', terminal_id=terminal_id, text=text, key=key, modifiers=modifiers, wait_ms=wait_ms)


@mcp.tool()
async def terminal_read(conversation_id: str, terminal_id: str, cursor: int | None=None, wait_ms: int=1000, max_output: int=8000) -> dict:
    """Read recent console history, including previous commands. cursor is an optional wait fence from the last response. Wait at most 30 seconds without affecting the shell/command lifetime. Archived history remains readable after a service restart."""
    return await execute(conversation_id, 'read', terminal_id=terminal_id, cursor=cursor, wait_ms=wait_ms, max_output=max_output)


@mcp.tool()
async def terminal_list(conversation_id: str) -> dict:
    """List live and retained terminal records belonging to this conversation."""
    return await execute(conversation_id, 'list')


@mcp.tool()
async def terminal_resize(conversation_id: str, terminal_id: str, rows: int, columns: int) -> dict:
    """Resize a terminal (2–500 rows/columns), preserving shell state and pending input."""
    return await execute(conversation_id, 'resize', terminal_id=terminal_id, rows=rows, columns=columns)


@mcp.tool()
async def terminal_close(conversation_id: str, terminal_id: str) -> dict:
    """Explicitly close a PTY and its foreground/background jobs; preserve its recent transcript."""
    return await execute(conversation_id, 'close', terminal_id=terminal_id)


@mcp.custom_route('/healthz', methods=['GET'])
async def health(request):
    return JSONResponse({'ok':True,'service':'aster-terminal-mcp','version':'1.0.3'})


class BearerAuth:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] == 'http':
            headers = dict(scope.get('headers', []))
            supplied = headers.get(b'authorization', b'')
            expected = ('Bearer ' + TOKEN).encode()
            if not hmac.compare_digest(supplied, expected):
                response = JSONResponse({'error':'unauthorized'}, status_code=401,
                    headers={'WWW-Authenticate':'Bearer realm="aster-terminal"','Cache-Control':'no-store'})
                return await response(scope, receive, send)
        return await self.app(scope, receive, send)


app = BearerAuth(mcp.streamable_http_app())

if __name__ == '__main__':
    uvicorn.run(app, host='127.0.0.1', port=PORT, access_log=False, proxy_headers=False,
                limit_concurrency=32, timeout_keep_alive=10)