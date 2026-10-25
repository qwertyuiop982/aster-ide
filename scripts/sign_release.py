"""Sign an optimized, non-debuggable release with the current local certificate.
Set ASTER_RELEASE_KEYSTORE / ASTER_RELEASE_KEY_ALIAS and password environment
variables when using another local signing configuration. Keys never ship in source.
"""
import argparse
import copy
import hashlib
import json
import os
import shutil
import struct
import zipfile
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android'))) / 'build-tools/35.0.0'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--variant', choices=['release'], default='release')
parser.add_argument('--build-log', type=Path, default=ROOT / 'logs/release-apks-build.log')
parser.add_argument('--log-dir', type=Path, default=ROOT / 'logs')
args = parser.parse_args()
args.log_dir.mkdir(parents=True, exist_ok=True)
task = args.variant[0].upper() + args.variant[1:]
directory = ROOT / 'core/build/application/outputs/apk' / args.variant
metadata = json.loads((directory / 'output-metadata.json').read_text())
assert metadata['applicationId'] == 'dev.aster.ide' and len(metadata['elements']) == 1
element = metadata['elements'][0]
UNSIGNED = directory / element['outputFile']
assert UNSIGNED.name.endswith('-unsigned.apk'), 'Expected unsigned Gradle output'
ALIGNED = UNSIGNED.with_name('app-' + args.variant + '-aligned.apk')
SIGNED = UNSIGNED.with_name('app-' + args.variant + '.apk')
assert UNSIGNED.is_file()
build_log = args.build_log.read_text()
assert '> Task :app:assemble' + task in build_log and 'BUILD SUCCESSFUL' in build_log and 'BUILD FAILED' not in build_log, 'Release assembly must complete before signing'
mapping = ROOT / 'core/build/application/outputs/mapping' / args.variant / 'mapping.txt'
assert 'minify' + task + 'WithR8' in build_log and mapping.stat().st_size > 0
assert any(name + task + suffix in build_log for name, suffix in [('shrink', 'Res'), ('optimize', 'Resources')]), 'Resource shrinking task missing'
local_key = Path.home() / '.android/debug.keystore'
keystore = Path(os.environ.get('ASTER_RELEASE_KEYSTORE', str(local_key)))
assert keystore.is_file(), 'Local signing keystore is missing'
environment = dict(os.environ)
if keystore == local_key:
    environment.setdefault('ASTER_RELEASE_STORE_PASSWORD', 'android')
    environment.setdefault('ASTER_RELEASE_KEY_PASSWORD', 'android')
    alias = environment.get('ASTER_RELEASE_KEY_ALIAS', 'androiddebugkey')
else:
    assert environment.get('ASTER_RELEASE_STORE_PASSWORD') and environment.get('ASTER_RELEASE_KEY_PASSWORD') and environment.get('ASTER_RELEASE_KEY_ALIAS'), 'Supply the signing configuration through environment variables'
    alias = environment['ASTER_RELEASE_KEY_ALIAS']

def aligned(path):
    with path.open('rb') as raw, zipfile.ZipFile(path) as archive:
        for item in archive.infolist():
            if item.compress_type != zipfile.ZIP_STORED or item.is_dir():
                continue
            raw.seek(item.header_offset)
            header = struct.unpack('<4s5H3I2H', raw.read(30))
            if (item.header_offset + 30 + header[-2] + header[-1]) % 4:
                return False
    return True

# Gradle's APK writer normally aligns stored entries. Check that layout directly;
# this also works on ARM hosts whose SDK zipalign executable cannot be launched.
if aligned(UNSIGNED):
    shutil.copyfile(UNSIGNED, ALIGNED)
else:
    with zipfile.ZipFile(UNSIGNED) as source, zipfile.ZipFile(ALIGNED, 'w') as target:
        for item in source.infolist():
            entry = copy.copy(item)
            if entry.compress_type == zipfile.ZIP_STORED and not entry.is_dir():
                offset = target.fp.tell() + 30 + len(entry.filename.encode('utf-8')) + len(entry.extra)
                padding = (-offset) % 4
                if padding:
                    entry.extra += struct.pack('<HH', 0xD935, padding) + bytes(padding)
            with source.open(item) as input_stream, target.open(entry, 'w') as output_stream:
                shutil.copyfileobj(input_stream, output_stream, 512 * 1024)
assert aligned(ALIGNED), 'APK entries must be aligned before signing'
subprocess.run([str(TOOLS / 'apksigner'), 'sign', '--ks', str(keystore), '--ks-key-alias', alias,
                '--ks-pass', 'env:ASTER_RELEASE_STORE_PASSWORD', '--key-pass', 'env:ASTER_RELEASE_KEY_PASSWORD',
                '--out', str(SIGNED), str(ALIGNED)], check=True, capture_output=True, env=environment)
verification = subprocess.run([str(TOOLS / 'apksigner'), 'verify', '--verbose', '--print-certs', str(SIGNED)], capture_output=True, text=True, check=True)
(args.log_dir / ('release-' + args.variant + '-signature.log')).write_text(verification.stdout + verification.stderr)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in verification.stdout
certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', verification.stdout).group(1)
previous = ROOT / 'logs/installed-signing-certificate.txt'
if previous.exists():
    expected = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', previous.read_text()).group(1)
    assert expected == certificate, 'The signing certificate differs from the installed application'

badging = subprocess.run([str(TOOLS / 'aapt2'), 'dump', 'badging', str(SIGNED)], capture_output=True, text=True, check=True)
assert 'application-debuggable' not in badging.stdout, 'Release must not be debuggable'
assert "name='dev.aster.ide'" in badging.stdout
version_code = element['versionCode']
version = element['versionName']
assert f"versionCode='{version_code}'" in badging.stdout and f"versionName='{version}'" in badging.stdout
lines = mapping.read_text().splitlines()
header = 'dev.aster.agent.AgentJavascriptBridge -> dev.aster.agent.AgentJavascriptBridge:'
assert header in lines, 'R8 renamed or removed the native JavaScript bridge'
members = []
for line in lines[lines.index(header) + 1:]:
    if line and not line.startswith((' ', '#')):
        break
    members.append(line)
for method in ['bootstrapToken', 'postMessage', 'readUiState']:
    assert any(method + '(' in line and line.rstrip().endswith(' -> ' + method) for line in members), 'R8 removed bridge method: ' + method
(args.log_dir / ('release-' + args.variant + '-manifest.log')).write_text(badging.stdout)
assert aligned(SIGNED), 'Signing must preserve ZIP alignment'
digest = hashlib.sha256()
with SIGNED.open('rb') as stream:
    for chunk in iter(lambda: stream.read(512 * 1024), b''):
        digest.update(chunk)
facts = {'version': version, 'versionCode': version_code, 'buildType': args.variant, 'debuggable': False,
         'minified': True, 'resourcesShrunk': True, 'certificateSha256': certificate,
         'compatibleWithInstalledCertificate': previous.exists(), 'size': SIGNED.stat().st_size,
         'localDebugCertificateForUpgradeCompatibility': keystore == local_key,
         'javascriptBridgeRetained': True, 'mappingSha256': hashlib.sha256(mapping.read_bytes()).hexdigest(),
         'apk': str(SIGNED.relative_to(ROOT)), 'sha256': digest.hexdigest()}
(args.log_dir / ('release-' + args.variant + '-signing.json')).write_text(json.dumps(facts, indent=2))
ALIGNED.unlink()
print(json.dumps(facts, indent=2))
