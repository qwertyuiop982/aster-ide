"""ARM64 Android runtime smoke test using a verified bootstrap and local packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'core/termux-runtime/src/main'
OLD = '/data/data/com.termux/files'
USER_TERMUX = '/data/user/10/com.termux'
TERMUX_EXEC_VERSION = '1:2.5.0-1'
TERMUX_EXEC_URL = ('https://packages.termux.dev/apt/termux-main/pool/main/t/termux-exec/'
                   'termux-exec_1%3A2.5.0-1_aarch64.deb')
TERMUX_EXEC_SHA256 = 'fd6a06a6a74c6fba2d1cec56dc8b5726e6488918322b06fd19fe5cff34b28a5d'


def verify(path, checksum):
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise ValueError(f'SHA-256 mismatch: {path}')


def unpack(archive, files):
    record = json.loads((RUNTIME / 'assets/runtime/checksums.json').read_text())
    verify(archive, record['bootstrap']['aarch64'])
    prefix = files / 'usr'
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if entry.filename == 'SYMLINKS.txt':
                continue
            path = prefix / entry.filename
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            contents = z.read(entry)
            path.write_bytes(contents)
            executable = (entry.filename.startswith(('bin/', 'libexec/')) or
                          contents.startswith((b'\x7fELF', b'#!')))
            path.chmod(0o700 if executable else 0o600)
        for line in z.read('SYMLINKS.txt').decode().splitlines():
            old_target, name = line.split('\u2190', 1)
            target = str(files) + old_target[len(OLD):] if old_target.startswith(OLD + '/') else old_target
            link = prefix / name
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(target)
    return prefix


def run(args, cwd, environment):
    result = subprocess.run(args, cwd=cwd, env=environment, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(str(args) + '\n' + result.stdout + result.stderr)
    return result.stdout.strip()


def probe_termux_exec(options, area, prefix, home, package_root, env):
    deb = area / 'termux-exec.deb'
    if options.termux_exec_deb is None:
        with urlopen(TERMUX_EXEC_URL, timeout=60) as response:
            deb.write_bytes(response.read())
    else:
        # The preload shim can redirect a package path in the host Termux directory.
        shutil.copyfile(options.termux_exec_deb, deb)
    verify(deb, TERMUX_EXEC_SHA256)

    dpkg = [str(prefix / 'bin/dpkg'), f'--instdir={package_root}', '--force-script-chrootless']
    query = [str(prefix / 'bin/dpkg-query'), '-W', '-f=${Status} ${Version}', 'termux-exec']
    lib = prefix / 'lib'
    primary = lib / 'libtermux-exec-ld-preload.so'
    run([*dpkg, '--unpack', str(deb)], home, env)
    assert run(query, home, env) == f'install ok unpacked {TERMUX_EXEC_VERSION}'

    # This alias is mapped only while the shim is loaded, and is never a host path.
    # The real Termux helper unsets LD_PRELOAD before calling cp on both paths.
    legacy_prefix = f'{package_root}{OLD}/usr'
    failed = subprocess.run([*dpkg, '--configure', '-a'], cwd=home,
                            env=dict(env, TERMUX__PREFIX=legacy_prefix),
                            text=True, capture_output=True, timeout=60)
    assert failed.returncode != 0 and 'cannot stat' in failed.stderr and legacy_prefix in failed.stderr, (
        failed.stdout, failed.stderr)
    assert run(query, home, env) == f'install ok half-configured {TERMUX_EXEC_VERSION}'
    assert not primary.exists()

    run([*dpkg, '--configure', '-a'], home, env)
    assert run(query, home, env) == f'install ok installed {TERMUX_EXEC_VERSION}'
    assert primary.read_bytes() == (lib / 'libtermux-exec-direct-ld-preload.so').read_bytes()

    selector = str(prefix / 'bin/termux-exec-system-linker-exec')
    setup = str(prefix / 'bin/termux-exec-ld-preload-lib')
    for sdk, enabled, variant in (('24', 'false', 'direct'), ('29', 'true', 'linker')):
        # Quiet mode keeps v2.5.0's selector diagnostics out of its stdout result.
        sdk_env = dict(env, ANDROID__BUILD_VERSION_SDK=sdk, TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE='force')
        assert run([selector, 'is-enabled', '-q'], home, sdk_env) == enabled
        run([setup, 'setup', '-q'], home, sdk_env)
        assert primary.read_bytes() == (lib / f'libtermux-exec-{variant}-ld-preload.so').read_bytes()
    # The deliberately failed postinst can leave empty directories at the bad prefix.
    legacy_files = [
        path for path in (package_root / OLD.lstrip('/')).rglob('*')
        if not path.is_dir() or path.is_symlink()
    ]
    assert not legacy_files, legacy_files
    print(f'Official termux-exec {TERMUX_EXEC_VERSION} recovered from half-configured; '
          'SDK 24/29 selector and library setup passed.')


def probe(options, area):
    files = area / 'files'
    prefix = unpack(options.bootstrap, files)
    home, tmp, package_root = files / 'home', prefix / 'tmp', area / 'packages'
    project = area / 'real project'
    cache = area / 'cache'
    for directory in (home, tmp, package_root, project, cache / 'apt/archives/partial'):
        directory.mkdir(exist_ok=True, parents=True)
    shim = area / 'libaster_runtime.so'
    if options.shim is not None:
        # Android workspace mounts can be noexec even for an otherwise valid ELF.
        shutil.copyfile(options.shim, shim)
    else:
        subprocess.run([options.cc, '-shared', '-fPIC', '-std=gnu11', '-O2', '-Wall', '-Wextra', '-Werror',
                        '-Wl,-z,max-page-size=16384', str(RUNTIME / 'cpp/path_redirect.c'), '-ldl', '-o', str(shim)],
                       check=True, timeout=60)
    bash = str(prefix / 'bin/bash')
    env = dict(HOME=str(home), PREFIX=str(prefix), TERMUX__PREFIX=str(prefix), TMPDIR=str(tmp),
               PATH=f'{prefix}/bin:/system/bin', ANDROID_DATA='/data', ANDROID_ROOT='/system',
               ANDROID__BUILD_VERSION_SDK='24', LANG='C.UTF-8',
               ASTER_RUNTIME_ROOT=str(area), ASTER_PRIVATE_FILES=str(files), ASTER_APT_ROOT=str(package_root),
               ASTER_PRIVATE_CACHE=str(cache), DPKG_ADMINDIR=str(prefix / 'var/lib/dpkg'),
               LD_LIBRARY_PATH=str(prefix / 'lib'),
               LD_PRELOAD=str(shim), DEBIAN_FRONTEND='noninteractive')

    current = run([bash, '-c', 'pwd -P'], project, env)
    assert os.path.samefile(current, project), (current, str(project.resolve()))
    # Prefix writes with the fixture's dpkg root so a missing shim cannot touch real Termux data.
    assert run([bash, '-c', f'printf mapped > "$ASTER_APT_ROOT{OLD}/home/output" && cat "{OLD}/home/output"'],
               project, env) == 'mapped'
    assert (home / 'output').read_text() == 'mapped'
    assert run([bash, '-c', f'printf user-mapped > "$ASTER_APT_ROOT{USER_TERMUX}/files/home/user-output" '
               f'&& cat "{OLD}/home/user-output"'], project, env) == 'user-mapped'
    assert (home / 'user-output').read_text() == 'user-mapped'
    (cache / 'redirect-probe').write_text('cache-mapped')
    assert run([bash, '-c', f'cat "{USER_TERMUX}/cache/redirect-probe"'], project, env) == 'cache-mapped'

    script = project / 'fixture'
    script.write_text(f'#!{OLD}/usr/bin/bash\nreadlink /proc/$$/exe; echo "$1"\n')
    script.chmod(0o700)
    output = run([bash, '-c', './fixture argument'], project, env).splitlines()
    assert len(output) == 2 and os.path.samefile(output[0], prefix / 'bin/bash') and output[1] == 'argument', output

    user_script = project / 'user-fixture'
    user_script.write_text(f'#!{USER_TERMUX}/files/usr/bin/bash\nreadlink /proc/$$/exe; echo "$1"\n')
    user_script.chmod(0o700)
    output = run([bash, '-c', './user-fixture user-argument'], project, env).splitlines()
    assert len(output) == 2 and os.path.samefile(output[0], prefix / 'bin/bash') and output[1] == 'user-argument', output

    package = area / 'deb'
    control = package / 'DEBIAN'
    control.mkdir(parents=True)
    control.chmod(0o755)
    (control / 'control').write_text('Package: aster-runtime-fixture\nVersion: 1.0\nArchitecture: all\n'
                                    'Maintainer: Test <test@example.invalid>\nDescription: Local relocation test\n')
    postinst = control / 'postinst'
    postinst.write_text(f'#!{OLD}/usr/bin/sh\nprintf installed > "$DPKG_ROOT{OLD}/usr/share/aster-fixture/installed"\n')
    postinst.chmod(0o755)
    payload = package / OLD.lstrip('/') / 'usr/share/aster-fixture'
    payload.mkdir(parents=True)
    (payload / 'value').write_text('fixture')
    (payload / 'link').symlink_to(OLD + '/usr/share/aster-fixture/value')
    deb = area / 'fixture.deb'
    subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(package), str(deb)], check=True, timeout=30)
    apt_config = prefix / 'etc/apt/aster-runtime.conf'
    apt_config.write_text(f'Dir::Etc "{prefix}/etc/apt";\nDir::State "{prefix}/var/lib/apt";\n'
                          f'Dir::State::status "{prefix}/var/lib/dpkg/status";\nDir::Cache "{cache}/apt";\n'
                          f'Dir::Bin::methods "{prefix}/lib/apt/methods";\nDir::Bin::dpkg "{prefix}/bin/dpkg";\n'
                          f'Dir::Log "{prefix}/var/log/apt";\nAPT::Sandbox::User "root";\n'
                          f'DPkg::Path "{prefix}/bin:/system/bin";\n'
                          f'DPkg::Options {{ "--instdir={package_root}"; "--force-script-chrootless"; }};\n')
    env.update(APT_CONFIG=str(apt_config))
    (prefix / 'etc/apt/sources.list').write_text('')
    apt = str(prefix / 'bin/apt-get')
    run([apt, '-y', 'install', str(deb)], home, env)
    installed = prefix / 'share/aster-fixture'
    assert (installed / 'value').read_text() == 'fixture'
    assert (installed / 'link').read_text() == 'fixture'
    assert (installed / 'installed').read_text() == 'installed'
    assert not (package_root / OLD.lstrip('/')).exists()
    run([apt, '-y', 'remove', 'aster-runtime-fixture'], home, env)
    assert not (installed / 'value').exists()
    print('Native working directory, Termux path mapping, shebang, and local apt/dpkg install/remove passed.')
    probe_termux_exec(options, area, prefix, home, package_root, env)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bootstrap', type=Path, required=True)
    parser.add_argument('--shim', type=lambda value: Path(value).resolve(strict=True),
                        help='use an already built ARM64 libaster_runtime.so instead of compiling it')
    parser.add_argument('--termux-exec-deb', type=lambda value: Path(value).resolve(),
                        help=f'offline copy of the official aarch64 {TERMUX_EXEC_VERSION} package')
    parser.add_argument('--cc', default='clang')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='aster-native-') as temporary:
        probe(args, Path(temporary).resolve())
