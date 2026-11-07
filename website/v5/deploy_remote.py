"""Run on the authorized server: deploy_remote.py site.tar.gz expected_sha256."""
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import hashlib, json, os, shutil, subprocess, sys, tarfile, urllib.request, urllib.error
root=Path('/var/www/aster')
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
archive=Path(sys.argv[1]); expected=sys.argv[2]
assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected
previous=(root/'current').resolve(strict=True)
release=root/'releases'/('site-v5-'+stamp)
backup=Path('/var/backups/aster-website')/('v5-'+stamp)
backup.mkdir(parents=True,mode=0o700)
(backup/'previous-release.txt').write_text(str(previous)+'\n')
config=Path('/etc/nginx/sites-available/aster.conf')
config_hash=hashlib.sha256(config.read_bytes()).hexdigest()
shutil.copyfile(config,backup/'aster.conf')
release.mkdir(mode=0o755)
with tarfile.open(archive,'r:gz') as tar:
    for item in tar.getmembers():
        name=PurePosixPath(item.name)
        assert item.isfile() and not name.is_absolute() and '..' not in name.parts
        assert not item.name.lower().endswith(('.apk','.zip','.key','.jks','.keystore'))
        target=release.joinpath(*name.parts)
        target.parent.mkdir(parents=True,exist_ok=True,mode=0o755)
        target.write_bytes(tar.extractfile(item).read());target.chmod(0o644)
manifest=json.loads((release/'site-manifest.json').read_text())
for name,digest in manifest['files'].items():
    assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
    assert hashlib.sha256((release/name).read_bytes()).hexdigest()==digest,name

def get(path):
    req=urllib.request.Request('https://asteride.cn'+path,headers={'User-Agent':'Aster-Website-Deploy/5','Cache-Control':'no-cache'})
    try:
        with urllib.request.urlopen(req,timeout=8) as response:return response.status,response.read()
    except urllib.error.HTTPError as error:return error.code,error.read()

def protected():
    return {path:get(path)[0] for path in ['/api/v1/health','/terminal-mcp','/.well-known/assetlinks.json']}
subprocess.run(['nginx','-t'],check=True,capture_output=True)
before=protected()
assert before['/api/v1/health']==200
link=root/('next-v5-'+stamp);link.symlink_to(release)
try:
    os.replace(link,root/'current')
    status,body=get('/?deployment='+stamp)
    assert status==200 and '把开发，'.encode() in body and b'https://github.com/qwertyuiop982/aster-ide/releases/download/v1.0.5/' in body
    assert get('/assets/site.js')[0]==200 and get('/privacy/')[0]==200
    after=protected();assert after==before,(before,after)
    assert hashlib.sha256(config.read_bytes()).hexdigest()==config_hash
except BaseException:
    link=root/('rollback-v5-'+stamp);link.symlink_to(previous);os.replace(link,root/'current');raise
receipt={'success':True,'url':'https://asteride.cn/','release':str(release),'previous_release':str(previous),'backup':str(backup),'archive_sha256':expected,'files_verified':len(manifest['files']),'nginx_config_unchanged':True,'backend_unchanged':True,'before':before,'after':after}
(backup/'deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
Path('/var/lib/aster-website-v5-deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt),flush=True)