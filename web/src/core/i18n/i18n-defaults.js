export function createI18nDefaults(deps) {
  const {
    byId,
    getValue,
    getBundleTextByLanguage,
    getUiLanguage
  } = deps;

  function getNotificationTemplateDefault(templateKey) {
    const preferredLang = getValue('s_language') || getUiLanguage() || 'zh-TW';
    return getBundleTextByLanguage(preferredLang, templateKey)
      || getBundleTextByLanguage(getUiLanguage(), templateKey)
      || getBundleTextByLanguage('zh-TW', templateKey)
      || '';
  }

  function isNotificationTemplateStillDefault(value, templateKey) {
    const current = String(value || '').trim();
    if (!current) return true;
    const knownDefaults = new Set();
    ['zh-TW', 'zh-CN', 'en'].forEach(code => {
      const text = getBundleTextByLanguage(code, templateKey);
      if (text) knownDefaults.add(text.trim());
    });
    return knownDefaults.has(current);
  }

  function resolveNotificationTemplateKey(controlId) {
    const mapping = {
      n_memberJoinTitle: 'notifications_default_member_join_title',
      n_memberJoinMessage: 'notifications_default_member_join',
      n_memberLeaveMessage: 'notifications_default_member_leave',
      n_voiceJoinMessage: 'notifications_default_voice_join',
      n_voiceLeaveMessage: 'notifications_default_voice_leave',
      n_voiceMoveMessage: 'notifications_default_voice_move'
    };
    return mapping[controlId] || '';
  }

  function applyNotificationTemplateDefaults() {
    [
      'n_memberJoinTitle',
      'n_memberJoinMessage',
      'n_memberLeaveMessage',
      'n_voiceJoinMessage',
      'n_voiceLeaveMessage',
      'n_voiceMoveMessage'
    ].forEach(controlId => {
      const el = byId(controlId);
      if (!el) return;
      const templateKey = resolveNotificationTemplateKey(controlId);
      if (!templateKey) return;
      if (!isNotificationTemplateStillDefault(el.value, templateKey)) return;
      const next = getNotificationTemplateDefault(templateKey);
      if (next) el.value = next;
    });
  }

  function getTicketPanelDefault(key) {
    const preferredLang = getValue('s_language') || getUiLanguage() || 'zh-TW';
    return getBundleTextByLanguage(preferredLang, key)
      || getBundleTextByLanguage(getUiLanguage(), key)
      || getBundleTextByLanguage('zh-TW', key)
      || '';
  }

  function isTicketPanelTextStillDefault(value, key) {
    const current = String(value || '').trim();
    if (!current) return true;
    const knownDefaults = new Set();
    ['zh-TW', 'zh-CN', 'en'].forEach(code => {
      const text = getBundleTextByLanguage(code, key);
      if (text) knownDefaults.add(text.trim());
    });
    return knownDefaults.has(current);
  }

  function applyTicketPanelDefaultsIfEmpty() {
    const title = byId('t_panelTitle');
    const desc = byId('t_panelDescription');
    if (title && isTicketPanelTextStillDefault(title.value, 'ticket_default_panel_title')) {
      title.value = getTicketPanelDefault('ticket_default_panel_title');
    }
    if (desc && isTicketPanelTextStillDefault(desc.value, 'ticket_default_panel_desc')) {
      desc.value = getTicketPanelDefault('ticket_default_panel_desc');
    }
  }

  return {
    applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty
  };
}
