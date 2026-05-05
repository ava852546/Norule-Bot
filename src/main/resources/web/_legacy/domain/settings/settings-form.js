import { createGeneralSchema } from '/web/domain/general/general-schema.js';
import { createLogsSchema } from '/web/domain/logs/logs-schema.js';
import { createMusicSchema } from '/web/domain/music/music-schema.js';
import { createNotificationsSchema } from '/web/domain/notifications/notifications-schema.js';
import { createNumberChainSchema } from '/web/domain/number-chain/number-chain-schema.js';
import { createPrivateRoomSchema } from '/web/domain/private-room/private-room-schema.js';
import { createTicketSchema } from '/web/domain/ticket/ticket-schema.js';
import { createWelcomeSchema } from '/web/domain/welcome/welcome-schema.js';

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
    general: createGeneralSchema(),
    notifications: createNotificationsSchema({ applyNotificationTemplateDefaults, renderNotificationPreview }),
    welcome: createWelcomeSchema({ renderWelcomePreview }),
    logs: createLogsSchema(),
    music: createMusicSchema({ renderMusicStats }),
    privateRoom: createPrivateRoomSchema(),
    numberChain: createNumberChainSchema(),
    ticket: createTicketSchema({ ticketModule, applyTicketPanelDefaultsIfEmpty })
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
