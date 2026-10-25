"""Build-time headers/libs from the exact Termux bootstrap already shipped by Aster.

Nothing from this sysroot is copied into the APK. Native agents resolve these
shared libraries from the existing Termux PREFIX at runtime.
"""
from pathlib import Path, PurePosixPath
import hashlib
import json
import re
import subprocess
import zipfile


def prepare_openssl(root, readelf='readelf'):
    from prepare_native import prepare_bootstrap
    archive = prepare_bootstrap()
    checksums = json.loads((root / 'core/termux-runtime/src/main/assets/runtime/checksums.json').read_text())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != checksums['bootstrap']['aarch64']:
        raise ValueError('Termux bootstrap checksum mismatch')
    prefix = root / 'build/termux-agent-sysroot/usr'
    files = {}
    with zipfile.ZipFile(archive) as source:
        status = source.read('var/lib/dpkg/status').decode()
        package = next(p for p in status.split('\n\n') if p.startswith('Package: openssl\n'))
        version = re.search(r'^Version: (.+)$', package, re.M).group(1)
        if 'Architecture: aarch64\n' not in package:
            raise ValueError('Termux OpenSSL is not aarch64')
        for name in source.namelist():
            if name.endswith('/'):
                continue
            if not (name.startswith('include/openssl/') or name in (
                'lib/libssl.so.3', 'lib/libcrypto.so.3', 'etc/tls/cert.pem', 'etc/tls/openssl.cnf'
            )):
                continue
            relative = PurePosixPath(name)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid bootstrap path')
            data = source.read(name)
            destination = prefix / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.is_file() or destination.read_bytes() != data:
                destination.write_bytes(data)
            files[name] = hashlib.sha256(data).hexdigest()
        links = dict(line.split('\u2190', 1)[::-1] for line in source.read('SYMLINKS.txt').decode().splitlines() if line)
        for name in ('libssl', 'libcrypto'):
            if links.get('./lib/' + name + '.so') != name + '.so.3':
                raise ValueError('Unexpected Termux OpenSSL library alias')
            target = prefix / 'lib' / (name + '.so')
            if target.is_symlink():
                if target.readlink() == Path(name + '.so.3'):
                    continue
                target.unlink()
            elif target.exists():
                raise ValueError('Unexpected file in build-time sysroot: ' + str(target))
            target.symlink_to(name + '.so.3')
    libraries = {}
    for name in ('libssl.so.3', 'libcrypto.so.3'):
        file = prefix / 'lib' / name
        details = subprocess.check_output([str(readelf), '-h', '-d', str(file)], text=True)
        if 'AArch64' not in details or not re.search(r'\(SONAME\).*\[' + re.escape(name) + r'\]', details):
            raise ValueError('Invalid Android OpenSSL ELF: ' + name)
        needed = re.findall(r'\(NEEDED\).*\[([^\]]+)\]', details)
        if set(needed) - {'libssl.so.3', 'libcrypto.so.3', 'libc.so', 'libdl.so', 'libm.so'}:
            raise ValueError('Unexpected OpenSSL shared dependencies: ' + repr(needed))
        libraries[name] = {'sha256': files['lib/' + name], 'needed': needed}
    receipt = {
        'source': str(archive.relative_to(root)), 'bootstrapSha256': digest,
        'package': 'openssl', 'version': version, 'architecture': 'aarch64',
        'linkage': 'dynamic', 'runtimePrefix': '/data/data/com.termux/files/usr',
        'extraApkPayload': False, 'libraries': libraries, 'files': files,
    }
    (prefix.parent / 'OPENSSL-SOURCE.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return prefix, receipt


def openssl_environment(prefix):
    # openssl-sys gives target-specific settings precedence over host settings.
    target = 'AARCH64_LINUX_ANDROID_'
    return {target + key: value for key, value in {
        'OPENSSL_DIR': str(prefix), 'OPENSSL_LIB_DIR': str(prefix / 'lib'),
        'OPENSSL_INCLUDE_DIR': str(prefix / 'include'), 'OPENSSL_STATIC': '0',
        'OPENSSL_NO_VENDOR': '1', 'OPENSSL_LIBS': 'ssl:crypto',
    }.items()}


def build_probe(root, prefix, compiler, readelf='readelf'):
    directory = root / 'build/termux-agent-sysroot'
    source = directory / 'openssl-probe.c'
    source.write_text('''#include <openssl/ssl.h>
#include <openssl/x509_vfy.h>
#include <stdio.h>
int main(int argc, char **argv) {
    SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
    if (!ctx) return 2;
    printf("version=%s\\n", OpenSSL_version(OPENSSL_VERSION));
    printf("default_ca=%s\\n", X509_get_default_cert_file());
    printf("openssl_dir=%s\\n", OpenSSL_version(OPENSSL_DIR));
    int loaded = argc == 2 ? SSL_CTX_load_verify_locations(ctx, argv[1], NULL)
                          : SSL_CTX_set_default_verify_paths(ctx);
    printf("ca_loaded=%d\\n", loaded);
    SSL_CTX_free(ctx);
    return loaded == 1 ? 0 : 3;
}
''')
    binary = directory / 'openssl-probe'
    subprocess.run([str(compiler), str(source), '-I' + str(prefix / 'include'),
                    '-L' + str(prefix / 'lib'), '-lssl', '-lcrypto', '-o', str(binary)], check=True)
    details = subprocess.check_output([str(readelf), '-h', '-l', '-d', str(binary)], text=True)
    if 'AArch64' not in details or '/system/bin/linker64' not in details:
        raise ValueError('Probe is not an Android executable')
    for name in ('libssl.so.3', 'libcrypto.so.3'):
        if not re.search(r'\(NEEDED\).*\[' + re.escape(name) + r'\]', details):
            raise ValueError('Probe did not dynamically link ' + name)
    (directory / 'openssl-probe.elf.txt').write_text(details)
    return binary
