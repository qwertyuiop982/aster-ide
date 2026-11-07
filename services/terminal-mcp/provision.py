"""Run as root on the explicitly authorized deployment host; payload stays private."""
from pathlib import Path, PurePosixPath
import base64
import hashlib
import io
import json
import os
import pwd
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request


def command(args, timeout=60):
    print('Running:', ' '.join(args[:5]), flush=True)
    subprocess.run(args, check=True, timeout=timeout)


def deploy(payload):
    stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    root = Path('/opt/aster-terminal-mcp')
    release = root/'releases'/stamp
    release.mkdir(parents=True, mode=0o755)
    raw = base64.b64decode(payload['archive'])
    assert hashlib.sha256(raw).hexdigest() == payload['archive_sha256']
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:gz') as archive:
        for item in archive.getmembers():
            name = PurePosixPath(item.name)
            if name.is_absolute() or '..' in name.parts or not item.isfile():
                raise ValueError('Invalid deployment archive entry')
            dest = release.joinpath(*name.parts)
            dest.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            dest.write_bytes(archive.extractfile(item).read())
            dest.chmod(0o644)
    try:
        account = pwd.getpwnam('aster-terminal')
    except KeyError:
        command(['useradd','--system','--user-group','--home-dir','/var/lib/aster-terminal-mcp','--create-home','--shell','/usr/sbin/nologin','aster-terminal'])
        account = pwd.getpwnam('aster-terminal')
    state = Path('/var/lib/aster-terminal-mcp')
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chown(state, account.pw_uid, account.pw_gid)
    if not (root/'venv/bin/python').is_file():
        try:
            command(['python3','-m','venv',str(root/'venv')])
        except subprocess.CalledProcessError:
            command(['apt-get','update'], timeout=180)
            command(['apt-get','install','-y','python3-venv'], timeout=180)
            command(['python3','-m','venv',str(root/'venv')])
    command([str(root/'venv/bin/python'),'-m','pip','install','--disable-pip-version-check','--timeout','20','--retries','1','-r',str(release/'requirements.txt')], timeout=300)
    env = Path('/etc/aster-terminal-mcp.env')
    env.write_text('ASTER_TERMINAL_ROOT=/var/lib/aster-terminal-mcp\nASTER_TERMINAL_PORT=8893\nASTER_TERMINAL_TOKEN='+payload['token']+'\n')
    env.chmod(0o600)
    link = root/'current'
    previous = str(link.resolve()) if link.exists() else None
    temp = root/'current.new'
    if temp.is_symlink():temp.unlink()
    temp.symlink_to(release)
    os.replace(temp, link)
    service = Path('/etc/systemd/system/aster-terminal-mcp.service')
    if service.exists() and 'Aster persistent terminal MCP' not in service.read_text():
        raise RuntimeError('Existing unrelated unit has the requested service name')
    shutil.copyfile(release/'aster-terminal-mcp.service', service)
    command(['systemctl','daemon-reload'])
    command(['systemctl','enable','--now','aster-terminal-mcp'])
    command(['systemctl','restart','aster-terminal-mcp'])
    ready = False
    for _ in range(50):
        try:
            request=urllib.request.Request('http://127.0.0.1:8893/healthz',headers={'Authorization':'Bearer '+payload['token']})
            with urllib.request.urlopen(request,timeout=2) as response:
                ready = response.status == 200
            if ready:break
        except OSError:time.sleep(.2)
    if not ready:
        command(['journalctl','-u','aster-terminal-mcp','-n','30','--no-pager'])
        raise RuntimeError('Terminal MCP did not become healthy')
    config = Path('/etc/nginx/sites-available/aster.conf')
    before = config.read_text()
    backup = Path('/var/backups/aster-terminal-mcp')/stamp
    backup.mkdir(parents=True, exist_ok=True, mode=0o700)
    (backup/'aster.conf').write_text(before)
    import re
    after = re.sub(r'    # BEGIN ASTER TERMINAL MCP.*?# END ASTER TERMINAL MCP\n?', '', before, flags=re.S)
    marker = '    server_name asteride.cn;\n'
    assert after.count(marker) == 1
    after = after.replace(marker, marker+'\n'+(release/'nginx-location.conf').read_text()+'\n')
    config.write_text(after)
    try:
        command(['nginx','-t'])
        command(['systemctl','reload','nginx'])
    except Exception:
        config.write_text(before)
        command(['nginx','-t'])
        command(['systemctl','reload','nginx'])
        raise
    evidence={'success':True,'endpoint':'https://asteride.cn/terminal-mcp','release':str(release),'previous_release':previous,'service':'aster-terminal-mcp','user':'aster-terminal','bind':'127.0.0.1:8893','archive_sha256':payload['archive_sha256'],'nginx_backup':str(backup/'aster.conf')}
    Path('/var/lib/aster-terminal-mcp-deployment.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print(json.dumps(evidence),flush=True)


if __name__=='__main__':
    try:
        deploy(json.loads(Path(sys.argv[1]).read_text()))
        Path('/root/.cache/aster-terminal-mcp-deploy.exit').write_text('0')
    except BaseException:
        Path('/root/.cache/aster-terminal-mcp-deploy.exit').write_text('1')
        raise