"""Build Aster's pinned Pi host and record its upstream and dependency inputs."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RESEARCH = ROOT / 'research/pi-agent'
TARGET = ROOT / 'core/app/src/main/assets/bridge/agent_vendor'
VERSION = '1.3.0'
COMMIT = '71023aec1546d6d677a65669c5665c0927ce0dd0'
ARCHIVE_SHA256 = 'd520df6b84f8860901f25f601c439deca752251515333dcbe406b7de26ba4ac4'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--no-install', action='store_true')
    args = parser.parse_args()
    environment = dict(os.environ, NODE_OPTIONS='--max-old-space-size=384')
    if not args.no_install:
        subprocess.run(['npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund'], cwd=RESEARCH, env=environment, check=True)
    subprocess.run(['node', 'build.mjs'], cwd=RESEARCH, env=environment, check=True)
    upstream = ROOT / 'vendor/pi'
    assert json.loads((upstream / 'packages/coding-agent/package.json').read_text())['version'] == VERSION
    shutil.copyfile(upstream / 'LICENSE', TARGET / 'PI-LICENSE.txt')
    lock = json.loads((RESEARCH / 'package-lock.json').read_text())
    packages = []
    notices = [(upstream / 'LICENSE').read_text()]
    licenses = RESEARCH / 'licenses'
    licenses.mkdir(exist_ok=True)
    for name, metadata in lock['packages'].items():
        if not name or metadata.get('dev') or 'node_modules/@esbuild/' in name:
            continue
        folder = RESEARCH / name
        license_file = next((p for p in folder.iterdir() if p.name.lower().startswith(('license', 'licence'))), None)
        assert license_file, name + ': missing license'
        package_name = name.removeprefix('node_modules/')
        filename = package_name.replace('/', '_').replace('@', '') + '-LICENSE.txt'
        shutil.copyfile(license_file, licenses / filename)
        notices.append('\n\n' + package_name + ' ' + metadata['version'] + '\n\n' + license_file.read_text())
        packages.append({'name': package_name, 'version': metadata['version'], 'integrity': metadata['integrity'],
                         'licenseFile': filename, 'licenseSha256': digest(license_file)})
    (TARGET / 'LICENSES.txt').write_text('\n'.join(notices))
    inputs = json.loads((RESEARCH / 'bundle-inputs.json').read_text())
    upstream_inputs = {}
    for path in inputs:
        file = (RESEARCH / path).resolve()
        if upstream in file.parents:
            upstream_inputs[str(file.relative_to(upstream))] = digest(file)
    provenance = {
        'engine': 'Pi Coding Agent 1.3.0', 'package': '@earendil-works/pi-coding-agent', 'version': VERSION,
        'repository': 'https://github.com/earendil-works/pi', 'commit': COMMIT, 'license': 'MIT',
        'components': ['pi-agent-core agent loop', 'coding-agent SessionManager', 'coding-agent context compaction'],
        'platformAdapter': 'Aster authenticated model/project bridge; Termux environment and native Compose UI',
        'bundleSha256': digest(TARGET / 'pi-agent.mjs'), 'runnerSha256': digest(RESEARCH / 'runner.ts'),
        'lockSha256': digest(RESEARCH / 'package-lock.json'), 'buildScriptSha256': digest(Path(__file__)),
        'buildConfigurationSha256': digest(RESEARCH / 'build.mjs'),
        'sourceSnapshot': {'url': 'https://github.com/earendil-works/pi/archive/' + COMMIT + '.tar.gz', 'sha256': ARCHIVE_SHA256, 'retainedSource': 'vendor/pi'},
        'upstreamInputs': upstream_inputs, 'packages': packages,
        'nodeMinimum': '22.19.0', 'nativeDependencies': False,
    }
    text = json.dumps(provenance, ensure_ascii=False, indent=2) + '\n'
    (RESEARCH / 'SOURCE.json').write_text(text)
    (TARGET / 'SOURCE.json').write_text(text)
    (TARGET / 'PI-SOURCE.json').write_text(text)
    print(json.dumps({'engine': provenance['engine'], 'bundleBytes': (TARGET / 'pi-agent.mjs').stat().st_size,
                      'upstreamFiles': len(upstream_inputs), 'runtimePackages': len(packages)}, ensure_ascii=False))


if __name__ == '__main__':
    main()