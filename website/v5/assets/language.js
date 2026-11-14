(() => {
  'use strict';
  const root = document.documentElement;
  const storageKey = 'aster.language';
  const themeStorageKey = 'aster.theme';
  const valid = value => value === 'zh' || value === 'en';
  const requested = new URLSearchParams(window.location.search).get('lang');
  const pathLanguage = window.location.pathname.split('/')[1];
  let saved;
  try { saved = window.localStorage.getItem(storageKey); } catch (_) { /* Storage is optional. */ }
  const device = (navigator.languages && navigator.languages[0]) || navigator.language || 'en';
  const preferred = valid(requested) ? requested : valid(saved) ? saved : /^zh(?:-|$)/i.test(device) ? 'zh' : 'en';
  const automatic = root.dataset.autoLanguage === 'true';
  const language = automatic ? (valid(requested) ? requested : valid(pathLanguage) ? pathLanguage : preferred) : root.lang.startsWith('zh') ? 'zh' : 'en';
  const themeEnabled = root.dataset.page === 'home';
  let lightTheme = false;
  if (themeEnabled) {
    try { lightTheme = window.localStorage.getItem(themeStorageKey) === 'light'; } catch (_) { /* Storage is optional. */ }
    root.classList.toggle('theme-light', lightTheme);
  }
  const updateThemeButton = button => {
    if (!button) return;
    const label = lightTheme ? button.dataset.darkLabel : button.dataset.lightLabel;
    button.setAttribute('aria-pressed', String(lightTheme));
    button.setAttribute('aria-label', label);
    button.setAttribute('title', label);
  };
  if (themeEnabled) document.addEventListener('DOMContentLoaded', () => updateThemeButton(document.getElementById('theme-toggle')));

  if (automatic) {
    root.lang = language === 'zh' ? 'zh-CN' : 'en';
    if (root.dataset.page === 'not-found') {
      document.title = root.dataset[language === 'zh' ? 'titleZh' : 'titleEn'];
    } else {
      const alternate = document.querySelector('link[rel="alternate"][hreflang="' + (language === 'zh' ? 'zh-CN' : 'en') + '"]');
      if (alternate) {
        const target = new URL(new URL(alternate.href).pathname, window.location.origin);
        target.search = window.location.search;
        target.hash = window.location.hash;
        if (target.pathname !== window.location.pathname) window.location.replace(target.href);
      }
    }
  }

  // Explicit language links work even when browser storage is unavailable.
  document.addEventListener('click', event => {
    const themeButton = event.target.closest && event.target.closest('button[data-theme-toggle]');
    if (themeEnabled && themeButton) {
      lightTheme = !lightTheme;
      root.classList.toggle('theme-light', lightTheme);
      updateThemeButton(themeButton);
      try { window.localStorage.setItem(themeStorageKey, lightTheme ? 'light' : 'dark'); } catch (_) { /* Keep the current-page preference. */ }
      return;
    }
    const link = event.target.closest && event.target.closest('a[data-language]');
    if (!link || !valid(link.dataset.language)) return;
    try { window.localStorage.setItem(storageKey, link.dataset.language); } catch (_) { /* Keep normal navigation. */ }
  });
})();
