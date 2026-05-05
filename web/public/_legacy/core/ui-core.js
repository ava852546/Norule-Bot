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
