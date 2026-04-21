export function createSettingsFormModule(deps) {
  const {
    getValue,
    setValue,
    getChecked,
    setChecked,
    setMultiSelectValues,
    getMultiSelectValues,
    selectedGuild,
    api,
    t,
    showStatus,
    showToast,
    renderMusicStats,
    renderNotificationPreview,
    renderWelcomePreview,
    applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty,
    ticketModule,
    dirtyStateModule,
    saveButtonId = 'saveSettingsBtn'
  } = deps;

  const SECTION_SCHEMAS = {
    general: {
      fields: [
        { id: 's_language', path: 'language', type: 'value', default: 'zh-TW' }
      ]
    },
    notifications: {
      fields: [
        { id: 'n_enabled', path: 'notifications.enabled', type: 'checked', default: true },
        { id: 'n_memberJoinEnabled', path: 'notifications.memberJoinEnabled', type: 'checked', default: true },
        { id: 'n_memberLeaveEnabled', path: 'notifications.memberLeaveEnabled', type: 'checked', default: true },
        { id: 'n_voiceLogEnabled', path: 'notifications.voiceLogEnabled', type: 'checked', default: true },
        { id: 'n_memberChannelId', path: 'notifications.memberChannelId', type: 'value', default: '' },
        { id: 'n_memberJoinChannelId', path: 'notifications.memberJoinChannelId', type: 'value', default: '' },
        { id: 'n_memberLeaveChannelId', path: 'notifications.memberLeaveChannelId', type: 'value', default: '' },
        { id: 'n_voiceChannelId', path: 'notifications.voiceChannelId', type: 'value', default: '' },
        { id: 'n_memberJoinTitle', path: 'notifications.memberJoinTitle', type: 'value', default: '' },
        { id: 'n_memberJoinMessage', path: 'notifications.memberJoinMessage', type: 'value', default: '' },
        { id: 'n_memberJoinThumbnailUrl', path: 'notifications.memberJoinThumbnailUrl', type: 'value', default: '' },
        { id: 'n_memberJoinImageUrl', path: 'notifications.memberJoinImageUrl', type: 'value', default: '' },
        { id: 'n_memberLeaveMessage', path: 'notifications.memberLeaveMessage', type: 'value', default: '' },
        { id: 'n_voiceJoinMessage', path: 'notifications.voiceJoinMessage', type: 'value', default: '' },
        { id: 'n_voiceLeaveMessage', path: 'notifications.voiceLeaveMessage', type: 'value', default: '' },
        { id: 'n_voiceMoveMessage', path: 'notifications.voiceMoveMessage', type: 'value', default: '' },
        { id: 'n_memberJoinColor', path: 'notifications.memberJoinColor', type: 'value', default: '#2ECC71' },
        { id: 'n_memberLeaveColor', path: 'notifications.memberLeaveColor', type: 'value', default: '#E74C3C' },
        { id: 'n_voiceJoinColor', path: 'notifications.voiceJoinColor', type: 'value', default: '#2ECC71' },
        { id: 'n_voiceLeaveColor', path: 'notifications.voiceLeaveColor', type: 'value', default: '#E74C3C' },
        { id: 'n_voiceMoveColor', path: 'notifications.voiceMoveColor', type: 'value', default: '#5865F2' }
      ],
      afterReset() {
        applyNotificationTemplateDefaults();
        renderNotificationPreview();
      },
      afterPopulate() {
        applyNotificationTemplateDefaults();
        renderNotificationPreview();
      }
    },
    welcome: {
      fields: [
        { id: 'w_enabled', path: 'welcome.enabled', type: 'checked', default: false },
        { id: 'w_channelId', path: 'welcome.channelId', type: 'value', default: '' },
        { id: 'w_title', path: 'welcome.title', type: 'value', default: '' },
        { id: 'w_message', path: 'welcome.message', type: 'value', default: '' },
        { id: 'w_thumbnailUrl', path: 'welcome.thumbnailUrl', type: 'value', default: '' },
        { id: 'w_imageUrl', path: 'welcome.imageUrl', type: 'value', default: '' }
      ],
      afterReset() {
        renderWelcomePreview();
      },
      afterPopulate() {
        renderWelcomePreview();
      }
    },
    logs: {
      fields: [
        { id: 'l_enabled', path: 'messageLogs.enabled', type: 'checked', default: true },
        { id: 'l_channelId', path: 'messageLogs.channelId', type: 'value', default: '' },
        { id: 'l_messageLogChannelId', path: 'messageLogs.messageLogChannelId', type: 'value', default: '' },
        { id: 'l_commandUsageChannelId', path: 'messageLogs.commandUsageChannelId', type: 'value', default: '' },
        { id: 'l_channelLifecycleChannelId', path: 'messageLogs.channelLifecycleChannelId', type: 'value', default: '' },
        { id: 'l_roleLogChannelId', path: 'messageLogs.roleLogChannelId', type: 'value', default: '' },
        { id: 'l_moderationLogChannelId', path: 'messageLogs.moderationLogChannelId', type: 'value', default: '' },
        { id: 'l_roleLogEnabled', path: 'messageLogs.roleLogEnabled', type: 'checked', default: true },
        { id: 'l_channelLifecycleLogEnabled', path: 'messageLogs.channelLifecycleLogEnabled', type: 'checked', default: true },
        { id: 'l_moderationLogEnabled', path: 'messageLogs.moderationLogEnabled', type: 'checked', default: true },
        { id: 'l_commandUsageLogEnabled', path: 'messageLogs.commandUsageLogEnabled', type: 'checked', default: true },
        { id: 'l_ignoredMemberIds', path: 'messageLogs.ignoredMemberIds', type: 'value', default: '' },
        { id: 'l_ignoredRoleIds', path: 'messageLogs.ignoredRoleIds', type: 'value', default: '' },
        { id: 'l_ignoredChannelIds', path: 'messageLogs.ignoredChannelIds', type: 'value', default: '' },
        { id: 'l_ignoredPrefixes', path: 'messageLogs.ignoredPrefixes', type: 'value', default: '' }
      ]
    },
    music: {
      fields: [
        { id: 'm_autoLeaveEnabled', path: 'music.autoLeaveEnabled', type: 'checked', default: true },
        { id: 'm_autoLeaveMinutes', path: 'music.autoLeaveMinutes', type: 'number', default: 5 },
        { id: 'm_autoplayEnabled', path: 'music.autoplayEnabled', type: 'checked', default: true },
        { id: 'm_defaultRepeatMode', path: 'music.defaultRepeatMode', type: 'value', default: 'OFF' },
        { id: 'm_commandChannelId', path: 'music.commandChannelId', type: 'value', default: '' }
      ],
      afterPopulate(settings) {
        renderMusicStats(settings.musicStats || {});
      }
    },
    privateRoom: {
      fields: [
        { id: 'p_enabled', path: 'privateRoom.enabled', type: 'checked', default: true },
        { id: 'p_triggerVoiceChannelId', path: 'privateRoom.triggerVoiceChannelId', type: 'value', default: '' },
        { id: 'p_userLimit', path: 'privateRoom.userLimit', type: 'number', default: 0 }
      ]
    },
    numberChain: {
      fields: [
        { id: 'nc_enabled', path: 'numberChain.enabled', type: 'checked', default: false },
        { id: 'nc_channelId', path: 'numberChain.channelId', type: 'value', default: '' },
        { id: 'nc_nextNumber', path: 'numberChain.nextNumber', type: 'number', default: 1, persist: false }
      ]
    },
    ticket: {
      fields: [
        { id: 't_enabled', path: 'ticket.enabled', type: 'checked', default: false },
        { id: 't_panelChannelId', path: 'ticket.panelChannelId', type: 'value', default: '' },
        { id: 't_panelTitle', path: 'ticket.panelTitle', type: 'value', default: '' },
        { id: 't_panelDescription', path: 'ticket.panelDescription', type: 'value', default: '' },
        { id: 't_panelColor', path: 'ticket.panelColor', type: 'value', default: '#5865F2' },
        { id: 't_panelButtonLimit', path: 'ticket.panelButtonLimit', type: 'number', default: 3 },
        { id: 't_autoCloseDays', path: 'ticket.autoCloseDays', type: 'number', default: 3 },
        { id: 't_maxOpenPerUser', path: 'ticket.maxOpenPerUser', type: 'number', default: 1 },
        { id: 't_openUiMode', path: 'ticket.openUiMode', type: 'value', default: 'BUTTONS' },
        { id: 't_supportRoleIds', path: 'ticket.supportRoleIds', type: 'multi', default: [] },
        { id: 't_blacklistedUserIds', path: 'ticket.blacklistedUserIds', type: 'value', default: '' }
      ],
      afterReset() {
        ticketModule.resetTicketState();
        applyTicketPanelDefaultsIfEmpty();
      },
      afterPopulate(settings) {
        ticketModule.applyTicketOptions(settings.ticket || {});
        applyTicketPanelDefaultsIfEmpty();
      }
    }
  };

  const state = {
    settingsCache: new Map(),
    loadedSectionsByGuild: new Map()
  };

  function getDefaultValue(spec) {
    return typeof spec.default === 'function' ? spec.default() : spec.default;
  }

  function readPath(source, path) {
    return String(path || '').split('.').reduce((acc, key) => (acc == null ? undefined : acc[key]), source);
  }

  function writePath(target, path, value) {
    const keys = String(path || '').split('.');
    let cursor = target;
    for (let i = 0; i < keys.length - 1; i += 1) {
      const key = keys[i];
      if (!cursor[key] || typeof cursor[key] !== 'object' || Array.isArray(cursor[key])) {
        cursor[key] = {};
      }
      cursor = cursor[key];
    }
    cursor[keys[keys.length - 1]] = value;
  }

  function normalizeMultiValue(value) {
    if (Array.isArray(value)) return value;
    if (value == null || value === '') return [];
    return String(value)
      .split(',')
      .map((segment) => segment.trim())
      .filter(Boolean);
  }

  function cloneValue(value) {
    if (value == null) return {};
    return JSON.parse(JSON.stringify(value));
  }

  function mergeObjects(base, patch) {
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
      return patch;
    }
    const seed = base && typeof base === 'object' && !Array.isArray(base) ? base : {};
    const next = { ...seed };
    Object.entries(patch).forEach(([key, value]) => {
      if (Array.isArray(value)) {
        next[key] = [...value];
        return;
      }
      if (value && typeof value === 'object') {
        next[key] = mergeObjects(seed[key], value);
        return;
      }
      next[key] = value;
    });
    return next;
  }

  function ensureLoadedSections(guildId) {
    if (!state.loadedSectionsByGuild.has(guildId)) {
      state.loadedSectionsByGuild.set(guildId, new Set());
    }
    return state.loadedSectionsByGuild.get(guildId);
  }

  function markSectionLoaded(guildId, section) {
    if (!guildId || !section) return;
    ensureLoadedSections(guildId).add(section);
  }

  function getLoadedSections(guildId) {
    return ensureLoadedSections(guildId);
  }

  function getCachedSettings(guildId) {
    return state.settingsCache.get(guildId) || {};
  }

  function mergeIntoSettingsCache(guildId, patch) {
    const merged = mergeObjects(getCachedSettings(guildId), patch);
    state.settingsCache.set(guildId, merged);
    return cloneValue(merged);
  }

  function setControlValue(spec, value) {
    const nextValue = value == null ? getDefaultValue(spec) : value;
    switch (spec.type) {
      case 'checked':
        setChecked(spec.id, !!nextValue);
        break;
      case 'number':
        setValue(spec.id, String(nextValue));
        break;
      case 'multi':
        setMultiSelectValues(spec.id, normalizeMultiValue(nextValue));
        break;
      default:
        setValue(spec.id, String(nextValue ?? ''));
        break;
    }
  }

  function readControlValue(spec) {
    switch (spec.type) {
      case 'checked':
        return getChecked(spec.id);
      case 'number':
        return Number(getValue(spec.id) || String(getDefaultValue(spec) ?? 0));
      case 'multi':
        return getMultiSelectValues(spec.id);
      default:
        return getValue(spec.id);
    }
  }

  function applySectionValues(sectionName, source) {
    const schema = SECTION_SCHEMAS[sectionName];
    if (!schema) return;
    schema.fields.forEach((spec) => {
      setControlValue(spec, readPath(source, spec.path));
    });
    if (typeof schema.afterPopulate === 'function') {
      schema.afterPopulate(source || {});
    }
  }

  function resetSectionValues(sectionName) {
    const schema = SECTION_SCHEMAS[sectionName];
    if (!schema) return;
    schema.fields.forEach((spec) => {
      setControlValue(spec, getDefaultValue(spec));
    });
    if (typeof schema.afterReset === 'function') {
      schema.afterReset();
    }
  }

  function mergeLoadedSectionsIntoPayload(payload, guildId) {
    getLoadedSections(guildId).forEach((sectionName) => {
      const schema = SECTION_SCHEMAS[sectionName];
      if (!schema) return;
      schema.fields.forEach((spec) => {
        if (spec.persist === false) return;
        writePath(payload, spec.path, readControlValue(spec));
      });
      if (sectionName === 'ticket') {
        writePath(payload, 'ticket.options', ticketModule.collectTicketOptions());
      }
    });
  }

  async function loadSettingsData(options = {}) {
    const guildId = options.guildId || selectedGuild();
    if (!guildId) return {};
    if (!options.force && state.settingsCache.has(guildId)) {
      return cloneValue(state.settingsCache.get(guildId));
    }
    const data = await api(`/api/guild/${guildId}/settings`);
    return mergeIntoSettingsCache(guildId, data);
  }

  async function loadSectionData(sectionName, options = {}) {
    const guildId = options.guildId || selectedGuild();
    if (!guildId) return {};
    const data = await api(`/api/guild/${guildId}/settings/${sectionName}`);
    return mergeIntoSettingsCache(guildId, data);
  }

  function invalidateGuildCache(guildId) {
    if (!guildId) return;
    state.settingsCache.delete(guildId);
    state.loadedSectionsByGuild.delete(guildId);
  }

  function invalidateCurrentGuild() {
    invalidateGuildCache(selectedGuild());
  }

  async function loadSection(sectionName, options = {}) {
    if (!SECTION_SCHEMAS[sectionName]) return {};
    const guildId = options.guildId || selectedGuild();
    if (!guildId) return {};
    const alreadyLoaded = getLoadedSections(guildId).has(sectionName);
    if (!options.force && alreadyLoaded) {
      return cloneValue(getCachedSettings(guildId));
    }
    const settings = await loadSectionData(sectionName, { guildId, force: !!options.force });
    applySectionValues(sectionName, settings);
    markSectionLoaded(guildId, sectionName);
    dirtyStateModule?.markClean(sectionName);
    if (!options.quiet) {
      showStatus(t('settingsLoaded'));
    }
    return settings;
  }

  async function loadSections(sectionNames, options = {}) {
    const names = Array.isArray(sectionNames) ? sectionNames.filter(Boolean) : [];
    if (names.length === 0) return {};
    const guildId = options.guildId || selectedGuild();
    if (!guildId) return {};
    for (const sectionName of names) {
      if (!SECTION_SCHEMAS[sectionName]) continue;
      await loadSection(sectionName, { guildId, force: !!options.force, quiet: true });
    }
    const settings = cloneValue(getCachedSettings(guildId));
    if (!options.quiet) {
      showStatus(t('settingsLoaded'));
    }
    return settings;
  }

  function resetSection(section) {
    if (!SECTION_SCHEMAS[section]) return;
    const guildId = selectedGuild();
    resetSectionValues(section);
    if (guildId) {
      markSectionLoaded(guildId, section);
    }
    dirtyStateModule?.markDirty(section);
    const sectionName = t(`tabs_${section}`) || section;
    const message = t('sectionResetDone').replace('{section}', sectionName);
    showStatus(message);
    showToast(message, 'success');
  }

  function populateSettings(settings) {
    Object.keys(SECTION_SCHEMAS).forEach((sectionName) => {
      applySectionValues(sectionName, settings || {});
      dirtyStateModule?.markClean(sectionName);
    });
    const guildId = selectedGuild();
    if (guildId) {
      Object.keys(SECTION_SCHEMAS).forEach((sectionName) => markSectionLoaded(guildId, sectionName));
    }
  }

  function collectSettings() {
    const guildId = selectedGuild();
    const payload = guildId && state.settingsCache.has(guildId)
      ? cloneValue(state.settingsCache.get(guildId))
      : {};
    mergeLoadedSectionsIntoPayload(payload, guildId);
    return payload;
  }

  async function saveSettings() {
    const guildId = selectedGuild();
    if (!guildId) return;
    const payload = collectSettings();
    const saveBtn = document.getElementById(saveButtonId);
    if (saveBtn) {
      saveBtn.disabled = true;
      saveBtn.classList.add('saving');
    }
    try {
      await api(`/api/guild/${guildId}/settings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const loadedSections = [...getLoadedSections(guildId)];
      state.settingsCache.set(guildId, {});
      for (const sectionName of loadedSections) {
        await loadSection(sectionName, { guildId, force: true, quiet: true });
      }
      const fresh = cloneValue(getCachedSettings(guildId));
      showStatus(t('settingsSaved'));
      showToast(t('settingsSaved'), 'success');
      return fresh;
    } catch (error) {
      showStatus(error.message || 'Save failed');
      showToast(error.message || 'Save failed', 'error');
      throw error;
    } finally {
      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.classList.remove('saving');
      }
    }
  }

  return {
    resetSection,
    populateSettings,
    collectSettings,
    loadSection,
    loadSections,
    loadSectionData,
    loadSettingsData,
    saveSettings,
    invalidateCurrentGuild,
    invalidateGuildCache,
    getLoadedSections: () => getLoadedSections(selectedGuild()),
    hasSectionSchema: (section) => !!SECTION_SCHEMAS[section]
  };
}
