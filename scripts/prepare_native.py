"""Generate native APK inputs under ignored build/native; no binaries are stored in source."""
from pathlib import Path
import argparse, fcntl, hashlib, io, json, os, shutil, subprocess, sys, tarfile, urllib.request, zipfile
ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('ASTER_NATIVE_CACHE', str(Path.home() / '.cache/aster-native')))
RELEASE = 'https://gh-proxy.org/https://github.com/qwertyuiop982/aster-ide/releases/download/v1.0.1/'

def verified_download(url, destination, expected):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.with_suffix(destination.suffix + '.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        return _verified_download(url, destination, expected)

def _verified_download(url, destination, expected):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
        return destination
    temporary = destination.with_suffix(destination.suffix + '.part')
    try:
        request = urllib.request.Request(url, headers={'User-Agent':'Aster-native-build/1.0.1'})
        with urllib.request.urlopen(request, timeout=90) as inp, temporary.open('wb') as out:
            shutil.copyfileobj(inp, out)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Official package checksum mismatch: ' + destination.name)
        temporary.replace(destination)
    except Exception as original_error:
        # Rolling Termux repositories remove old packages. The Release retains the exact pinned .deb inputs.
        archive = CACHE / 'aster-native-dependencies-1.0.1.zip'
        with (CACHE / 'dependency-archive.lock').open('a') as archive_lock:
            fcntl.flock(archive_lock.fileno(), fcntl.LOCK_EX)
            if not archive.is_file():
                request = urllib.request.Request(RELEASE + archive.name, headers={'User-Agent':'Aster-native-build/1.0.1'})
                with urllib.request.urlopen(request, timeout=120) as inp, archive.with_suffix('.part').open('wb') as out:
                    shutil.copyfileobj(inp, out)
                archive.with_suffix('.part').replace(archive)
            with zipfile.ZipFile(archive) as bundle:
                data = bundle.read(destination.name)
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Release dependency checksum mismatch: ' + destination.name) from original_error
        temporary.write_bytes(data)
        temporary.replace(destination)
    return destination

def deb_entries(path):
    members = subprocess.check_output(['ar','t',str(path)], text=True).splitlines()
    member = next(x for x in members if x.startswith('data.tar.'))
    data = subprocess.check_output(['ar','p',str(path),member])
    if member.endswith('.zst'):
        data = subprocess.check_output(['zstd','-d','-c'], input=data)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for entry in archive:
            if entry.isfile(): yield entry.name, archive.extractfile(entry).read()

def prepare_bootstrap():
    record=json.loads((ROOT/'core/termux-runtime/src/main/assets/runtime/checksums.json').read_text())['bootstrap']
    source=verified_download('https://gh-proxy.org/https://github.com/termux/termux-packages/releases/download/bootstrap-'+record['tag']+'/bootstrap-aarch64.zip',CACHE/'bootstrap-aarch64.zip',record['aarch64'])
    output=ROOT/'build/native/runtime-assets/runtime/bootstrap-aarch64.zip'
    output.parent.mkdir(parents=True,exist_ok=True)
    with (ROOT / 'build/bootstrap-assets.lock').open('a') as asset_lock:
        fcntl.flock(asset_lock.fileno(), fcntl.LOCK_EX)
        if not output.is_file() or hashlib.sha256(output.read_bytes()).hexdigest()!=record['aarch64']:
            temporary=output.with_suffix('.part')
            shutil.copy2(source,temporary)
            temporary.replace(output)
    return output

def prepare_runtime(force=False):
    prepare_bootstrap()
    destination = ROOT / 'build/native/runtime/arm64-v8a'
    destination.mkdir(parents=True, exist_ok=True)
    expected = {'libaster_proot.so','libaster_loader.so','libaster_loader32.so','libtalloc.so','libandroid-shmem.so'}
    if not force and all((destination/name).is_file() for name in expected): return
    records = json.loads((ROOT/'core/termux-runtime/src/main/assets/runtime/checksums.json').read_text())
    for item in records['hostPackages']:
        path = verified_download('https://packages.termux.dev/apt/termux-main/'+item['Filename'], CACHE/Path(item['Filename']).name, item['SHA256'])
        for name,data in deb_entries(path):
            file=Path(name).name; target=None
            if item['Package']=='proot' and file=='proot': target='libaster_proot.so'
            elif item['Package']=='proot' and file.startswith('loader') and data.startswith(b'\x7fELF'): target='libaster_'+file.replace('-','_')+'.so'
            elif item['Package']=='libtalloc' and file.startswith('libtalloc.so'): target='libtalloc.so'
            elif item['Package']=='libandroid-shmem' and file.startswith('libandroid-shmem.so'): target='libandroid-shmem.so'
            if target:
                (destination/target).write_bytes(data); (destination/target).chmod(0o755)
    if not all((destination/name).is_file() for name in expected): raise RuntimeError('Incomplete native runtime inputs')

def prepare_v8():
    prefix=Path(os.environ.get('ASTER_V8_PREFIX',str(CACHE/'v8/usr')))
    names=['lib/librusty_v8.a','include/librusty_v8/src_binding.rs']
    if all((prefix/name).is_file() for name in names): return prefix
    path=verified_download('https://packages.termux.dev/apt/termux-main/pool/main/l/librusty-v8/librusty-v8_2.9.7_aarch64.deb', CACHE/'librusty-v8_2.9.7_aarch64.deb','18ddeeff336a696a27f3d8fe5ed223c2f6e6471ab0150bd139599f85a401657f')
    for name,data in deb_entries(path):
        relative=name.removeprefix('./').removeprefix('data/data/com.termux/files/usr/')
        if relative in names:
            output=prefix/relative; output.parent.mkdir(parents=True,exist_ok=True); output.write_bytes(data)
    if not all((prefix/name).is_file() for name in names): raise RuntimeError('Incomplete V8 input; set ASTER_V8_PREFIX')
    return prefix

def prepare_codex(force=False):
    dest=ROOT/'build/native/codex/arm64-v8a'
    if not force and all((dest/name).is_file() for name in ['libaster_codex.so','libaster_codex_host.so']): return
    for name in ['build_codex_agent.py','build_codex_host.py']:
        subprocess.run([sys.executable,'-B',str(ROOT/'scripts'/name)],cwd=ROOT,check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component',choices=['codex','runtime','all'],default='all')
    parser.add_argument('--force',action='store_true')
    args=parser.parse_args(); (ROOT/'build').mkdir(exist_ok=True)
    with (ROOT/'build'/('prepare-'+args.component+'.lock')).open('a') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        if args.component in ['runtime','all']: prepare_runtime(args.force)
        if args.component in ['codex','all']: prepare_codex(args.force)
    print('Native inputs ready in build/native/'+args.component)

if __name__=='__main__': main()
