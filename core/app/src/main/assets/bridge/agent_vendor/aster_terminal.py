"""Conversation-scoped PTYs. Tool waits never set a shell/command lifetime."""
from __future__ import annotations
import atexit
import codecs
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import threading
import time
import uuid

from aster_diagnostics import LinkerWarningFilter, console_text

MAX_BUFFER = 256 * 1024  # characters, at most 1 MiB encoded UTF-8
MAX_OUTPUT = 8000
KEYS = {'enter': b'\r', 'ctrl_c': b'\x03', 'ctrl_d': b'\x04', 'ctrl_z': b'\x1a', 'tab': b'\t', 'escape': b'\x1b'}


def encode_input(text='', key='', modifiers=None):
    if not isinstance(text, str) or not isinstance(key, str) or len(text.encode()) > 8192:
        raise ValueError('终端文本或按键无效，最多 8192 字节')
    modifiers = [] if modifiers is None else modifiers
    if not isinstance(modifiers, list) or any(value not in ('ctrl', 'alt', 'shift') for value in modifiers):
        raise ValueError('modifiers 需要 ctrl/alt/shift 数组')
    mods = set(modifiers)
    if key in KEYS and key.startswith('ctrl_'):
        mods.add('ctrl'); key = key[-1]
    if mods and not key:
        if len(text) != 1:
            raise ValueError('组合键需要 key，或单字符 text')
        key, text = text, ''
    if not key:
        return text.encode()
    lower = key.lower()
    simple = {'enter': b'\r', 'return': b'\r', 'tab': b'\t', 'escape': b'\x1b', 'esc': b'\x1b', 'backspace': b'\x7f', 'space': b' '}
    directions = {'up': 'A', 'down': 'B', 'right': 'C', 'left': 'D', 'home': 'H', 'end': 'F'}
    numbered = {'insert': 2, 'delete': 3, 'pageup': 5, 'pagedown': 6, 'f5': 15, 'f6': 17, 'f7': 18, 'f8': 19, 'f9': 20, 'f10': 21, 'f11': 23, 'f12': 24}
    modifier = 1 + ('shift' in mods) + 2 * ('alt' in mods) + 4 * ('ctrl' in mods)
    if lower in directions:
        data = ('\x1b[' + (('1;' + str(modifier)) if mods else '') + directions[lower]).encode()
        mods = set()
    elif lower in numbered:
        data = ('\x1b[' + str(numbered[lower]) + ((';' + str(modifier)) if mods else '') + '~').encode()
        mods = set()
    elif lower in ('f1', 'f2', 'f3', 'f4'):
        suffix = 'PQRS'[int(lower[-1]) - 1]
        data = ('\x1b[1;' + str(modifier) + suffix if mods else '\x1bO' + suffix).encode()
        mods = set()
    elif lower == 'tab' and mods == {'shift'}:
        data = b'\x1b[Z';mods = set()
    elif lower in simple:
        data = simple[lower]
        if ('shift' in mods or 'ctrl' in mods) and lower not in ('space',):
            data = ('\x1b[' + str(data[0]) + ';' + str(modifier) + 'u').encode()
            mods = set()
        elif lower == 'space' and 'ctrl' in mods:
            data = b'\x00'
    elif len(key) == 1:
        char = key.upper() if 'shift' in mods else key
        if 'ctrl' in mods:
            number = ord(char.upper())
            if char == ' ': number = 64
            if not 64 <= number <= 95:
                raise ValueError('Ctrl 组合键需要 A–Z 或 @ [ \u005c\u005c ] ^ _')
            data = bytes([number & 31])
        else:
            data = char.encode()
    else:
        raise ValueError('不支持的按键；组合键请通过 modifiers 传入')
    if 'alt' in mods:
        data = b'\x1b' + data
    return text.encode() + data


def bounded_integer(value, name, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f'{name} 必须是 {minimum}–{maximum} 的整数')
    return value


def conversation_scope(base, workspace, engine, conversation):
    if engine not in ('pi', 'codex', 'gemini', 'openclaude') or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', conversation):
        raise ValueError('终端所属引擎或对话无效')
    return str(Path(base).resolve()), str(Path(workspace).resolve()), engine, conversation


def session_scope(session):
    return conversation_scope(session.base, session.workspace, getattr(session, 'terminal_engine_id', session.engine_id), getattr(session, 'terminal_conversation_id', session.session_id))


