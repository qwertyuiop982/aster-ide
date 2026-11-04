"""Build the compact Aster website using only the Python standard library."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit
from datetime import date
import argparse, hashlib, html, json, re, shutil
ROOT = Path(__file__).resolve().parent
REPO = 'https://github.com/qwertyuiop982/aster-ide'
APK = REPO + '/releases/download/v1.0.4/Aster-account-v1.0.4-release.apk'
SHA = '20b63382e2a6debd0cb43dc103344c43b867fa3a7afcf35b4e32db513fab523b'

class Links(HTMLParser):
    def __init__(self): super().__init__(); self.links=[]; self.ids=[]
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs)
        if 'id' in attrs: self.ids.append(attrs['id'])
        if tag in ('a','link','img','script'):
            link=attrs.get('src') or attrs.get('href')
            if link: self.links.append(link)
        assert not any(key.startswith('on') or key=='style' for key in attrs), 'Inline code violates CSP'
        if tag=='script': assert attrs.get('src'), 'Inline script violates CSP'

def build(output):
    output.mkdir(parents=True, exist_ok=True)
    (output/'assets').mkdir(exist_ok=True)
    for file in (ROOT/'assets').iterdir():
        if file.is_file(): shutil.copyfile(file, output/'assets'/file.name)
    version=hashlib.sha256(b''.join(p.read_bytes() for p in [ROOT/'index.template.html',ROOT/'assets/site.css',ROOT/'assets/site.js'])).hexdigest()[:12]
    values={'asset_version':version,'version':'1.0.4','apk_url':'https://gh-proxy.org/'+APK,'apk_size':'131 MiB','year':str(date.today().year)}
    home=(ROOT/'index.template.html').read_text()
    for key,value in values.items(): home=home.replace('{{'+key+'}}',html.escape(value,quote=True))
    (output/'index.html').write_text(home)
    header=re.search(r'<header class="header">.*?</header>',home,re.S)[0]
    footer=re.search(r'<footer class="footer">.*?</footer>',home,re.S)[0]
    def page(title,content):
        return f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#f7f8f6"><title>{title} — Aster</title><link rel="icon" href="/assets/mark.svg" type="image/svg+xml"><link rel="stylesheet" href="/assets/site.css?v={version}"></head><body><div class="site-shell">{header}<main class="document" id="main">{content}</main>{footer}</div></body></html>'
    privacy='''<h1>隐私说明</h1><p>更新于 2026 年 11 月 4 日</p><h2>网站访问</h2><p>官网不使用第三方统计、广告或远程字体。服务器可能记录请求路径、来源 IP 和访问时间，用于维护服务。APK 下载请求经过 gh-proxy.org 与 GitHub。</p><h2>客户端与模型</h2><p>账户登录、积分和用量在 Aster App 内管理。使用模型时，对话、所选项目内容及工具结果会发送给所用服务。自定义模型请求发往你配置的供应商；图片输入同样遵循所选供应商的数据政策。</p><h2>你的选择</h2><p>请只提供愿意交给对应服务处理的内容。你可以在 App 内选择模型、管理项目上下文或退出账户。</p><a class="back" href="/">← 返回首页</a>'''
    (output/'privacy').mkdir(exist_ok=True)
    (output/'privacy/index.html').write_text(page('隐私说明',privacy))
    (output/'404.html').write_text(page('页面未找到','<div class="not-found"><h1>404</h1><p>这个页面还没抵达。</p><a class="primary" href="/">返回首页 ↗</a></div>'))
    release={'site_version':'5.0','version':'1.0.4','versionCode':15,'apk':{'url':values['apk_url'],'github_url':APK,'sha256':SHA,'bytes':137364575}}
    (output/'release.json').write_text(json.dumps(release,ensure_ascii=False,indent=2)+'\n')
    (output/'robots.txt').write_text('User-agent: *\nAllow: /\nSitemap: https://asteride.cn/sitemap.xml\n')
    (output/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>https://asteride.cn/</loc></url><url><loc>https://asteride.cn/privacy/</loc></url></urlset>')
    parsed={}
    for file in output.rglob('*.html'):
        text=file.read_text(); assert '{{' not in text
        parser=Links();parser.feed(text);parsed[file]=parser
        assert len(parser.ids)==len(set(parser.ids)), 'Duplicate IDs'
        assert '/downloads/' not in text and 'source.zip' not in text and '源码' not in text
    for file,parser in parsed.items():
        for link in parser.links:
            url=urlsplit(link)
            if url.scheme or url.netloc: continue
            target=file if not url.path else output/url.path.lstrip('/')
            if target.is_dir(): target=target/'index.html'
            assert target.is_file(), link
            if url.fragment: assert url.fragment in parsed[target].ids, link
    manifest={p.relative_to(output).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name!='site-manifest.json'}
    (output/'site-manifest.json').write_text(json.dumps({'site_version':'5.0','files':manifest},indent=2)+'\n')
    print(json.dumps({'success':True,'files':len(manifest)+1,'bytes':sum(p.stat().st_size for p in output.rglob('*') if p.is_file()),'asset_version':version,'apk_url':values['apk_url'],'links_validated':True}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True)
    build(parser.parse_args().output.resolve())