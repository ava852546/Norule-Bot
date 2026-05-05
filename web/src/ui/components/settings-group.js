export function createSettingsGroupComponent(tabId) {
  const pane = document.querySelector(`.tab-pane[data-pane="${tabId}"]`);
  return {
    tabId,
    pane,
    query(selector) {
      return pane?.querySelector(selector) || null;
    },
    queryAll(selector) {
      return pane ? [...pane.querySelectorAll(selector)] : [];
    }
  };
}
