export function createMusicSchema({ renderMusicStats }) {
  return {
    fields: [
      { id: 'm_autoLeaveEnabled', path: 'music.autoLeaveEnabled', type: 'checked', default: true },
      { id: 'm_autoLeaveMinutes', path: 'music.autoLeaveMinutes', type: 'number', default: 5 },
      { id: 'm_autoplayEnabled', path: 'music.autoplayEnabled', type: 'checked', default: true },
      { id: 'm_defaultRepeatMode', path: 'music.defaultRepeatMode', type: 'value', default: 'OFF' },
      { id: 'm_commandChannelId', path: 'music.commandChannelId', type: 'value', default: '' },
      { id: 'm_historyLimit', path: 'music.historyLimit', type: 'number', default: 50 },
      { id: 'm_statsRetentionDays', path: 'music.statsRetentionDays', type: 'number', default: 0 }
    ],
    afterPopulate(settings) {
      renderMusicStats(settings.musicStats || {});
    }
  };
}
