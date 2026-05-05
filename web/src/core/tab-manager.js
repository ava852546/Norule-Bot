export function createTabManager({ sections, defaultTab = 'general', onActivated }) {
  const buttons = [...document.querySelectorAll('.tab-btn')];
  const panes = [...document.querySelectorAll('.tab-pane')];
  const entries = new Map(
    (sections || []).map((section) => [section.id, { ...section, initialized: false }])
  );
  let activeTab = defaultTab;

  function syncActiveClasses(tabId) {
    buttons.forEach((button) => {
      button.classList.toggle('active', button.getAttribute('data-tab') === tabId);
    });
    panes.forEach((pane) => {
      pane.classList.toggle('active', pane.getAttribute('data-pane') === tabId);
    });
  }

  async function activate(tabId, options = {}) {
    const entry = entries.get(tabId);
    if (!entry) return;
    activeTab = tabId;
    syncActiveClasses(tabId);
    if (!entry.initialized) {
      await entry.init?.();
      entry.initialized = true;
    }
    await entry.load?.(options);
    onActivated?.(tabId, options);
  }

  function bind() {
    buttons.forEach((button) => {
      button.onclick = () => {
        const tabId = button.getAttribute('data-tab') || defaultTab;
        activate(tabId).catch(() => {});
      };
    });
    syncActiveClasses(activeTab);
  }

  async function refreshActive(options = {}) {
    await activate(activeTab, { ...options, force: true });
  }

  async function onGuildChanged(options = {}) {
    entries.forEach((entry) => {
      entry.onGuildChanged?.(options);
    });
    await activate(activeTab, { ...options, force: true, reason: 'guild-change' });
  }

  return {
    bind,
    activate,
    refreshActive,
    onGuildChanged,
    getActiveTab: () => activeTab
  };
}
