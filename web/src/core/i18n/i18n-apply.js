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
      button.className = [
        'inline-flex',
        'items-center',
        'justify-center',
        'min-h-[38px]',
        'px-3',
        'py-2',
        'min-w-[72px]',
        'rounded-[12px]',
        'border',
        'border-[rgba(255,255,255,0.12)]',
        'bg-[linear-gradient(180deg,rgba(7,15,28,0.96),rgba(10,18,33,0.94))]',
        'text-[#f9fbff]',
        'font-extrabold',
        'text-[12px]',
        'tracking-[0.06em]',
        'transition',
        'shadow-[0_12px_24px_rgba(0,0,0,0.12)]',
        'hover:translate-y-[-1px]',
        'active:translate-y-0',
        'hover:border-[rgba(255,255,255,0.18)]'
      ].join(' ');
      button.textContent = label;
      button.dataset.lang = code;
      if (getUiLanguage() === code) {
        button.classList.add(
          'bg-gradient-to-r',
          'from-[var(--accent-2)]',
          'to-[var(--accent)]',
          'border-transparent'
        );
      }
      button.onclick = () => setUiLanguage(code);
      root.appendChild(button);
    });
  }

  function localizeStaticOptions() {
    setSelectOptionLabel('t_openUiMode', 'BUTTONS', 't_openUiMode_buttons');
    setSelectOptionLabel('t_openUiMode', 'SELECT', 't_openUiMode_select');
    setSelectOptionLabel('t_panelButtonStyle', 'PRIMARY', 't_panelButtonStyle_primary');
    setSelectOptionLabel('t_panelButtonStyle', 'SECONDARY', 't_panelButtonStyle_secondary');
    setSelectOptionLabel('t_panelButtonStyle', 'SUCCESS', 't_panelButtonStyle_success');
    setSelectOptionLabel('t_panelButtonStyle', 'DANGER', 't_panelButtonStyle_danger');
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
    const uiLang = getUiLanguage() || 'en';
    document.documentElement.lang = uiLang;
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
