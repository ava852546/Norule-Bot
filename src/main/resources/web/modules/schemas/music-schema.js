export function createMusicSchema({ renderMusicStats }) {
  return {
    fields: [
      { id: 'm_autoLeaveEnabled', path: 'music.autoLeaveEnabled', type: 'checked', default: true },
      { id: 'm_autoLeaveMinutes', path: 'music.autoLeaveMinutes', type: 'number', default: 5 },
      { id: 'm_autoplayEnabled', path: 'music.autoplayEnabled', type: 'checked', default: true },
      { id: 'm_defaultRepeatMode', path: 'music.defaultRepeatMode', type: 'value', default: 'OFF' },
      { id: 'm_commandChannelId', path: 'music.commandChannelId', type: 'value', default: '' }
    ],
    afterPopulate(settings) {
      renderMusicStats(settings.musicStats || {});
    }
  };
}
