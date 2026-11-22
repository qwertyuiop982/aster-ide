#!/usr/bin/env python3
"""Authenticated loopback transport running in the original Termux UID.
LSP keeps standard Content-Length framing; PTY uses JSON/base64 for resize.
"""
import argparse
import base64
import codecs
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path, PurePosixPath
import pty
import re
import select
import shlex
import shutil
import signal
import socket
import time
import socketserver
import struct
import sys
import subprocess
import tarfile
import tempfile
import termios
import threading
import urllib.request
import zipfile

MAX_PACKET = 24 * 1024 * 1024
MAX_FILE = 12 * 1024 * 1024
INSTALL_LOCK = threading.Lock()
JOBS_LOCK = threading.Lock()
JOBS = {}
CHILDREN_LOCK = threading.Lock()
CHILDREN = set()
SHUTTING_DOWN = False
CATALOG = {}
TOKEN = ''
BASE = Path.home() / '.aster'
WORKSPACES = BASE / 'workspaces'
WORKSPACE_BINDINGS = os.environ.get('ASTER_WORKSPACE_BINDINGS')
BIN = BASE / 'bin'
LIMIT = threading.BoundedSemaphore(24)
PROCESSES = threading.BoundedSemaphore(8)
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / 'agent_vendor'))
from aster_diagnostics import LinkerWarningFilter


def packet(stream, value):
    stream.write((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))
    stream.flush()


def receive(stream):
    line = stream.readline(MAX_PACKET + 1)
    if not line:
        raise EOFError('Connection closed')
    if len(line) > MAX_PACKET or not line.endswith(b'\n'):
        raise ValueError('Packet exceeds 24 MiB')
    value = json.loads(line)
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object')
    return value


def workspace(identifier, *, use_binding=True, require_binding=False):
    identifier = str(identifier)
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', identifier):
        raise ValueError('Invalid workspace ID')
    if use_binding and WORKSPACE_BINDINGS:
        try:
            bindings = json.loads(Path(WORKSPACE_BINDINGS).read_text(encoding='utf-8'))
        except FileNotFoundError:
            bindings = {}
        if identifier in bindings:
            target = Path(bindings[identifier])
            if not target.is_absolute() or not target.is_dir() or not os.access(target, os.R_OK | os.X_OK):
                raise PermissionError('绑定的项目目录已不可访问，请重新授权')
            return target.resolve(strict=True)
        if require_binding and identifier != 'agent-chat':
            raise PermissionError('原生模式需要已授权的项目目录')
    root = WORKSPACES / identifier
    root.mkdir(parents=True, exist_ok=True)
    resolved = root.resolve()
    if resolved != WORKSPACES.resolve() / identifier:
        raise ValueError('工作区缓存目录不能指向其他位置')
    return resolved


def workspace_for_request(op, request):
    identifier = request.get('workspace', 'default')
    if op in ('agent', 'agent_warmup', 'agent_terminal_close', 'agent_engine_stop'):
        mode = (request.get('configuration') or {}).get('permissionMode')
        if mode is None and op in ('agent_terminal_close', 'agent_engine_stop'):
            review = workspace(identifier, use_binding=False)
            try:
                native = workspace(identifier)
            except PermissionError:
                return (review,)
            return (review, native) if native != review else (review,)
        root = workspace(identifier, use_binding=mode == 'native', require_binding=mode == 'native')
        return (root,) if op in ('agent_terminal_close', 'agent_engine_stop') else root
    return workspace(identifier, use_binding=op not in ('put', 'remove'))


def runtime_path(value):
    # Saved SDK locations from a previous version may still use the guest prefix.
    runtime_files = os.environ.get('ASTER_PRIVATE_FILES')
    if runtime_files and value:
        for alias in ('/data/data/com.termux/files', '/data/user/0/com.termux/files'):
            if value == alias or value.startswith(alias + '/'):
                return runtime_files + value[len(alias):]
    return value


def safe_path(root, relative):
    name = PurePosixPath(str(relative))
    if name.is_absolute() or not name.parts or '..' in name.parts or '\x00' in str(name):
        raise ValueError('Invalid relative path')
    dest = root.joinpath(*name.parts).resolve()
    if dest == root or root not in dest.parents:
        raise ValueError('Path escapes the workspace')
    return dest


