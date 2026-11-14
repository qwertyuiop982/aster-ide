'use strict';
// Run with: node website/v5/test_language.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, 'assets/language.js'), 'utf8');
let passed = 0;

function run(options = {}) {
  const url = new URL(options.path || '/', 'https://preview.example');
  const redirects = [];
  const writes = [];
  const handlers = {};
  const root = { lang: options.htmlLanguage || 'zh-CN', dataset: {
    autoLanguage: options.automatic === false ? 'false' : 'true',
    page: options.page || 'home', titleZh: '页面未找到 — Aster', titleEn: 'Page not found — Aster',
  } };
  const document = { documentElement: root, title: '页面未找到 — Aster',
    querySelector(selector) {
      const locale = selector.includes('"zh-CN"') ? 'zh' : 'en';
      const suffix = options.page === 'privacy' ? 'privacy/' : '';
      return { href: 'https://asteride.cn/' + locale + '/' + suffix };
    },
    addEventListener(name, handler) { handlers[name] = handler; },
  };
  const window = { location: { origin: url.origin, pathname: url.pathname, search: url.search,
    hash: url.hash, replace(target) { redirects.push(target); } } };
  Object.defineProperty(window, 'localStorage', { get() {
    if (options.blocked) throw new Error('Storage disabled');
    return { getItem: () => options.saved || null, setItem: (key, value) => writes.push([key, value]) };
  } });
  vm.runInNewContext(source, { window, document, URL, URLSearchParams,
    navigator: { languages: options.languages || [options.device || 'en-US'], language: options.device || 'en-US' } });
  return { root, document, handlers, redirects, writes };
}

function test(name, verify) {
  verify();
  passed += 1;
  console.log('PASS ' + name);
}

for (const device of ['zh-CN', 'zh-TW', 'zh-Hant-HK', 'zh']) {
  test('Chinese device ' + device, () => assert.equal(run({ device }).redirects[0], 'https://preview.example/zh/'));
}
for (const device of ['en-US', 'en-GB', 'de-DE', 'ja-JP']) {
  test('English fallback ' + device, () => assert.equal(run({ device }).redirects[0], 'https://preview.example/en/'));
}
test('Primary device language wins over fallback languages', () => assert.equal(run({ languages: ['en-US', 'zh-CN'] }).root.lang, 'en'));
test('Manual preference overrides device language', () => assert.equal(run({ device: 'en-US', saved: 'zh' }).root.lang, 'zh-CN'));
test('Invalid saved preference is ignored', () => assert.equal(run({ device: 'en-US', saved: 'invalid' }).root.lang, 'en'));
test('Query and fragment survive same-origin language navigation', () => {
  assert.equal(run({ path: '/?campaign=launch#main', device: 'en-US' }).redirects[0], 'https://preview.example/en/?campaign=launch#main');
});
test('Privacy entry selects translated privacy page', () => assert.equal(run({ path: '/privacy/', page: 'privacy' }).redirects[0], 'https://preview.example/en/privacy/'));
test('Explicit English page is stable with Chinese preference', () => {
  const result = run({ path: '/en/', automatic: false, htmlLanguage: 'en', device: 'zh-CN', saved: 'zh' });
  assert.equal(result.root.lang, 'en');
  assert.equal(result.redirects.length, 0);
});
test('Explicit Chinese page is stable with English preference', () => {
  const result = run({ path: '/zh/', automatic: false, device: 'en-US', saved: 'en' });
  assert.equal(result.root.lang, 'zh-CN');
  assert.equal(result.redirects.length, 0);
});
test('Query language override', () => assert.equal(run({ path: '/?lang=zh', saved: 'en' }).root.lang, 'zh-CN'));
test('Unknown URL keeps path and 404 response', () => {
  const result = run({ path: '/missing?campaign=launch', page: 'not-found' });
  assert.equal(result.redirects.length, 0);
  assert.equal(result.root.lang, 'en');
  assert.equal(result.document.title, 'Page not found — Aster');
});
test('Missing page under explicit locale respects the path', () => assert.equal(run({ path: '/en/missing', page: 'not-found', saved: 'zh' }).root.lang, 'en'));
test('404 manual query overrides path language', () => assert.equal(run({ path: '/en/missing?lang=zh', page: 'not-found' }).root.lang, 'zh-CN'));
test('Storage disabled still allows automatic language selection', () => assert.equal(run({ blocked: true }).root.lang, 'en'));
test('Manual language click saves preference without preventing navigation', () => {
  const result = run();
  result.handlers.click({ target: { closest: () => ({ dataset: { language: 'zh' } }) } });
  assert.deepEqual(result.writes, [['aster.language', 'zh']]);
});
test('Manual language click works with blocked storage', () => {
  const result = run({ blocked: true });
  assert.doesNotThrow(() => result.handlers.click({ target: { closest: () => ({ dataset: { language: 'zh' } }) } }));
});
console.log(passed + ' language behavior checks passed.');