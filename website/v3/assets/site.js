'use strict';
(() => {
  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => Array.from(document.querySelectorAll(selector));
  const menu = $('.menu-toggle');
  const nav = $('#site-navigation');
  function closeMenu() {
    nav?.classList.remove('open');
    menu?.setAttribute('aria-expanded', 'false');
    menu?.setAttribute('aria-label', '展开导航');
  }
  menu?.addEventListener('click', () => {
    const open = menu.getAttribute('aria-expanded') !== 'true';
    menu.setAttribute('aria-expanded', String(open));
    menu.setAttribute('aria-label', open ? '收起导航' : '展开导航');
    nav.classList.toggle('open', open);
  });
  $$('#site-navigation a').forEach(link => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu?.getAttribute('aria-expanded') === 'true') {
      closeMenu();
      menu.focus();
    }
  });
  document.addEventListener('click', event => {
    if (!event.target.closest('.site-header')) closeMenu();
  });

  function tabs(selector, activate) {
    const buttons = $$(selector);
    const choose = button => {
      buttons.forEach(item => {
        item.setAttribute('aria-selected', String(item === button));
        item.tabIndex = item === button ? 0 : -1;
      });
      activate(button);
    };
    buttons.forEach((button, index) => {
      button.addEventListener('click', () => choose(button));
      button.addEventListener('keydown', event => {
        let next;
        if (event.key === 'ArrowRight') next = (index + 1) % buttons.length;
        if (event.key === 'ArrowLeft') next = (index + buttons.length - 1) % buttons.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = buttons.length - 1;
        if (next !== undefined) {
          event.preventDefault();
          buttons[next].focus();
          choose(buttons[next]);
        }
      });
    });
  }
  tabs('[data-preview]', button => {
    $$('.preview-pane').forEach(pane => { pane.hidden = pane.id !== button.getAttribute('aria-controls'); });
  });
  const engines = {
    pi: ['账户模型 / 自定义 API', '登录 Aster 账户即可使用 free 与 free go，也可以保留自己的模型配置。'],
    codex: ['自定义模型', '使用你的模型、API 地址和密钥连接原生 Codex 引擎。Codex 不接入账户 free 服务。'],
    gemini: ['自定义模型', '按引擎配置 Gemini API 或兼容的 OpenAI 接口。Gemini 不接入账户 free 服务。'],
  };
  tabs('[data-engine]', button => {
    const values = engines[button.dataset.engine];
    $('#engine-source').textContent = values[0];
    $('#engine-description').textContent = values[1];
    $('#engine-info').setAttribute('aria-labelledby', button.id);
  });
  const count = $('#token-count');
  const format = new Intl.NumberFormat('zh-CN', {maximumFractionDigits: 3});
  count?.addEventListener('input', () => {
    const tokens = Math.max(1000, Math.min(128000, Number(count.value)));
    $('#token-label').textContent = format.format(tokens);
    $('#normal-cost').textContent = format.format(tokens / 1000);
    $('#go-cost').textContent = format.format(tokens * 50 / 1000);
  });

  const data = window.ASTER_RELEASE;
  let activeEdition = 'account';
  function selectEdition(button) {
    if (!data) return;
    activeEdition = button.dataset.edition;
    const edition = data.editions[activeEdition];
    $('#download-content').setAttribute('aria-labelledby', button.id);
    $('#edition-name').textContent = edition.title;
    $('#edition-badge').textContent = edition.badge;
    $('#edition-description').textContent = edition.description;
    $('#apk-link').href = edition.apk.url;
    $('#source-link').href = edition.source.url;
    $('#apk-size').textContent = edition.apk.size_label + ' ↗';
    $('#source-size').textContent = edition.source.size_label + ' ↗';
    $('#apk-sha').textContent = edition.apk.sha256;
    $('#validation-link').href = edition.validation_url;
    $('#checksums-link').href = edition.checksums_url;
    $('#copy-message').textContent = '';
  }
  tabs('[data-edition]', selectEdition);
  $('#copy-checksum')?.addEventListener('click', async () => {
    const text = $('#apk-sha').textContent.trim();
    try {
      await navigator.clipboard.writeText(text);
      $('#copy-message').textContent = '已复制 SHA-256';
    } catch {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents($('#apk-sha'));
      selection.removeAllRanges();
      selection.addRange(range);
      $('#copy-message').textContent = '请复制已选中的校验值';
    }
  });

  async function checkService() {
    if (!$('#service-status')) return;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch('/api/v1/health', {signal:controller.signal, cache:'no-store', credentials:'omit'});
      if (!response.ok) throw new Error('Service status unavailable');
      const result = await response.json();
      if (result.status !== 'ok' || typeof result.capabilities !== 'object') throw new Error('Invalid service status');
      const capabilities = result.capabilities;
      const available = ['free', 'free_go'].filter(tier => capabilities[tier] === true).length;
      $('#service-status').classList.toggle('online', available === 2);
      $('#service-status').classList.toggle('offline', available !== 2);
      $('#service-status-text').textContent = available === 2 ? '账户模型服务已就绪' : available ? '部分账户模型可用' : '账户模型暂不可用';
      $$('[data-tier-state]').forEach(label => {
        const online = capabilities[label.dataset.tierState] === true;
        label.textContent = online ? '已配置' : '暂不可用';
        label.classList.toggle('online', online);
      });
    } catch {
      $('#service-status').classList.add('offline');
      $('#service-status-text').textContent = '状态暂时无法获取，请在应用内重试';
      $$('[data-tier-state]').forEach(label => { label.textContent = '状态未知'; });
    } finally { clearTimeout(timer); }
  }
  checkService();
})();