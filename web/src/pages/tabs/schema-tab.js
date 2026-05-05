import { createSettingsGroupComponent } from '/web/ui/components/settings-group.js';
import { bindToggleComponents } from '/web/ui/components/toggle.js';
import { bindFieldInlineComponents } from '/web/ui/components/field-inline.js';

export function createSchemaTabRenderer({ id, settingsFormModule, onAfterLoad, onInit, onGuildChanged, dirtyStateModule }) {
  const shell = createSettingsGroupComponent(id);

  async function init() {
    if (shell.pane) {
      bindToggleComponents(shell.pane);
      bindFieldInlineComponents(shell.pane);
      dirtyStateModule?.bindPane(id);
    }
    onInit?.(shell);
  }

  async function load(options = {}) {
    const settings = await settingsFormModule.loadSection(id, options);
    onAfterLoad?.(settings, options, shell);
    return settings;
  }

  return {
    id,
    init,
    load,
    onGuildChanged
  };
}
