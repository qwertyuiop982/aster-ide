export function uiLocale() {
  return typeof window !== 'undefined' && window.AsterBoot?.locale === 'zh-Hans' ? 'zh-Hans' : 'en';
}

export function tr(chinese, english) {
  return uiLocale() === 'zh-Hans' ? chinese : english;
}
