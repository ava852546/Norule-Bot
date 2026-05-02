export function createPrivateRoomSchema() {
  return {
    fields: [
      { id: 'p_enabled', path: 'privateRoom.enabled', type: 'checked', default: true },
      { id: 'p_triggerVoiceChannelId', path: 'privateRoom.triggerVoiceChannelId', type: 'value', default: '' },
      { id: 'p_userLimit', path: 'privateRoom.userLimit', type: 'number', default: 0 }
    ]
  };
}
