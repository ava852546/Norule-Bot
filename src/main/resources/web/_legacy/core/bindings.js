export function bindAppEvents(app, deps) {
  const { createSectionActionsComponent } = deps;
  const { byId, refs, showStatus, modules } = app;

  if (byId('loginBtn')) byId('loginBtn').onclick = () => { location.href = '/auth/login'; };
  if (byId('logoutBtn')) byId('logoutBtn').onclick = () => { location.href = '/auth/logout'; };
  if (byId('guildReloadBtn')) byId('guildReloadBtn').onclick = () => {
    const guildsModule = modules.getGuildsModule();
    if (guildsModule) guildsModule.onGuildSelected(refs.guildSelect.value).catch((error) => showStatus(error.message));
  };

  createSectionActionsComponent({
    loadButton: byId('loadSettingsBtn'),
    saveButton: byId('saveSettingsBtn'),
    onReload: () => {
      const tabManager = modules.getTabManager();
      const dirtyStateModule = modules.getDirtyStateModule?.();
      const activeTab = tabManager?.getActiveTab?.();
      if (dirtyStateModule && activeTab && dirtyStateModule.isDirty(activeTab) && !dirtyStateModule.confirmDiscard([activeTab])) {
        return;
      }
      if (tabManager) tabManager.refreshActive().catch((error) => alert(error.message));
    },
    onSave: () => {
      const settingsFormModule = modules.getSettingsFormModule();
      if (settingsFormModule) settingsFormModule.saveSettings().catch(() => {});
    }
  });

  refs.guildSelect.onchange = () => {
    const guildsModule = modules.getGuildsModule();
    if (guildsModule) guildsModule.onGuildSelected(refs.guildSelect.value).catch((error) => showStatus(error.message));
  };
}