def archive_folder(scope):
    base, workspace, engine, conversation = scope
    project = hashlib.sha256(workspace.encode()).hexdigest()[:24]
    folder = Path(base) / 'agent-terminals' / project / engine / conversation
    if Path(base) not in folder.resolve().parents:
        raise ValueError('终端记录目录超出运行环境')
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    return folder


class Terminal:
    def __init__(self, scope, name, cwd, shell, environment, rows, columns):
        self.scope, self.name, self.cwd = scope, name, str(cwd)
        self.identifier = uuid.uuid4().hex
        self.folder = archive_folder(scope)
        self.created = time.time()
        self.rows, self.columns = rows, columns
        self.changed = threading.Condition()
        self.input_lock = threading.Lock()
        self.buffer, self.end, self.read_cursor = '', 0, 0
        self.closed = False
        self.eof = False
        self.last_saved = 0
        master, slave = pty.openpty()
        try:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', rows, columns, 0, 0))
            env = dict(environment, TERM='xterm-256color', COLORTERM='truecolor', PS1='aster$ ', PROMPT_COMMAND='')
            # A fresh helper acquires a controlling TTY after setsid. Avoid
            # preexec_fn/fork callbacks in the multithreaded bridge process.
            self.process = subprocess.Popen([sys.executable, '-u', str(Path(__file__).resolve()), '--child', shell],
                cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave, start_new_session=True, close_fds=True)
        except BaseException:
            os.close(master)
            raise
        finally:
            os.close(slave)
        self.master = master
        os.set_blocking(master, False)
        with self.changed:
            self._save()
        self.reader = threading.Thread(target=self._pump, name='Aster-PTY-' + self.identifier[:8], daemon=True)
        self.reader.start()

    def _record(self):
        code = self.process.poll()
        return {'terminal_id': self.identifier, 'name': self.name, 'cwd': self.cwd,
                'created_at': self.created, 'pid': self.process.pid, 'rows': self.rows, 'columns': self.columns,
                'running': code is None and not self.closed, 'state': 'closed' if self.closed else 'running' if code is None else 'exited',
                'exit_code': code, 'start_cursor': self.end - len(self.buffer), 'end_cursor': self.end}

    def _save(self):
        # Called under changed. Logs contain filtered output, never an environment dump.
        for suffix, content in (('.log', self.buffer), ('.json', json.dumps(self._record(), ensure_ascii=False))):
            dest = self.folder / (self.identifier + suffix)
            temp = dest.with_suffix(suffix + '.tmp')
            temp.write_text(content, encoding='utf-8')
            temp.chmod(0o600)
            os.replace(temp, dest)
        self.last_saved = time.monotonic()

    def _append(self, text):
        if not text:
            return
        with self.changed:
            self.buffer = (self.buffer + text)[-MAX_BUFFER:]
            self.end += len(text)
            if time.monotonic() - self.last_saved >= .5:
                self._save()
            self.changed.notify_all()

    def _pump(self):
        filter_output = LinkerWarningFilter()
        decoder = codecs.getincrementaldecoder('utf-8')('replace')
        try:
            while True:
                ready, _, _ = select.select([self.master], [], [], .2)
                if not ready:
                    if self.process.poll() is not None:
                        break
                    continue
                try:
                    chunk = os.read(self.master, 32768)
                except BlockingIOError:
                    continue
                if not chunk:
                    break
                self._append(decoder.decode(filter_output.feed(chunk)))
        except (OSError, ValueError):
            pass  # Linux PTYs report EIO when their last slave closes.
        finally:
            self._append(decoder.decode(filter_output.feed(final=True), final=True))
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.process.wait(timeout=.5)
            with self.changed:
                self.eof = True
                self._save()
                self.changed.notify_all()
            with self.input_lock:
                with contextlib.suppress(OSError):
                    os.close(self.master)
                self.master = -1

    def snapshot(self, cursor=None, wait_ms=0, max_output=MAX_OUTPUT, check=None):
        if cursor is not None:
            bounded_integer(cursor, 'cursor', 0, 2 ** 63 - 1)
        bounded_integer(wait_ms, 'wait_ms', 0, 30000)
        bounded_integer(max_output, 'max_output', 1, MAX_OUTPUT)
        deadline = time.monotonic() + wait_ms / 1000
        with self.changed:
            position = self.read_cursor if cursor is None else cursor
            if position > self.end:
                raise ValueError('cursor 超出终端输出范围')
            while position >= self.end and not self.eof and not self.closed:
                if check:
                    check()
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.changed.wait(min(.1, remaining))
            if check:
                check()
            start = self.end - len(self.buffer)
            truncated = position < start
            position = max(position, start)
            text = self.buffer[position - start:position - start + max_output]
            position += len(text)
            if cursor is None:
                self.read_cursor = self.end
            self._save()
            result = {**self._record(), 'ok': True, 'output': console_text(self.buffer[-max_output:]), 'cursor': self.end,
                      'truncated': truncated or len(self.buffer) > max_output, 'has_more': False, 'history_included': True, 'reconnectable': not self.eof}
            result['foreground_running'] = self.foreground() not in (None, self.process.pid)
            return result

    def foreground(self):
        with contextlib.suppress(OSError):
            group = os.tcgetpgrp(self.master)
            if group > 0 and os.getsid(group) == self.process.pid:
                return group
        return None

    def write(self, data, check):
        deadline = time.monotonic() + 5
        with self.input_lock:
            if self.closed or self.master < 0 or self.process.poll() is not None:
                raise ValueError('终端已退出；请打开新终端，不会自动重放输入')
            while data:
                check()
                if time.monotonic() > deadline:
                    raise TimeoutError('终端输入缓冲区繁忙；部分输入可能已送达，请先读取输出，避免重复提交')
                _, ready, _ = select.select([], [self.master], [], .1)
                if ready:
                    try:
                        count = os.write(self.master, data)
                        data = data[count:]
                    except BlockingIOError:
                        pass

    def resize(self, rows, columns):
        with self.input_lock:
            if self.master < 0:
                raise ValueError('终端已退出')
            fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, columns, 0, 0))
            self.rows, self.columns = rows, columns

    def interrupt(self):
        group = self.foreground()
        if group and group != self.process.pid:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(group, signal.SIGINT)

    def close(self):
        with self.changed:
            if self.closed:
                return
            self.closed = True
        # Interactive jobs have their own process groups inside this PTY session.
        # Terminate the whole session, including background jobs, on explicit close.
        groups = {}
        if self.process.poll() is None:
            for entry in Path('/proc').iterdir():
                if not entry.name.isdigit():
                    continue
                try:
                    fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
                    if int(fields[3]) == self.process.pid:
                        groups.setdefault(int(fields[2]), []).append((entry, fields[19]))
                except (OSError, ValueError, IndexError):
                    continue
        for sig in (signal.SIGHUP, signal.SIGKILL):
            for group, members in groups.items():
                for entry, started in members:
                    try:
                        fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
                        if fields[19] == started and int(fields[2]) == group and int(fields[3]) == self.process.pid:
                            os.killpg(group, sig)
                            break
                    except (OSError, ValueError, IndexError):
                        continue
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.process.wait(timeout=.3)
        self.reader.join(timeout=2)
        with self.changed:
            self._save()
            self.changed.notify_all()


