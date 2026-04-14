import { createSchemaTabRenderer } from '/web/modules/sections/schema-tab.js';

export function createGeneralTab(deps) {
  return createSchemaTabRenderer({
    id: 'general',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetGeneralBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('general');
      }
      const languageSelect = shell.query('#s_language');
      if (languageSelect && languageSelect.dataset.generalBound !== '1') {
        languageSelect.dataset.generalBound = '1';
        languageSelect.addEventListener('change', () => {
          deps.i18nModule.applyNotificationTemplateDefaults();
          deps.notificationsModule.renderNotificationPreview();
          deps.i18nModule.applyTicketPanelDefaultsIfEmpty();
          deps.welcomeModule.renderWelcomePreview();
        });
      }
    }
  });
}
