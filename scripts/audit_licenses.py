"""Local provenance/notice inventory; never substitutes for a legal compliance review."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from prepare_native import BOOTSTRAP_ASSET, BOOTSTRAP_MANIFEST

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    'LICENSE', 'THIRD_PARTY_NOTICES.md', 'vendor/termux-app/LICENSE.md',
    'vendor/qingzhou/LICENSE', 'vendor/operit/LICENSE', 'vendor/pi/LICENSE', 'vendor/codex/LICENSE',
    'research/acs-ui/ACS-LICENSE.txt', 'client/SOURCES.json',
    'client/browser/src/main/assets/licenses/CLIENT-NOTICE.txt',
    'client/browser/src/main/assets/licenses/CLIENT-SOURCES.json',
    'client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt',
    'agent/app/src/main/assets/agent/LUCIDE-LICENSE.txt',
    'core/app/src/main/assets/bridge/agent_vendor/LICENSES.txt',
    'core/app/src/main/assets/bridge/agent_vendor/PI-LICENSE.txt',
] + ['core/app/src/main/assets/licenses/' + name + '.txt' for name in
     ['GPL-2', 'GPL-3', 'LGPL-2.1', 'LGPL-3', 'Apache-2.0', 'Pi-MIT']]
PROVENANCE = [BOOTSTRAP_MANIFEST, 'research/pi-agent/SOURCE.json',
              'research/agent-engines/codex/SOURCE.json', 'research/agent-engines/gemini/SOURCE.json',
              'research/acs-ui/SOURCE.json', 'client/SOURCES.json']
LEGACY_RUNTIME_LIBRARIES = {
    'libaster_proot.so', 'libaster_loader.so', 'libaster_loader32.so', 'libaster_redirect.so',
    'libtalloc.so', 'libandroid-shmem.so',
}

def stream_sha(stream):
    digest = hashlib.sha256()
    for data in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(data)
    return digest.hexdigest()

def sha(path):
    with path.open('rb') as stream:
        return stream_sha(stream)

def inventory(apk=None):
    failures, files, provenance = [], [], {}
    for relative in REQUIRED + PROVENANCE:
        path = ROOT / relative
        if not path.is_file() or not path.stat().st_size:
            failures.append('Missing or empty required source/notice: ' + relative)
        else:
            files.append({'path': relative, 'bytes': path.stat().st_size, 'sha256': sha(path)})
    for relative in PROVENANCE:
        try:
            provenance[relative] = json.loads((ROOT / relative).read_text())
        except (OSError, ValueError) as error:
            failures.append('Invalid provenance ' + relative + ': ' + str(error))
    ui_manifest = ROOT / 'agent/design/UI-BUNDLE.json'
    try:
        manifest = json.loads(ui_manifest.read_text())
        if manifest['sha256'] != sha(ROOT / 'agent/app/src/main/assets/agent/app.bundle.js'):
            failures.append('UI output hash is stale')
        for relative, expected in manifest['inputs'].items():
            if sha(ROOT / relative) != expected:
                failures.append('UI input hash is stale: ' + relative)
    except (OSError, ValueError, KeyError) as error:
        failures.append('Cannot validate UI manifest: ' + str(error))
    apk_result = None
    if apk:
        apk_result = {'path': str(apk), 'sha256': sha(apk), 'checkedAssets': []}
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
            legacy = sorted({Path(entry).name for entry in entries if entry.startswith('lib/')} & LEGACY_RUNTIME_LIBRARIES)
            if legacy:
                failures.append('Obsolete PRoot libraries in APK: ' + ', '.join(legacy))
            if 'lib/arm64-v8a/libaster_runtime.so' not in entries:
                failures.append('Missing native Termux path compatibility library')
            try:
                with archive.open(BOOTSTRAP_ASSET) as stream:
                    expected = provenance[BOOTSTRAP_MANIFEST]['bootstrap']['aarch64']
                    if stream_sha(stream) != expected:
                        failures.append('APK bootstrap differs from the pinned Termux archive')
                apk_result['checkedAssets'].append(BOOTSTRAP_ASSET)
            except KeyError as error:
                failures.append('Missing APK bootstrap or checksum: ' + str(error))
            for relative in REQUIRED + [BOOTSTRAP_MANIFEST]:
                marker = '/src/main/assets/'
                if marker not in relative:
                    continue
                packaged = 'assets/' + relative.split(marker, 1)[1]
                try:
                    actual = hashlib.sha256(archive.read(packaged)).hexdigest()
                    if actual != sha(ROOT / relative):
                        failures.append('APK asset differs: ' + packaged)
                    apk_result['checkedAssets'].append(packaged)
                except KeyError:
                    failures.append('Missing APK notice: ' + packaged)
    return {'schema': 1, 'technicalChecksPassed': not failures, 'legalClearance': False,
            'limitations': ['Not an exhaustive dependency license inventory.',
                            'Source availability and preferred-form completeness require manual review.',
                            'GPLv3/AGPLv3 section 13 permits combination under its conditions; presence of AGPL is not itself incompatibility.',
                            'No permission to relicense third-party components is implied.'],
            'failures': failures, 'files': files, 'provenance': provenance, 'apk': apk_result}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inventory(args.apk)
    output = json.dumps(result, indent=2, ensure_ascii=False) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
        print(json.dumps({'technicalChecksPassed': result['technicalChecksPassed'],
                          'legalClearance': False, 'files': len(result['files']),
                          'failures': result['failures'], 'output': str(args.output)}, ensure_ascii=False))
    else:
        print(output)
    raise SystemExit(0 if result['technicalChecksPassed'] else 1)

if __name__ == '__main__':
    main()
