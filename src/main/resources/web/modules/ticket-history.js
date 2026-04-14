import { renderHistoryList } from '/web/modules/components/history-list.js';

export function createTicketHistoryModule(deps) {
  const {
    byId,
    esc,
    t,
    selectedGuild,
    api,
    showToast,
    formatBytes,
    formatDateTime
  } = deps;

  let ticketHistoryFilesState = [];
  let pendingDeleteTranscriptName = '';
  let ticketHistoryRetentionDays = 90;
  let ticketHistoryCleanedCount = 0;
  let loadedGuildId = '';
  let historyLoaded = false;

  function resetTicketHistoryState() {
    ticketHistoryFilesState = [];
    pendingDeleteTranscriptName = '';
    ticketHistoryRetentionDays = 90;
    ticketHistoryCleanedCount = 0;
    loadedGuildId = '';
    historyLoaded = false;
  }

  function updateTicketHistoryMeta(countOverride = ticketHistoryFilesState.length) {
    const metaEl = byId('ticketHistoryMeta');
    if (!metaEl) return;
    metaEl.textContent = t('ticket_history_meta')
      .replace('{count}', String(countOverride))
      .replace('{days}', String(ticketHistoryRetentionDays))
      .replace('{cleaned}', String(ticketHistoryCleanedCount));
  }

  function renderTicketHistoryList() {
    const listEl = byId('ticketHistoryList');
    if (!listEl) return;
    const files = Array.isArray(ticketHistoryFilesState) ? ticketHistoryFilesState : [];
    renderHistoryList({
      container: listEl,
      items: files,
      emptyText: t('ticket_history_empty'),
      renderItem(file) {
        const item = document.createElement('div');
        item.className = 'history-item';
        const mention = file.channelId && Number(file.channelId) > 0 ? `<#${file.channelId}>` : '-';
        const encodedName = encodeURIComponent(String(file.name || ''));
        const isConfirming = pendingDeleteTranscriptName === String(file.name || '');
        item.innerHTML = `
          <div>
            <div><a href="${esc(file.url || '#')}" target="_blank" rel="noopener">${esc(file.name || '-')}</a></div>
            <div class="history-meta">${esc(t('ticket_history_channel'))}: ${esc(mention)} | ${esc(t('ticket_history_time'))}: ${esc(formatDateTime(file.lastModifiedAt))}</div>
          </div>
          <div class="history-actions">
            <div class="history-meta">${esc(formatBytes(file.size))}</div>
            ${isConfirming
              ? `<button type="button" class="warn" data-action="confirm-delete-transcript" data-file="${encodedName}" data-name="${encodedName}">${esc(t('ticket_history_delete_confirm'))}</button>
                 <button type="button" data-action="cancel-delete-transcript">${esc(t('ticket_history_delete_cancel'))}</button>`
              : `<button type="button" class="warn" data-action="delete-transcript" data-file="${encodedName}" data-name="${encodedName}">${esc(t('ticket_history_delete'))}</button>`}
          </div>
        `;
        return item;
      }
    });
  }

  async function loadTicketHistory(options = {}) {
    const guildId = selectedGuild();
    if (!guildId) return;
    const listEl = byId('ticketHistoryList');
    const metaEl = byId('ticketHistoryMeta');
    if (!options.force && historyLoaded && loadedGuildId === guildId) {
      updateTicketHistoryMeta();
      renderTicketHistoryList();
      return ticketHistoryFilesState;
    }
    if (listEl) listEl.innerHTML = '';
    if (metaEl) metaEl.textContent = t('ticket_history_loading');
    try {
      const data = await api(`/api/guild/${guildId}/ticket/transcripts`);
      const files = Array.isArray(data?.files) ? data.files : [];
      ticketHistoryFilesState = files;
      ticketHistoryRetentionDays = Number(data?.retentionDays || 90);
      ticketHistoryCleanedCount = Number(data?.cleaned || 0);
      loadedGuildId = guildId;
      historyLoaded = true;
      updateTicketHistoryMeta(files.length);
      renderTicketHistoryList();
      return files;
    } catch (e) {
      if (metaEl) metaEl.textContent = e.message || t('ticket_history_load_failed');
      throw e;
    }
  }

  async function deleteTicketTranscript(encodedName, displayName) {
    const guildId = selectedGuild();
    if (!guildId || !encodedName) return;
    try {
      const data = await api(`/api/guild/${guildId}/ticket/transcript/${encodedName}`, { method: 'DELETE' });
      pendingDeleteTranscriptName = '';
      ticketHistoryFilesState = ticketHistoryFilesState.filter(item => String(item.name || '') !== String(displayName || ''));
      renderTicketHistoryList();
      updateTicketHistoryMeta();
      const message = data?.message || t('ticket_history_delete_success').replace('{name}', String(displayName || ''));
      showToast(message, 'success');
    } catch (e) {
      showToast(e.message || t('ticket_history_delete_failed'), 'error');
    }
  }

  async function handleTicketHistoryListClick(event) {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;
    const action = btn.dataset.action || '';
    if (action === 'delete-transcript') {
      pendingDeleteTranscriptName = decodeURIComponent(btn.dataset.name || '');
      renderTicketHistoryList();
      return;
    }
    if (action === 'cancel-delete-transcript') {
      pendingDeleteTranscriptName = '';
      renderTicketHistoryList();
      return;
    }
    if (action === 'confirm-delete-transcript') {
      const encodedName = btn.dataset.file || '';
      const displayName = decodeURIComponent(btn.dataset.name || '');
      await deleteTicketTranscript(encodedName, displayName);
    }
  }

  return {
    resetTicketHistoryState,
    renderTicketHistoryList,
    loadTicketHistory,
    handleTicketHistoryListClick
  };
}
