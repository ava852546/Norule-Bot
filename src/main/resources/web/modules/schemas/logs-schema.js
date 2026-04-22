export function createLogsSchema() {
  return {
    fields: [
      { id: 'l_enabled', path: 'messageLogs.enabled', type: 'checked', default: true },
      { id: 'l_channelId', path: 'messageLogs.channelId', type: 'value', default: '' },
      { id: 'l_messageLogChannelId', path: 'messageLogs.messageLogChannelId', type: 'value', default: '' },
      { id: 'l_commandUsageChannelId', path: 'messageLogs.commandUsageChannelId', type: 'value', default: '' },
      { id: 'l_channelLifecycleChannelId', path: 'messageLogs.channelLifecycleChannelId', type: 'value', default: '' },
      { id: 'l_roleLogChannelId', path: 'messageLogs.roleLogChannelId', type: 'value', default: '' },
      { id: 'l_moderationLogChannelId', path: 'messageLogs.moderationLogChannelId', type: 'value', default: '' },
      { id: 'l_roleLogEnabled', path: 'messageLogs.roleLogEnabled', type: 'checked', default: true },
      { id: 'l_channelLifecycleLogEnabled', path: 'messageLogs.channelLifecycleLogEnabled', type: 'checked', default: true },
      { id: 'l_moderationLogEnabled', path: 'messageLogs.moderationLogEnabled', type: 'checked', default: true },
      { id: 'l_commandUsageLogEnabled', path: 'messageLogs.commandUsageLogEnabled', type: 'checked', default: true },
      { id: 'l_ignoredMemberIds', path: 'messageLogs.ignoredMemberIds', type: 'value', default: '' },
      { id: 'l_ignoredRoleIds', path: 'messageLogs.ignoredRoleIds', type: 'value', default: '' },
      { id: 'l_ignoredChannelIds', path: 'messageLogs.ignoredChannelIds', type: 'value', default: '' },
      { id: 'l_ignoredPrefixes', path: 'messageLogs.ignoredPrefixes', type: 'value', default: '' }
    ]
  };
}
