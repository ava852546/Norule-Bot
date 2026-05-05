import { createApiClient } from '/web/api/ApiClient.js';
import { createI18nModule } from '/web/core/i18n/i18n.js';
import { createTabManager } from '/web/core/tab-manager.js';
import { createDirtyStateModule } from '/web/core/dirty-state.js';
import { createStatusHelpers } from '/web/core/ui-core.js';
import { esc } from '/web/utils/html.js';
import {
  selectedGuildValue,
  selectedGuildLabel,
  guildInitial
} from '/web/utils/guild.js';
import {
  getValue as coreGetValue,
  setValue as coreSetValue,
  getChecked as coreGetChecked,
  setChecked as coreSetChecked,
  getMultiSelectValues as coreGetMultiSelectValues,
  setMultiSelectValues as coreSetMultiSelectValues,
  populateSelect
} from '/web/utils/dom-values.js';
import { formatBytes, formatDateTime } from '/web/utils/formatters.js';

export function createAppRuntime(deps) {
  const {
    createWelcomeModule,
    createNotificationsModule,
    createMusicModule,
    createTicketModule,
    createGuildsModule,
    createSettingsFormModule,
    createAppActionsModule,
    createGeneralTab,
    createNotificationsTab,
    createLogsTab,
    createMusicTab,
    createPrivateRoomTab,
    createWelcomeTab,
    createNumberChainTab,
    createTicketTab,
    createCustomSelectComponent,
    renderHistoryList
  } = deps;

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
  const guildSelectComponent = createCustomSelectComponent(refs.guildSelect);
  const state = {
    lastMusicStats: {}
  };
  const api = createApiClient();
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
  let tabManager = null;
  let i18nModule = null;
  let dirtyStateModule = null;
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

  const notificationsModule = createNotificationsModule({
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
    renderHistoryList,
    formatBytes,
    formatDateTime
  });

  i18nModule = createI18nModule({
    byId,
    getValue,
    renderNotificationPreview: () => notificationsModule.renderNotificationPreview(),
    renderWelcomePreview: () => welcomeModule.renderWelcomePreview(),
    renderTicketOptions: () => ticketModule.renderTicketOptions(),
    renderMusicStats,
    getLastMusicStats: () => state.lastMusicStats,
    reloadGuilds: () => guildsModule?.loadGuilds(),
    isGuildsVisible: () => !refs.guildsBlock.classList.contains('hidden')
  });
  t = (key) => i18nModule.t(key);
  dirtyStateModule = createDirtyStateModule({
    t: (key) => t(key)
  });

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
      renderNotificationPreview: () => notificationsModule.renderNotificationPreview(),
      applyNotificationTemplateDefaults: () => i18nModule.applyNotificationTemplateDefaults(),
      applyTicketPanelDefaultsIfEmpty: () => i18nModule.applyTicketPanelDefaultsIfEmpty(),
      ticketModule,
      dirtyStateModule,
      saveButtonId: 'saveSettingsBtn'
    });

    tabManager = createTabManager({
      defaultTab: 'general',
      sections: [
        createGeneralTab({ settingsFormModule, i18nModule, notificationsModule, welcomeModule, dirtyStateModule }),
        createNotificationsTab({ settingsFormModule, notificationsModule, dirtyStateModule }),
        createLogsTab({ settingsFormModule, dirtyStateModule }),
        createMusicTab({ settingsFormModule, dirtyStateModule }),
        createPrivateRoomTab({ settingsFormModule, dirtyStateModule }),
        createWelcomeTab({ settingsFormModule, welcomeModule, actions: appActions, dirtyStateModule }),
        createNumberChainTab({ settingsFormModule, actions: appActions, dirtyStateModule }),
        createTicketTab({ settingsFormModule, ticketModule, updateSupportRoleCount, dirtyStateModule })
      ]
    });
    tabManager.bind();
    dirtyStateModule.bindBeforeUnload();

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
      updateSupportRoleCount,
      beforeGuildChange: async () => {
        if (!dirtyStateModule?.hasDirty()) return true;
        return dirtyStateModule.confirmDiscard();
      },
      onGuildChanged: async () => {
        dirtyStateModule?.clearAll();
        settingsFormModule.invalidateCurrentGuild();
        await tabManager.onGuildChanged();
      }
    });

    await i18nModule.loadWebI18n(api);
    i18nModule.setUiLanguage(uiLang);
    dirtyStateModule.refreshLabels();
    try {
      const me = await api('/api/me');
      refs.authBlock.classList.add('hidden');
      refs.userBlock.classList.remove('hidden');
      refs.guildsBlock.classList.remove('hidden');
      refs.settingsBlock.classList.remove('hidden');
      guildSelectComponent?.refresh();
      byId('meLine').dataset.username = me.username;
      byId('meLine').dataset.userId = me.id;
      byId('meLine').dataset.avatarUrl = me.avatarUrl || '';
      i18nModule.updateMeLine();
      await guildsModule.loadGuilds();
    } catch (_) {
      i18nModule.setFallbackLanguages();
      i18nModule.applyUiLanguage();
      dirtyStateModule.refreshLabels();
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
      getTabManager: () => tabManager,
      getDirtyStateModule: () => dirtyStateModule,
      getGuildSelectComponent: () => guildSelectComponent,
      musicModule,
      notificationsModule,
      welcomeModule,
      ticketModule,
      i18nModule
    },
    actions: appActions,
    init
  };
}
