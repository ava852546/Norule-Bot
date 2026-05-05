import { createTicketHistoryModule } from '/web/domain/ticket/ticket-history.js';
import { createTicketOptionsModule } from '/web/domain/ticket/ticket-options.js';

export function createTicketModule(deps) {
  const {
    byId,
    t,
    getValue,
    getChecked,
    setValue,
    selectedGuild,
    api,
    showStatus,
    showToast,
    formatBytes,
    formatDateTime
  } = deps;
  const ticketHistoryModule = createTicketHistoryModule({
    byId,
    esc: deps.esc,
    t,
    selectedGuild,
    api,
    showToast,
    renderHistoryList: deps.renderHistoryList,
    formatBytes,
    formatDateTime
  });
  const ticketOptionsModule = createTicketOptionsModule({
    byId,
    esc: deps.esc,
    t,
    getValue,
    getChecked,
    setValue,
    setChecked: deps.setChecked,
    showToast
  });

  function resetTicketState() {
    ticketOptionsModule.resetTicketOptionsState();
    ticketHistoryModule.resetTicketHistoryState();
  }

  function applyTicketOptions(ticketCfg) {
    ticketOptionsModule.applyTicketOptions(ticketCfg?.options || []);
  }

  function collectTicketOptions() {
    return ticketOptionsModule.collectTicketOptions();
  }

  async function sendTicketPanel() {
    const guildId = selectedGuild();
    if (!guildId) return;
    try {
      const data = await api(`/api/guild/${guildId}/ticket/panel`, { method: 'POST' });
      showStatus(data?.message || t('ticketPanelSent'));
      showToast(data?.message || t('ticketPanelSent'), 'success');
    } catch (e) {
      showStatus(e.message || t('ticketPanelSendFailed'));
      showToast(e.message || t('ticketPanelSendFailed'), 'error');
    }
  }

  return {
    renderTicketOptions: ticketOptionsModule.renderTicketOptions,
    bindTicketOptionEditorAutoSync: ticketOptionsModule.bindTicketOptionEditorAutoSync,
    syncTicketOptionDraftFromEditor: ticketOptionsModule.syncTicketOptionDraftFromEditor,
    addTicketOption: ticketOptionsModule.addTicketOption,
    deleteCurrentTicketOption: ticketOptionsModule.deleteCurrentTicketOption,
    resetTicketState,
    applyTicketOptions,
    collectTicketOptions,
    sendTicketPanel,
    loadTicketHistory: ticketHistoryModule.loadTicketHistory,
    handleTicketHistoryListClick: ticketHistoryModule.handleTicketHistoryListClick
  };
}
