import { createSchemaTabRenderer } from '/web/modules/sections/schema-tab.js';

export function createNumberChainTab(deps) {
  return createSchemaTabRenderer({
    id: 'numberChain',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetNumberChainBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('numberChain');
      }
      const progressButton = shell.query('#resetNumberChainProgressBtn');
      if (progressButton) {
        progressButton.onclick = () => deps.actions.resetNumberChainProgress();
      }
    }
  });
}
