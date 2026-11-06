<script setup lang="ts">
import { computed } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.music)
const stats = computed(() => dashboard.state.settings.musicStats)
const dirty = () => dashboard.markDirty('music')
const invalidModernChannel = computed(() => cfg.value.musicPanelMode === 'MODERN'
  && !dashboard.state.textChannels.some((channel) => channel.id === cfg.value.commandChannelId && channel.musicPanelUsable))

const statCards = computed(() => [
  { label: dashboard.i18n.t('music_stats_top_song', '最常播放歌曲'), value: stats.value.topSongLabel || dashboard.i18n.t('music_stats_none', '尚無資料'), meta: stats.value.topSongCount ? dashboard.i18n.t('dashboard_count_times', '{count} 次', { count: stats.value.topSongCount }) : '' },
  { label: dashboard.i18n.t('music_stats_top_requester', '最常點歌成員'), value: stats.value.topRequesterDisplay || dashboard.i18n.t('music_stats_none', '尚無資料'), meta: stats.value.topRequesterCount ? dashboard.i18n.t('dashboard_count_times', '{count} 次', { count: stats.value.topRequesterCount }) : '' },
  { label: dashboard.i18n.t('music_stats_today_time', '今日播放時間'), value: stats.value.todayPlaybackDisplay || '00:00', meta: '' },
  { label: dashboard.i18n.t('music_stats_history_count', '歷史紀錄數'), value: String(stats.value.historyCount || 0), meta: '' },
])
</script>

<template>
  <div class="nr-stack">
    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_music', 'Music')" :title="dashboard.i18n.t('section_music', '音樂設定')" :description="dashboard.i18n.t('music_settings_lead', '集中管理自動播放、循環模式與音樂指令頻道。')">
      <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('music')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button></template>
      <div class="nr-toggle-grid">
        <p class="muted">{{ dashboard.i18n.t('dashboard_music_auto_leave_description', '所有伺服器固定啟用自動離開：無真人 10 秒；未播放或暫停 10 分鐘。') }}</p>
        <DashboardToggle :model-value="cfg.autoplayEnabled" :label="dashboard.i18n.t('m_autoplayEnabled', '啟用自動播放')" :description="dashboard.i18n.t('dashboard_music_autoplay_description', '佇列結束後繼續推薦歌曲')" @update:model-value="cfg.autoplayEnabled = $event; dirty()" />
      </div>
      <div class="nr-form-grid is-two nr-form-block">
        <label class="nr-field is-full"><span>{{ dashboard.i18n.t('panel_mode_label', '音樂面板模式') }}</span>
          <select v-model="cfg.musicPanelMode" @change="dirty">
            <option value="MODERN">{{ dashboard.i18n.t('panel_mode_modern', '新版模式（預設）') }}</option>
            <option value="LEGACY">{{ dashboard.i18n.t('panel_mode_legacy', '傳統模式') }}</option>
          </select>
          <small>{{ cfg.musicPanelMode === 'MODERN'
            ? dashboard.i18n.t('panel_mode_modern_description', '常駐音樂控制面板，自動清理指定音樂頻道訊息。')
            : dashboard.i18n.t('panel_mode_legacy_description', '每次成功點歌後，移除舊音樂面板並在頻道底部建立新面板；不清理一般訊息。') }}</small>
        </label>
        <p v-if="invalidModernChannel" class="nr-field is-full" role="status">{{ dashboard.i18n.t('panel_fallback_legacy', '預設音樂面板頻道不存在、無法存取或尚未設定，目前暫時使用傳統模式，仍可正常點歌。請透過 /settings 設定有效的預設音樂面板頻道。如果不希望每次點歌都收到提示，可以在設定中切換為 LEGACY 傳統模式。在頻道恢復有效或手動切換為 LEGACY 前，每次 /play 都會顯示此私人提示。') }}</p>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_defaultRepeatMode', '預設循環模式') }}</span><select v-model="cfg.defaultRepeatMode" @change="dirty"><option value="OFF">{{ dashboard.i18n.t('repeat_mode_off', '關閉') }}</option><option value="SINGLE">{{ dashboard.i18n.t('repeat_mode_single', '單曲循環') }}</option><option value="ALL">{{ dashboard.i18n.t('repeat_mode_all', '佇列循環') }}</option></select></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_historyLimit', '歷史紀錄上限') }}</span><input v-model.number="cfg.historyLimit" type="number" min="1" max="500" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_range', '允許範圍：{min}–{max}', { min: 1, max: 500 }) }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_statsRetentionDays', '統計保留天數') }}</span><input v-model.number="cfg.statsRetentionDays" type="number" min="0" max="3650" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_zero_system_default', '0 代表使用系統預設') }}</small></label>
        <label class="nr-field is-full"><span>{{ dashboard.i18n.t('panel_default_channel', '預設音樂面板頻道（新版模式）') }}</span><select v-model="cfg.commandChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('panel_channel_not_configured', '尚未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
      </div>
    </DashboardSection>
    <DashboardSection :title="dashboard.i18n.t('music_stats_title', '音樂統計')" :description="dashboard.i18n.t('dashboard_music_stats_description', '依目前伺服器的播放紀錄即時整理。')">
      <div class="nr-stat-grid"><article v-for="card in statCards" :key="card.label" class="nr-stat-card"><span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.meta }}</small></article></div>
    </DashboardSection>
  </div>
</template>
