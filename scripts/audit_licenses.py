"""Local provenance/notice inventory; never substitutes for a legal compliance review."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    'LICENSE', 'THIRD_PARTY_NOTICES.md', 'vendor/termux-app/LICENSE.md',
    'vendor/qingzhou/LICENSE', 'vendor/pi/LICENSE', 'vendor/codex/LICENSE',
    'research/acs-ui/ACS-LICENSE.txt', 'client/SOURCES.json',
    'client/browser/src/main/assets/licenses/browser-LICENSE.txt',
    'client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt',
    'agent/app/src/main/assets/agent/LUCIDE-LICENSE.txt',
    'core/app/src/main/assets/bridge/agent_vendor/LICENSES.txt',
    'core/app/src/main/assets/bridge/agent_vendor/PI-LICENSE.txt',
] + ['core/app/src/main/assets/licenses/' + name + '.txt' for name in
     ['GPL-2', 'GPL-3', 'LGPL-2.1', 'LGPL-3', 'Apache-2.0', 'Pi-MIT']]
PROVENANCE = ['research/pi-agent/SOURCE.json',
              'research/agent-engines/codex/SOURCE.json', 'research/agent-engines/gemini/SOURCE.json',
              'research/acs-ui/SOURCE.json', 'client/SOURCES.json']
RUNTIME_SOURCES = ['research/runtime-sources/proot-5.1.107.96.zip',
                   'research/runtime-sources/talloc-2.5.0.tar.gz',
                   'research/runtime-sources/libandroid-shmem-0.7.tar.gz']

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(data)
    return digest.hexdigest()

def inventory(apk=None):
    failures, files, provenance = [], [], {}
    for relative in REQUIRED + RUNTIME_SOURCES + PROVENANCE:
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
    # Validate the actual compiled browser inputs, not only a research reference.
    browser = ROOT / 'vendor/qingzhou/app/src/main/java/com/liulanqi/app'
    for name in ['BrowserEngine.kt', 'WebViewEngine.kt']:
        path = browser / name
        if not path.is_file():
            failures.append('Missing compiled AGPL source: ' + str(path.relative_to(ROOT)))
        else:
            files.append({'path': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size, 'sha256': sha(path)})
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
            for relative in REQUIRED:
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