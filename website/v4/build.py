"""Dependency-free Aster landing build. python3 build.py --output /path/to/site"""
from pathlib import Path
import argparse,base64,hashlib,html,json,re,shutil
from html.parser import HTMLParser
from urllib.parse import urlsplit,unquote
ROOT=Path(__file__).resolve().parent
SHA='916158787ff666ff394eb0649ab00b93155c563b9f964c8744a795d4fe5d2524'
APK='/downloads/Aster-v1.0.4-agent-tools.apk'
REPO='https://github.com/qwertyuiop982/aster-ide'

class Page(HTMLParser):
    def __init__(self):super().__init__();self.ids=[];self.links=[]
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if 'id' in attrs:self.ids.append(attrs['id'])
        if tag in ('a','img','script','link'):
            link=attrs.get('src') or attrs.get('href')
            if link:self.links.append(link)

def validate(output):
    parsed={}
    for file in output.rglob('*.html'):
        page=Page();page.feed(file.read_text());parsed[file]=page
        assert len(page.ids)==len(set(page.ids)),str(file)+' duplicate IDs'
        assert '{{' not in file.read_text(),'Unresolved placeholder'
    for file,page in parsed.items():
        for link in page.links:
            url=urlsplit(link)
            if url.scheme or url.netloc:continue
            if url.path.startswith('/downloads/'):continue
            target=file if not url.path else output/unquote(url.path.lstrip('/')) if url.path.startswith('/') else file.parent/unquote(url.path)
            if target.is_dir():target=target/'index.html'
            assert target.is_file(),f'{file}: missing {link}'
            if url.fragment and target in parsed:assert url.fragment in parsed[target].ids,f'{file}: missing anchor {link}'
    return len(parsed)

