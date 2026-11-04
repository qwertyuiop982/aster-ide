(() => {
  'use strict';
  const menu = document.querySelector('.menu-toggle');
  const links = document.querySelector('.nav-links');
  function closeMenu() {
    links?.classList.remove('open');
    menu?.setAttribute('aria-expanded', 'false');
    menu?.setAttribute('aria-label', '展开导航');
  }
  menu?.addEventListener('click', () => {
    const open = menu.getAttribute('aria-expanded') !== 'true';
    menu.setAttribute('aria-expanded', String(open));
    menu.setAttribute('aria-label', open ? '收起导航' : '展开导航');
    links?.classList.toggle('open', open);
  });
  links?.querySelectorAll('a').forEach(link => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); });
  window.matchMedia('(min-width: 651px)').addEventListener('change', event => { if (event.matches) closeMenu(); });
  let timer;
  const notice = document.querySelector('.toast');
  function toast(text) {
    if (!notice) return;
    notice.textContent = text;
    notice.classList.add('visible');
    clearTimeout(timer);
    timer = setTimeout(() => notice.classList.remove('visible'), 3500);
  }
  document.querySelectorAll('[data-copy]').forEach(button => {
    button.addEventListener('click', async () => {
      const text = button.dataset.copy;
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
        } else {
          const field = document.createElement('textarea');
          field.value = text;
          field.setAttribute('readonly', '');
          field.className = 'sr-only';
          document.body.append(field);
          field.select();
          const copied = document.execCommand('copy');
          field.remove();
          if (!copied) throw new Error('Clipboard unavailable');
        }
        toast('已复制完整 SHA-256 校验值');
      } catch {
        toast('暂时无法复制，可在更新说明中查看完整校验值');
      }
    });
  });
})();