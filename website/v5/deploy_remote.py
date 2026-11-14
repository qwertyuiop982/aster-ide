"""Run on the authorized server: deploy_remote.py site.tar.gz expected_sha256."""
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import hashlib, json, os, shutil, subprocess, sys, tarfile, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
root=Path('/var/www/aster')
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
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
assert manifest['site_version']=='5.3'
assert {p.relative_to(release).as_posix() for p in release.rglob('*') if p.is_file()} == set(manifest['files']) | {'site-manifest.json'}
for name,digest in manifest['files'].items():
    assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
    assert hashlib.sha256((release/name).read_bytes()).hexdigest()==digest,name

def get(path):
    req=urllib.request.Request('https://asteride.cn'+path,headers={'User-Agent':'Aster-Website-Deploy/5.3','Cache-Control':'no-cache'})
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
    assert status==200 and b'<h1>Aster</h1>' in body and b'class="download-button"' in body
    published=json.loads((release/'release.json').read_text())
    assert published['version']=='v1.06-aster'
    assert published['apk']['github_url'].encode() in body
    assert b'workbench' not in body and b'data-tab' not in body
    # Verify the exact public response body, not just files on disk or a cached 200.
    def verify_public(item):
        name,digest=item
        status,data=get('/'+name+'?deployment='+stamp)
        # nginx intentionally marks its internal error document as HTTP 404.
        # It still must return the exact published bytes, just like every asset.
        expected_status=404 if name=='404.html' else 200
        actual_digest=hashlib.sha256(data).hexdigest()
        assert status==expected_status and actual_digest==digest,(name,status,expected_status,actual_digest)
        return name
    with ThreadPoolExecutor(max_workers=6) as pool:
        public_verified=list(pool.map(verify_public,manifest['files'].items()))
    for locale in ('zh','en'):
        status,data=get('/'+locale+'/')
        assert status==200 and b'<h1>Aster</h1>' in data
        assert ('lang="'+('zh-CN' if locale=='zh' else 'en')+'"').encode() in data
        for page in ('why','help','credits','licenses','about','privacy'):
            status,data=get('/'+locale+'/'+page+'/')
            assert status==200 and b'<h1>' in data,(locale,page)
    assert get('/privacy/')[0]==200
    assert get('/not-a-real-aster-page-'+stamp)[0]==404
    after=protected();assert after==before,(before,after)
    assert hashlib.sha256(config.read_bytes()).hexdigest()==config_hash
except BaseException:
    link=root/('rollback-v5-'+stamp);link.symlink_to(previous);os.replace(link,root/'current');raise
receipt={'success':True,'url':'https://asteride.cn/','release':str(release),'previous_release':str(previous),'backup':str(backup),'archive_sha256':expected,'site_version':manifest['site_version'],'files_verified':len(manifest['files']),'public_files_verified':len(public_verified),'nginx_config_unchanged':True,'backend_unchanged':True,'before':before,'after':after}
(backup/'deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
Path('/var/lib/aster-website-v5-deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt),flush=True)