def main():
    args=argparse.ArgumentParser(description=__doc__);args.add_argument('--output',type=Path,required=True);options=args.parse_args()
    output=options.output.resolve();output.mkdir(parents=True,exist_ok=True);(output/'assets').mkdir(exist_ok=True)
    # Only completed screenshot compositions are distributed; the blank mockup stays in the source workspace.
    for name in ['landing.css','landing.js','aster-mark.svg','aster-screen-1.jpg','aster-screen-2.jpg']:
        shutil.copyfile(ROOT/'assets'/name,output/'assets'/name)
    frame=base64.b64encode((ROOT/'assets/device-front.png').read_bytes()).decode() if (ROOT/'assets/device-front.png').exists() else None
    for i in (1,2):
        composed=ROOT/'assets'/f'aster-phone-{i}.svg'
        if composed.is_file():
            shutil.copyfile(composed,output/'assets'/composed.name)
            continue
        assert frame is not None, 'Provide the completed phone composition or the licensed frame locally'
        screen=base64.b64encode((ROOT/'assets'/f'aster-screen-{i}.jpg').read_bytes()).decode()
        svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="389" height="800" viewBox="0 0 389 800"><defs><clipPath id="screen"><rect x="17" y="14" width="355" height="773" rx="48"/></clipPath></defs><g clip-path="url(#screen)"><image x="17" y="14" width="355" height="773" preserveAspectRatio="none" href="data:image/jpeg;base64,{screen}"/></g><image width="389" height="800" href="data:image/png;base64,{frame}"/></svg>'
        (output/'assets'/f'aster-phone-{i}.svg').write_text(svg)
    version=hashlib.sha256((ROOT/'index.template.html').read_bytes()+(ROOT/'assets/landing.css').read_bytes()+(ROOT/'assets/landing.js').read_bytes()).hexdigest()[:12]
    previous=json.loads((ROOT/'content/previous-release.json').read_text())
    values={'asset_version':version,'repo_url':REPO,'latest_apk_url':APK,'previous_apk_url':previous['editions']['account']['apk']['url'],'apk_sha256':SHA,'apk_sha_short':SHA[:12]+'…'+SHA[-8:]}
    def render(text):
        for key,value in values.items():text=text.replace('{{'+key+'}}',html.escape(str(value),quote=True))
        return text
    home=render((ROOT/'index.template.html').read_text())
    home=re.sub(r'<img class="phone-screen" src="/assets/aster-screen-(\d).jpg"[^>]*alt="([^"]*)"[^>]*><img class="phone-frame"[^>]*>',lambda m:f'<img class="phone-art" src="/assets/aster-phone-{m[1]}.svg" width="389" height="800" alt="{m[2]}"'+(' fetchpriority="high"' if m[1]=='1' else ' decoding="async"')+'>',home)
    (output/'index.html').write_text(home)
    header=re.search(r'<header class="site-nav">.*?</header>',home,re.S)[0].replace('href="#','href="/#')
    footer=re.search(r'<footer class="footer">.*?</footer>',home,re.S)[0].replace('href="#main"','href="/#main"')
    def document(slug,title,description,content,toc):
        nav='<aside class="document-nav" aria-label="本页目录"><span>ON THIS PAGE</span>'+''.join('<a href="#'+identifier+'">'+label+'</a>' for identifier,label in toc)+'</aside>'
        page=f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0c1110"><title>{title} — Aster</title><meta name="description" content="{description}"><link rel="canonical" href="https://asteride.cn/{slug}/"><link rel="icon" href="/assets/aster-mark.svg" type="image/svg+xml"><link rel="stylesheet" href="/assets/landing.css?v={version}"><script src="/assets/landing.js?v={version}" defer></script></head><body><a class="skip-link" href="#main">跳到主要内容</a>{header}<main id="main"><div class="document-cover"><div class="container"><div class="breadcrumbs"><a href="/">ASTER</a> / {title}</div><h1>{title}</h1><p>{description}</p></div></div><div class="container document-layout">{nav}<article class="document-content">{content}</article></div></main>{footer}<div class="toast" role="status" aria-live="polite"></div></body></html>'
        (output/slug).mkdir(exist_ok=True);(output/slug/'index.html').write_text(page)
    for slug,title,description in [('guide','使用指南','安装、模型、交互终端和子代理，从这里开始。'),('licenses','源码与许可','了解项目源码、组件许可与构建记录。'),('privacy','数据说明','了解网站、账户、模型请求与图片输入。')]:
        content=(ROOT/'content'/(slug+'.html')).read_text()
        if slug=='guide':content=(ROOT/'content/new-guide.html').read_text()+content
        elif slug=='licenses':content='<h2 id="preview-source">1.0.4 预览版源码</h2><p>当前首页提供 1.0.4-agent-tools 预览 APK。<a href="/downloads/Aster-v1.0.4-agent-tools-source-changes.zip">源码变更包</a>包含本次 Kotlin、Python、MCP 服务端和验证脚本，属于增量源码，不能作为完整项目独立构建。完整项目及各组件许可请参考下方仓库与公开版本源码。</p>'+content
        elif slug=='privacy':content='<h2 id="image-input">图片与子代理</h2><p>read_file 读取图片时，会将图片内容和分析意图发送到当前配置的模型供应商。子代理使用其所选模型对应的接口，并接收主模型分配的任务、提供的上下文及工具结果。不同供应商的数据处理规则由其政策约定。页面不加载第三方统计、远程字体或广告脚本。</p>'+content
        toc=[(i,re.sub('<[^>]+>','',label)) for i,label in re.findall(r'<h2[^>]*id="([^"]+)"[^>]*>(.*?)</h2>',content,re.S)]
        document(slug,title,description,content,toc)
    content=(ROOT/'content/updates.html').read_text().replace('{{apk_sha256}}',SHA)
    document('updates','1.0.4 更新说明','客户端子代理、交互终端与图片读取。',content,[('release','版本与下载'),('features','这次更新'),('validation','验证记录')])
    credits='<h2 id="screens">应用界面</h2><p>页面中的浅色 Codex 与深色 Pi 工作台截图由 Aster 提供。三机组合重复使用两张真实截图，不代表三个独立功能页面。</p><h2 id="frame">手机展示边框</h2><p>使用 <a href="https://www.webmobilefirst.com/en/mockups/apple-iphone-18-pro-2026/" target="_blank" rel="noopener noreferrer">Mobile FIRST 提供的 iPhone 18 Pro 正面 Mockup</a>，与 Aster 截图组合展示。该页面允许个人与商业使用、修改及裁剪，禁止单独转售或分发空白素材。本网站仅发布完成的界面组合图。</p><p>Aster 当前面向 Android。展示边框不表示支持 iOS，也不表示与设备品牌存在合作。</p><h2 id="design">网页设计</h2><p>排版、标记、流程图与终端示意为本网站制作。流程展示已标注为示意，不作为真实执行记录。网站字体使用设备本地系统字体，所有页面素材从本站加载。</p>'
    document('credits','素材致谢','真实的工作台，和用来呈现它的素材。',credits,[('screens','应用界面'),('frame','手机展示边框'),('design','网页设计')])
    (output/'404.html').write_text(f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>页面未找到 — Aster</title><link rel="stylesheet" href="/assets/landing.css?v={version}"><script src="/assets/landing.js?v={version}" defer></script></head><body>{header}<main class="container not-found" id="main"><h1>404</h1><p>这个页面还没有抵达。回到工作台，接着构建。</p><a class="button button-primary" href="/">返回首页 ↗</a></main>{footer}</body></html>')
    release={'site_version':'4.0','version':'1.0.4-agent-tools','channel':'preview','release_date':'2026-10-25','repo_url':REPO,'android_requirement':'Android 7.0+ / ARM64','apk':{'url':APK,'bytes':163580902,'sha256':SHA},'previous_public_release':previous}
    (output/'release.json').write_text(json.dumps(release,ensure_ascii=False,indent=2)+'\n')
    shutil.copyfile(ROOT/'assets/aster-screen-1.jpg',output/'assets/aster-social.jpg')
    urls=['/','/guide/','/licenses/','/privacy/','/updates/','/credits/']
    (output/'robots.txt').write_text('User-agent: *\nAllow: /\nSitemap: https://asteride.cn/sitemap.xml\n')
    (output/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>https://asteride.cn'+url+'</loc><lastmod>2026-10-25</lastmod></url>' for url in urls)+'</urlset>')
    pages=validate(output)
    manifest={p.relative_to(output).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name not in ('site-manifest.json','SITE-SHA256SUMS.txt')}
    (output/'site-manifest.json').write_text(json.dumps({'site_version':'4.0','files':manifest},indent=2)+'\n')
    (output/'SITE-SHA256SUMS.txt').write_text(''.join(digest+'  '+file+'\n' for file,digest in manifest.items()))
    print(json.dumps({'success':True,'output':str(output),'pages':pages,'files':len(manifest),'bytes':sum(p.stat().st_size for p in output.rglob('*') if p.is_file()),'asset_version':version,'local_links_validated':True}))
if __name__=='__main__':main()