def environment(options=None):
    env = dict(os.environ)
    env['PATH'] = str(BIN) + os.pathsep + env.get('PATH', '')
    env.update(TERM='xterm-256color', COLORTERM='truecolor', LANG='C.UTF-8', PYTHONUNBUFFERED='1', DEBIAN_FRONTEND='noninteractive')
    env['npm_config_update_notifier'] = 'false'
    options = options or {}
    sdk = runtime_path(options.get('sdk')) or str(Path.home() / 'android-sdk')
    record = None
    if env.get('PREFIX'):
        env.pop('ASTER_AAPT2', None)
        env.pop('ANDROID_NDK_HOME', None)
    if Path(sdk).is_dir():
        env['ANDROID_HOME'] = sdk
        env['ANDROID_SDK_ROOT'] = sdk
        if env.get('PREFIX'):
            from toolchain_native import native_aapt2, installed
            native = native_aapt2(sdk, str(options.get('sdkBuildTools', '35.0.1')))
            if native:
                env['ASTER_AAPT2'] = native
                env['PATH'] = str(Path(native).parent) + os.pathsep + str(Path(sdk) / 'platform-tools') + os.pathsep + env['PATH']
            record = installed(sdk)
            if record and record.get('ndk'):
                env['ANDROID_NDK_HOME'] = str(Path(sdk) / 'ndk' / record['ndk'])
    prefix = env.get('PREFIX', '/data/data/com.termux/files/usr')
    requested_jdk = str(options.get('sdkJdk') or (record or {}).get('jdk') or '17')
    for ver in dict.fromkeys([requested_jdk, '17', '21']):
        jdk = Path(prefix) / f'lib/jvm/java-{ver}-openjdk'
        if jdk.is_dir():
            if env.get('PREFIX'):
                from toolchain_native import android_elf
                try:
                    android_elf(jdk / 'bin/java')
                except (OSError, ValueError, struct.error, UnicodeError):
                    continue
            env['JAVA_HOME'] = str(jdk)
            env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']
            break
    memory = max(256, min(int(options.get('memory', 1024)), 4096))
    workers = max(1, min(int(options.get('workers', 2)), 8))
    env['GRADLE_OPTS'] = f'-Xmx{memory}m -Dorg.gradle.workers.max={workers} -Dorg.gradle.daemon=false'
    return env


def resolve_command(server_id, override=None, root=None):
    item = CATALOG.get(server_id)
    if not item:
        raise ValueError('Unknown language server')
    command = shlex.split(override) if override else list(item['command'])
    if not command or len(command) > 48:
        raise ValueError('Invalid server command')
    exe = shutil.which(command[0], path=environment()['PATH'])
    if not exe:
        raise FileNotFoundError(f'{command[0]} is not installed; install {item["name"]} in Settings')
    command[0] = exe
    if server_id == 'java' and root:
        command += ['-data', str(BASE / 'jdt-workspaces' / root.name)]
    return command


class ProcessJob:
    def __init__(self):
        self.cancelled = threading.Event()
        self.process = None


def terminate(process):
    # The shell can already have exited while its descendants still own pipes.
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(process.pid, signal.SIGTERM)
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=0.3)
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(process.pid, signal.SIGKILL)
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=0.5)


def start_child(argv, **kwargs):
    with CHILDREN_LOCK:
        if SHUTTING_DOWN:
            raise RuntimeError('Bridge is shutting down')
        process = subprocess.Popen(argv, start_new_session=True, **kwargs)
        CHILDREN.add(process)
        return process


def stop_child(process):
    with CHILDREN_LOCK:
        if process not in CHILDREN:
            return
        CHILDREN.remove(process)
    terminate(process)


def stop_runtime():
    global SHUTTING_DOWN
    with CHILDREN_LOCK:
        SHUTTING_DOWN = True
        children = list(CHILDREN)
    runner = sys.modules.get('agent_runner')
    if runner is not None:
        with runner.SESSIONS_LOCK:
            sessions = [session for session in runner.SESSIONS.values() if session.root_session is session]
        for session in sessions:
            with contextlib.suppress(Exception):
                session.cancel()
    for child in children:
        stop_child(child)


