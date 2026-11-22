"""Cargo configuration shared by the Android Codex builds."""
import json
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import subprocess


RUST_TOOLCHAIN = '1.95.0'
ANDROID_TARGET = 'aarch64-linux-android'
DEFAULT_REGISTRY = 'sparse+https://rsproxy.cn/index/'
DEFAULT_GITHUB_PROXY = 'https://gh-proxy.org/https://github.com/'


def open_project_lock(root, name, nonblocking=False):
    lock_root = Path(os.environ.get('TMPDIR', '/tmp')) / 'aster-build-locks'
    project_id = hashlib.sha256(str(Path(root).resolve()).encode()).hexdigest()[:16]
    directory = lock_root / project_id
    directory.mkdir(parents=True, exist_ok=True)
    handle = (directory / name).open('a')
    flags = fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblocking else 0)
    try:
        fcntl.flock(handle.fileno(), flags)
    except BaseException:
        handle.close()
        raise
    return handle


def cargo_environment(cargo, ndk_bin, cargo_home, rustup_home, target_dir):
    env = dict(os.environ, CARGO_HOME=cargo_home, RUSTUP_HOME=rustup_home,
               CARGO_TARGET_DIR=str(target_dir), CARGO_PROFILE_RELEASE_LTO='false',
               CARGO_PROFILE_RELEASE_OPT_LEVEL='1', CARGO_PROFILE_RELEASE_CODEGEN_UNITS='16',
               CARGO_PROFILE_RELEASE_DEBUG='0')
    env['PATH'] = os.pathsep.join((str(ndk_bin), str(cargo.parent), env['PATH']))
    env.setdefault('RUSTUP_DIST_SERVER', 'https://rsproxy.cn')
    env.setdefault('CARGO_NET_GIT_FETCH_WITH_CLI', 'true')
    proxy = env.get('ASTER_CODEX_GITHUB_PROXY', DEFAULT_GITHUB_PROXY)
    if proxy != 'direct':
        index = int(env.get('GIT_CONFIG_COUNT', '0'))
        for source in ('https://github.com/', 'ssh://git@github.com/'):
            env[f'GIT_CONFIG_KEY_{index}'] = f'url.{proxy.rstrip("/")}/.insteadOf'
            env[f'GIT_CONFIG_VALUE_{index}'] = source
            index += 1
        env['GIT_CONFIG_COUNT'] = str(index)
    return env


def cargo_command(cargo, env):
    toolchain = env.get('ASTER_CODEX_RUST_TOOLCHAIN', RUST_TOOLCHAIN)
    if toolchain == RUST_TOOLCHAIN:
        return [str(cargo), f'+{RUST_TOOLCHAIN}']
    if toolchain != 'system':
        raise SystemExit(f'ASTER_CODEX_RUST_TOOLCHAIN must be {RUST_TOOLCHAIN} or system')

    rustc = shutil.which(env.get('RUSTC', 'rustc'), path=env['PATH'])
    if rustc is None:
        raise SystemExit('System Rust is not on PATH; install rust with apt on Termux')
    libdir = subprocess.check_output(
        [rustc, '--target', ANDROID_TARGET, '--print', 'target-libdir'],
        env=env, text=True,
    ).strip()
    if not any(Path(libdir).glob('libstd-*.rlib')):
        raise SystemExit(f'System Rust is missing the standard library for {ANDROID_TARGET}')
    version = subprocess.check_output([rustc, '-vV'], env=env, text=True)
    if '\nhost: ' + ANDROID_TARGET + '\n' in version and not env.get('PROTOC'):
        protoc = shutil.which('protoc', path=env['PATH'])
        if protoc is None:
            raise SystemExit('Android host needs protoc; install protobuf with apt or set PROTOC')
        env['PROTOC'] = protoc
    return [str(cargo)]


def cargo_registry_args(env):
    registry = env.get('ASTER_CODEX_CRATES_REGISTRY', DEFAULT_REGISTRY)
    if registry == 'crates-io':
        return []
    return ['--config', 'source.crates-io.replace-with="rsproxy"',
            '--config', f'source.rsproxy.registry={json.dumps(registry)}']
