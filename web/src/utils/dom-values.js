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