class TerminalRegistry:
    def __init__(self):
        self.lock = threading.RLock()
        self.items = {}

    def _live(self, scope, identifier):
        if not isinstance(identifier, str) or not re.fullmatch(r'[a-f0-9]{32}', identifier):
            raise ValueError('terminal_id 无效')
        item = self.items.get((scope, identifier))
        if item is None:
            raise ValueError('当前对话没有可连接的此终端；可用 list/read 查看历史，不能重放旧输入')
        return item

    def records(self, scope):
        folder = archive_folder(scope)
        result = []
        for file in folder.glob('*.json'):
            try:
                value = json.loads(file.read_text())
                live = self.items.get((scope, value['terminal_id']))
                if live is not None:
                    value = live.snapshot(max_output=1, cursor=live.end)
                    value.pop('output', None)
                elif value.get('running'):
                    value.update(running=False, state='runtime_stopped', reconnectable=False)
                result.append(value)
            except (OSError, ValueError, KeyError):
                continue
        return sorted(result, key=lambda value: value['created_at'], reverse=True)[:32]

    def close_scope(self, scope):
        with self.lock:
            items = [item for (owner, _), item in self.items.items() if owner == scope]
        for item in items:
            item.close()
        return len(items)

    def close_all(self):
        with self.lock:
            items = list(self.items.values())
        for item in items:
            item.close()

    def execute(self, session, arguments):
        scope = session_scope(session)
        action = arguments.get('action')
        if action == 'list':
            return {'ok': True, 'terminals': self.records(scope), 'lifetime': 'until explicitly closed or runtime stops'}
        if action not in ('open', 'exec', 'read', 'write', 'interrupt', 'resize', 'close'):
            raise ValueError('未知终端操作')
        wait_ms = bounded_integer(arguments.get('wait_ms', 300 if action in ('open', 'write') else 0), 'wait_ms', 0, 30000)
        maximum = bounded_integer(arguments.get('max_output', MAX_OUTPUT), 'max_output', 1, MAX_OUTPUT)
        cursor = arguments.get('cursor')
        if action == 'open':
            name = arguments.get('name', 'shell')
            if not isinstance(name, str) or not 1 <= len(name) <= 64 or any(ord(c) < 32 for c in name):
                raise ValueError('终端名称无效')
            from aster_tools import ProjectEnvironment
            cwd = ProjectEnvironment(session).path(arguments.get('cwd', '.'), folder=True)
            if not cwd.is_dir():
                raise ValueError('终端目录不存在')
            rows = bounded_integer(arguments.get('rows', 24), 'rows', 2, 500)
            columns = bounded_integer(arguments.get('columns', 80), 'columns', 2, 500)
            if not session.approve_command('创建持久交互终端：' + name + '\n目录：' + str(cwd), 0):
                return {'ok': False, 'error': '用户未批准创建终端'}
            session.check_cancelled()
            with self.lock:
                self.items = {key: value for key, value in self.items.items() if value.process.poll() is None}
                if len(self.items) >= 8 or sum(owner == scope for owner, _ in self.items) >= 4:
                    raise ValueError('持久终端数量已达上限（每个对话 4 个，总计 8 个）；请关闭不用的终端')
                item = Terminal(scope, name, cwd, session.command_shell(), session.environment, rows, columns)
                self.items[(scope, item.identifier)] = item
        else:
            identifier = arguments.get('terminal_id')
            if action == 'read' and (scope, identifier) not in self.items:
                if not isinstance(identifier, str) or not re.fullmatch(r'[a-f0-9]{32}', identifier):
                    raise ValueError('terminal_id 无效')
                folder = archive_folder(scope)
                meta = folder / (identifier + '.json')
                if not meta.is_file():
                    raise ValueError('当前对话没有此终端')
                value = json.loads(meta.read_text())
                text = (folder / (identifier + '.log')).read_text()
                start = int(value['end_cursor']) - len(text)
                position = start if cursor is None else bounded_integer(cursor, 'cursor', 0, int(value['end_cursor']))
                text = text[max(0, position - start):max(0, position - start) + maximum]
                return {**value, 'ok': True, 'running': False, 'reconnectable': False,
                        'state': 'runtime_stopped' if value.get('running') else value['state'], 'output': console_text(text), 'history_included': True,
                        'cursor': max(start, position) + len(text), 'truncated': position < start,
                        'has_more': max(start, position) + len(text) < value['end_cursor']}
            with self.lock:
                item = self._live(scope, identifier)
            if action in ('write', 'exec'):
                if action == 'exec':
                    command = arguments.get('command')
                    if not isinstance(command, str) or not command.strip() or len(command.encode()) > 8192:
                        raise ValueError('命令无效，最多 8192 字节')
                    data = command.encode() + b'\r'
                    description = command
                else:
                    data = encode_input(arguments.get('text', ''), arguments.get('key', ''), arguments.get('modifiers'))
                    description = str(arguments.get('text', '')) + '\n按键：' + str(arguments.get('key', '')) + ' ' + str(arguments.get('modifiers', []))
                if not data:
                    raise ValueError('需要 text 或 key')
                if not session.approve_command('持久终端 ' + item.name + ' 输入：\n' + description, 0):
                    return {'ok': False, 'error': '用户未批准此终端输入'}
                item.write(data, session.check_cancelled)
            elif action == 'interrupt':
                item.interrupt()
            elif action == 'resize':
                item.resize(bounded_integer(arguments.get('rows'), 'rows', 2, 500), bounded_integer(arguments.get('columns'), 'columns', 2, 500))
            elif action == 'close':
                item.close()
        session.active_terminal = item
        try:
            return item.snapshot(cursor, wait_ms, maximum, session.check_cancelled)
        except Exception:
            if session.cancelled.is_set():
                item.interrupt()
            raise
        finally:
            session.active_terminal = None


TERMINALS = TerminalRegistry()
atexit.register(TERMINALS.close_all)

if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] != '--child':
        raise SystemExit('Internal PTY helper')
    fcntl.ioctl(0, termios.TIOCSCTTY, 0)
    os.execv(sys.argv[2], [sys.argv[2], '--noprofile', '--norc', '-i'])