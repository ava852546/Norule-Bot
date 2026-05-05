import { createSchemaTabRenderer } from '/web/pages/tabs/schema-tab.js';

export function createTicketTab(deps) {
  const schemaTab = createSchemaTabRenderer({
    id: 'ticket',
    settingsFormModule: deps.settingsFormModule,
    dirtyStateModule: deps.dirtyStateModule,
    onInit(shell) {
      const resetButton = shell.query('#resetTicketBtn');
      if (resetButton) {
        resetButton.onclick = () => deps.settingsFormModule.resetSection('ticket');
      }
      const sendPanelButton = shell.query('#sendTicketPanelBtn');
      if (sendPanelButton) {
        sendPanelButton.onclick = () => deps.ticketModule.sendTicketPanel();
      }
      const addOptionButton = shell.query('#t_addOptionBtn');
      if (addOptionButton) {
        addOptionButton.onclick = () => {
          deps.ticketModule.addTicketOption();
          deps.dirtyStateModule?.markDirty('ticket');
        };
      }
      const deleteOptionButton = shell.query('#t_deleteOptionBtn');
      if (deleteOptionButton) {
        deleteOptionButton.onclick = () => {
          deps.ticketModule.deleteCurrentTicketOption();
          deps.dirtyStateModule?.markDirty('ticket');
        };
      }
      const loadHistoryButton = shell.query('#loadTicketHistoryBtn');
      if (loadHistoryButton) {
        loadHistoryButton.onclick = () => deps.ticketModule.loadTicketHistory({ force: true }).catch(() => {});
      }
      const historyList = shell.query('#ticketHistoryList');
      if (historyList && historyList.dataset.ticketHistoryBound !== '1') {
        historyList.dataset.ticketHistoryBound = '1';
        historyList.onclick = (event) => deps.ticketModule.handleTicketHistoryListClick(event).catch(() => {});
      }
      const supportRoles = shell.query('#t_supportRoleIds');
      if (supportRoles && supportRoles.dataset.supportRolesBound !== '1') {
        supportRoles.dataset.supportRolesBound = '1';
        supportRoles.addEventListener('change', deps.updateSupportRoleCount);
      }
      deps.ticketModule.bindTicketOptionEditorAutoSync();
    },
    onAfterLoad() {
      deps.ticketModule.renderTicketOptions();
      deps.updateSupportRoleCount();
    },
    onGuildChanged() {
      deps.ticketModule.resetTicketState();
    }
  });

  return {
    ...schemaTab,
    async load(options = {}) {
      const settings = await schemaTab.load(options);
      await deps.ticketModule.loadTicketHistory({ force: false });
      return settings;
    }
  };
}
