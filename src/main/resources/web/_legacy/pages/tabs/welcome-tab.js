import { createSchemaTabRenderer } from '/web/pages/tabs/schema-tab.js';

export function createWelcomeTab(deps) {
  return createSchemaTabRenderer({
    id: 'welcome',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetWelcomeBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('welcome');
      }
      const previewButton = shell.query('#sendWelcomePreviewBtn');
      if (previewButton) {
        previewButton.onclick = () => deps.actions.sendWelcomePreview().catch(() => {});
      }
      const openButton = shell.query('#openWelcomeEditorBtn');
      if (openButton) {
        openButton.onclick = () => deps.welcomeModule.openWelcomeEditor();
      }
      const closeButton = shell.query('#closeWelcomeEditorBtn');
      if (closeButton) {
        closeButton.onclick = () => deps.welcomeModule.closeWelcomeEditor();
      }
      const saveButton = shell.query('#saveWelcomeSettingsBtn');
      if (saveButton) {
        saveButton.onclick = () => deps.welcomeModule.saveWelcomeSettings().catch(() => {});
      }
      const modal = shell.query('#welcomeEditorModal');
      if (modal && modal.dataset.modalBound !== '1') {
        modal.dataset.modalBound = '1';
        modal.onclick = (event) => {
          if (event.target === modal) {
            deps.welcomeModule.closeWelcomeEditor();
          }
        };
      }
      if (shell.pane) {
        deps.welcomeModule.bindWelcomePreviewAutoSync();
      }
      if (!document.body.dataset.welcomeEscapeBound) {
        document.body.dataset.welcomeEscapeBound = '1';
        document.addEventListener('keydown', (event) => {
          if (event.key === 'Escape' && modal && !modal.classList.contains('hidden')) {
            deps.welcomeModule.closeWelcomeEditor();
          }
        });
      }
    },
    onAfterLoad() {
      deps.welcomeModule.renderWelcomePreview();
    }
  });
}
