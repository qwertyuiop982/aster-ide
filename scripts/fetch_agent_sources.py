"""Fetch immutable GitHub snapshots through the configured GitHub-only proxy."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROXY = 'https://v4.gh-proxy.org/'
REPOSITORIES = {
    'gemini': ('google-gemini/gemini-cli', 'v0.64.0', 'vendor/gemini-cli'),
    'codex': ('openai/codex', None, 'vendor/codex'),
    'openclaude': ('Twigpine/openclaude', 'v0.33.0', 'research/restricted/openclaude'),
}

def metadata(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'Aster-Agent-Import'})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('engine', choices=REPOSITORIES)
    args = parser.parse_args()
    repo, tag, folder = REPOSITORIES[args.engine]
    api = 'https://api.github.com/repos/' + repo
    record_dir = ROOT / 'research/agent-engines' / args.engine
    record_dir.mkdir(parents=True, exist_ok=True)
    target = ROOT / folder
    manifest = record_dir / 'SOURCE.json'
    if target.exists():
        if manifest.exists():
            print(manifest.read_text(), flush=True)
            return
        raise RuntimeError('Existing source without provenance: ' + str(target))
    if tag is None:
        tag = metadata(api + '/releases/latest')['tag_name']
    commit = metadata(api + '/commits/' + tag)['sha']
    url = 'https://github.com/' + repo + '/archive/' + commit + '.tar.gz'
    archive = record_dir / ('upstream-' + tag.replace('/', '_') + '.tar.gz')
    print(json.dumps({'engine': args.engine, 'tag': tag, 'commit': commit, 'phase': 'download', 'url': PROXY + url}), flush=True)
    subprocess.run(['curl', '-fL', '--retry', '3', '--connect-timeout', '20', '--max-time', '300',
                    '-o', str(archive.with_suffix('.part')), PROXY + url], check=True)
    archive.with_suffix('.part').replace(archive)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent) as directory:
        staging = Path(directory) / 'source'
        staging.mkdir()
        with tarfile.open(archive) as source:
            members = source.getmembers()
            prefix = members[0].name.split('/')[0] + '/'
            for member in members:
                if not member.name.startswith(prefix):
                    continue
                member.name = member.name[len(prefix):]
                if member.name:
                    source.extract(member, staging, filter='data')
        staging.rename(target)
    license_path = target / 'LICENSE'
    record = {
        'engine': args.engine, 'repository': 'https://github.com/' + repo, 'tag': tag, 'commit': commit,
        'sourceDirectory': folder, 'archiveUrl': url, 'downloadUrl': PROXY + url,
        'archive': str(archive.relative_to(ROOT)), 'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'licenseSha256': hashlib.sha256(license_path.read_bytes()).hexdigest(),
        'redistribution': 'blocked-license-review' if args.engine == 'openclaude' else 'Apache-2.0',
    }
    manifest.write_text(json.dumps(record, indent=2) + chr(10))
    print(json.dumps(record, indent=2), flush=True)

if __name__ == '__main__':
    main()
