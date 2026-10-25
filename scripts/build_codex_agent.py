"""Build only the upstream Codex app-server, never its TUI, for Android/Termux."""
from pathlib import Path
import argparse
import hashlib
import fcntl
import json
import os
import shutil
import subprocess
import re
from termux_agent_sysroot import prepare_openssl, openssl_environment, build_probe
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', default=os.environ.get('ANDROID_NDK_HOME', str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android'))) / 'ndk/29.0.14206865')))
    parser.add_argument('--cargo-home', default=os.environ.get('CARGO_HOME', str(Path.home() / ('.cache/aster-agent/cargo' if (Path.home() / '.cache/aster-agent/cargo').exists() else '.cargo'))))
    parser.add_argument('--rustup-home', default=os.environ.get('RUSTUP_HOME', str(Path.home() / ('.cache/aster-agent/rustup' if (Path.home() / '.cache/aster-agent/rustup').exists() else '.rustup'))))
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--api', type=int, default=28)
    parser.add_argument('--prepare-only', action='store_true', help='Verify shared Termux dependencies and compile the Android TLS probe')
    parser.add_argument('--update-lock', action='store_true')
    args = parser.parse_args()
    (ROOT / 'build').mkdir(exist_ok=True)
    build_lock = (ROOT / 'build/codex-android.lock').open('a')
    try:
        fcntl.flock(build_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('A Codex Android build is already running; inspect logs/codex-android-build.json')
    source = ROOT / 'vendor/codex/codex-rs'
    prebuilts = Path(args.ndk) / 'toolchains/llvm/prebuilt'
    toolchain = next((p / 'bin' for p in prebuilts.iterdir() if (p / 'bin/llvm-ar').is_file()), None)
    if toolchain is None: raise RuntimeError('No host-compatible LLVM toolchain in ' + str(prebuilts))
    cargo = Path(shutil.which('cargo') or str(Path(args.cargo_home) / 'bin/cargo'))
    target = Path(os.environ.get('ASTER_CODEX_TARGET_DIR', str(ROOT / 'build/codex-android')))
    env = dict(os.environ, CARGO_HOME=args.cargo_home, RUSTUP_HOME=args.rustup_home,
               CARGO_TARGET_DIR=str(target), RUSTUP_DIST_SERVER='https://rsproxy.cn',
               CARGO_NET_GIT_FETCH_WITH_CLI='true', CARGO_PROFILE_RELEASE_LTO='false',
               CARGO_PROFILE_RELEASE_OPT_LEVEL='1', CARGO_PROFILE_RELEASE_CODEGEN_UNITS='16',
               CARGO_PROFILE_RELEASE_DEBUG='0', GIT_CONFIG_COUNT='2',
               GIT_CONFIG_KEY_0='url.https://gh-proxy.org/https://github.com/.insteadOf',
               GIT_CONFIG_VALUE_0='https://github.com/',
               GIT_CONFIG_KEY_1='url.https://gh-proxy.org/https://github.com/.insteadOf',
               GIT_CONFIG_VALUE_1='ssh://git@github.com/')
    env['PATH'] = str(toolchain) + os.pathsep + str(cargo.parent) + os.pathsep + env['PATH']
    env['ANDROID_NDK_HOME'] = args.ndk
    env['ANDROID_NDK_ROOT'] = args.ndk
    prefix, dependency = prepare_openssl(ROOT)
    env.update(openssl_environment(prefix))
    env['CC_aarch64_linux_android'] = str(toolchain / f'aarch64-linux-android{args.api}-clang')
    env['CXX_aarch64_linux_android'] = str(toolchain / f'aarch64-linux-android{args.api}-clang++')
    env['AR_aarch64_linux_android'] = str(toolchain / 'llvm-ar')
    env['CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER'] = env['CC_aarch64_linux_android']
    probe = build_probe(ROOT, prefix, env['CC_aarch64_linux_android'])
    dependency_receipt = {key: value for key, value in dependency.items() if key != 'files'}
    if args.prepare_only:
        print(json.dumps({'status': 'prepared', 'probe': str(probe.relative_to(ROOT)), 'openssl': dependency_receipt}), flush=True)
        return
    command = [str(cargo), '+1.95.0', 'build', '--locked', '--release', '-p', 'codex-app-server',
               '--bin', 'codex-app-server', '--target', 'aarch64-linux-android', '-j', str(args.jobs),
               '--config', 'source.crates-io.replace-with="rsproxy"',
               '--config', 'source.rsproxy.registry="sparse+https://rsproxy.cn/index/"']
    if args.update_lock:
        command.remove('--locked')
    log = ROOT / 'logs/codex-android-build.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    receipt_path = ROOT / 'logs/codex-android-build.json'
    receipt = {'status': 'running', 'pid': os.getpid(), 'target': 'aarch64-linux-android',
               'androidApi': args.api, 'tui': False, 'log': str(log.relative_to(ROOT)), 'openssl': dependency_receipt}
    def save_receipt():
        temporary = receipt_path.with_suffix('.tmp')
        temporary.write_text(json.dumps(receipt, indent=2) + chr(10))
        temporary.replace(receipt_path)
    save_receipt()
    try:
        with log.open('w') as output:
            result = subprocess.run(command, cwd=source, env=env, stdout=output, stderr=subprocess.STDOUT)
        receipt.update(status='failed', exitCode=result.returncode)
        if result.returncode == 0:
            binary = target / 'aarch64-linux-android/release/codex-app-server'
            header = subprocess.check_output(['readelf', '-h', '-l', '-d', str(binary)], text=True)
            if 'AArch64' not in header or '/system/bin/linker64' not in header:
                raise ValueError('Not an Android executable')
            needed = re.findall(r'\(NEEDED\).*\[([^\]]+)\]', header)
            if not {'libssl.so.3', 'libcrypto.so.3'}.issubset(needed):
                raise ValueError('Codex must reuse shared Termux OpenSSL')
            dest = ROOT / 'build/native/codex/arm64-v8a/libaster_codex.so'
            dest.parent.mkdir(parents=True, exist_ok=True)
            temporary = dest.with_suffix('.tmp')
            shutil.copyfile(binary, temporary)
            subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', str(temporary)], check=True)
            temporary.chmod(0o755)
            temporary.replace(dest)
            receipt.update(status='succeeded', binary=str(dest.relative_to(ROOT)), needed=needed,
                           sha256=hashlib.sha256(dest.read_bytes()).hexdigest())
    except Exception as error:
        receipt.update(status='failed', error=str(error))
        raise
    finally:
        save_receipt()
    print(json.dumps(receipt), flush=True)
    raise SystemExit(result.returncode)

if __name__ == '__main__':
    main()
