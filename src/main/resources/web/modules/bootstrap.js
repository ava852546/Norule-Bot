import { createWelcomeModule } from '/web/modules/welcome.js';
import { createMusicModule } from '/web/modules/music.js';
import { createTicketModule } from '/web/modules/ticket.js';
import { createI18nModule } from '/web/modules/i18n.js';
import { createGuildsModule } from '/web/modules/guilds.js';
import { createSettingsFormModule } from '/web/modules/settings-form.js';
import { createAppActionsModule } from '/web/modules/app-actions.js';
import {
  esc,
  createApi,
  createStatusHelpers,
  selectedGuildValue,
  selectedGuildLabel,
  getValue as coreGetValue,
  setValue as coreSetValue,
  getChecked as coreGetChecked,
  setChecked as coreSetChecked,
  getMultiSelectValues as coreGetMultiSelectValues,
  setMultiSelectValues as coreSetMultiSelectValues,
  guildInitial,
  populateSelect,
  formatBytes,
  formatDateTime,
  initTabs
} from '/web/modules/ui-core.js';

export function createAppRuntime() {
  const byId = (id) => document.getElementById(id);
  const refs = {
    authBlock: byId('authBlock'),
    userBlock: byId('userBlock'),
    guildsBlock: byId('guildsBlock'),
    settingsBlock: byId('settingsBlock'),
    statusEl: byId('status'),
    guildSelect: byId('guildSelect'),
    guildList: byId('guildList')
  };
  const state = {
    lastMusicStats: {}
  };
  const api = createApi();
  const { showStatus, showToast } = createStatusHelpers(refs.statusEl);
  const TEXT_CHANNEL_SELECT_IDS = [
    'n_memberChannelId',
    'n_memberJoinChannelId',
    'n_memberLeaveChannelId',
    'n_voiceChannelId',
    'l_channelId',
    'l_messageLogChannelId',
    'l_commandUsageChannelId',
    'l_channelLifecycleChannelId',
    'l_roleLogChannelId',
    'l_moderationLogChannelId',
    'm_commandChannelId',
    'w_channelId',
    'nc_channelId',
    't_panelChannelId'
  ];
  const VOICE_CHANNEL_SELECT_IDS = ['p_triggerVoiceChannelId'];

  function selectedGuild() {
    return selectedGuildValue(refs.guildSelect);
  }

  function selectedGuildName() {
    return selectedGuildLabel(refs.guildSelect, 'NoRule Bot');
  }

  function getValue(id) {
    return coreGetValue(byId, id);
  }

  function setValue(id, value) {
    coreSetValue(byId, id, value);
  }

  function getChecked(id) {
    return coreGetChecked(byId, id);
  }

  function setChecked(id, value) {
    coreSetChecked(byId, id, value);
  }

  function getMultiSelectValues(id) {
    return coreGetMultiSelectValues(byId, id);
  }

  function setMultiSelectValues(id, values) {
    coreSetMultiSelectValues(byId, id, values);
    updateSupportRoleCount();
  }

  function updateSupportRoleCount() {
    const el = byId('t_supportRoleIds');
    const counter = byId('t_supportRoleCount');
    if (!el || !counter) return;
    counter.textContent = String(el.selectedOptions.length);
  }

  let guildsModule = null;
  let settingsFormModule = null;
  let i18nModule = null;
  let t = (key) => key;

  const musicModule = createMusicModule({ byId, esc, t: (key) => t(key) });
  function renderMusicStats(stats) {
    musicModule.renderMusicStats(stats);
    state.lastMusicStats = musicModule.getLastMusicStats();
  }

  const welcomeModule = createWelcomeModule({
    byId,
    esc,
    t: (key) => t(key),
    getValue,
    getChecked,
    selectedGuildName,
    saveSettings: () => settingsFormModule?.saveSettings()
  });

  const ticketModule = createTicketModule({
    byId,
    esc,
    t: (key) => t(key),
    getValue,
    getChecked,
    setValue,
    setChecked,
    selectedGuild,
    api,
    showStatus,
    showToast,
    formatBytes,
    formatDateTime
  });

  i18nModule = createI18nModule({
    byId,
    getValue,
    renderWelcomePreview: () => welcomeModule.renderWelcomePreview(),
    renderTicketOptions: () => ticketModule.renderTicketOptions(),
    renderMusicStats,
    getLastMusicStats: () => state.lastMusicStats,
    reloadGuilds: () => guildsModule?.loadGuilds(),
    isGuildsVisible: () => !refs.guildsBlock.classList.contains('hidden')
  });
  t = (key) => i18nModule.t(key);

  const appActions = createAppActionsModule({
    byId,
    api,
    t: (key) => t(key),
    selectedGuild,
    collectSettings: () => settingsFormModule?.collectSettings() || {},
    setValue,
    showStatus,
    showToast
  });

  async function init() {
    initTabs();
    const uiLang = localStorage.getItem('norule.web.ui.lang') || 'zh-TW';
    settingsFormModule = createSettingsFormModule({
      getValue,
      setValue,
      getChecked,
      setChecked,
      setMultiSelectValues,
      getMultiSelectValues,
      selectedGuild,
      api,
      t: (key) => t(key),
      showStatus,
      showToast,
      renderMusicStats,
      renderWelcomePreview: () => welcomeModule.renderWelcomePreview(),
      applyNotificationTemplateDefaults: () => i18nModule.applyNotificationTemplateDefaults(),
      applyTicketPanelDefaultsIfEmpty: () => i18nModule.applyTicketPanelDefaultsIfEmpty(),
      ticketModule,
      saveButtonId: 'saveSettingsBtn'
    });
    guildsModule = createGuildsModule({
      byId,
      guildSelect: refs.guildSelect,
      guildList: refs.guildList,
      api,
      esc,
      guildInitial,
      t: (key) => t(key),
      showStatus,
      populateSelect,
      textChannelSelectIds: TEXT_CHANNEL_SELECT_IDS,
      voiceChannelSelectIds: VOICE_CHANNEL_SELECT_IDS,
      getMultiSelectValues,
      loadSettings: () => settingsFormModule?.loadSettings(),
      loadTicketHistory: () => ticketModule.loadTicketHistory(),
      updateSupportRoleCount
    });
    await i18nModule.loadWebI18n(api);
    i18nModule.setUiLanguage(uiLang);
    try {
      const me = await api('/api/me');
      refs.authBlock.classList.add('hidden');
      refs.userBlock.classList.remove('hidden');
      refs.guildsBlock.classList.remove('hidden');
      refs.settingsBlock.classList.remove('hidden');
      byId('meLine').dataset.username = me.username;
      byId('meLine').dataset.userId = me.id;
      byId('meLine').dataset.avatarUrl = me.avatarUrl || '';
      i18nModule.updateMeLine();
      await guildsModule.loadGuilds();
    } catch (_) {
      i18nModule.setFallbackLanguages();
      i18nModule.applyUiLanguage();
    }
  }

  return {
    byId,
    refs,
    api,
    showStatus,
    showToast,
    selectedGuild,
    selectedGuildName,
    updateSupportRoleCount,
    modules: {
      getGuildsModule: () => guildsModule,
      getSettingsFormModule: () => settingsFormModule,
      musicModule,
      welcomeModule,
      ticketModule,
      i18nModule
    },
    actions: appActions,
    init
  };
}
