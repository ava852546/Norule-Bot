import { createSchemaTabRenderer } from '/web/pages/tabs/schema-tab.js';

export function createMusicTab(deps) {
  return createSchemaTabRenderer({
    id: 'music',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetMusicBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('music');
      }
    }
  });
}
