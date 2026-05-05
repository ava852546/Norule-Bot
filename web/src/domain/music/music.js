export function createMusicModule(deps) {
  const { byId, esc, t } = deps;
  let lastMusicStats = {};

  function renderMusicStats(stats) {
    lastMusicStats = stats || {};
    const root = byId('musicStatsCards');
    if (!root) return;
    const topSongLabel = String(lastMusicStats.topSongLabel || '').trim();
    const topSongCount = Number(lastMusicStats.topSongCount || 0);
    const topRequesterDisplay = String(lastMusicStats.topRequesterDisplay || '').trim();
    const topRequesterCount = Number(lastMusicStats.topRequesterCount || 0);
    const todayPlaybackDisplay = String(lastMusicStats.todayPlaybackDisplay || '00:00');
    const historyCount = Number(lastMusicStats.historyCount || 0);
    const noneText = t('music_stats_none');
    const cards = [
      {
        label: t('music_stats_top_song'),
        value: topSongLabel || noneText,
        meta: topSongCount > 0 ? `${t('music_stats_play_count')}: ${topSongCount}` : ''
      },
      {
        label: t('music_stats_top_requester'),
        value: topRequesterDisplay || noneText,
        meta: topRequesterCount > 0 ? `${t('music_stats_request_count')}: ${topRequesterCount}` : ''
      },
      {
        label: t('music_stats_today_time'),
        value: todayPlaybackDisplay || '00:00',
        meta: ''
      },
      {
        label: t('music_stats_history_count'),
        value: String(historyCount),
        meta: ''
      }
    ];
    root.innerHTML = cards.map(card => `
      <div class="stat-card">
        <div class="stat-card-label">${esc(card.label)}</div>
        <div class="stat-card-value">${esc(card.value)}</div>
        <div class="stat-card-meta">${esc(card.meta || '')}</div>
      </div>
    `).join('');
  }

  function getLastMusicStats() {
    return lastMusicStats;
  }

  return {
    renderMusicStats,
    getLastMusicStats
  };
}
