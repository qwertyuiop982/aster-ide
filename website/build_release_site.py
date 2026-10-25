"""Build the Aster product website from a verified ordinary v1.0.1 delivery."""
from pathlib import Path
import argparse
import datetime
import hashlib
import html
import json
import re
import tarfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, default=ROOT.parent / 'artifacts/v1.0.1')
    parser.add_argument('--output', type=Path, default=ROOT / 'build-v101')
    parser.add_argument('--site-url', default='https://qwertyuiop982.github.io/aster-ide')
    parser.add_argument('--repository', default='qwertyuiop982/aster-ide')
    parser.add_argument('--download-proxy', default='https://gh-proxy.org/')
    parser.add_argument('--preview', action='store_true', help='Local preview only, using APK metadata before source archive is ready')
    options = parser.parse_args()
    site_url = options.site_url.rstrip('/')
    parsed = urlsplit(site_url)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.query or parsed.fragment:
        raise RuntimeError('Expected an HTTPS site URL')
    base_path = parsed.path.rstrip('/')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', options.repository):
        raise RuntimeError('Invalid repository')
    if options.preview:
        record = json.loads((ROOT.parent / 'logs/v1.0.1-dual/release-apks-build.json').read_text())
        apk = record['artifacts']['release']
        facts = {'name': 'Aster', 'version': '1.0.1', 'versionCode': 11, 'abi': 'arm64-v8a', 'minSdk': 24,
                 'targetSdk': 28, 'releaseDate': '2026-10-18', 'engines': ['Pi', 'Codex', 'Gemini'],
                 'downloads': {'aster-v1.0.1-release.apk': {'size': Path(apk['exportPath']).stat().st_size, 'sha256': apk['sha256']},
                               'aster-source-1.0.1.zip': {'size': 0, 'sha256': ''}}, 'previewOnly': True}
    else:
        facts = json.loads((options.release_dir / 'delivery.json').read_text())
        for name, item in facts['downloads'].items():
            path = options.release_dir / name
            if path.name != name or path.stat().st_size != item['size'] or sha256(path) != item['sha256']:
                raise RuntimeError('Delivery mismatch: ' + name)
    version = facts['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise RuntimeError('Invalid version')
    apk_name = f'aster-v{version}-release.apk'
    source_name = f'aster-source-{version}.zip'
    output = options.output.resolve()
    if output in (ROOT, ROOT.parent) or not output.is_relative_to(ROOT):
        raise RuntimeError('Build output must be inside website/')
    output.mkdir(parents=True, exist_ok=True)
    generated = []
    release_base = 'https://github.com/' + options.repository + '/releases/download/v' + version + '/'
    proxy = options.download_proxy.rstrip('/') + '/' if options.download_proxy else ''
    def download_url(name):
        return proxy + release_base + name
    def write(relative, text):
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if base_path and relative.endswith('.html'):
            text = re.sub(r'(href|src)="(/(?!/)[^"]*)"', lambda m: m.group(1) + '="' + base_path + m.group(2) + '"', text)
        target.write_text(text)
        generated.append(relative)
    values = {}
    for filename, key, target_name in [('site-v2.css', 'site_css', 'site'), ('app-v2.js', 'site_js', 'app'), ('aster-v2.svg', 'logo', 'aster')]:
        source = ROOT / 'assets' / filename
        target = f'assets/{target_name}.{sha256(source)[:12]}.{filename.rsplit(".", 1)[-1]}'
        write(target, source.read_text())
        values[key] = target
    release_day = datetime.date.fromisoformat(facts['releaseDate'])
    values.update(apk_url=download_url(apk_name), source_url=download_url(source_name),
                  checksums_url=download_url(f'SHA256SUMS-{version}.txt'),
                  validation_url=download_url(f'validation-{version}.json'),
                  client_notice_url=download_url(f'CLIENT-NOTICE-{version}.txt'),
                  version=version, site_url=site_url, apk_file=apk_name, source_file=source_name,
                  apk_sha256=facts['downloads'][apk_name]['sha256'],
                  apk_size=f"{facts['downloads'][apk_name]['size'] / 1024 ** 2:.1f} MiB",
                  source_size=('准备中' if options.preview else f"{facts['downloads'][source_name]['size'] / 1024 ** 2:.1f} MiB"),
                  release_date=f'{release_day.year} 年 {release_day.month} 月 {release_day.day} 日')
    def render(text):
        for key, value in values.items():
            text = text.replace('{{' + key + '}}', html.escape(str(value), quote=True))
        if re.search(r'\{\{[^}]+\}\}', text):
            raise RuntimeError('Unresolved website placeholder')
        return text
    page = render((ROOT / 'index.template.html').read_text())
    write('index.html', page)
    header = re.search(r'<header class="site-header">.*?</header>', page, re.S).group(0).replace('href="#', 'href="/#')
    footer = re.search(r'<footer class="site-footer">.*?</footer>', page, re.S).group(0).replace('href="#', 'href="/#')
    head = re.search(r'<head>.*?</head>', page, re.S).group(0)
    documents = [
        ('guide', '使用指南', '从安装，到第一次工具调用。', '为项目环境、Agent 配置、网页自动化和桌面工具找到入口。',
         [('install', '安装与升级'), ('workspace', '准备工作台'), ('models', '配置模型'), ('browser', '轻舟网页工具'),
          ('device', '桌面与识图'), ('mcp', 'MCP 工具'), ('troubleshooting', '问题排查'), ('validation', '验证范围')]),
        ('licenses', '源码与许可', '每一份能力，都有来源。', '查看对应源码、组件集成范围与许可证声明。',
         [('source', '对应源码'), ('components', '开源组件'), ('inventory', '固定版本'), ('research', '本地研究版'), ('checksums', '下载与校验')])
    ]
    for slug, title, heading, description, links in documents:
        doc_head = head.replace('<title>Aster — 从代码，到行动。</title>', f'<title>{title} — Aster {version}</title>')
        doc_head = doc_head.replace(f'{site_url}/"', f'{site_url}/{slug}/"')
        doc_head = re.sub(r'<meta name="description" content="[^"]*">', '<meta name="description" content="' + description + '">', doc_head)
        doc_nav = ''.join(f'<a href="#{key}">{label}</a>' for key, label in links)
        content = render((ROOT / f'{slug}.content.html').read_text())
        write(f'{slug}/index.html', '<!doctype html><html lang="zh-CN">' + doc_head + '<body><a class="skip-link" href="#main">跳到主要内容</a>' + header +
              f'<main id="main"><div class="wrap docs-hero"><p class="eyebrow">ASTER {version} / {title}</p><h1>{heading}</h1><p>{description}</p></div>' +
              '<div class="wrap docs-layout"><nav class="docs-nav" aria-label="本页目录">' + doc_nav + '</nav><div class="docs-content">' + content + '</div></div></main>' + footer + '</body></html>')
    write('licenses/client-sources.json', (ROOT.parent / 'client/SOURCES.json').read_text())
    for name, path in [('qingzhou', 'client/browser/src/main/assets/licenses/browser-LICENSE.txt'),
                       ('shizuku-api', 'client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt'),
                       ('operit', 'vendor/operit/LICENSE'), ('project', 'LICENSE')]:
        write(f'licenses/{name}.txt', (ROOT.parent / path).read_text())
    write('.nojekyll', '')
    write('robots.txt', f'User-agent: *\nAllow: /\nSitemap: {site_url}/sitemap.xml\n')
    day = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    write('sitemap.xml', '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' +
          ''.join(f'<url><loc>{site_url}{path}</loc><lastmod>{day}</lastmod></url>' for path in ['/', '/guide/', '/licenses/']) + '</urlset>\n')
    write('404.html', '<!doctype html><html lang="zh-CN">' + head.replace('<title>Aster — 从代码，到行动。</title>', '<title>页面未找到 — Aster</title>') +
          '<body>' + header + '<main class="wrap section"><p class="eyebrow">404 / PAGE NOT FOUND</p><h1>这一页，暂时没有内容。</h1><p class="section-description">回到工作台，继续寻找下一个想法。</p><p class="section-description"><a class="button primary" href="/">返回首页 ↗</a></p></main>' + footer + '</body></html>')
    public_release = {key: facts[key] for key in ['name', 'version', 'versionCode', 'abi', 'minSdk', 'targetSdk', 'releaseDate', 'engines']}
    public_release.update(siteUrl=site_url, deviceUiVerified=False, previewOnly=options.preview,
                          sourceLicenses='Component-specific; GPL/AGPL/LGPL and other upstream licenses retained in corresponding source',
                          downloads={name: {'url': download_url(name), **item} for name, item in facts['downloads'].items()})
    write('release.json', json.dumps(public_release, ensure_ascii=False, indent=2) + '\n')
    files = {name: sha256(output / name) for name in generated}
    manifest = {'version': version, 'siteUrl': site_url, 'previewOnly': options.preview, 'files': files, 'downloads': public_release['downloads']}
    write('.build-manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    bundle = ROOT / 'aster-site-v101.tar.gz'
    with tarfile.open(bundle, 'w:gz') as archive:
        for relative in sorted(generated):
            archive.add(output / relative, arcname=relative, recursive=False)
    print(json.dumps({'version': version, 'previewOnly': options.preview, 'output': str(output), 'bundle': str(bundle), 'files': len(generated),
                      'bundleSize': bundle.stat().st_size, 'bundleSha256': sha256(bundle)}, indent=2))


if __name__ == '__main__':
    main()
