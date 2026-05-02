export function createGuildsModule(deps) {
  const {
    byId,
    guildSelect,
    guildList,
    api,
    esc,
    guildInitial,
    t,
    showStatus,
    populateSelect,
    textChannelSelectIds,
    voiceChannelSelectIds,
    getMultiSelectValues,
    updateSupportRoleCount,
    beforeGuildChange,
    onGuildChanged
  } = deps;

  let channelsCache = { textChannels: [], voiceChannels: [] };
  let rolesCache = [];

  function selectedGuild() {
    return guildSelect?.value || '';
  }

  function refreshGuildSelectUi() {
    guildSelect?.dispatchEvent(new Event('custom-select:refresh'));
  }

  function syncActiveGuildState(guildId = selectedGuild()) {
    guildList?.querySelectorAll('.guild-item').forEach((node) => {
      const isActive = node.dataset.manageable === '1' && node.dataset.guildId === String(guildId || '');
      node.classList.toggle('active', isActive);
    });
  }

  async function loadChannels() {
    const guildId = selectedGuild();
    if (!guildId) return;
    const payload = await api(`/api/guild/${guildId}/channels`);
    channelsCache = {
      textChannels: Array.isArray(payload?.textChannels) ? payload.textChannels : [],
      voiceChannels: Array.isArray(payload?.voiceChannels) ? payload.voiceChannels : []
    };
    textChannelSelectIds.forEach((id) => populateSelect(byId, id, channelsCache.textChannels));
    voiceChannelSelectIds.forEach((id) => populateSelect(byId, id, channelsCache.voiceChannels));
  }

  async function loadRoles() {
    const guildId = selectedGuild();
    if (!guildId) return;
    const payload = await api(`/api/guild/${guildId}/roles`);
    rolesCache = Array.isArray(payload?.roles) ? payload.roles : [];
    const select = byId('t_supportRoleIds');
    if (!select) return;
    const previous = new Set(getMultiSelectValues('t_supportRoleIds'));
    select.innerHTML = '';
    rolesCache.forEach((role) => {
      const option = document.createElement('option');
      option.value = String(role.id ?? '');
      option.textContent = role.name || String(role.id ?? '');
      option.selected = previous.has(option.value);
      select.appendChild(option);
    });
    updateSupportRoleCount();
  }

  async function onGuildSelected(gid) {
    if (!gid) return;
    const previousGuildId = selectedGuild();
    const allowed = await beforeGuildChange?.({
      currentGuildId: previousGuildId,
      nextGuildId: gid
    });
    if (allowed === false) {
      guildSelect.value = previousGuildId;
      refreshGuildSelectUi();
      syncActiveGuildState(previousGuildId);
      return;
    }
    guildSelect.value = gid;
    refreshGuildSelectUi();
    syncActiveGuildState(gid);
    await loadChannels().catch(() => {});
    await loadRoles().catch(() => {});
    await onGuildChanged?.(gid).catch((error) => showStatus(error.message));
  }

  async function loadGuilds() {
    const response = await api('/api/guilds');
    const allGuilds = response.guilds || [];
    const manageable = allGuilds.filter((item) => item.botInGuild);
    const invitables = allGuilds.filter((item) => !item.botInGuild);

    guildList.innerHTML = '';
    allGuilds.forEach((item) => {
      const buttonHtml = item.botInGuild
        ? `<button class="primary" data-manage="${esc(item.id)}">${esc(t('manage'))}</button>`
        : `<a class="guild-link-button warn" href="${esc(item.inviteUrl)}" target="_blank" rel="noopener">${esc(t('inviteBot'))}</a>`;
      const badge = item.botInGuild
        ? (item.botCanManage ? t('badgeManageable') : t('badgeMissingPerm'))
        : t('badgeBotMissing');
      const iconHtml = item.iconUrl
        ? `<img class="guild-icon" src="${esc(item.iconUrl)}" alt="${esc(item.name)}" loading="lazy" referrerpolicy="no-referrer" />`
        : `<div class="guild-icon guild-icon-fallback" aria-hidden="true">${esc(guildInitial(item.name))}</div>`;
      const block = document.createElement('div');
      block.className = `guild-item ${item.botInGuild ? 'is-manageable' : 'is-invite'}`;
      block.dataset.guildId = String(item.id ?? '');
      block.dataset.manageable = item.botInGuild ? '1' : '0';
      block.innerHTML = `
        <div class="guild-card-top">
          <div class="guild-head">
            ${iconHtml}
            <div class="guild-copy">
              <div class="guild-name">${esc(item.name)}</div>
              <div class="badge">${esc(badge)}</div>
            </div>
          </div>
          <div class="guild-item-actions">${buttonHtml}</div>
        </div>
      `;
      guildList.appendChild(block);
    });

    guildList.querySelectorAll('[data-manage]').forEach((btn) => {
      btn.onclick = async () => {
        const gid = btn.getAttribute('data-manage');
        await onGuildSelected(gid);
      };
    });

    guildSelect.innerHTML = '';
    manageable.forEach((item) => {
      const option = document.createElement('option');
      option.value = item.id;
      option.textContent = item.name;
      guildSelect.appendChild(option);
    });
    refreshGuildSelectUi();

    if (!guildSelect.value) {
      syncActiveGuildState('');
      showStatus(invitables.length > 0 ? t('noGuildWithBot') : t('noManageableGuild'));
      return;
    }
    syncActiveGuildState(guildSelect.value);
    await onGuildSelected(guildSelect.value);
  }

  return {
    loadChannels,
    loadRoles,
    onGuildSelected,
    loadGuilds,
    getChannelsCache: () => channelsCache,
    getRolesCache: () => rolesCache
  };
}
