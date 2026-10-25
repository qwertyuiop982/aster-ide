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
CATALOG = {}
TOKEN = ''
BASE = Path.home() / '.aster'
WORKSPACES = BASE / 'workspaces'
BIN = BASE / 'bin'
LIMIT = threading.BoundedSemaphore(24)
PROCESSES = threading.BoundedSemaphore(8)
sys.path.insert(0, str(Path(__file__).resolve().parent))


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


def workspace(identifier):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', str(identifier)):
        raise ValueError('Invalid workspace ID')
    root = WORKSPACES / str(identifier)
    root.mkdir(parents=True, exist_ok=True)
    return root.resolve()


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
    sdk = options.get('sdk') or str(Path.home() / 'android-sdk')
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
        process = subprocess.Popen(argv, cwd=root, env=environment(options), stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        with JOBS_LOCK:
            control.process = process
        if control.cancelled.is_set():
            terminate(process)
        decode = codecs.getincrementaldecoder('utf-8')('replace')
        while True:
            chunk = process.stdout.read1(8192)
            if not chunk:
                break
            packet(out, {'type': 'output', 'text': decode.decode(chunk)})
        tail = decode.decode(b'', final=True)
        if tail:
            packet(out, {'type': 'output', 'text': tail})
        return process.wait()
    finally:
        if process is not None:
            terminate(process)
            process.stdout.close()
        with JOBS_LOCK:
            if control.process is process:
                control.process = None
            if own_job and JOBS.get(job_id) is control:
                JOBS.pop(job_id, None)


def fetch(url, destination):
    req = urllib.request.Request(url, headers={'User-Agent': 'AsterIDE/0.1'})
    with urllib.request.urlopen(req, timeout=45) as response, destination.open('wb') as out:
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
    with urllib.request.urlopen(req, timeout=35) as response:
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


def install_special(kind, out, job_id, options):
    proxy = options.get('proxy', True)
    dest = BASE / 'servers' / kind
    dest.mkdir(parents=True, exist_ok=True)
    packet(out, {'type': 'output', 'text': f'\nDownloading {kind} from upstream…\n'})
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
        code = stream_process(['gem', 'install', 'solargraph', '--no-document'], BASE, out, job_id, options)
        if code:
            return code
    return 0


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
        else:
            item = CATALOG.get(server_id)
            if not item:
                raise ValueError('Unknown server')
        apt = shutil.which('apt-get')
        if not apt or not shutil.which('pkg') or not os.environ.get('PREFIX'):
            raise RuntimeError('Installation must run inside Termux')
        mirror = options.get('mirror', 'ustc')
        if mirror in ('ustc', 'tsinghua'):
            prefix = Path(os.environ['PREFIX'])
            sources = prefix / 'etc/apt/sources.list'
            backup = sources.with_suffix('.list.aster-backup')
            if sources.exists() and not backup.exists():
                shutil.copyfile(sources, backup)
            temporary = sources.with_suffix('.list.aster-tmp')
            temporary.write_text('deb https://mirrors.ustc.edu.cn/termux/termux-main stable main\n')
            temporary.replace(sources)
        # pkg probes/randomizes mirrors; use the selected source consistently for all installs.
        apt_options = ['-o', 'Acquire::Retries=1', '-o', 'Acquire::http::Timeout=15', '-o', 'Acquire::https::Timeout=15']
        code = stream_process([apt] + apt_options + ['-o', 'APT::Update::Error-Mode=any', 'update'], BASE, out, job_id, options)
        if code:
            return code
        if server_id in ('base', 'agent'):
            code = stream_process([apt] + apt_options + ['-y', '-o', 'DPkg::Lock::Timeout=30', '-o', 'Dpkg::Options::=--force-confold', 'upgrade'], BASE, out, job_id, options)
            if code:
                return code
        code = stream_process([apt] + apt_options + ['-y', '-o', 'DPkg::Lock::Timeout=30', 'install'] + item.get('packages', []), BASE, out, job_id, options)
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
            code = install_special(item['special'], out, job_id, options)
            if code:
                return code
        if server_id == 'android':
            arguments = ['python', str(BASE / 'setup_android.py'), '--sdk', options.get('sdk') or str(Path.home() / 'android-sdk'),
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
    path = (options or {}).get('sdk') or os.environ.get('ANDROID_HOME') or str(Path.home() / 'android-sdk')
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
                                    'tools': {k: shutil.which(k, path=env['PATH']) for k in ['python', 'clang', 'node', 'java', 'gradle', 'git', 'aapt2']}, "androidToolchain": toolchain_info(request.get("options"))})
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
            root = workspace(request.get('workspace', 'default'))
            if op in ('agent_decision', 'agent_cancel', 'agent_client_result'):
                import agent_runner
                accepted = agent_runner.control(request, cancel=op == 'agent_cancel')
                packet(self.wfile, {'ok': True, 'accepted': accepted})
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
                        agent_runner.handle(root, BASE, request, environment(request.get('options')), lambda event: packet(self.wfile, event), connection=self.request)
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
        except (BrokenPipeError, ConnectionResetError, EOFError):
            pass
        except Exception as e:
            with contextlib.suppress(Exception):
                packet(self.wfile, {'ok': False, 'type': 'error', 'error': str(e)[:2000]})
        finally:
            LIMIT.release()

    def lsp(self, request, root):
        command = resolve_command(request['server'], request.get('command'), root)
        process = subprocess.Popen(command, cwd=root, env=environment(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True, bufsize=0)
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
            terminate(process)
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
        process = subprocess.Popen([shell, '--noprofile', '--norc', '-i'], cwd=root, env=environment(),
                                   stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
        os.close(slave)
        with JOBS_LOCK:
            JOBS['pty:' + job] = master
        packet(self.wfile, {'ok': True, 'job': job})
        stop = threading.Event()
        def output():
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
            terminate(process)
            with JOBS_LOCK:
                JOBS.pop('pty:' + job, None)
            with contextlib.suppress(OSError):
                os.close(master)
            reader.join(timeout=1)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    global TOKEN, CATALOG, BASE, WORKSPACES, BIN
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8768)
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--catalog', default=str(BASE / 'servers.json'))
    parser.add_argument('--base', default=str(BASE))
    args = parser.parse_args()
    TOKEN = Path(args.token_file).read_text().strip()
    if len(TOKEN) < 32:
        parser.error('Token must contain at least 32 characters')
    BASE = Path(args.base).resolve()
    WORKSPACES = BASE / 'workspaces'
    BIN = BASE / 'bin'
    BASE.mkdir(parents=True, exist_ok=True)
    WORKSPACES.mkdir(exist_ok=True)
    CATALOG = {v['id']: v for v in json.loads(Path(args.catalog).read_text())}
    with Server(('127.0.0.1', args.port), Handler) as server:
        print('Aster bridge ready on loopback port ' + str(args.port), flush=True)
        server.serve_forever(poll_interval=0.5)


if __name__ == '__main__':
    main()
