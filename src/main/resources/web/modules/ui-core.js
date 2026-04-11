export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function createApi() {
  return async function api(url, options = {}) {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...(options.headers || {}) },
      ...options
    });
    const text = await response.text();
    let data = {};
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = { error: text };
      }
    }
    if (!response.ok) {
      throw new Error(data?.error || data?.message || `HTTP ${response.status}`);
    }
    return data;
  };
}

export function createStatusHelpers(statusEl) {
  function showStatus(message) {
    if (!statusEl) return;
    statusEl.textContent = String(message || '');
  }

  function showToast(message, kind = 'info') {
    const text = String(message || '').trim();
    if (!text) return;
    const toast = document.createElement('div');
    toast.className = `toast toast-${kind}`;
    toast.textContent = text;
    Object.assign(toast.style, {
      position: 'fixed',
      right: '20px',
      bottom: '20px',
      zIndex: '9999',
      padding: '10px 14px',
      borderRadius: '10px',
      color: '#fff',
      background: kind === 'error' ? '#d9534f' : (kind === 'success' ? '#2e8b57' : '#5865f2'),
      boxShadow: '0 10px 30px rgba(0,0,0,0.25)',
      maxWidth: '320px'
    });
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2600);
  }

  return { showStatus, showToast };
}

export function selectedGuildValue(guildSelect) {
  return guildSelect?.value || '';
}

export function selectedGuildLabel(guildSelect, fallback = 'NoRule Bot') {
  return guildSelect?.selectedOptions?.[0]?.textContent || fallback;
}

export function getValue(byId, id) {
  return byId(id)?.value ?? '';
}

export function setValue(byId, id, value) {
  const el = byId(id);
  if (el) el.value = value ?? '';
}

export function getChecked(byId, id) {
  return !!byId(id)?.checked;
}

export function setChecked(byId, id, value) {
  const el = byId(id);
  if (el) el.checked = !!value;
}

export function getMultiSelectValues(byId, id) {
  const el = byId(id);
  if (!el) return [];
  return [...el.selectedOptions].map((option) => option.value);
}

export function setMultiSelectValues(byId, id, values) {
  const selected = new Set((values || []).map(String));
  const el = byId(id);
  if (!el) return;
  [...el.options].forEach((option) => {
    option.selected = selected.has(option.value);
  });
}

export function guildInitial(name) {
  const trimmed = String(name || '').trim();
  if (!trimmed) return '?';
  return Array.from(trimmed)[0].toUpperCase();
}

export function populateSelect(byId, selectId, items, currentValue = '') {
  const select = byId(selectId);
  if (!select) return;
  const previous = currentValue || select.value || '';
  select.innerHTML = '<option value="">-</option>';
  items.forEach((item) => {
    const option = document.createElement('option');
    option.value = String(item.id ?? '');
    option.textContent = item.name || String(item.id ?? '');
    select.appendChild(option);
  });
  if ([...select.options].some((option) => option.value === previous)) {
    select.value = previous;
  }
}

export function formatBytes(size) {
  const value = Number(size || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatDateTime(timestamp) {
  const value = Number(timestamp || 0);
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleString();
}

export function initTabs() {
  const buttons = document.querySelectorAll('.tab-btn');
  const panes = document.querySelectorAll('.tab-pane');
  buttons.forEach((button) => {
    button.onclick = () => {
      const tab = button.getAttribute('data-tab');
      buttons.forEach((candidate) => candidate.classList.toggle('active', candidate === button));
      panes.forEach((pane) => pane.classList.toggle('active', pane.getAttribute('data-pane') === tab));
    };
  });
}
