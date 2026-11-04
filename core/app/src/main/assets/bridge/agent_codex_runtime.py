"""Keep native app-server execution state alive between turns in a conversation."""
from contextlib import contextmanager, suppress
import atexit
import hashlib
import json
import os
import signal
import subprocess
import tempfile
import threading
import time

_SERVERS = {}
_LOCK = threading.RLock()
MAX_IDLE_SECONDS = 1800
MAX_SERVERS = 2


def _close(entry):
    entry['wire'].close_interactions()
    process = entry['wire'].process
    if process.poll() is None:
        with suppress(ProcessLookupError, PermissionError):
            os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=0.6)
        except subprocess.TimeoutExpired:
            with suppress(ProcessLookupError, PermissionError):
                os.killpg(process.pid, signal.SIGKILL)
            with suppress(subprocess.TimeoutExpired):
                process.wait(timeout=0.6)
    for stream in (process.stdin, process.stdout, entry['errors']):
        with suppress(OSError):
            stream.close()


def close_all():
    with _LOCK:
        for entry in list(_SERVERS.values()):
            _close(entry)
        _SERVERS.clear()


atexit.register(close_all)


@contextmanager
def native_server(session, home, command, environment, transport):
    key = str(home.resolve())
    config = home / 'config.toml'
    fingerprint = hashlib.sha256(json.dumps([command, environment, str(session.stage)], sort_keys=True).encode() + (config.read_bytes() if config.is_file() else b'')).hexdigest()
    with _LOCK:
        now = time.monotonic()
        for old_key, entry in list(_SERVERS.items()):
            if not entry['busy'] and (entry['wire'].process.poll() is not None or now - entry['used'] > MAX_IDLE_SECONDS):
                _SERVERS.pop(old_key)
                _close(entry)
        entry = _SERVERS.get(key)
        if entry and entry['busy']:
            raise ValueError('Codex conversation already has an active turn')
        if entry and entry['fingerprint'] != fingerprint:
            _SERVERS.pop(key)
            _close(entry)
            entry = None
        if entry is None:
            while len(_SERVERS) >= MAX_SERVERS:
                idle = [(value['used'], name) for name, value in _SERVERS.items() if not value['busy']]
                if not idle:
                    raise ValueError('Native Codex server capacity reached')
                _, victim = min(idle)
                _close(_SERVERS.pop(victim))
            errors = tempfile.TemporaryFile()
            try:
                process = subprocess.Popen(command, cwd=session.stage, env=environment, stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE, stderr=errors, start_new_session=True)
                wire = transport(session, process, errors)
            except BaseException:
                errors.close()
                raise
            entry = {'wire': wire, 'errors': errors, 'fingerprint': fingerprint, 'busy': False, 'used': now}
            _SERVERS[key] = entry
        entry['busy'] = True
    wire = entry['wire']
    wire.begin_turn(session)
    session.engine_process = wire.process
    try:
        yield wire
    except BaseException:
        with _LOCK:
            if _SERVERS.get(key) is entry:
                _SERVERS.pop(key)
            _close(entry)
        raise
    finally:
        session.engine_process = None
        with _LOCK:
            entry['busy'] = False
            entry['used'] = time.monotonic()
