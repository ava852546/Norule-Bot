import { createSchemaTabRenderer } from '/web/pages/tabs/schema-tab.js';

export function createLogsTab(deps) {
  return createSchemaTabRenderer({
    id: 'logs',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetLogsBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('logs');
      }
    }
  });
}