def stream_process(argv, root, out, job_id, options=None):
    with JOBS_LOCK:
        control = JOBS.get(job_id)
        own_job = control is None
        if own_job:
            control = ProcessJob()
            JOBS[job_id] = control
        if not isinstance(control, ProcessJob) or control.process is not None:
            raise ValueError('Job ID already running')
    process = None
    try:
        if control.cancelled.is_set():
            return 130
        process = start_child(argv, cwd=root, env=environment(options), stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        with JOBS_LOCK:
            control.process = process
        if control.cancelled.is_set():
            terminate(process)
        decode = codecs.getincrementaldecoder('utf-8')('replace')
        filter_output = LinkerWarningFilter()
        while True:
            chunk = process.stdout.read1(8192)
            if not chunk:
                break
            text = decode.decode(filter_output.feed(chunk))
            if text:
                packet(out, {'type': 'output', 'text': text})
        tail = decode.decode(filter_output.feed(final=True), final=True)
        if tail:
            packet(out, {'type': 'output', 'text': tail})
        code = process.wait()
        return 130 if control.cancelled.is_set() else code
    finally:
        if process is not None:
            stop_child(process)
            process.stdout.close()
        with JOBS_LOCK:
            if control.process is process:
                control.process = None
            if own_job and JOBS.get(job_id) is control:
                JOBS.pop(job_id, None)


def fetch(url, destination):
    req = urllib.request.Request(url, headers={'User-Agent': 'AsterIDE/0.1'})
    with urllib.request.urlopen(req) as response, destination.open('wb') as out:
        total = 0
        while True:
            part = response.read(256 * 1024)
            if not part:
                break
            total += len(part)
            if total > 350 * 1024 * 1024:
                raise ValueError('Download exceeds 350 MiB')
            out.write(part)
    return destination


def web_text(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'AsterIDE/0.1'})
    with urllib.request.urlopen(req) as response:
        return response.read(4 * 1024 * 1024).decode('utf-8')


def github(url, proxy):
    if proxy and url.startswith(('https://github.com/', 'https://raw.githubusercontent.com/')):
        return 'https://gh-proxy.org/' + url
    return url


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                safe_path(destination.resolve(), entry.filename.rstrip('/'))
                if ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                    raise ValueError('Archive symlink is not accepted')
            z.extractall(destination)
    else:
        with tarfile.open(archive) as t:
            members = []
            for entry in t.getmembers():
                if entry.name in ('.', './'):
                    continue
                safe_path(destination.resolve(), entry.name.rstrip('/'))
                if not entry.isfile() and not entry.isdir():
                    raise ValueError('Archive links are not accepted')
                members.append(entry)
            t.extractall(destination, members=members, filter='data')


def wrapper(name, script):
    BIN.mkdir(parents=True, exist_ok=True)
    file = BIN / name
    file.write_text('#!' + (shutil.which('sh') or '/bin/sh') + '\n' + script + '\n')
    file.chmod(0o700)


def install_special(kind, proxy):
    dest = BASE / 'servers' / kind
    dest.mkdir(parents=True, exist_ok=True)
    print(f'\nDownloading {kind} from upstream…', flush=True)
    if kind == 'kotlin':
        release = json.loads(web_text('https://api.github.com/repos/fwcd/kotlin-language-server/releases/latest'))
        asset = next(a for a in release['assets'] if a['name'] == 'server.zip')
        archive = fetch(github(asset['browser_download_url'], proxy), dest / 'server.zip')
        extract(archive, dest / 'dist')
        executable = next((dest / 'dist').rglob('bin/kotlin-language-server'))
        executable.chmod(0o700)
        wrapper('kotlin-language-server', 'exec ' + shlex.quote(str(executable)) + ' "$@"')
    elif kind == 'jdtls':
        base = 'https://download.eclipse.org/jdtls/milestones/'
        versions = re.findall(r'href=[\"\'](?:https://download\.eclipse\.org)?/jdtls/milestones/([0-9]+\.[0-9]+\.[0-9]+)/?[\"\']', web_text(base))
        if not versions:
            raise ValueError('Cannot find a stable JDT LS milestone')
        version = max(versions, key=lambda x: tuple(map(int, x.split('.'))))
        page = web_text(base + version + '/')
        archive_name = next(iter(re.findall(r'jdt-language-server-[0-9A-Za-z.\-]+\.tar\.gz', page)))
        archive = fetch(base + version + '/' + archive_name, dest / 'jdtls.tar.gz')
        extract(archive, dest / 'dist')
        launcher = next((dest / 'dist/plugins').glob('org.eclipse.equinox.launcher_*.jar'))
        java = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr')) / 'lib/jvm/java-21-openjdk/bin/java'
        executable = str(java) if java.exists() else (shutil.which('java') or 'java')
        wrapper('jdtls', 'exec ' + shlex.quote(executable) + ' -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4 -Declipse.product=org.eclipse.jdt.ls.core.product -Dlog.protocol=false -Xmx768m --add-modules=ALL-SYSTEM --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -jar ' + shlex.quote(str(launcher)) + ' -configuration ' + shlex.quote(str(dest / 'dist/config_linux')) + ' "$@"')
    elif kind == 'phpactor':
        release = json.loads(web_text('https://api.github.com/repos/phpactor/phpactor/releases/latest'))
        asset = next(a for a in release['assets'] if a['name'] == 'phpactor.phar')
        phar = fetch(github(asset['browser_download_url'], proxy), dest / 'phpactor.phar')
        wrapper('phpactor', 'exec php ' + shlex.quote(str(phar)) + ' "$@"')
    elif kind == 'solargraph':
        return subprocess.call(['gem', 'install', 'solargraph', '--no-document'])
    return 0


