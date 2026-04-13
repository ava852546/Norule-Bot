export function bindAppEvents(app) {
  const { byId, refs, showStatus, modules, actions, updateSupportRoleCount } = app;

  if (byId('loginBtn')) byId('loginBtn').onclick = () => { location.href = '/auth/login'; };
  if (byId('logoutBtn')) byId('logoutBtn').onclick = () => { location.href = '/auth/logout'; };
  if (byId('guildReloadBtn')) byId('guildReloadBtn').onclick = () => {
    const guildsModule = modules.getGuildsModule();
    if (guildsModule) guildsModule.onGuildSelected(refs.guildSelect.value).catch((e) => showStatus(e.message));
  };
  if (byId('loadSettingsBtn')) byId('loadSettingsBtn').onclick = () => {
    const settingsFormModule = modules.getSettingsFormModule();
    if (settingsFormModule) settingsFormModule.loadSettings().catch((e) => alert(e.message));
  };
  if (byId('resetGeneralBtn')) byId('resetGeneralBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('general');
  if (byId('resetNotificationsBtn')) byId('resetNotificationsBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('notifications');
  if (byId('openNotificationEditorBtn')) byId('openNotificationEditorBtn').onclick = () => modules.notificationsModule.openNotificationEditor();
  if (byId('closeNotificationEditorBtn')) byId('closeNotificationEditorBtn').onclick = () => modules.notificationsModule.closeNotificationEditor();
  if (byId('saveNotificationSettingsBtn')) byId('saveNotificationSettingsBtn').onclick = () => modules.notificationsModule.saveNotificationSettings().catch(() => {});
  if (byId('resetLogsBtn')) byId('resetLogsBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('logs');
  if (byId('resetMusicBtn')) byId('resetMusicBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('music');
  if (byId('resetPrivateRoomBtn')) byId('resetPrivateRoomBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('privateRoom');
  if (byId('resetWelcomeBtn')) byId('resetWelcomeBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('welcome');
  if (byId('sendWelcomePreviewBtn')) byId('sendWelcomePreviewBtn').onclick = () => actions.sendWelcomePreview().catch(() => {});
  if (byId('openWelcomeEditorBtn')) byId('openWelcomeEditorBtn').onclick = () => modules.welcomeModule.openWelcomeEditor();
  if (byId('closeWelcomeEditorBtn')) byId('closeWelcomeEditorBtn').onclick = () => modules.welcomeModule.closeWelcomeEditor();
  if (byId('saveWelcomeSettingsBtn')) byId('saveWelcomeSettingsBtn').onclick = () => modules.welcomeModule.saveWelcomeSettings().catch(() => {});
  if (byId('resetNumberChainBtn')) byId('resetNumberChainBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('numberChain');
  if (byId('resetTicketBtn')) byId('resetTicketBtn').onclick = () => modules.getSettingsFormModule()?.resetSection('ticket');
  if (byId('s_language')) byId('s_language').addEventListener('change', () => {
    modules.i18nModule.applyNotificationTemplateDefaults();
    modules.notificationsModule.renderNotificationPreview();
    modules.i18nModule.applyTicketPanelDefaultsIfEmpty();
    modules.welcomeModule.renderWelcomePreview();
  });
  if (byId('resetNumberChainProgressBtn')) byId('resetNumberChainProgressBtn').onclick = () => actions.resetNumberChainProgress();
  if (byId('sendTicketPanelBtn')) byId('sendTicketPanelBtn').onclick = () => modules.ticketModule.sendTicketPanel();
  if (byId('t_addOptionBtn')) byId('t_addOptionBtn').onclick = () => modules.ticketModule.addTicketOption();
  if (byId('t_deleteOptionBtn')) byId('t_deleteOptionBtn').onclick = () => modules.ticketModule.deleteCurrentTicketOption();
  if (byId('loadTicketHistoryBtn')) byId('loadTicketHistoryBtn').onclick = () => modules.ticketModule.loadTicketHistory().catch(() => {});
  if (byId('ticketHistoryList')) byId('ticketHistoryList').onclick = (event) => modules.ticketModule.handleTicketHistoryListClick(event).catch(() => {});
  if (byId('saveSettingsBtn')) byId('saveSettingsBtn').onclick = () => {
    const settingsFormModule = modules.getSettingsFormModule();
    if (settingsFormModule) settingsFormModule.saveSettings().catch(() => {});
  };
  if (byId('welcomeEditorModal')) byId('welcomeEditorModal').onclick = (event) => {
    if (event.target === byId('welcomeEditorModal')) {
      modules.welcomeModule.closeWelcomeEditor();
    }
  };
  if (byId('notificationEditorModal')) byId('notificationEditorModal').onclick = (event) => {
    if (event.target === byId('notificationEditorModal')) {
      modules.notificationsModule.closeNotificationEditor();
    }
  };
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && byId('welcomeEditorModal') && !byId('welcomeEditorModal').classList.contains('hidden')) {
      modules.welcomeModule.closeWelcomeEditor();
    }
    if (event.key === 'Escape' && byId('notificationEditorModal') && !byId('notificationEditorModal').classList.contains('hidden')) {
      modules.notificationsModule.closeNotificationEditor();
    }
  });
  if (byId('t_supportRoleIds')) byId('t_supportRoleIds').addEventListener('change', updateSupportRoleCount);
  modules.ticketModule.bindTicketOptionEditorAutoSync();
  modules.notificationsModule.bindNotificationPreviewAutoSync();
  modules.welcomeModule.bindWelcomePreviewAutoSync();
  refs.guildSelect.onchange = () => {
    const guildsModule = modules.getGuildsModule();
    if (guildsModule) guildsModule.onGuildSelected(refs.guildSelect.value).catch((e) => showStatus(e.message));
  };
}
