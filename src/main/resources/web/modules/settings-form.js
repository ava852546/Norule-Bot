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
    renderWelcomePreview,
    applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty,
    ticketModule,
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
        { id: 'n_memberLeaveColor', path: 'notifications.memberLeaveColor', type: 'value', default: '#E74C3C' }
      ],
      afterReset() {
        applyNotificationTemplateDefaults();
      },
      afterPopulate() {
        applyNotificationTemplateDefaults();
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
        { id: 'l_commandUsageLogEnabled', path: 'messageLogs.commandUsageLogEnabled', type: 'checked', default: true }
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
      .map(s => s.trim())
      .filter(Boolean);
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
    schema.fields.forEach(spec => {
      setControlValue(spec, readPath(source, spec.path));
    });
    if (typeof schema.afterPopulate === 'function') {
      schema.afterPopulate(source);
    }
  }

  function resetSectionValues(sectionName) {
    const schema = SECTION_SCHEMAS[sectionName];
    if (!schema) return;
    schema.fields.forEach(spec => {
      setControlValue(spec, getDefaultValue(spec));
    });
    if (typeof schema.afterReset === 'function') {
      schema.afterReset();
    }
  }

  function resetSection(section) {
    if (!SECTION_SCHEMAS[section]) return;
    resetSectionValues(section);
    const sectionName = t(`tabs_${section}`) || section;
    const message = t('sectionResetDone').replace('{section}', sectionName);
    showStatus(message);
    showToast(message, 'success');
  }

  function populateSettings(settings) {
    Object.keys(SECTION_SCHEMAS).forEach(sectionName => {
      applySectionValues(sectionName, settings || {});
    });
  }

  function collectSettings() {
    const payload = {};
    Object.values(SECTION_SCHEMAS).forEach(schema => {
      schema.fields.forEach(spec => {
        if (spec.persist === false) return;
        writePath(payload, spec.path, readControlValue(spec));
      });
    });
    writePath(payload, 'ticket.options', ticketModule.collectTicketOptions());
    return payload;
  }

  async function loadSettings() {
    const guildId = selectedGuild();
    if (!guildId) return;
    const data = await api(`/api/guild/${guildId}/settings`);
    populateSettings(data);
    showStatus(t('settingsLoaded'));
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
      await loadSettings();
      showStatus(t('settingsSaved'));
      showToast(t('settingsSaved'), 'success');
    } catch (e) {
      showStatus(e.message || 'Save failed');
      showToast(e.message || 'Save failed', 'error');
      throw e;
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
    loadSettings,
    saveSettings
  };
}
