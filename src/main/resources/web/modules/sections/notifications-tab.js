import { createSchemaTabRenderer } from '/web/modules/sections/schema-tab.js';

export function createNotificationsTab(deps) {
  return createSchemaTabRenderer({
    id: 'notifications',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetNotificationsBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('notifications');
      }
      const openButton = shell.query('#openNotificationEditorBtn');
      if (openButton) {
        openButton.onclick = () => deps.notificationsModule.openNotificationEditor();
      }
      const closeButton = shell.query('#closeNotificationEditorBtn');
      if (closeButton) {
        closeButton.onclick = () => deps.notificationsModule.closeNotificationEditor();
      }
      const saveButton = shell.query('#saveNotificationSettingsBtn');
      if (saveButton) {
        saveButton.onclick = () => deps.notificationsModule.saveNotificationSettings().catch(() => {});
      }
      const modal = shell.query('#notificationEditorModal');
      if (modal && modal.dataset.modalBound !== '1') {
        modal.dataset.modalBound = '1';
        modal.onclick = (event) => {
          if (event.target === modal) {
            deps.notificationsModule.closeNotificationEditor();
          }
        };
      }
      if (shell.pane) {
        deps.notificationsModule.bindNotificationPreviewAutoSync();
      }
      if (!document.body.dataset.notificationsEscapeBound) {
        document.body.dataset.notificationsEscapeBound = '1';
        document.addEventListener('keydown', (event) => {
          if (event.key === 'Escape' && modal && !modal.classList.contains('hidden')) {
            deps.notificationsModule.closeNotificationEditor();
          }
        });
      }
    },
    onAfterLoad() {
      deps.notificationsModule.renderNotificationPreview();
    }
  });
}
