"""Build the static Aster v3 website locally from verified Release metadata.

Usage: python3 website/v3/build.py --release-input release-input.json --output /tmp/aster-site-v3
The server receives only the generated static files, never build dependencies.
"""
from pathlib import Path
import argparse, hashlib, html, json, re, shutil
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote

ROOT = Path(__file__).resolve().parent

def icon(path):
    return '<svg viewBox="0 0 24 24" aria-hidden="true">'+path+'</svg>'

ICONS = {
    'github_icon': icon('<path d="M9 19c-4 1-4-2-6-2m12 4v-3.5c0-1 .1-1.5-.5-2 3-.3 6-1.5 6-6a4.7 4.7 0 0 0-1.3-3.3A4.4 4.4 0 0 0 19 3s-1.1-.4-3.5 1.3a12 12 0 0 0-7 0C6.1 2.6 5 3 5 3a4.4 4.4 0 0 0-.2 3.2A4.7 4.7 0 0 0 3.5 9.5c0 4.5 3 5.7 6 6-.6.5-.5 1.2-.5 2V21"/>'),
    'code_icon': icon('<path d="m8 7-5 5 5 5m8-10 5 5-5 5m-3-14-2 18"/>'),
    'terminal_icon': icon('<rect x="2" y="4" width="20" height="16" rx="3"/><path d="m6 8 4 4-4 4m7 0h4"/>'),
    'spark_icon': icon('<path d="M12 2v20M2 12h20M5 5l14 14M5 19 19 5"/>'),
    'download_icon': icon('<path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5"/>'),
    'copy_icon': icon('<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 5V3H3v13h2"/>'),
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    options = parser.parse_args()
    data = json.loads(options.release_input.read_text())
    assert data['repo_url'] == 'https://github.com/qwertyuiop982/aster-ide'
    for edition in data['editions'].values():
        for kind in ('apk', 'source'):
            asset = edition[kind]
            assert re.fullmatch(r'[0-9a-f]{64}', asset['sha256'])
            assert asset['url'].startswith('https://gh-proxy.org/https://github.com/qwertyuiop982/aster-ide/releases/download/')
            assert asset['bytes'] > 0
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output/'assets').mkdir(exist_ok=True)
    for asset in (ROOT/'assets').iterdir():
        if asset.is_file(): shutil.copy2(asset, output/'assets'/asset.name)
    version = hashlib.sha256(json.dumps(data, sort_keys=True).encode() + b''.join(p.read_bytes() for p in sorted((ROOT/'assets').iterdir()) if p.is_file())).hexdigest()[:12]
    header = '''<header class="site-header"><div class="shell nav-shell"><a class="brand" href="/" aria-label="Aster 首页"><img src="/assets/aster.svg" alt="" width="35" height="35"><span>Aster<b>.</b></span></a><button type="button" class="menu-toggle" aria-label="展开导航" aria-controls="site-navigation" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h16M4 16h16"/></svg></button><nav class="nav-links" id="site-navigation" aria-label="主要导航"><a href="/#workspace">工作台</a><a href="/#models">账户模型</a><a href="/#download">下载</a><a href="/guide/">使用指南</a><a class="nav-github" href="{{repo_url}}" target="_blank" rel="noopener">GitHub {{github_icon}} <span aria-hidden="true">↗</span></a></nav></div></header>'''
    footer = '''<footer class="site-footer"><div class="shell footer-main"><a class="brand" href="/" aria-label="Aster 首页"><img src="/assets/aster.svg" width="32" height="32" alt=""><span>Aster<b>.</b></span></a><p class="footer-tagline">把开发现场，带在身边。</p><nav class="footer-nav" aria-label="页脚导航"><a href="/guide/">使用指南</a><a href="/licenses/">源码与许可</a><a href="/privacy/">数据说明</a><a href="{{repo_url}}">GitHub ↗</a></nav></div><div class="shell footer-bottom"><span>© 2026 Aster · 为 Android 上的创造而做。</span><a href="/release.json">RELEASE NOTES / 1.0.1 ↗</a><a href="#main">回到顶部 ↑</a></div></footer>'''
    account = data['editions']['account']; original = data['editions']['original']
    values = dict(ICONS, repo_url=data['repo_url'], release_url=data['release_url'], android_requirement=data['android_requirement'],
                  asset_version=version, account_apk_url=account['apk']['url'], account_apk_size=account['apk']['size_label'],
                  account_source_url=account['source']['url'], account_source_size=account['source']['size_label'], account_apk_sha=account['apk']['sha256'],
                  original_apk_url=original['apk']['url'], original_source_url=original['source']['url'], original_validation_url=original['validation_url'],
                  validation_url=account['validation_url'], live_validation_url=data['live_validation_url'], checksums_url=account['checksums_url'])
    def render(text):
        for _ in range(3):
            for key, value in values.items(): text = text.replace('{{'+key+'}}', str(value))
        assert not re.search(r'\{\{[^}]+\}\}', text), 'Unresolved template value'
        return text
    values.update(header=render(header), footer=render(footer))
    (output/'index.html').write_text(render((ROOT/'index.template.html').read_text()))
    pages = {
        'guide': ('使用指南', '从安装与登录，到项目、工具和下一步。', [('install','安装与升级'),('workspace','工作方式'),('runtime','运行环境'),('account','登录与积分'),('models','模型配置'),('continuity','连续工具调用'),('tools','扩展工具'),('troubleshooting','常见问题')]),
        'licenses': ('源码与许可', '每个版本，都有自己的源码与构建记录。', [('source','客户端源码'),('components','组件与许可'),('build','构建与校验'),('redistribution','再分发')]),
        'privacy': ('数据说明', '了解网站、账户与模型请求会处理哪些信息。', [('website','浏览官网'),('account','账户登录'),('model','模型与项目内容'),('control','你的选择')]),
    }
    for slug, (title, description, toc) in pages.items():
        navigation = '<aside class="document-nav" aria-label="本页目录"><span>ON THIS PAGE</span>'+''.join(f'<a href="#{id}">{html.escape(label)}</a>' for id,label in toc)+'</aside>'
        content = render((ROOT/(slug+'.content.html')).read_text())
        page = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#102724"><title>{title} — Aster</title><meta name="description" content="{description}"><link rel="canonical" href="https://asteride.cn/{slug}/"><link rel="icon" href="/assets/aster.svg" type="image/svg+xml"><link rel="stylesheet" href="/assets/site.css?v={version}"><script src="/assets/site.js?v={version}" defer></script></head><body><a class="skip" href="#main">跳到主要内容</a>{values['header']}<main id="main"><div class="document-cover"><div class="shell"><div class="breadcrumbs"><a href="/">Aster</a> &nbsp; / &nbsp; {title}</div><h1>{title}</h1><p>{description}</p></div></div><div class="shell document-layout">{navigation}<div class="document-content">{content}</div></div></main>{values['footer']}</body></html>'''
        (output/slug).mkdir(exist_ok=True)
        (output/slug/'index.html').write_text(page)
    # Metadata is a local static input, contains only public labels and artifact hashes.
    (output/'release.json').write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
    (output/'assets/release-data.js').write_text('window.ASTER_RELEASE='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n')
    index=(output/'index.html').read_text().replace('<script src="/assets/site.js',f'<script src="/assets/release-data.js?v={version}" defer></script><script src="/assets/site.js')
    (output/'index.html').write_text(index)
    (output/'robots.txt').write_text('User-agent: *\nAllow: /\nSitemap: https://asteride.cn/sitemap.xml\n')
    urls=['/','/guide/','/licenses/','/privacy/']
    (output/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>https://asteride.cn'+url+'</loc><lastmod>2026-10-25</lastmod></url>' for url in urls)+'</urlset>')
    (output/'404.html').write_text(f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>页面未找到 — Aster</title><link rel="stylesheet" href="/assets/site.css?v={version}"><script src="/assets/site.js?v={version}" defer></script></head><body>{values["header"]}<main id="main" class="not-found shell"><h1>404</h1><p>这条路径还没有文件。回到工作台，继续下一步。</p><a class="button lime" href="/">返回首页 ↗</a></main>{values["footer"]}</body></html>')
    (output/'assets/social.svg').write_text('''<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630"><rect width="1200" height="630" fill="#102724"/><path d="M740 80v470M820 80v470M900 80v470M980 80v470M1060 80v470M710 160h440M710 240h440M710 320h440M710 400h440M710 480h440" stroke="#dcfa91" opacity=".08"/><text x="82" y="118" fill="#dcfa91" font-family="sans-serif" font-size="44" font-weight="600">Aster.</text><text x="82" y="275" fill="#f5f4ed" font-family="sans-serif" font-size="66" font-weight="600">把开发现场，</text><text x="82" y="373" fill="#dcfa91" font-family="sans-serif" font-size="66" font-weight="600">带在身边。</text><text x="85" y="518" fill="#a7bc92" font-family="sans-serif" font-size="22">ANDROID IDE / TERMINAL / AGENT</text><text x="836" y="387" fill="#dcfa91" font-family="sans-serif" font-size="160">64</text><text x="845" y="437" fill="#a7bc92" font-family="sans-serif" font-size="18">TOOL CALLS / TASK</text></svg>''')
    validate(output)
    manifest={str(p.relative_to(output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name!='site-manifest.json'}
    (output/'site-manifest.json').write_text(json.dumps({'site_version':'3.0','files':manifest},indent=2)+'\n')
    print(json.dumps({'output':str(output),'files':len(manifest),'asset_version':version,'bytes':sum(p.stat().st_size for p in output.rglob('*') if p.is_file()),'internal_links_validated':True},ensure_ascii=False))

class Page(HTMLParser):
    def __init__(self):
        super().__init__();self.ids=[];self.links=[];self.resources=[]
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs)
        if 'id' in attrs:self.ids.append(attrs['id'])
        if tag=='a' and 'href' in attrs:self.links.append(attrs['href'])
        if tag in ('script','img','link'):
            url=attrs.get('src') or attrs.get('href')
            if url:self.resources.append(url)

def validate(output):
    parsed={}
    for path in output.rglob('*.html'):
        page=Page();page.feed(path.read_text());parsed[path]=page
        assert len(page.ids)==len(set(page.ids)),str(path)+' duplicate IDs'
    for path,page in parsed.items():
        for link in page.links+page.resources:
            url=urlsplit(link)
            if url.scheme or url.netloc:continue
            target=path if not url.path else output/unquote(url.path.lstrip('/')) if url.path.startswith('/') else path.parent/unquote(url.path)
            if target.is_dir():target=target/'index.html'
            assert target.exists(),f'{path.name}: broken local link {link}'
            if url.fragment and target in parsed:assert unquote(url.fragment) in parsed[target].ids,f'{path}: missing anchor {link}'
    for path in output.rglob('*'):
        if path.is_file():assert not re.search(rb'(?:sk-[0-9a-f]{32,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|zzz-proxy[.]pages[.]dev|api[.]uselunora[.]com)',path.read_bytes()),'private provider config in '+path.name

if __name__=='__main__':main()