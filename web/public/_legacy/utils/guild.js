export function selectedGuildValue(guildSelect) {
  return guildSelect?.value || '';
}

export function selectedGuildLabel(guildSelect, fallback = 'NoRule Bot') {
  return guildSelect?.selectedOptions?.[0]?.textContent || fallback;
}

export function guildInitial(name) {
  const trimmed = String(name || '').trim();
  if (!trimmed) return '?';
  return Array.from(trimmed)[0].toUpperCase();
}
