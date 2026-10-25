from pathlib import Path
import concurrent.futures
import hashlib
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'research/runtime-sources'
OUT.mkdir(exist_ok=True)
items = [
    ('https://gh-proxy.org/https://github.com/termux/proot/archive/v5.1.107.96.zip', 'proot-5.1.107.96.zip', '75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa'),
    ('https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz', 'talloc-2.5.0.tar.gz', '912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007'),
    ('https://gh-proxy.org/https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz', 'libandroid-shmem-0.7.tar.gz', '1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867')
]

def get(item):
    url, name, expected = item
    dest = OUT / name
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == expected: return
    req = urllib.request.Request(url, headers={'User-Agent': 'AsterIDE/0.1'})
    with urllib.request.urlopen(req, timeout=60) as inp, dest.open('wb') as out:
        shutil.copyfileobj(inp, out, 262144)
    assert hashlib.sha256(dest.read_bytes()).hexdigest() == expected, 'Source checksum mismatch: ' + name
    print('Source verified:', name, dest.stat().st_size, flush=True)

with concurrent.futures.ThreadPoolExecutor(3) as pool:
    list(pool.map(get, items))