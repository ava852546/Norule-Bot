import { createI18nStore } from '/web/modules/i18n-store.js';
import { createI18nDefaults } from '/web/modules/i18n-defaults.js';
import { createI18nApplyModule } from '/web/modules/i18n-apply.js';

export function createI18nModule(deps) {
  const {
    byId,
    getValue,
    renderWelcomePreview,
    renderTicketOptions,
    renderMusicStats,
    getLastMusicStats,
    reloadGuilds,
    isGuildsVisible
  } = deps;

  const store = createI18nStore();

  const defaults = createI18nDefaults({
    byId,
    getValue,
    getBundleTextByLanguage: store.getBundleTextByLanguage,
    getUiLanguage: store.getUiLanguage
  });

  function updateMeLine() {
    const meLine = byId('meLine');
    if (!meLine || !meLine.dataset.userId) return;
    const username = meLine.dataset.username || '';
    const userId = meLine.dataset.userId || '';
    const avatarUrl = meLine.dataset.avatarUrl || '';
    const meAvatar = byId('meAvatar');
    if (meAvatar) {
      if (avatarUrl) {
        meAvatar.src = avatarUrl;
        meAvatar.alt = username || 'avatar';
        meAvatar.classList.remove('hidden');
      } else {
        meAvatar.removeAttribute('src');
        meAvatar.classList.add('hidden');
      }
    }
    meLine.textContent = `${store.t('signedInPrefix')}: ${username} (${userId})`;
  }

  let applyModule = null;
  const setUiLanguage = (lang) => {
    store.setUiLanguageState(lang);
    applyModule.applyUiLanguage();
    if (isGuildsVisible()) {
      reloadGuilds().catch(() => {});
    }
  };

  applyModule = createI18nApplyModule({
    byId,
    getValue,
    t: store.t,
    getUiLanguage: store.getUiLanguage,
    getUiLanguages: store.getUiLanguages,
    getBotLanguages: store.getBotLanguages,
    renderWelcomePreview,
    renderTicketOptions,
    renderMusicStats,
    getLastMusicStats,
    updateMeLine,
    applyNotificationTemplateDefaults: defaults.applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty: defaults.applyTicketPanelDefaultsIfEmpty,
    setUiLanguage
  });

  return {
    t: store.t,
    updateMeLine,
    applyNotificationTemplateDefaults: defaults.applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty: defaults.applyTicketPanelDefaultsIfEmpty,
    applyUiLanguage: applyModule.applyUiLanguage,
    setUiLanguage,
    loadWebI18n: store.loadWebI18n,
    setFallbackLanguages: store.setFallbackLanguages,
    getBotLanguages: store.getBotLanguages,
    getUiLanguage: store.getUiLanguage
  };
}
