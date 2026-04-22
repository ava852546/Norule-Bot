export function createNotificationsSchema({ applyNotificationTemplateDefaults, renderNotificationPreview }) {
  return {
    fields: [
      { id: 'n_enabled', path: 'notifications.enabled', type: 'checked', default: true },
      { id: 'n_memberJoinEnabled', path: 'notifications.memberJoinEnabled', type: 'checked', default: true },
      { id: 'n_memberLeaveEnabled', path: 'notifications.memberLeaveEnabled', type: 'checked', default: true },
      { id: 'n_voiceLogEnabled', path: 'notifications.voiceLogEnabled', type: 'checked', default: true },
      { id: 'n_memberChannelId', path: 'notifications.memberChannelId', type: 'value', default: '' },
      { id: 'n_memberJoinChannelId', path: 'notifications.memberJoinChannelId', type: 'value', default: '' },
      { id: 'n_memberLeaveChannelId', path: 'notifications.memberLeaveChannelId', type: 'value', default: '' },
      { id: 'n_voiceChannelId', path: 'notifications.voiceChannelId', type: 'value', default: '' },
      { id: 'n_memberJoinTitle', path: 'notifications.memberJoinTitle', type: 'value', default: '' },
      { id: 'n_memberJoinMessage', path: 'notifications.memberJoinMessage', type: 'value', default: '' },
      { id: 'n_memberJoinThumbnailUrl', path: 'notifications.memberJoinThumbnailUrl', type: 'value', default: '' },
      { id: 'n_memberJoinImageUrl', path: 'notifications.memberJoinImageUrl', type: 'value', default: '' },
      { id: 'n_memberLeaveMessage', path: 'notifications.memberLeaveMessage', type: 'value', default: '' },
      { id: 'n_voiceJoinMessage', path: 'notifications.voiceJoinMessage', type: 'value', default: '' },
      { id: 'n_voiceLeaveMessage', path: 'notifications.voiceLeaveMessage', type: 'value', default: '' },
      { id: 'n_voiceMoveMessage', path: 'notifications.voiceMoveMessage', type: 'value', default: '' },
      { id: 'n_memberJoinColor', path: 'notifications.memberJoinColor', type: 'value', default: '#2ECC71' },
      { id: 'n_memberLeaveColor', path: 'notifications.memberLeaveColor', type: 'value', default: '#E74C3C' },
      { id: 'n_voiceJoinColor', path: 'notifications.voiceJoinColor', type: 'value', default: '#2ECC71' },
      { id: 'n_voiceLeaveColor', path: 'notifications.voiceLeaveColor', type: 'value', default: '#E74C3C' },
      { id: 'n_voiceMoveColor', path: 'notifications.voiceMoveColor', type: 'value', default: '#5865F2' }
    ],
    afterReset() {
      applyNotificationTemplateDefaults();
      renderNotificationPreview();
    },
    afterPopulate() {
      applyNotificationTemplateDefaults();
      renderNotificationPreview();
    }
  };
}
