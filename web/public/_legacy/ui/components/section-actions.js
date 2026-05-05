export function createSectionActionsComponent({ loadButton, saveButton, onReload, onSave }) {
  if (loadButton) {
    loadButton.onclick = () => onReload?.();
  }
  if (saveButton) {
    saveButton.onclick = () => onSave?.();
  }

  return {
    setBusy(isBusy) {
      [loadButton, saveButton].forEach((button) => {
        if (!button) return;
        button.disabled = !!isBusy;
      });
    }
  };
}