ENVIRONMENT_PACKAGES = {
    'env-python': {'packages': ['python', 'python-pip']},
    'env-node': {'packages': ['nodejs-lts'], 'npm': ['typescript']},
    'env-cpp': {'packages': ['clang', 'make', 'cmake', 'ninja', 'pkg-config']},
    'env-rust': {'packages': ['rust']},
    'env-go': {'packages': ['golang']},
    'env-jvm': {'packages': ['kotlin']},
    'env-cli': {'packages': ['git', 'ripgrep', 'fd', 'jq', 'tree', 'zip', 'unzip']},
}
ENVIRONMENT_TOOLS = (
    'python', 'pip', 'node', 'npm', 'tsc', 'clang', 'clang++', 'make', 'cmake', 'ninja',
    'pkg-config', 'rustc', 'cargo', 'go', 'java', 'javac', 'kotlinc', 'gradle',
    'git', 'rg', 'fd', 'jq', 'tree', 'zip', 'unzip', 'aapt2',
)


def install(server_id, out, job_id, options):
    if not INSTALL_LOCK.acquire(blocking=False):
        raise ValueError('Another installation is running')
    try:
        if server_id == 'base':
            item = {'packages': ['python', 'libc++', 'nodejs-lts', 'git', 'ripgrep', 'openssl', 'openssl-tool', 'ca-certificates', 'clang', 'make', 'cmake', 'ninja', 'unzip', 'zip']}
        elif server_id == 'agent':
            item = {'packages': ['libc++', 'nodejs-lts', 'git', 'ripgrep', 'openssl', 'openssl-tool', 'ca-certificates']}
        elif server_id == 'android':
            item = {'packages': ['python', 'gradle', 'tar', 'unzip', 'xz-utils', 'jq', 'wget', 'aria2', 'libcurl']}
        elif server_id == 'env-jvm':
            version = '21' if options.get('sdkJdk') == '21' else '17'
            item = dict(ENVIRONMENT_PACKAGES[server_id], packages=['openjdk-' + version, 'kotlin'])
        elif server_id in ENVIRONMENT_PACKAGES:
            item = ENVIRONMENT_PACKAGES[server_id]
        else:
            item = CATALOG.get(server_id)
            if not item:
                raise ValueError('Unknown server')
        apt = shutil.which('apt-get')
        if not apt or not os.environ.get('PREFIX'):
            raise RuntimeError('Installation must run inside Termux')
        # Use APT directly so all configured sources stay in effect.
        code = stream_process([apt, '-o', 'APT::Update::Error-Mode=any', 'update'], BASE, out, job_id, options)
        if code:
            return code
        if server_id in ('base', 'agent'):
            code = stream_process([apt, '-y', '-o', 'Dpkg::Options::=--force-confold', 'upgrade'], BASE, out, job_id, options)
            if code:
                return code
        code = stream_process([apt, '-y', 'install'] + item.get('packages', []), BASE, out, job_id, options)
        if code:
            return code
        if item.get('npm'):
            registry = options.get('registry', 'https://registry.npmmirror.com')
            if not registry.startswith('https://') or any(c.isspace() for c in registry):
                raise ValueError('npm registry must be an HTTPS URL')
            code = stream_process(['npm', 'install', '--global', '--registry', registry] + item['npm'], BASE, out, job_id, options)
            if code:
                return code
        if item.get('special'):
            # Downloads must be cancellable even while a network read is blocked.
            arguments = [sys.executable, str(Path(__file__).resolve()), '--base', str(BASE), '--install-special', item['special']]
            if not options.get('proxy', True):
                arguments.append('--no-github-proxy')
            code = stream_process(arguments, BASE, out, job_id, options)
            if code:
                return code
        if server_id == 'android':
            arguments = ['python', str(BASE / 'setup_android.py'), '--sdk', runtime_path(options.get('sdk')) or str(Path.home() / 'android-sdk'),
                         '--build-tools', str(options.get('sdkBuildTools', '35.0.1')), '--jdk', str(options.get('sdkJdk', '17')),
                         '--ndk', str(options.get('sdkNdk', '29.0.14206865'))]
            if not options.get('sdkCmdlineTools', True):
                arguments.append('--skip-cmdline-tools')
            return stream_process(arguments, BASE, out, job_id, options)
        return 0
    finally:
        INSTALL_LOCK.release()


