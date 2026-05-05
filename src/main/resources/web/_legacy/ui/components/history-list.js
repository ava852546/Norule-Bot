export function renderHistoryList({ container, items, emptyText, renderItem }) {
  if (!container) return;
  container.innerHTML = '';
  const rows = Array.isArray(items) ? items : [];
  if (rows.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'keyhint';
    empty.textContent = emptyText || '';
    container.appendChild(empty);
    return;
  }
  rows.forEach((item, index) => {
    const rendered = renderItem(item, index);
    if (rendered) {
      container.appendChild(rendered);
    }
  });
}
