export function createI18nStore() {
  let uiLanguages = [];
  let botLanguages = [];
  let defaultUiLanguage = 'zh-TW';
  let uiLang = 'zh-TW';

  const bundles = {
    'zh-TW': {},
    'zh-CN': {},
    en: {}
  };

  function t(key) {
    const dict = bundles[uiLang] || bundles['zh-TW'] || {};
    return dict[key] || bundles.en[key] || key;
  }

  function getBundleTextByLanguage(code, key) {
    const bundle = bundles[code] || {};
    return bundle[key] ? String(bundle[key]) : '';
  }

  function getUiLanguages() {
    return uiLanguages;
  }

  function getBotLanguages() {
    return botLanguages;
  }

  function getDefaultUiLanguage() {
    return defaultUiLanguage;
  }

  function getUiLanguage() {
    return uiLang;
  }

  function setUiLanguageState(lang) {
    const codes = uiLanguages.map(item => item.code);
    uiLang = codes.includes(lang) ? lang : (codes.includes(defaultUiLanguage) ? defaultUiLanguage : 'zh-TW');
    localStorage.setItem('norule.web.ui.lang', uiLang);
    return uiLang;
  }

  function setFallbackLanguages() {
    uiLanguages = [
      { code: 'zh-TW', label: '繁體中文' },
      { code: 'zh-CN', label: '简体中文' },
      { code: 'en', label: 'ENG' }
    ];
    botLanguages = [
      { code: 'zh-TW', label: 'Traditional Chinese' },
      { code: 'zh-CN', label: 'Simplified Chinese' },
      { code: 'en', label: 'English' }
    ];
  }

  async function loadWebI18n(api) {
    try {
      const payload = await api('/api/web/i18n');
      defaultUiLanguage = payload?.defaultLanguage || 'zh-TW';
      uiLanguages = Array.isArray(payload?.uiLanguages) ? payload.uiLanguages : [];
      botLanguages = Array.isArray(payload?.botLanguages) ? payload.botLanguages : [];
      const nextBundles = payload?.bundles || {};
      Object.keys(nextBundles).forEach(lang => {
        bundles[lang] = Object.assign({}, bundles[lang] || {}, nextBundles[lang] || {});
      });
      if (uiLanguages.length === 0) {
        uiLanguages = Object.keys(nextBundles).map(code => ({ code, label: code }));
      }
      if (botLanguages.length === 0) {
        botLanguages = [
          { code: 'zh-TW', label: 'Traditional Chinese' },
          { code: 'zh-CN', label: 'Simplified Chinese' },
          { code: 'en', label: 'English' }
        ];
      }
    } catch (_) {
      setFallbackLanguages();
    }
  }

  return {
    t,
    getBundleTextByLanguage,
    getUiLanguages,
    getBotLanguages,
    getDefaultUiLanguage,
    getUiLanguage,
    setUiLanguageState,
    setFallbackLanguages,
    loadWebI18n
  };
}
