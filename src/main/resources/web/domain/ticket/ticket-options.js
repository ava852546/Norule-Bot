export function createTicketOptionsModule(deps) {
  const {
    byId,
    esc,
    t,
    getValue,
    getChecked,
    setValue,
    setChecked,
    showToast
  } = deps;

  let ticketOptionsState = [];
  let selectedTicketOptionId = '';

  function defaultTicketOptionLabel() {
    const key = 'ticket_option_default_label';
    const translated = t(key);
    return translated !== key ? translated : 'General';
  }

  function createTicketOptionDraft(overrides = {}) {
    const stamp = Date.now().toString(36);
    return {
      id: overrides.id || `option-${stamp}`,
      label: overrides.label || '',
      panelTitle: overrides.panelTitle || '',
      panelDescription: overrides.panelDescription || '',
      panelButtonStyle: String(overrides.panelButtonStyle || 'PRIMARY').toUpperCase(),
      welcomeMessage: overrides.welcomeMessage || t('ticket_default_welcome_message'),
      preOpenFormEnabled: !!overrides.preOpenFormEnabled,
      preOpenFormTitle: overrides.preOpenFormTitle || '',
      preOpenFormLabel: overrides.preOpenFormLabel || '',
      preOpenFormPlaceholder: overrides.preOpenFormPlaceholder || ''
    };
  }

  function ensureSelectedTicketOption() {
    if (ticketOptionsState.length === 0) {
      const draft = createTicketOptionDraft();
      ticketOptionsState = [draft];
      selectedTicketOptionId = draft.id;
      return;
    }
    if (!selectedTicketOptionId || !ticketOptionsState.some(item => item.id === selectedTicketOptionId)) {
      selectedTicketOptionId = ticketOptionsState[0].id;
    }
  }

  function getSelectedTicketOption() {
    ensureSelectedTicketOption();
    return ticketOptionsState.find(item => item.id === selectedTicketOptionId) || null;
  }

  function populateTicketOptionEditor(option) {
    const current = option || createTicketOptionDraft();
    setValue('t_optionLabel', current.label || '');
    setValue('t_optionButtonStyle', (current.panelButtonStyle || 'PRIMARY').toUpperCase());
    setValue('t_optionPanelTitle', current.panelTitle || '');
    setValue('t_optionPanelDescription', current.panelDescription || '');
    setValue('t_optionWelcomeMessage', current.welcomeMessage || '');
    setChecked('t_optionPreOpenFormEnabled', !!current.preOpenFormEnabled);
    setValue('t_optionPreOpenFormTitle', current.preOpenFormTitle || '');
    setValue('t_optionPreOpenFormLabel', current.preOpenFormLabel || '');
    setValue('t_optionPreOpenFormPlaceholder', current.preOpenFormPlaceholder || '');
  }

  function renderTicketOptions() {
    const root = byId('ticketOptionList');
    if (!root) return;
    ensureSelectedTicketOption();
    root.innerHTML = '';
    ticketOptionsState.forEach(option => {
      const card = document.createElement('div');
      card.className = 'option-card';
      if (option.id === selectedTicketOptionId) {
        card.classList.add('active');
      }
      const formState = option.preOpenFormEnabled ? t('enabledOption') : t('disabledOption');
      const styleKey = 't_panelButtonStyle_' + String(option.panelButtonStyle || 'PRIMARY').toLowerCase();
      const styleLabel = t(styleKey) !== styleKey ? t(styleKey) : String(option.panelButtonStyle || 'PRIMARY');
      const welcomeLabel = option.welcomeMessage || t('ticket_default_welcome_message');
      card.innerHTML = `
        <div class="option-card-title">${esc(option.label || t('ticket_option_unnamed'))}</div>
        <div class="option-card-sub">${esc(option.panelTitle || t('ticket_default_panel_title'))}</div>
        <div class="option-card-grid">
          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_name'))}</span><strong>${esc(option.label || t('ticket_option_unnamed'))}</strong></div>
          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_style'))}</span><strong>${esc(styleLabel)}</strong></div>
          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_form'))}</span><strong>${esc(formState)}</strong></div>
          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_welcome'))}</span><strong>${esc(welcomeLabel.length > 40 ? welcomeLabel.slice(0, 40) + '...' : welcomeLabel)}</strong></div>
        </div>
      `;
      card.onclick = () => {
        selectedTicketOptionId = option.id;
        populateTicketOptionEditor(option);
        renderTicketOptions();
      };
      root.appendChild(card);
    });
    populateTicketOptionEditor(getSelectedTicketOption());
  }

  function syncTicketOptionDraftFromEditor() {
    ensureSelectedTicketOption();
    const index = ticketOptionsState.findIndex(item => item.id === selectedTicketOptionId);
    if (index < 0) return;
    ticketOptionsState[index] = {
      id: selectedTicketOptionId,
      label: getValue('t_optionLabel'),
      panelButtonStyle: getValue('t_optionButtonStyle') || 'PRIMARY',
      panelTitle: getValue('t_optionPanelTitle'),
      panelDescription: getValue('t_optionPanelDescription'),
      welcomeMessage: getValue('t_optionWelcomeMessage'),
      preOpenFormEnabled: getChecked('t_optionPreOpenFormEnabled'),
      preOpenFormTitle: getValue('t_optionPreOpenFormTitle'),
      preOpenFormLabel: getValue('t_optionPreOpenFormLabel'),
      preOpenFormPlaceholder: getValue('t_optionPreOpenFormPlaceholder')
    };
  }

  function bindTicketOptionEditorAutoSync() {
    const ids = [
      't_optionLabel',
      't_optionButtonStyle',
      't_optionPanelTitle',
      't_optionPanelDescription',
      't_optionWelcomeMessage',
      't_optionPreOpenFormEnabled',
      't_optionPreOpenFormTitle',
      't_optionPreOpenFormLabel',
      't_optionPreOpenFormPlaceholder'
    ];
    ids.forEach(id => {
      const el = byId(id);
      if (!el || el.dataset.autosyncBound === '1') return;
      el.dataset.autosyncBound = '1';
      const syncOnly = () => syncTicketOptionDraftFromEditor();
      const syncAndRefresh = () => {
        syncTicketOptionDraftFromEditor();
        renderTicketOptions();
      };
      el.addEventListener('input', syncOnly);
      el.addEventListener('change', syncAndRefresh);
    });
  }

  function addTicketOption() {
    const draft = createTicketOptionDraft();
    ticketOptionsState = [...ticketOptionsState, draft];
    selectedTicketOptionId = draft.id;
    renderTicketOptions();
  }

  function deleteCurrentTicketOption() {
    if (ticketOptionsState.length <= 1) {
      showToast(t('ticket_option_delete_blocked'), 'error');
      return;
    }
    ticketOptionsState = ticketOptionsState.filter(item => item.id !== selectedTicketOptionId);
    selectedTicketOptionId = ticketOptionsState[0]?.id || '';
    renderTicketOptions();
    showToast(t('ticket_option_deleted'), 'success');
  }

  function resetTicketOptionsState() {
    ticketOptionsState = [createTicketOptionDraft({
      id: 'general',
      label: defaultTicketOptionLabel(),
      welcomeMessage: ''
    })];
    selectedTicketOptionId = 'general';
    renderTicketOptions();
  }

  function applyTicketOptions(options) {
    ticketOptionsState = Array.isArray(options)
      ? options.map(item => createTicketOptionDraft({
          id: String(item.id || `option-${Date.now().toString(36)}`),
          label: String(item.label || ''),
          panelTitle: String(item.panelTitle || ''),
          panelDescription: String(item.panelDescription || ''),
          panelButtonStyle: String(item.panelButtonStyle || 'PRIMARY').toUpperCase(),
          welcomeMessage: String(item.welcomeMessage || t('ticket_default_welcome_message')),
          preOpenFormEnabled: !!item.preOpenFormEnabled,
          preOpenFormTitle: String(item.preOpenFormTitle || ''),
          preOpenFormLabel: String(item.preOpenFormLabel || ''),
          preOpenFormPlaceholder: String(item.preOpenFormPlaceholder || '')
        }))
      : [];
    selectedTicketOptionId = ticketOptionsState[0]?.id || '';
    renderTicketOptions();
  }

  function collectTicketOptions() {
    syncTicketOptionDraftFromEditor();
    return ticketOptionsState.map(item => ({
      id: item.id,
      label: item.label,
      panelTitle: item.panelTitle,
      panelDescription: item.panelDescription,
      panelButtonStyle: item.panelButtonStyle,
      welcomeMessage: item.welcomeMessage,
      preOpenFormEnabled: !!item.preOpenFormEnabled,
      preOpenFormTitle: item.preOpenFormTitle,
      preOpenFormLabel: item.preOpenFormLabel,
      preOpenFormPlaceholder: item.preOpenFormPlaceholder
    }));
  }

  return {
    renderTicketOptions,
    bindTicketOptionEditorAutoSync,
    syncTicketOptionDraftFromEditor,
    addTicketOption,
    deleteCurrentTicketOption,
    resetTicketOptionsState,
    applyTicketOptions,
    collectTicketOptions
  };
}
