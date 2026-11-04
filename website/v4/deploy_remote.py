"""Run on the explicitly authorized web server. Static release switch only."""
from pathlib import Path,PurePosixPath
from datetime import datetime,timezone
import hashlib,json,os,shutil,subprocess,sys,tarfile,urllib.request,urllib.error

root=Path('/var/www/aster');stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
site_archive=Path(sys.argv[1]);source_archive=Path(sys.argv[2]);expected=sys.argv[3]
assert hashlib.sha256(site_archive.read_bytes()).hexdigest()==expected
previous=(root/'current').resolve();release=root/'releases'/('site-v4-'+stamp);source=root/'sources'/('site-v4-'+stamp)
backup=Path('/var/backups/aster-website')/stamp;backup.mkdir(parents=True,mode=0o700)
config=Path('/etc/nginx/sites-available/aster.conf');config_hash=hashlib.sha256(config.read_bytes()).hexdigest()
shutil.copyfile(config,backup/'aster.conf')
(backup/'previous-release.txt').write_text(str(previous)+'\n')

def unpack(archive,destination):
    destination.mkdir(parents=True,mode=0o755)
    with tarfile.open(archive,'r:gz') as tar:
        for item in tar.getmembers():
            name=PurePosixPath(item.name)
            assert item.isfile() and not name.is_absolute() and '..' not in name.parts
            target=destination.joinpath(*name.parts);target.parent.mkdir(parents=True,exist_ok=True,mode=0o755)
            target.write_bytes(tar.extractfile(item).read());target.chmod(0o644)

def get(path):
    request=urllib.request.Request('https://asteride.cn'+path,headers={'User-Agent':'Aster-Website-Deployment/4','Cache-Control':'no-cache'})
    try:
        with urllib.request.urlopen(request,timeout=12) as response:return response.status,response.read()
    except urllib.error.HTTPError as error:return error.code,error.read()

unpack(site_archive,release);unpack(source_archive,source)
manifest=json.loads((release/'site-manifest.json').read_text())
for name,digest in manifest['files'].items():assert hashlib.sha256((release/name).read_bytes()).hexdigest()==digest,name
for name in ('Aster-v1.0.4-agent-tools.apk','Aster-v1.0.4-agent-tools.apk.sha256','Aster-v1.0.4-agent-tools-source-changes.zip'):
    assert (root/'downloads'/name).is_file(),name
assert hashlib.sha256((root/'downloads/Aster-v1.0.4-agent-tools.apk').read_bytes()).hexdigest()=='916158787ff666ff394eb0649ab00b93155c563b9f964c8744a795d4fe5d2524'
subprocess.run(['nginx','-t'],check=True,capture_output=True)
before={'mcp':get('/terminal-mcp')[0],'assetlinks':get('/.well-known/assetlinks.json')[0]}
next_link=root/('current-v4-'+stamp);next_link.symlink_to(release)
try:
    os.replace(next_link,root/'current')
    status,body=get('/?deployment='+stamp)
    assert status==200 and '一句话，'.encode() in body and b'aster-phone-' in body
    assert get('/release.json?deployment='+stamp)[0]==200
    after={'mcp':get('/terminal-mcp')[0],'assetlinks':get('/.well-known/assetlinks.json')[0]}
    assert after==before,(before,after)
    assert hashlib.sha256(config.read_bytes()).hexdigest()==config_hash
except BaseException:
    rollback=root/('rollback-'+stamp);rollback.symlink_to(previous);os.replace(rollback,root/'current');raise
receipt={'success':True,'url':'https://asteride.cn/','release':str(release),'previous_release':str(previous),'source':str(source),'backup':str(backup),'archive_sha256':expected,'files_verified':len(manifest['files']),'nginx_config_unchanged':True,'protected_services_before':before,'protected_services_after':after}
(backup/'deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
Path('/var/lib/aster-website-v4-deployment.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt),flush=True)