def toolchain_info(options=None):
    from toolchain_native import installed, sdk_binaries, ndk_binaries
    path = runtime_path((options or {}).get('sdk')) or os.environ.get('ANDROID_HOME') or str(Path.home() / 'android-sdk')
    info = installed(path)
    if info is None:
        return None
    result = dict(info, sdk=str(path), sdkBinaries=sdk_binaries(path, info['buildTools']))
    if info.get('ndk'):
        result['ndkBinaries'] = ndk_binaries(Path(path) / 'ndk' / info['ndk'])
    return result


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        if not LIMIT.acquire(blocking=False):
            return
        try:
            self.request.settimeout(15)
            request = receive(self.rfile)
            if request.get('op') == 'android_mcp_call':
                from agent_android_mcp import call_active_session
                self.request.settimeout(None)
                packet(self.wfile, call_active_session(request))
                return
            if not hmac.compare_digest(str(request.get('token', '')), TOKEN):
                packet(self.wfile, {'ok': False, 'error': 'Authentication failed'})
                return
            self.request.settimeout(None)
            op = request.get('op')
            if op == 'agent_runtime':
                from agent_engines import node_runtime
                packet(self.wfile, {'ok': True, 'node': node_runtime(environment(request.get('options')))})
                return
            if op == 'agent_engines':
                from agent_engines import availability
                packet(self.wfile, {'ok': True, 'engines': availability(BASE, Path(__file__).parent / 'agent_vendor', environment(request.get('options')))})
                return
            if op == 'info':
                env = environment(request.get('options'))
                installed = {k: bool(shutil.which(v['command'][0], path=env['PATH'])) for k, v in CATALOG.items()}
                packet(self.wfile, {'ok': True, 'version': 1, 'home': str(Path.home()), 'root': str(WORKSPACES), 'installed': installed,
                                    'tools': {k: shutil.which(k, path=env['PATH']) for k in ENVIRONMENT_TOOLS}, "androidToolchain": toolchain_info(request.get("options"))})
                return
            if op == 'cancel':
                with JOBS_LOCK:
                    control = JOBS.get(str(request.get('job')))
                    found = isinstance(control, ProcessJob)
                    if found:
                        control.cancelled.set()
                        process = control.process
                    else:
                        process = None
                if process is not None:
                    terminate(process)
                packet(self.wfile, {'ok': True, 'cancelled': found})
                return
            root = workspace_for_request(op, request) if op not in ('agent_decision', 'agent_cancel', 'agent_client_result', 'install', 'resize') else None
            if op in ('agent_decision', 'agent_cancel', 'agent_client_result'):
                import agent_runner
                accepted = agent_runner.control(request, cancel=op == 'agent_cancel')
                packet(self.wfile, {'ok': True, 'accepted': accepted})
            elif op == 'agent_engine_stop':
                from agent_engines import engine_info
                engine_id = str(request.get('engineId', ''))
                engine_info(engine_id)
                from agent_engine_runtime import close_scope as close_graph_scope, stopping_engine
                from agent_codex_runtime import close_scope as close_codex_scope
                from aster_terminal import TERMINALS
                closed = terminals = 0
                for directory in root:
                    with stopping_engine(BASE, directory, engine_id):
                        closed += close_codex_scope(BASE, directory) if engine_id == 'codex' else close_graph_scope(BASE, directory, engine_id)
                        terminals += TERMINALS.close_engine(BASE, directory, engine_id)
                packet(self.wfile, {'ok': True, 'stopped': True, 'closed': closed, 'terminals': terminals})
            elif op == 'agent_terminal_close':
                from aster_terminal import TERMINALS, conversation_scope
                engine_id = str(request.get('engineId', 'pi'))
                session_id = str(request.get('sessionId', ''))
                from agent_engine_runtime import close_scope as close_graph_scope
                from agent_codex_runtime import close_scope as close_codex_scope
                closed = 0
                for directory in root:
                    scope = conversation_scope(BASE, directory, engine_id, session_id)
                    closed += TERMINALS.close_scope(scope)
                    closed += close_codex_scope(BASE, directory, session_id) if engine_id == 'codex' else close_graph_scope(BASE, directory, engine_id, session_id)
                packet(self.wfile, {'ok': True, 'closed': closed})
            elif op == 'agent_warmup':
                import agent_runner
                session = agent_runner.AgentSession(str(request.get('job')), root, BASE, request, environment(request.get('options')), lambda event: None)
                session.android_mcp_port = self.server.server_address[1]
                session.deadline = time.monotonic() + 60
                from agent_engine_runtime import engine_operation
                with engine_operation(session):
                    try:
                        from agent_engines import require_available
                        require_available(session.engine_id, BASE, Path(__file__).parent / 'agent_vendor', session.environment)
                        from agent_native import sync_mcp_configuration
                        sync_mcp_configuration(session, request.get('configuration') or {})
                        session.prepare()
                        if session.engine_id == 'codex' and (request.get('configuration') or {}).get('model'):
                            from agent_codex import run_codex
                            run_codex(session, request['configuration'], prepare_only=True)
                        elif session.engine_id in ('pi', 'gemini'):
                            session.run_graph(None, request.get('configuration') or {}, prepare_only=True)
                        packet(self.wfile, {'ok': True, 'ready': True, 'modelRequested': False})
                    finally:
                        if session.stage_lock is not None:
                            session.stage_lock.close()
            elif op == 'put':
                data = base64.b64decode(request.get('data', ''), validate=True)
                if len(data) > MAX_FILE:
                    raise ValueError('File exceeds 12 MiB')
                dest = safe_path(root, request['path'])
                digest = hashlib.sha256(data).hexdigest()
                if request.get('sha256') and request['sha256'] != digest:
                    raise ValueError('Content checksum mismatch')
                dest.parent.mkdir(parents=True, exist_ok=True)
                fd, temp = tempfile.mkstemp(prefix='.aster-', dir=dest.parent)
                try:
                    with os.fdopen(fd, 'wb') as out:
                        out.write(data)
                    os.chmod(temp, 0o700 if dest.name in ['gradlew', 'mvnw'] or dest.suffix == '.sh' else 0o600)
                    os.replace(temp, dest)
                finally:
                    with contextlib.suppress(FileNotFoundError):
                        os.unlink(temp)
                packet(self.wfile, {'ok': True, 'sha256': digest, 'root': str(root)})
            elif op == 'artifacts':
                artifacts = []
                for folder in ['build', 'app/build', '.aster-build', 'target']:
                    base = safe_path(root, folder)
                    if not base.is_dir():
                        continue
                    for file in base.rglob('*'):
                        if len(artifacts) >= 100:
                            break
                        if file.is_file() and not file.is_symlink() and file.suffix.lower() in ['.apk', '.aab', '.jar', '.zip', '.so', '.out']:
                            resolved = file.resolve()
                            if root in resolved.parents:
                                artifacts.append({'path': str(file.relative_to(root)), 'size': file.stat().st_size})
                direct = root / '.aster-build/app'
                if direct.is_file() and not direct.is_symlink():
                    artifacts.append({'path': '.aster-build/app', 'size': direct.stat().st_size})
                packet(self.wfile, {'ok': True, 'artifacts': artifacts[:100]})
            elif op == 'readChunk':
                file = safe_path(root, request['path'])
                size = file.stat().st_size
                if size > 512 * 1024 * 1024:
                    raise ValueError('Artifact exceeds 512 MiB')
                offset = int(request.get('offset', 0))
                if offset < 0 or offset > size:
                    raise ValueError('Invalid artifact offset')
                with file.open('rb') as inp:
                    inp.seek(offset)
                    chunk = inp.read(512 * 1024)
                packet(self.wfile, {'ok': True, 'data': base64.b64encode(chunk).decode('ascii'), 'size': size, 'modified': file.stat().st_mtime_ns, 'eof': offset + len(chunk) >= size})
            elif op == 'read':
                file = safe_path(root, request['path'])
                if file.stat().st_size > MAX_FILE:
                    raise ValueError('File exceeds 12 MiB')
                packet(self.wfile, {'ok': True, 'data': base64.b64encode(file.read_bytes()).decode('ascii')})
            elif op == 'remove':
                file = safe_path(root, request['path'])
                if file.is_file():
                    file.unlink()
                packet(self.wfile, {'ok': True})
            elif op == 'resize':
                with JOBS_LOCK:
                    item = JOBS.get('pty:' + str(request['job']))
                if isinstance(item, int):
                    fcntl.ioctl(item, termios.TIOCSWINSZ, struct.pack('HHHH', max(2, min(500, int(request['rows']))), max(2, min(500, int(request['columns']))), 0, 0))
                packet(self.wfile, {'ok': True})
            elif op in ['run', 'install', 'lsp', 'terminal', 'agent']:
                if not PROCESSES.acquire(blocking=False):
                    raise ValueError('Process limit reached (8)')
                try:
                    if op == 'agent':
                        import agent_runner
                        agent_runner.handle(root, BASE, request, environment(request.get('options')), lambda event: packet(self.wfile, event),
                                            connection=self.request, android_mcp_port=self.server.server_address[1])
                    elif op == 'lsp':
                        self.lsp(request, root)
                    elif op == 'terminal':
                        self.terminal(request, root)
                    else:
                        job = str(request.get('job', 'job'))
                        control = ProcessJob()
                        with JOBS_LOCK:
                            if job in JOBS:
                                raise ValueError('Job ID already running')
                            JOBS[job] = control
                        try:
                            packet(self.wfile, {'ok': True, 'job': job})
                            if op == 'install':
                                code = install(request['server'], self.wfile, job, request.get('options', {}))
                            else:
                                shell = shutil.which('bash') or '/bin/sh'
                                code = stream_process([shell, '-c', request['command']], root, self.wfile, job, request.get('options', {}))
                        finally:
                            with JOBS_LOCK:
                                if JOBS.get(job) is control:
                                    JOBS.pop(job, None)
                        packet(self.wfile, {'type': 'exit', 'code': code})
                finally:
                    PROCESSES.release()
            else:
                raise ValueError('Unknown operation')
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, EOFError):
            pass
        except Exception as e:
            with contextlib.suppress(Exception):
                packet(self.wfile, {'ok': False, 'type': 'error', 'error': str(e)[:2000]})
        finally:
            LIMIT.release()

    def lsp(self, request, root):
        command = resolve_command(request['server'], request.get('command'), root)
        process = start_child(command, cwd=root, env=environment(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, bufsize=0)
        packet(self.wfile, {'ok': True, 'pid': process.pid, 'root': str(root)})
        stop = threading.Event()
        def read_stdout():
            try:
                while not stop.is_set():
                    data = os.read(process.stdout.fileno(), 32768)
                    if not data:
                        break
                    self.request.sendall(data)
            except (OSError, ValueError):
                pass
            finally:
                stop.set()
                with contextlib.suppress(OSError):
                    self.request.shutdown(socket.SHUT_RDWR)
        def read_stderr():
            file = BASE / 'logs' / (request['server'] + '.log')
            file.parent.mkdir(exist_ok=True)
            with file.open('wb') as out:
                while not stop.is_set():
                    data = process.stderr.read(4096)
                    if not data:
                        break
                    if out.tell() > 512 * 1024:
                        out.seek(0)
                        out.truncate()
                    out.write(data)
                    out.flush()
        reader = threading.Thread(target=read_stdout, daemon=True)
        errors = threading.Thread(target=read_stderr, daemon=True)
        reader.start()
        errors.start()
        try:
            # rfile retains any bytes buffered beyond the handshake.
            while not stop.is_set():
                data = self.rfile.read1(32768)
                if not data:
                    break
                process.stdin.write(data)
                process.stdin.flush()
        finally:
            stop.set()
            stop_child(process)
            for stream in [process.stdin, process.stdout, process.stderr]:
                with contextlib.suppress(OSError):
                    stream.close()
            reader.join(timeout=1)
            errors.join(timeout=1)

    def terminal(self, request, root):
        master, slave = pty.openpty()
        job = str(request.get('job', 'terminal'))
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
        shell = shutil.which('bash') or '/bin/sh'
        try:
            process = start_child([shell, '--noprofile', '--norc', '-i'], cwd=root, env=environment(),
                                  stdin=slave, stdout=slave, stderr=slave)
        except BaseException:
            os.close(master)
            raise
        finally:
            os.close(slave)
        with JOBS_LOCK:
            JOBS['pty:' + job] = master
        packet(self.wfile, {'ok': True, 'job': job})
        stop = threading.Event()
        def output():
            filter_output = LinkerWarningFilter()
            try:
                while not stop.is_set():
                    ready, _, _ = select.select([master], [], [], 0.5)
                    if not ready:
                        if process.poll() is not None:
                            break
                        continue
                    data = os.read(master, 8192)
                    if not data:
                        break
                    data = filter_output.feed(data)
                    if data:
                        packet(self.wfile, {'type': 'output', 'data': base64.b64encode(data).decode('ascii')})
            except OSError:
                pass
            finally:
                stop.set()
                with contextlib.suppress(Exception):
                    packet(self.wfile, {'type': 'exit', 'code': process.poll() or 0})
                    self.request.shutdown(socket.SHUT_RDWR)
        reader = threading.Thread(target=output, daemon=True)
        reader.start()
        try:
            while not stop.is_set():
                data = receive(self.rfile)
                if data.get('type') == 'input':
                    raw = base64.b64decode(data.get('data', ''), validate=True)
                    if len(raw) > 65536:
                        raise ValueError('Input too large')
                    os.write(master, raw)
                elif data.get('type') == 'resize':
                    rows = max(2, min(500, int(data['rows'])))
                    cols = max(2, min(500, int(data['columns'])))
                    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
        finally:
            stop.set()
            stop_child(process)
            with JOBS_LOCK:
                JOBS.pop('pty:' + job, None)
            with contextlib.suppress(OSError):
                os.close(master)
            reader.join(timeout=1)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def shutdown_signal(_signal, _frame):
    raise SystemExit(0)


def main():
    global TOKEN, CATALOG, BASE, WORKSPACES, BIN
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--token-file')
    parser.add_argument('--catalog', default=str(BASE / 'servers.json'))
    parser.add_argument('--base', default=str(BASE))
    parser.add_argument('--install-special', choices=('kotlin', 'jdtls', 'phpactor', 'solargraph'))
    parser.add_argument('--no-github-proxy', action='store_true')
    args = parser.parse_args()
    BASE = Path(args.base).resolve()
    BIN = BASE / 'bin'
    if args.install_special:
        try:
            return install_special(args.install_special, not args.no_github_proxy)
        except Exception as error:
            parser.exit(1, str(error) + '\n')
    if not args.token_file:
        parser.error('--token-file is required')
    TOKEN = Path(args.token_file).read_text().strip()
    if len(TOKEN) < 32:
        parser.error('Token must contain at least 32 characters')
    WORKSPACES = BASE / 'workspaces'
    BASE.mkdir(parents=True, exist_ok=True)
    WORKSPACES.mkdir(exist_ok=True)
    CATALOG = {v['id']: v for v in json.loads(Path(args.catalog).read_text())}
    signal.signal(signal.SIGTERM, shutdown_signal)
    try:
        with Server(('127.0.0.1', args.port), Handler) as server:
            print('Aster bridge ready on loopback port ' + str(server.server_address[1]), flush=True)
            server.serve_forever(poll_interval=0.5)
    finally:
        stop_runtime()


if __name__ == '__main__':
    sys.exit(main())
