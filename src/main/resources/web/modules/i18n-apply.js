export function createI18nApplyModule(deps) {
  const {
    byId,
    t,
    getUiLanguage,
    getUiLanguages,
    getBotLanguages,
    renderNotificationPreview,
    renderWelcomePreview,
    renderTicketOptions,
    renderMusicStats,
    getLastMusicStats,
    updateMeLine,
    applyNotificationTemplateDefaults,
    applyTicketPanelDefaultsIfEmpty,
    setUiLanguage
  } = deps;

  function applyDataI18n(root = document) {
    root.querySelectorAll('[data-i18n]').forEach((element) => {
      const key = element.dataset.i18n;
      if (!key) return;
      element.textContent = t(key);
    });
    root.querySelectorAll('[data-i18n-placeholder]').forEach((element) => {
      const key = element.dataset.i18nPlaceholder;
      if (!key) return;
      element.setAttribute('placeholder', t(key));
    });
    root.querySelectorAll('[data-i18n-title]').forEach((element) => {
      const key = element.dataset.i18nTitle;
      if (!key) return;
      element.setAttribute('title', t(key));
    });
    root.querySelectorAll('[data-i18n-aria-label]').forEach((element) => {
      const key = element.dataset.i18nAriaLabel;
      if (!key) return;
      element.setAttribute('aria-label', t(key));
    });
  }

  function setSelectOptionLabel(selectId, optionValue, key) {
    const select = byId(selectId);
    if (!select) return;
    const option = [...select.options].find((candidate) => candidate.value === optionValue);
    if (option) option.textContent = t(key);
  }

  function localizeLanguageOptions() {
    const select = byId('s_language');
    if (!select) return;
    const current = select.value;
    select.innerHTML = '';
    getBotLanguages().forEach((item) => {
      const option = document.createElement('option');
      option.value = item.code;
      const key = `language_option_${String(item.code || '').toLowerCase().replace(/[^a-z0-9]/g, '_')}`;
      const translated = t(key);
      option.textContent = translated !== key
        ? translated
        : `${item.code} - ${item.label || item.code}`;
      select.appendChild(option);
    });
    if (current && [...select.options].some((option) => option.value === current)) {
      select.value = current;
    } else if (select.options.length > 0) {
      select.value = select.options[0].value;
    }
  }

  function renderUiLanguageButtons() {
    const root = byId('uiLangButtons');
    if (!root) return;
    root.innerHTML = '';
    getUiLanguages().forEach((item) => {
      const code = item.code || '';
      const label = item.label || code;
      if (!code) return;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'lang-btn';
      button.textContent = label;
      button.dataset.lang = code;
      button.classList.toggle('active', getUiLanguage() === code);
      button.onclick = () => setUiLanguage(code);
      root.appendChild(button);
    });
  }

  function localizeStaticOptions() {
    setSelectOptionLabel('t_openUiMode', 'BUTTONS', 't_openUiMode_buttons');
    setSelectOptionLabel('t_openUiMode', 'SELECT', 't_openUiMode_select');
    setSelectOptionLabel('t_optionButtonStyle', 'PRIMARY', 't_panelButtonStyle_primary');
    setSelectOptionLabel('t_optionButtonStyle', 'SECONDARY', 't_panelButtonStyle_secondary');
    setSelectOptionLabel('t_optionButtonStyle', 'SUCCESS', 't_panelButtonStyle_success');
    setSelectOptionLabel('t_optionButtonStyle', 'DANGER', 't_panelButtonStyle_danger');
    setSelectOptionLabel('m_defaultRepeatMode', 'OFF', 'repeat_mode_off');
    setSelectOptionLabel('m_defaultRepeatMode', 'SINGLE', 'repeat_mode_single');
    setSelectOptionLabel('m_defaultRepeatMode', 'ALL', 'repeat_mode_all');
  }

  function localizeSettingsForm() {
    applyDataI18n(document);
    localizeLanguageOptions();
    localizeStaticOptions();
    applyNotificationTemplateDefaults();
    applyTicketPanelDefaultsIfEmpty();
    renderNotificationPreview();
    renderWelcomePreview();
    renderTicketOptions();
    renderMusicStats(getLastMusicStats());
  }

  function applyUiLanguage() {
    document.title = t('titleMain');
    applyDataI18n(document);
    renderUiLanguageButtons();
    localizeSettingsForm();
    updateMeLine();
  }

  return {
    applyUiLanguage,
    updateMeLine,
    localizeSettingsForm
  };
}
