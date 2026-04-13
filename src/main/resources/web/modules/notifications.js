export function createNotificationsModule(deps) {
  const {
    byId,
    esc,
    t,
    getValue,
    getChecked,
    selectedGuildName,
    saveSettings
  } = deps;

  function normalizeColor(value, fallback) {
    const text = String(value || '').trim();
    if (/^#[0-9a-f]{6}$/i.test(text)) return text.toUpperCase();
    return fallback;
  }

  function applyVoicePreviewTemplate(value) {
    const guildName = selectedGuildName();
    return String(value || '')
      .replace(/\{user\}|\{使用者\}/g, '@VoiceMember')
      .replace(/\{channel\}/g, '#General')
      .replace(/\{from\}/g, '#Lobby')
      .replace(/\{to\}/g, '#Gaming')
      .replace(/\{guild\}|\{群組名稱\}/g, guildName)
      .replace(/\{id\}/g, '123456789012345678');
  }

  function renderVoiceEmbed(titleKey, messageId, defaultTemplateKey, colorId, fallbackColor) {
    const title = t(titleKey);
    const message = applyVoicePreviewTemplate(getValue(messageId) || t(defaultTemplateKey));
    const color = normalizeColor(getValue(colorId), fallbackColor);
    return `
      <div class="notification-preview-embed" style="--embed-color:${esc(color)}">
        <div class="notification-preview-embed-title">${esc(title)}</div>
        <div class="notification-preview-embed-desc">${esc(message)}</div>
      </div>
    `;
  }

  function renderNotificationPreview() {
    const root = byId('notificationVoicePreviewCard');
    if (!root) return;

    const enabled = getChecked('n_voiceLogEnabled');
    const channelSelect = byId('n_voiceChannelId');
    const channelLabel = channelSelect?.selectedOptions?.[0]?.textContent || '-';

    root.innerHTML = `
      <div class="welcome-preview-head">
        <div class="welcome-preview-title">${esc(t('notification_preview_embed_title'))}</div>
        <div class="welcome-preview-badges">
          <span class="welcome-preview-badge">${esc(t('n_voiceChannelId'))}: ${esc(channelLabel)}</span>
          <span class="welcome-preview-badge">${esc(enabled ? t('enabledOption') : t('disabledOption'))}</span>
        </div>
      </div>
      <div class="welcome-preview-body">
        <div class="notification-preview-grid">
          ${renderVoiceEmbed('notification_preview_voice_join_title', 'n_voiceJoinMessage', 'notifications_default_voice_join', 'n_voiceJoinColor', '#2ECC71')}
          ${renderVoiceEmbed('notification_preview_voice_leave_title', 'n_voiceLeaveMessage', 'notifications_default_voice_leave', 'n_voiceLeaveColor', '#E74C3C')}
          ${renderVoiceEmbed('notification_preview_voice_move_title', 'n_voiceMoveMessage', 'notifications_default_voice_move', 'n_voiceMoveColor', '#5865F2')}
        </div>
      </div>
    `;
  }

  function bindNotificationPreviewAutoSync() {
    [
      'n_voiceLogEnabled',
      'n_voiceChannelId',
      'n_voiceJoinMessage',
      'n_voiceLeaveMessage',
      'n_voiceMoveMessage',
      'n_voiceJoinColor',
      'n_voiceLeaveColor',
      'n_voiceMoveColor'
    ].forEach(id => {
      const el = byId(id);
      if (!el || el.dataset.notificationPreviewBound === '1') return;
      el.dataset.notificationPreviewBound = '1';
      el.addEventListener('input', renderNotificationPreview);
      el.addEventListener('change', renderNotificationPreview);
    });
  }

  function openNotificationEditor() {
    const modal = byId('notificationEditorModal');
    if (!modal) return;
    modal.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    const firstInput = byId('n_memberJoinTitle');
    if (firstInput) setTimeout(() => firstInput.focus(), 20);
  }

  function closeNotificationEditor() {
    const modal = byId('notificationEditorModal');
    if (!modal) return;
    modal.classList.add('hidden');
    document.body.style.overflow = '';
  }

  async function saveNotificationSettings() {
    const saveBtn = byId('saveNotificationSettingsBtn');
    if (saveBtn) {
      saveBtn.disabled = true;
      saveBtn.classList.add('saving');
    }
    try {
      await saveSettings();
      closeNotificationEditor();
    } finally {
      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.classList.remove('saving');
      }
    }
  }

  return {
    renderNotificationPreview,
    bindNotificationPreviewAutoSync,
    openNotificationEditor,
    closeNotificationEditor,
    saveNotificationSettings
  };
}
