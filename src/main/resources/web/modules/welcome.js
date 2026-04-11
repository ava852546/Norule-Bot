export function createWelcomeModule(deps) {
  const {
    byId,
    esc,
    t,
    getValue,
    getChecked,
    selectedGuildName,
    saveSettings
  } = deps;

  // Legacy corrupted placeholders kept only for backwards compatibility
  // with older saved templates.
  const LEGACY_PLACEHOLDER_ALIASES = new Map([
    ['\u007b\u96ff\u8f3b\ue705\u003f\uf148\u007d', '{user}'],
    ['\u007b\u96ff\u8f3b\ue705\u003f\uf1af\u003f\u8754\u5d24\u007d', '{username}'],
    ['\u007b\u8762\u65a4\u003f\u003f\uf699\u8fc2\u007d', '{guild}']
  ]);

  function sanitizePreviewUrl(value) {
    const url = String(value || '').trim();
    return /^https?:\/\//i.test(url) ? url : '';
  }

  function normalizeLegacyPlaceholders(value) {
    let next = String(value || '');
    for (const [legacy, modern] of LEGACY_PLACEHOLDER_ALIASES.entries()) {
      next = next.split(legacy).join(modern);
    }
    return next;
  }

  function applyWelcomePreviewTemplate(value) {
    const guildName = selectedGuildName();
    return normalizeLegacyPlaceholders(value)
      .replace(/\{user\}|\{使用者\}/g, '@NewMember')
      .replace(/\{username\}|\{使用者名稱\}/g, 'NewMember')
      .replace(/\{guild\}|\{群組名稱\}/g, guildName)
      .replace(/\{id\}/g, '123456789012345678')
      .replace(/\{tag\}/g, 'NewMember#0001')
      .replace(/\{isBot\}/g, 'false')
      .replace(/\{createdAt\}/g, '2026-04-01 12:00')
      .replace(/\{accountAgeDays\}/g, '5');
  }

  function renderWelcomePreview() {
    const root = byId('welcomePreviewCard');
    if (!root) return;
    const enabled = getChecked('w_enabled');
    const channelSelect = byId('w_channelId');
    const channelLabel = channelSelect?.selectedOptions?.[0]?.textContent || '-';
    const guildName = selectedGuildName();
    const title = applyWelcomePreviewTemplate(getValue('w_title')) || t('w_title');
    const message = applyWelcomePreviewTemplate(getValue('w_message')) || t('w_message');
    const thumb = sanitizePreviewUrl(getValue('w_thumbnailUrl'));
    const image = sanitizePreviewUrl(getValue('w_imageUrl'));
    const footerIcon = 'https://cdn.discordapp.com/embed/avatars/0.png';
    root.innerHTML = `
      <div class="welcome-preview-head">
        <div class="welcome-preview-title">${esc(t('welcome_preview_embed_title'))}</div>
        <div class="welcome-preview-badges">
          <span class="welcome-preview-badge">${esc(t('w_channelId'))}: ${esc(channelLabel)}</span>
          <span class="welcome-preview-badge">${esc(enabled ? t('enabledOption') : t('disabledOption'))}</span>
        </div>
      </div>
      <div class="welcome-preview-body">
        <div class="welcome-preview-embed">
          <div class="welcome-preview-embed-head">
            <div class="welcome-preview-embed-main">
              <div class="welcome-preview-embed-title">${esc(title)}</div>
              <div class="welcome-preview-embed-desc">${esc(message)}</div>
            </div>
            ${thumb ? `<img class="welcome-preview-thumb" src="${esc(thumb)}" alt="thumbnail" loading="lazy" referrerpolicy="no-referrer" />` : ''}
          </div>
          ${image ? `<img class="welcome-preview-image" src="${esc(image)}" alt="preview" loading="lazy" referrerpolicy="no-referrer" />` : `<div class="welcome-preview-empty">${esc(t('welcome_preview_no_image'))}</div>`}
          <div class="welcome-preview-footer">
            <img class="welcome-preview-footer-icon" src="${footerIcon}" alt="guild" loading="lazy" referrerpolicy="no-referrer" />
            <span>${esc(guildName)} • ${esc(t('welcome_preview_now'))}</span>
          </div>
        </div>
      </div>
    `;
  }

  function bindWelcomePreviewAutoSync() {
    ['w_enabled', 'w_channelId', 'w_title', 'w_message', 'w_thumbnailUrl', 'w_imageUrl'].forEach(id => {
      const el = byId(id);
      if (!el || el.dataset.previewBound === '1') return;
      el.dataset.previewBound = '1';
      el.addEventListener('input', renderWelcomePreview);
      el.addEventListener('change', renderWelcomePreview);
    });
  }

  function openWelcomeEditor() {
    const modal = byId('welcomeEditorModal');
    if (!modal) return;
    modal.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    const firstInput = byId('w_title');
    if (firstInput) setTimeout(() => firstInput.focus(), 20);
  }

  function closeWelcomeEditor() {
    const modal = byId('welcomeEditorModal');
    if (!modal) return;
    modal.classList.add('hidden');
    document.body.style.overflow = '';
  }

  async function saveWelcomeSettings() {
    const saveBtn = byId('saveWelcomeSettingsBtn');
    if (saveBtn) {
      saveBtn.disabled = true;
      saveBtn.classList.add('saving');
    }
    try {
      await saveSettings();
      closeWelcomeEditor();
    } finally {
      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.classList.remove('saving');
      }
    }
  }

  return {
    renderWelcomePreview,
    bindWelcomePreviewAutoSync,
    openWelcomeEditor,
    closeWelcomeEditor,
    saveWelcomeSettings
  };
}
