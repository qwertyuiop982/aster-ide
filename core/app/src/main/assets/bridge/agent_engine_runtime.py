"""Resident Node engine processes, isolated by project and conversation."""
from contextlib import contextmanager, suppress
import atexit
import hashlib
import json
import os
import select
import signal
import subprocess
import tempfile
import threading
import time

_PROCESSES = {}
_LOCK = threading.RLock()
# Lifecycle admission stays responsive while a process is being disposed.
_CHANGED = threading.Condition()
_OPERATIONS = set()
_STOPPING = set()
_GENERATIONS = {}
MAX_IDLE_PROCESSES = 4
MAX_MESSAGE_BYTES = 4 * 1024 * 1024


def _engine_scope(base, workspace, engine_id):
    return str(base.resolve()), str(workspace.resolve()), engine_id


@contextmanager
def engine_operation(session):
    scope = _engine_scope(session.base, session.workspace, session.engine_id)
    key = scope + (session.session_id,)
    with _CHANGED:
        generation = _GENERATIONS.get(scope, 0)
        while key in _OPERATIONS:
            session.check_cancelled()
            if scope in _STOPPING or _GENERATIONS.get(scope, 0) != generation:
                raise ValueError('原引擎正在退出，请稍后重试')
            _CHANGED.wait(0.1)
        # A short shutdown may finish before this queued operation wakes up.
        if scope in _STOPPING or _GENERATIONS.get(scope, 0) != generation:
            raise ValueError('原引擎正在退出，请稍后重试')
        session.check_cancelled()
        _OPERATIONS.add(key)
    try:
        yield
    finally:
        with _CHANGED:
            _OPERATIONS.discard(key)
            _CHANGED.notify_all()


@contextmanager
def stopping_engine(base, workspace, engine_id, timeout=10):
    key = _engine_scope(base, workspace, engine_id)
    with _CHANGED:
        if key in _STOPPING:
            raise ValueError('原引擎正在退出，请稍后重试')
        _STOPPING.add(key)
        _GENERATIONS[key] = _GENERATIONS.get(key, 0) + 1
        _CHANGED.notify_all()
    try:
        # Reserve the engine before waiting so queued warmups cannot restart it.
        deadline = time.monotonic() + timeout
        with _CHANGED:
            while any(item[:3] == key for item in _OPERATIONS):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError('请先停止或等待原引擎任务完成')
                _CHANGED.wait(min(remaining, 0.1))
        yield
    finally:
        with _CHANGED:
            _STOPPING.discard(key)
            _CHANGED.notify_all()


class EngineProcess:
    def __init__(self, command, stage, environment):
        self.errors = tempfile.TemporaryFile()
        self.buffer = b''
        try:
            self.process = subprocess.Popen(command, cwd=stage, env=environment,
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=self.errors, start_new_session=True)
        except BaseException:
            self.errors.close()
            raise

    def send(self, value):
        self.process.stdin.write((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode())
        self.process.stdin.flush()

    def receive(self, session):
        while True:
            session.check_cancelled()
            if b'\n' in self.buffer:
                line, self.buffer = self.buffer.split(b'\n', 1)
                return json.loads(line)
            ready, _, _ = select.select([self.process.stdout], [], [], 0.1)
            if ready:
                chunk = os.read(self.process.stdout.fileno(), 32768)
                if chunk:
                    self.buffer += chunk
                    if len(self.buffer) > MAX_MESSAGE_BYTES:
                        raise ValueError('Agent 请求超过 4 MiB，请缩小上下文窗口或先压缩会话')
                    continue
            if ready or self.process.poll() is not None:
                self.errors.seek(max(0, os.fstat(self.errors.fileno()).st_size - 1600))
                detail = self.errors.read(1600).decode(errors='replace').strip()
                raise RuntimeError(session.engine_info['name'] + ' 引擎连接中断' + (': ' + detail if detail else ''))

    def close(self):
        process = self.process
        # EOF lets the JS host checkpoint/dispose its native client and MCPs.
        with suppress(OSError):
            process.stdin.close()
        try:
            process.wait(timeout=0.8)
        except subprocess.TimeoutExpired:
            with suppress(ProcessLookupError, PermissionError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                with suppress(ProcessLookupError, PermissionError):
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=1.5)
        # Include native shell/MCP descendants that outlived the host.
        with suppress(ProcessLookupError, PermissionError):
            os.killpg(process.pid, signal.SIGKILL)
        for stream in (process.stdout, self.errors):
            with suppress(OSError):
                stream.close()


def close_scope(base, workspace, engine_id, session_id=None):
    prefix = (str(base.resolve()), str(workspace.resolve()), engine_id)
    with _LOCK:
        selected = [(key, entry) for key, entry in _PROCESSES.items()
                    if key[:3] == prefix and (session_id is None or key[3] == session_id)]
        if any(entry['busy'] for _, entry in selected):
            raise ValueError('请先停止或等待原引擎任务完成')
        for key, entry in selected:
            entry['host'].close()
            _PROCESSES.pop(key)
        return len(selected)


def close_all():
    with _LOCK:
        for entry in _PROCESSES.values():
            with suppress(OSError, subprocess.TimeoutExpired):
                entry['host'].close()
        _PROCESSES.clear()


atexit.register(close_all)


@contextmanager
def graph_server(session, command, environment, start):
    persistent = session.engine_id in ('pi', 'gemini')
    key = (str(session.base.resolve()), str(session.workspace.resolve()), session.engine_id, session.session_id)
    # User messages/history change every turn. Authentication, instructions,
    # permissions, tools or model changes require a fresh native client.
    stable = {name: value for name, value in start.items()
              if name not in ('task', 'history', 'compactOnly', 'prepareOnly', 'type')}
    bundle = os.stat(command[-1])
    fingerprint = hashlib.sha256(json.dumps([command, environment, stable,
                                             bundle.st_size, bundle.st_mtime_ns], sort_keys=True).encode()).hexdigest()
    with _LOCK:
        entry = _PROCESSES.get(key) if persistent else None
        if entry and entry['busy']:
            raise ValueError('此引擎会话已有任务在运行')
        if entry and (entry['fingerprint'] != fingerprint or entry['host'].process.poll() is not None):
            entry['host'].close()
            _PROCESSES.pop(key)
            entry = None
        if entry is None:
            idle = sorted((value['used'], name) for name, value in _PROCESSES.items() if not value['busy'])
            while persistent and len(idle) >= MAX_IDLE_PROCESSES:
                _, victim = idle.pop(0)
                _PROCESSES[victim]['host'].close()
                _PROCESSES.pop(victim)
            entry = {'host': EngineProcess(command, session.stage, environment), 'fingerprint': fingerprint,
                     'busy': False, 'used': time.monotonic()}
            if persistent:
                _PROCESSES[key] = entry
        entry['busy'] = True
    session.engine_process = entry['host'].process
    failed = False
    try:
        yield entry['host']
    except BaseException:
        failed = True
        with _LOCK:
            entry['host'].close()
            if _PROCESSES.get(key) is entry:
                _PROCESSES.pop(key)
        raise
    finally:
        session.engine_process = None
        with _LOCK:
            entry['busy'] = False
            entry['used'] = time.monotonic()
            if not persistent and not failed:
                entry['host'].close()
