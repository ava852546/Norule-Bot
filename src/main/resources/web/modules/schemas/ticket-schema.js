export function createTicketSchema({ ticketModule, applyTicketPanelDefaultsIfEmpty }) {
  return {
    fields: [
      { id: 't_enabled', path: 'ticket.enabled', type: 'checked', default: false },
      { id: 't_panelChannelId', path: 'ticket.panelChannelId', type: 'value', default: '' },
      { id: 't_panelTitle', path: 'ticket.panelTitle', type: 'value', default: '' },
      { id: 't_panelDescription', path: 'ticket.panelDescription', type: 'value', default: '' },
      { id: 't_panelColor', path: 'ticket.panelColor', type: 'value', default: '#5865F2' },
      { id: 't_panelButtonLimit', path: 'ticket.panelButtonLimit', type: 'number', default: 3 },
      { id: 't_autoCloseDays', path: 'ticket.autoCloseDays', type: 'number', default: 3 },
      { id: 't_maxOpenPerUser', path: 'ticket.maxOpenPerUser', type: 'number', default: 1 },
      { id: 't_openUiMode', path: 'ticket.openUiMode', type: 'value', default: 'BUTTONS' },
      { id: 't_supportRoleIds', path: 'ticket.supportRoleIds', type: 'multi', default: [] },
      { id: 't_blacklistedUserIds', path: 'ticket.blacklistedUserIds', type: 'value', default: '' }
    ],
    afterReset() {
      ticketModule.resetTicketState();
      applyTicketPanelDefaultsIfEmpty();
    },
    afterPopulate(settings) {
      ticketModule.applyTicketOptions(settings.ticket || {});
      applyTicketPanelDefaultsIfEmpty();
    }
  };
}
