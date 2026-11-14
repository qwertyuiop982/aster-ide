"""Build Aster's self-hosted bilingual static website (Python standard library only)."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit
from datetime import date
import argparse
import hashlib
import html
import json
import re
import shutil

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parents[1]
REPO = 'https://github.com/qwertyuiop982/aster-ide'
DOMAIN = 'https://asteride.cn'
REVISION = 'c4878eeb4fe403f257e2f465d3c3de983a90d5aa'
SITE_VERSION = '5.3'
LOCALES = ('zh', 'en')
PAGES = ('home', 'why', 'help', 'credits', 'licenses', 'about', 'privacy')
RAW_KEYS = {'language_attrs', 'alternate_links', 'language_switch', 'footer_links'}

def esc(value):
    return html.escape(str(value), quote=True)

class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links, self.ids, self.h1 = [], [], 0
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'h1': self.h1 += 1
        if 'id' in attrs: self.ids.append(attrs['id'])
        if tag in ('a', 'link', 'img', 'script'):
            link = attrs.get('src') or attrs.get('href')
            if link: self.links.append(link)
        assert not any(key.startswith('on') or key == 'style' for key in attrs), 'Inline code violates CSP'
        if tag == 'script': assert attrs.get('src'), 'Inline script violates CSP'

def render(template, values):
    def substitute(match):
        key = match[1]
        assert key in values, f'Missing translation or build value: {key}'
        return values[key] if key in RAW_KEYS else esc(values[key])
    return re.sub(r'\{\{([a-z_]+)\}\}', substitute, template)

def page_path(locale, kind):
    suffix = '' if kind == 'home' else '404.html' if kind == 'not-found' else kind + '/'
    return f'/{locale}/{suffix}'

def alternate_links(kind):
    if kind == 'not-found': return '<meta name="robots" content="noindex">'
    default = '/' if kind == 'home' else '/' + kind + '/'
    return '\n  '.join(f'<link rel="alternate" hreflang="{lang}" href="{DOMAIN}{path}">'
        for lang, path in [('zh-CN', page_path('zh', kind)), ('en', page_path('en', kind)), ('x-default', default)])

def language_switch(locale, kind, label, automatic_error=False):
    links = []
    for code, language, text, accessible in [('zh', 'zh-CN', '中文', '切换到中文'), ('en', 'en', 'EN', 'Switch to English')]:
        href = '?lang=' + code if automatic_error else page_path(code, kind)
        current = ' aria-current="true"' if locale == code else ''
        links.append(f'<a href="{href}" lang="{language}" hreflang="{language}" data-language="{code}" aria-label="{accessible}"{current}>{text}</a>')
    return f'<nav class="language-switch" aria-label="{esc(label)}">' + ''.join(links) + '</nav>'

def anchor(url, text):
    return f'<a href="{esc(url)}">{esc(text)}</a>'

def inventory():
    # Authoritative local sources; do not infer a blanket project license.
    notices = (SOURCE / 'THIRD_PARTY_NOTICES.md').read_text()
    rows = []
    for line in notices.splitlines():
        if not line.startswith('| '): continue
        cells = [x.strip() for x in line.strip('|').split('|')]
        if cells[0] in ('Component', '---'): continue
        rows.append(cells)
    for name, version, license_name, file in [
        ('Pi', 'retained upstream snapshot', 'MIT', 'vendor/pi/LICENSE'),
        ('Codex', 'retained upstream snapshot', 'Apache-2.0', 'vendor/codex/LICENSE'),
        ('Gemini CLI', 'retained upstream snapshot', 'Apache-2.0', 'vendor/gemini-cli/LICENSE'),
        ('Qingzhou BrowserEngine / WebViewEngine', 'compiled browser source', 'AGPL-3.0', 'vendor/qingzhou/LICENSE'),
        ('Operit', 'reference attribution', 'LGPL-3.0', 'vendor/operit/LICENSE'),
        ('Shizuku API / provider', '13.1.5; app separately installed', 'MIT', 'client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt'),
        ('Android Code Studio / AndroidIDE UI', 'e93e7468e6482ea51ef8e1adad6b7c6de86ae7cd', 'GPL-3.0-or-later', 'core/app/src/main/assets/licenses/ACS-SOURCE.json'),
    ]:
        path = SOURCE / file
        assert path.is_file(), file
        rows.append([name, version, license_name, file])
    return rows

def build(output, release_dir):
    delivery = json.loads((release_dir / 'delivery.json').read_text())
    version = delivery['version']
    assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version)
    tag = version if version.startswith('v') else 'v' + version
    downloads = delivery['downloads']
    assert len(downloads) == 1, 'Expected one signed release APK'
    apk_name, apk_meta = next(iter(downloads.items()))
    assert Path(apk_name).name == apk_name and apk_name.endswith('.apk')
    apk_file = release_dir / apk_name

    digest = hashlib.sha256()
    with apk_file.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
    assert apk_file.stat().st_size == apk_meta['size'] and digest.hexdigest() == apk_meta['sha256'], 'APK mismatch'
    github_url = REPO + f'/releases/download/{tag}/{apk_name}'
    apk_url = 'https://gh-proxy.org/' + github_url
    translations = json.loads((ROOT / 'locales.json').read_text())
    contents = json.loads((ROOT / 'pages.json').read_text())
    assert set(translations) == set(LOCALES) == set(contents)
    assert translations['zh'].keys() == translations['en'].keys()
    assert all(isinstance(x, str) and x for loc in translations.values() for x in loc.values())
    inputs = [ROOT / 'build.py', ROOT / 'index.template.html', ROOT / 'locales.json', ROOT / 'pages.json', *sorted((ROOT / 'assets').rglob('*'))]
    asset_version = hashlib.sha256(b''.join(p.relative_to(ROOT).as_posix().encode() + p.read_bytes() for p in inputs if p.is_file())).hexdigest()[:12]
    common = {'asset_version': asset_version, 'version': version, 'apk_url': apk_url,
              'apk_size': f"{apk_meta['size'] / (1024 * 1024):.1f} MiB", 'year': str(date.today().year)}
    output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'assets', output / 'assets', dirs_exist_ok=True)
    license_dir = output / 'assets/licenses'; license_dir.mkdir(exist_ok=True)
    license_map = {}
    # Retain exact local originals on the website, including component exceptions.
    originals = [SOURCE / 'LICENSE', SOURCE / 'THIRD_PARTY_NOTICES.md', SOURCE / 'client/SOURCES.json',
                 SOURCE / 'docs/license-review-and-release-gates.md',
                 *sorted((SOURCE / 'core/app/src/main/assets/licenses').glob('*')),
                 *sorted((SOURCE / 'vendor').glob('*/LICENSE*')),
                 SOURCE / 'client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt']
    for path in originals:
        if not path.is_file(): continue
        relative = path.relative_to(SOURCE).as_posix()
        filename = relative.replace('/', '__')
        shutil.copyfile(path, license_dir / filename)
        license_map[relative] = '/assets/licenses/' + filename
    license_rows = inventory()
    template = (ROOT / 'index.template.html').read_text()
    def href(locale, target):
        external = {'github': REPO, 'author': 'https://github.com/qwertyuiop982',
                    'issues': REPO + '/issues', 'release': REPO + '/releases/tag/' + tag}
        return external[target] if target in external else page_path(locale, target)
    def footer_links(locale):
        t = translations[locale]
        # The sixth entry goes straight to the author profile as requested.
        return ''.join(anchor(href(locale, target), t[label + '_label']) for target, label in
                       [('why','why'),('help','help'),('credits','credits'),('licenses','licenses'),('github','github'),('author','about')])
    def table(headers, rows):
        return '<div class="table-scroll" role="region" tabindex="0" aria-label="' + esc(headers[0]) + '"><table><thead><tr>' + ''.join('<th scope="col">' + esc(x) + '</th>' for x in headers) + '</tr></thead><tbody>' + ''.join('<tr>' + ''.join('<td>' + x + '</td>' for x in row) + '</tr>' for row in rows) + '</tbody></table></div>'
    def document_content(locale, kind, values):
        t = translations[locale]
        if kind == 'not-found':
            return '<div class="not-found"><h1>404</h1><p>' + esc(t['not_found_description']) + '</p>' + anchor(page_path(locale, 'home'), t['back_home']) + '</div>'
        if kind == 'privacy':
            text = '<h1>' + esc(t['privacy_title']) + '</h1><p>' + esc(t['privacy_updated']) + '</p>'
            for heading, keys in [('privacy_site_heading',['privacy_site','privacy_language']),('privacy_client_heading',['privacy_client']),('privacy_terminal_heading',['privacy_terminal','privacy_history']),('privacy_choices_heading',['privacy_choices'])]:
                text += '<section><h2>' + esc(t[heading]) + '</h2>' + ''.join('<p>' + esc(t[k]) + '</p>' for k in keys) + '</section>'
        elif kind == 'licenses':
            text = '<h1>' + esc(t['license_title']) + '</h1><p class="intro">' + esc(t['license_intro']) + '</p><p class="note">' + esc(t['license_warning']) + '</p>'
            rendered_rows = []
            for name, version_text, license_name, source in license_rows:
                local = next((p for p in license_map if p in source), 'THIRD_PARTY_NOTICES.md')
                rendered_rows.append([esc(name), esc(version_text), esc(license_name), anchor(license_map[local], t['source_label']) + '<br>' + esc(source)])
            text += '<section><h2>' + esc(t['license_runtime']) + ' / ' + esc(t['license_agents']) + '</h2>' + table(t['license_columns'].split('|'), rendered_rows) + '</section>'
            text += '<section><h2>' + esc(t['license_site']) + '</h2><p>' + esc(t['license_web_text']) + '</p><p>' + anchor('/assets/vendor/THREE-LICENSE.txt', 'Three.js — MIT') + ' · ' + anchor('/assets/vendor/provenance.json', 'npm / SHA-512') + '</p></section>'
            text += '<section><h2>' + esc(t['license_originals']) + '</h2><ul>' + ''.join('<li>' + anchor(url, source) + '</li>' for source, url in license_map.items()) + '</ul></section>'
        else:
            data = contents[locale][kind]
            text = '<h1>' + esc(data['title']) + '</h1><p class="intro">' + esc(data['intro']) + '</p>'
            for section in data['sections']:
                text += '<section><h2>' + esc(section['title']) + '</h2>'
                text += ''.join('<p>' + esc(x) + '</p>' for x in section.get('paragraphs', []))
                for key, tag in [('items','ul'),('steps','ol')]:
                    if key in section: text += '<' + tag + '>' + ''.join('<li>' + esc(x) + '</li>' for x in section[key]) + '</' + tag + '>'
                if 'table' in section:
                    data_table = section['table']; text += table(data_table['headers'], [[esc(x) for x in row] for row in data_table['rows']])
                if 'note' in section: text += '<p class="note">' + esc(section['note']) + '</p>'
                if 'links' in section: text += '<p>' + ' · '.join(anchor(href(locale, target), label) for target, label in section['links']) + '</p>'
                text += '</section>'
        return text + '<a class="back" href="' + page_path(locale, 'home') + '">← ' + esc(t['back_home']) + '</a>'
    def page(locale, kind='home', automatic=False, automatic_error=False):
        t = translations[locale]
        values = {**t, **common, 'home_url': page_path(locale,'home'),
                  'canonical_url': DOMAIN + page_path(locale,kind), 'alternate_links': alternate_links(kind),
                  'language_attrs': f'data-page="{kind}"' + (' data-auto-language="true"' if automatic else ''),
                  'language_switch': language_switch(locale,kind,t['language_label'],automatic_error),
                  'footer_links': footer_links(locale)}
        if kind != 'home':
            title_key = 'license_title' if kind == 'licenses' else 'not_found_title' if kind == 'not-found' else kind + '_title'
            values['title'] = t[title_key] + ' — Aster'
            values['meta_description'] = t.get('license_intro' if kind == 'licenses' else 'privacy_site' if kind == 'privacy' else 'not_found_description', t['meta_description']) if kind in ('licenses','privacy','not-found') else contents[locale][kind]['intro']
        home = render(template, values)
        if kind == 'home': return home
        head = re.search(r'<head>.*?</head>',home,re.S)[0]
        header = '<header class="header"><a class="brand" href="' + values['home_url'] + '"><img src="/assets/mark.svg" width="26" height="26" alt=""><span>Aster</span></a><div class="header-actions"><a class="small-link" href="' + esc(apk_url) + '">' + esc(t['nav_download']) + '</a>' + values['language_switch'] + '</div></header>'
        footer = '<footer class="footer"><span>© ' + common['year'] + ' Aster</span><nav>' + anchor(page_path(locale,'help'),t['help_label']) + anchor(page_path(locale,'about'),t['about_title']) + anchor(page_path(locale,'licenses'),t['licenses_label']) + anchor(page_path(locale,'privacy'),t['privacy_link']) + '</nav></footer>'
        content = document_content(locale,kind,values)
        return f'<!doctype html><html lang="{t["html_lang"]}" {values["language_attrs"]}>{head}<body class="document-page"><a class="skip" href="#main">{esc(t["skip"])}</a><div class="site-shell">{header}<main class="document" id="main"><p class="document-kicker">{esc(t["document_kicker"])}</p>{content}</main>{footer}</div></body></html>'
    def write(path, text):
        path.parent.mkdir(parents=True,exist_ok=True); path.write_text(text,encoding='utf-8')
    for locale in LOCALES:
        for kind in (*PAGES,'not-found'):
            relative = page_path(locale,kind).lstrip('/')
            if relative.endswith('/'): relative += 'index.html'
            write(output / relative,page(locale,kind))
    for kind in PAGES:
        relative = 'index.html' if kind == 'home' else kind + '/index.html'
        write(output / relative,page('zh',kind,automatic=True))
    errors = {loc:page(loc,'not-found',automatic_error=True) for loc in LOCALES}
    head = re.search(r'<head>.*?</head>',errors['zh'],re.S)[0]
    bodies=[]
    for loc in LOCALES:
        body=re.search(r'<body[^>]*>(.*?)</body>',errors[loc],re.S)[1]
        body=body.replace('href="#main"',f'href="#main-{loc}"').replace('id="main"',f'id="main-{loc}"')
        body=body.replace('class="site-shell"',f'class="site-shell" data-localized="{loc}" lang="{translations[loc]["html_lang"]}"')
        body=re.sub(r'(<a class="skip".*?</a>)(<div class="site-shell"[^>]*>)',r'\2\1',body,count=1)
        bodies.append(body)
    titles=' '.join(f'data-title-{loc}="{esc(translations[loc]["not_found_title"] + " — Aster")}"' for loc in LOCALES)
    write(output/'404.html',f'<!doctype html><html lang="zh-CN" data-auto-language="true" data-page="not-found" {titles}>{head}<body class="document-page">'+''.join(bodies)+'</body></html>')
    release={'site_version':SITE_VERSION,'languages':['zh-CN','en'],'version':version,'versionCode':delivery['versionCode'],'scene':{'engine':'Three.js','version':'0.187.0','devices':4,'self_hosted':True},'apk':{'url':apk_url,'github_url':github_url,'sha256':apk_meta['sha256'],'bytes':apk_meta['size']}}
    write(output/'release.json',json.dumps(release,ensure_ascii=False,indent=2)+'\n')
    write(output/'robots.txt',f'User-agent: *\nAllow: /\nSitemap: {DOMAIN}/sitemap.xml\n')
    urls=[]
    for kind in PAGES:
        default='/' if kind=='home' else '/' + kind + '/'
        alternates=''.join(f'<xhtml:link rel="alternate" hreflang="{lang}" href="{DOMAIN}{path}"/>' for lang,path in [('zh-CN',page_path('zh',kind)),('en',page_path('en',kind)),('x-default',default)])
        for loc in LOCALES: urls.append(f'<url><loc>{DOMAIN}{page_path(loc,kind)}</loc>{alternates}</url>')
    write(output/'sitemap.xml','<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">'+''.join(urls)+'</urlset>')
    parsed={}
    for file in output.rglob('*.html'):
        text=file.read_text();assert '{{' not in text,f'Unresolved placeholder: {file}'
        parser=Links();parser.feed(text);parsed[file]=parser
        assert len(parser.ids)==len(set(parser.ids)),f'Duplicate IDs: {file}'
        assert parser.h1 == (2 if file==output/'404.html' else 1),f'Heading count: {file}'
        assert '/downloads/' not in text and 'source.zip' not in text
    for file,parser in parsed.items():
        for link in parser.links:
            url=urlsplit(link)
            if url.scheme or url.netloc: continue
            target=file if not url.path else output/url.path.lstrip('/')
            if target.is_dir(): target/='index.html'
            assert target.is_file(),link
            if url.fragment: assert url.fragment in parsed[target].ids,link
    for file in (output/'assets').rglob('*.js'):
        for module in re.findall(r"(?:from\s+|import\s*)['\"](\./[^'\"]+)['\"]",file.read_text()):
            assert (file.parent/module).is_file(),module
    for loc in LOCALES:
        text=(output/loc/'index.html').read_text()
        assert '<h1>Aster</h1>' in text and text.count('class="download-button"')==1
        assert 'workbench' not in text and 'data-tab' not in text
    manifest={p.relative_to(output).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name!='site-manifest.json'}
    write(output/'site-manifest.json',json.dumps({'site_version':SITE_VERSION,'languages':['zh-CN','en'],'files':manifest},indent=2)+'\n')
    print(json.dumps({'success':True,'files':len(manifest)+1,'bytes':sum(p.stat().st_size for p in output.rglob('*') if p.is_file()),'asset_version':asset_version,'languages':['zh-CN','en'],'links_validated':True,'license_components':len(license_rows)}))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--release-dir',type=Path,required=True,help='Verified delivery.json and signed APK directory')
    args=parser.parse_args()
    build(args.output.resolve(),args.release_dir.resolve())
