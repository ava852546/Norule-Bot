export function createDirtyStateModule(deps) {
  const { t } = deps;

  const dirtyTabs = new Set();
  let boundBeforeUnload = false;

  function getTabButton(tabId) {
    return document.querySelector(`.tab-btn[data-tab="${tabId}"]`);
  }

  function getTabPane(tabId) {
    return document.querySelector(`.tab-pane[data-pane="${tabId}"]`);
  }

  function ensurePaneDirtyNote(tabId) {
    const pane = getTabPane(tabId);
    if (!pane) return null;
    let note = pane.querySelector('.pane-dirty-note');
    if (note) return note;
    const head = pane.querySelector('.pane-head');
    if (!head) return null;
    note = document.createElement('span');
    note.className = 'pane-dirty-note hidden';
    note.dataset.i18n = 'unsavedChangesBadge';
    note.textContent = t('unsavedChangesBadge');
    const title = head.querySelector('h3');
    if (title && title.nextSibling) {
      head.insertBefore(note, title.nextSibling);
    } else if (title) {
      head.appendChild(note);
    } else {
      head.prepend(note);
    }
    return note;
  }

  function updateIndicators() {
    document.querySelectorAll('.tab-btn').forEach((button) => {
      const tabId = button.dataset.tab || '';
      const isDirty = dirtyTabs.has(tabId);
      button.classList.toggle('dirty', isDirty);
      if (isDirty) {
        button.setAttribute('title', t('unsavedChangesBadge'));
      } else {
        button.removeAttribute('title');
      }
    });
    document.querySelectorAll('.tab-pane').forEach((pane) => {
      const tabId = pane.dataset.pane || '';
      const isDirty = dirtyTabs.has(tabId);
      pane.classList.toggle('is-dirty', isDirty);
      const note = ensurePaneDirtyNote(tabId);
      if (note) {
        note.classList.toggle('hidden', !isDirty);
      }
    });
  }

  function bindPane(tabId) {
    const pane = getTabPane(tabId);
    if (!pane || pane.dataset.dirtyBound === '1') return;
    pane.dataset.dirtyBound = '1';
    ensurePaneDirtyNote(tabId);
    const handleChange = (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (!target.closest('input, select, textarea')) return;
      markDirty(tabId);
    };
    pane.addEventListener('input', handleChange, true);
    pane.addEventListener('change', handleChange, true);
  }

  function markDirty(tabId) {
    if (!tabId) return;
    dirtyTabs.add(tabId);
    updateIndicators();
  }

  function markClean(tabId) {
    if (!tabId) return;
    dirtyTabs.delete(tabId);
    updateIndicators();
  }

  function clearAll() {
    dirtyTabs.clear();
    updateIndicators();
  }

  function isDirty(tabId) {
    return dirtyTabs.has(tabId);
  }

  function hasDirty() {
    return dirtyTabs.size > 0;
  }

  function getDirtyTabs() {
    return [...dirtyTabs];
  }

  function confirmDiscard(tabIds = getDirtyTabs()) {
    const names = [...new Set((tabIds || []).filter((tabId) => dirtyTabs.has(tabId)))]
      .map((tabId) => t(`tabs_${tabId}`))
      .filter(Boolean);
    if (names.length === 0) return true;
    const message = names.length === 1
      ? t('unsavedChangesConfirmSingle').replace('{tab}', names[0])
      : t('unsavedChangesConfirmMulti')
          .replace('{count}', String(names.length))
          .replace('{tabs}', names.join(', '));
    return window.confirm(message);
  }

  function bindBeforeUnload() {
    if (boundBeforeUnload) return;
    boundBeforeUnload = true;
    window.addEventListener('beforeunload', (event) => {
      if (!hasDirty()) return;
      event.preventDefault();
      event.returnValue = '';
    });
  }

  function refreshLabels() {
    updateIndicators();
  }

  return {
    bindPane,
    bindBeforeUnload,
    markDirty,
    markClean,
    clearAll,
    isDirty,
    hasDirty,
    getDirtyTabs,
    confirmDiscard,
    refreshLabels
  };
}

