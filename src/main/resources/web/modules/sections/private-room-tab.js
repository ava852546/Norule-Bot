import { createSchemaTabRenderer } from '/web/modules/sections/schema-tab.js';

export function createPrivateRoomTab(deps) {
  return createSchemaTabRenderer({
    id: 'privateRoom',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetPrivateRoomBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('privateRoom');
      }
    }
  });
}
