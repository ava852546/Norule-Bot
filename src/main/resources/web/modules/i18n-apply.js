export function createI18nApplyModule(deps) {
  const {
    byId,
    getValue,
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

  function setText(id, key) {
    const el = byId(id);
    if (el) el.textContent = t(key);
  }

  function setTabLabel(tab, key) {
    const btn = document.querySelector(`.tab-btn[data-tab="${tab}"]`);
    if (btn) btn.textContent = t(key);
  }

  function setSectionLabel(pane, key) {
    const el = document.querySelector(`.tab-pane[data-pane="${pane}"] h3`);
    if (el) el.textContent = t(key);
  }

  function setCheckboxLabel(forId, key) {
    const el = document.querySelector(`label[for="${forId}"]`);
    if (el) el.textContent = t(key);
  }

  function setFieldLabel(controlId, key) {
    const control = byId(controlId);
    const label = control?.closest('.field')?.querySelector('label');
    if (label) label.textContent = t(key);
  }

  function setSelectOptionLabel(selectId, optionValue, key) {
    const select = byId(selectId);
    if (!select) return;
    const option = [...select.options].find(op => op.value === optionValue);
    if (option) option.textContent = t(key);
  }

  function localizeLanguageOptions() {
    const select = byId('s_language');
    if (!select) return;
    const current = select.value;
    select.innerHTML = '';
    getBotLanguages().forEach(item => {
      const op = document.createElement('option');
      op.value = item.code;
      const key = `language_option_${String(item.code || '').toLowerCase().replace(/[^a-z0-9]/g, '_')}`;
      const translated = t(key);
      op.textContent = translated !== key
        ? translated
        : `${item.code} - ${item.label || item.code}`;
      select.appendChild(op);
    });
    if (current && [...select.options].some(op => op.value === current)) {
      select.value = current;
    } else if (select.options.length > 0) {
      select.value = select.options[0].value;
    }
  }

  function renderUiLanguageButtons() {
    const root = byId('uiLangButtons');
    if (!root) return;
    root.innerHTML = '';
    getUiLanguages().forEach(item => {
      const code = item.code || '';
      const label = item.label || code;
      if (!code) return;
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'lang-btn';
      btn.textContent = label;
      btn.dataset.lang = code;
      btn.classList.toggle('active', getUiLanguage() === code);
      btn.onclick = () => setUiLanguage(code);
      root.appendChild(btn);
    });
  }

  function localizeSettingsForm() {
    setText('label_s_language', 'label_s_language');
    setText('hint_s_language', 'hint_s_language');
    localizeLanguageOptions();

    setTabLabel('general', 'tabs_general');
    setTabLabel('notifications', 'tabs_notifications');
    setTabLabel('logs', 'tabs_logs');
    setTabLabel('music', 'tabs_music');
    setTabLabel('privateRoom', 'tabs_privateRoom');
    setTabLabel('welcome', 'tabs_welcome');
    setTabLabel('numberChain', 'tabs_numberChain');
    setTabLabel('ticket', 'tabs_ticket');
    setTabLabel('ticketHistory', 'tabs_ticket_history');

    setSectionLabel('general', 'section_language');
    setSectionLabel('notifications', 'section_notifications');
    setSectionLabel('logs', 'section_logs');
    setSectionLabel('music', 'section_music');
    setSectionLabel('privateRoom', 'section_privateRoom');
    setSectionLabel('welcome', 'section_welcome');
    setSectionLabel('numberChain', 'section_numberChain');
    setSectionLabel('ticket', 'section_ticket');
    setSectionLabel('ticketHistory', 'section_ticket_history');

    setCheckboxLabel('n_enabled', 'n_enabled');
    setCheckboxLabel('n_memberJoinEnabled', 'n_memberJoinEnabled');
    setCheckboxLabel('n_memberLeaveEnabled', 'n_memberLeaveEnabled');
    setCheckboxLabel('n_voiceLogEnabled', 'n_voiceLogEnabled');
    setFieldLabel('n_memberChannelId', 'n_memberChannelId');
    setFieldLabel('n_memberJoinChannelId', 'n_memberJoinChannelId');
    setFieldLabel('n_memberLeaveChannelId', 'n_memberLeaveChannelId');
    setFieldLabel('n_voiceChannelId', 'n_voiceChannelId');
    setFieldLabel('n_memberJoinTitle', 'n_memberJoinTitle');
    setText('hint_n_memberJoinTitle', 'n_memberJoinTitle_hint');
    setFieldLabel('n_memberJoinMessage', 'n_memberJoinMessage');
    setText('hint_n_memberJoinMessage', 'n_memberJoinMessage_hint');
    setFieldLabel('n_memberLeaveMessage', 'n_memberLeaveMessage');
    setFieldLabel('n_voiceJoinMessage', 'n_voiceJoinMessage');
    setText('hint_n_voiceJoinMessage', 'n_voiceJoinMessage_hint');
    setFieldLabel('n_voiceLeaveMessage', 'n_voiceLeaveMessage');
    setText('hint_n_voiceLeaveMessage', 'n_voiceLeaveMessage_hint');
    setFieldLabel('n_voiceMoveMessage', 'n_voiceMoveMessage');
    setText('hint_n_voiceMoveMessage', 'n_voiceMoveMessage_hint');
    setFieldLabel('n_memberJoinColor', 'n_memberJoinColor');
    setFieldLabel('n_memberLeaveColor', 'n_memberLeaveColor');
    setFieldLabel('n_voiceJoinColor', 'n_voiceJoinColor');
    setFieldLabel('n_voiceLeaveColor', 'n_voiceLeaveColor');
    setFieldLabel('n_voiceMoveColor', 'n_voiceMoveColor');
    setText('notifications_group_title', 'section_notifications');
    setText('notifications_message_card_title', 'notifications_message_card_title');
    setText('notifications_message_lead', 'notifications_message_lead');
    setText('openNotificationEditorBtn', 'openNotificationEditorBtn');
    setText('notificationEditorTitle', 'notificationEditorTitle');
    setText('closeNotificationEditorBtn', 'closeBtn');
    setText('saveNotificationSettingsBtn', 'saveSettingsBtn');
    setText('notification_member_template_card_title', 'notification_member_template_card_title');
    setText('notification_voice_template_card_title', 'notification_voice_template_card_title');
    setText('notification_voice_color_card_title', 'notification_voice_color_card_title');
    setText('notification_voice_preview_card_title', 'notification_voice_preview_card_title');
    renderNotificationPreview();

    setText('welcome_group_title', 'welcome_group_title');
    setText('welcome_message_card_title', 'welcome_message_card_title');
    setText('welcome_message_card_title_modal', 'welcome_message_card_title');
    setText('welcome_message_lead', 'welcome_message_lead');
    setCheckboxLabel('w_enabled', 'w_enabled');
    setFieldLabel('w_channelId', 'w_channelId');
    setText('openWelcomeEditorBtn', 'openWelcomeEditorBtn');
    setText('welcomeEditorTitle', 'welcomeEditorTitle');
    setText('closeWelcomeEditorBtn', 'closeBtn');
    setText('saveWelcomeSettingsBtn', 'saveSettingsBtn');
    setFieldLabel('w_title', 'w_title');
    setText('hint_w_title', 'w_title_hint');
    setFieldLabel('w_message', 'w_message');
    setText('hint_w_message', 'w_message_hint');
    setText('welcome_media_card_title', 'welcome_media_card_title');
    setText('welcome_thumbnail_card_title', 'welcome_thumbnail_card_title');
    setFieldLabel('w_thumbnailUrl', 'w_thumbnailUrl');
    setText('hint_w_thumbnailUrl', 'w_thumbnailUrl_hint');
    setText('welcome_image_card_title', 'welcome_image_card_title');
    setFieldLabel('w_imageUrl', 'w_imageUrl');
    setText('hint_w_imageUrl', 'w_imageUrl_hint');
    setText('welcome_preview_card_title', 'welcome_preview_card_title');
    setText('sendWelcomePreviewBtn', 'sendWelcomePreviewBtn');
    renderWelcomePreview();

    setCheckboxLabel('l_enabled', 'l_enabled');
    setCheckboxLabel('l_roleLogEnabled', 'l_roleLogEnabled');
    setCheckboxLabel('l_channelLifecycleLogEnabled', 'l_channelLifecycleLogEnabled');
    setCheckboxLabel('l_moderationLogEnabled', 'l_moderationLogEnabled');
    setCheckboxLabel('l_commandUsageLogEnabled', 'l_commandUsageLogEnabled');
    setFieldLabel('l_channelId', 'l_channelId');
    setFieldLabel('l_messageLogChannelId', 'l_messageLogChannelId');
    setFieldLabel('l_commandUsageChannelId', 'l_commandUsageChannelId');
    setFieldLabel('l_channelLifecycleChannelId', 'l_channelLifecycleChannelId');
    setFieldLabel('l_roleLogChannelId', 'l_roleLogChannelId');
    setFieldLabel('l_moderationLogChannelId', 'l_moderationLogChannelId');

    setCheckboxLabel('m_autoLeaveEnabled', 'm_autoLeaveEnabled');
    setFieldLabel('m_autoLeaveMinutes', 'm_autoLeaveMinutes');
    setCheckboxLabel('m_autoplayEnabled', 'm_autoplayEnabled');
    setFieldLabel('m_defaultRepeatMode', 'm_defaultRepeatMode');
    setFieldLabel('m_commandChannelId', 'm_commandChannelId');
    setText('music_settings_card_title', 'section_music');
    setText('music_stats_title', 'music_stats_title');

    setCheckboxLabel('p_enabled', 'p_enabled');
    setFieldLabel('p_triggerVoiceChannelId', 'p_triggerVoiceChannelId');
    setFieldLabel('p_userLimit', 'p_userLimit');

    setCheckboxLabel('nc_enabled', 'nc_enabled');
    setFieldLabel('nc_channelId', 'nc_channelId');
    setFieldLabel('nc_nextNumber', 'nc_nextNumber');
    setText('resetNumberChainProgressBtn', 'resetNumberChainProgressBtn');

    setCheckboxLabel('t_enabled', 't_enabled');
    setText('ticket_group_basic_title', 'ticket_group_basic_title');
    setText('ticket_group_access_title', 'ticket_group_access_title');
    setText('ticket_group_panel_title', 'ticket_group_panel_title');
    setText('ticket_group_options_title', 'ticket_group_options_title');
    setText('ticket_group_history_title', 'ticket_group_history_title');
    setFieldLabel('t_panelChannelId', 't_panelChannelId');
    setFieldLabel('t_panelTitle', 't_panelTitle');
    setFieldLabel('t_panelDescription', 't_panelDescription');
    setFieldLabel('t_panelColor', 't_panelColor');
    setFieldLabel('t_panelButtonLimit', 't_panelButtonLimit');
    setFieldLabel('t_autoCloseDays', 't_autoCloseDays');
    setFieldLabel('t_maxOpenPerUser', 't_maxOpenPerUser');
    setFieldLabel('t_openUiMode', 't_openUiMode');
    setText('hint_t_openUiMode', 't_openUiMode_hint');
    setSelectOptionLabel('t_openUiMode', 'BUTTONS', 't_openUiMode_buttons');
    setSelectOptionLabel('t_openUiMode', 'SELECT', 't_openUiMode_select');
    setFieldLabel('t_supportRoleIds', 't_supportRoleIds');
    setText('hint_t_supportRoleIds', 't_supportRoleIds_hint');
    setFieldLabel('t_blacklistedUserIds', 't_blacklistedUserIds');
    setText('hint_t_blacklistedUserIds', 't_blacklistedUserIds_hint');
    setText('hint_t_optionEditor', 't_optionEditor_hint');
    setText('label_t_optionLabel', 't_optionLabel');
    setText('label_t_optionButtonStyle', 't_optionButtonStyle');
    setSelectOptionLabel('t_optionButtonStyle', 'PRIMARY', 't_panelButtonStyle_primary');
    setSelectOptionLabel('t_optionButtonStyle', 'SECONDARY', 't_panelButtonStyle_secondary');
    setSelectOptionLabel('t_optionButtonStyle', 'SUCCESS', 't_panelButtonStyle_success');
    setSelectOptionLabel('t_optionButtonStyle', 'DANGER', 't_panelButtonStyle_danger');
    setText('label_t_optionPanelTitle', 't_optionPanelTitle');
    setText('label_t_optionPanelDescription', 't_optionPanelDescription');
    setText('label_t_optionWelcomeMessage', 't_optionWelcomeMessage');
    setText('hint_t_optionWelcomeMessage', 't_welcomeMessage_hint');
    setText('label_t_optionPreOpenFormEnabled', 't_optionPreOpenFormEnabled');
    setText('label_t_optionPreOpenFormTitle', 't_optionPreOpenFormTitle');
    setText('label_t_optionPreOpenFormLabel', 't_optionPreOpenFormLabel');
    setText('label_t_optionPreOpenFormPlaceholder', 't_optionPreOpenFormPlaceholder');
    setText('t_addOptionBtn', 't_addOptionBtn');
    setText('t_deleteOptionBtn', 't_deleteOptionBtn');
    setText('loadTicketHistoryBtn', 'loadTicketHistoryBtn');

    applyNotificationTemplateDefaults();
    applyTicketPanelDefaultsIfEmpty();
    renderTicketOptions();
    renderMusicStats(getLastMusicStats());
  }

  function applyUiLanguage() {
    setText('titleMain', 'titleMain');
    setText('subtitleMain', 'subtitleMain');
    setText('langLabel', 'langLabel');
    renderUiLanguageButtons();
    setText('loginBtn', 'loginBtn');
    setText('logoutBtn', 'logoutBtn');
    setText('guildsTitle', 'guildsTitle');
    setText('guildsSubtitle', 'guildsSubtitle');
    setText('settingsTitle', 'settingsTitle');
    setText('settingsSubtitle', 'settingsSubtitle');
    setText('guildReloadBtn', 'guildReloadBtn');
    setText('loadSettingsBtn', 'loadSettingsBtn');
    setText('sendTicketPanelBtn', 'sendTicketPanelBtn');
    setText('saveSettingsBtn', 'saveSettingsBtn');
    setText('resetGeneralBtn', 'resetSectionBtn');
    setText('resetNotificationsBtn', 'resetSectionBtn');
    setText('resetLogsBtn', 'resetSectionBtn');
    setText('resetMusicBtn', 'resetSectionBtn');
    setText('resetPrivateRoomBtn', 'resetSectionBtn');
    setText('resetWelcomeBtn', 'resetSectionBtn');
    setText('resetNumberChainBtn', 'resetSectionBtn');
    setText('resetTicketBtn', 'resetSectionBtn');
    localizeSettingsForm();
    updateMeLine();
  }

  return {
    applyUiLanguage,
    updateMeLine,
    localizeSettingsForm
  };
}
