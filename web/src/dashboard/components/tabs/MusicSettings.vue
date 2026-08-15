<script setup lang="ts">
import { computed } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.music)
const stats = computed(() => dashboard.state.settings.musicStats)
const dirty = () => dashboard.markDirty('music')

const statCards = computed(() => [
  { label: dashboard.i18n.t('music_stats_top_song', '最常播放歌曲'), value: stats.value.topSongLabel || dashboard.i18n.t('music_stats_none', '尚無資料'), meta: stats.value.topSongCount ? dashboard.i18n.t('dashboard_count_times', '{count} 次', { count: stats.value.topSongCount }) : '' },
  { label: dashboard.i18n.t('music_stats_top_requester', '最常點歌成員'), value: stats.value.topRequesterDisplay || dashboard.i18n.t('music_stats_none', '尚無資料'), meta: stats.value.topRequesterCount ? dashboard.i18n.t('dashboard_count_times', '{count} 次', { count: stats.value.topRequesterCount }) : '' },
  { label: dashboard.i18n.t('music_stats_today_time', '今日播放時間'), value: stats.value.todayPlaybackDisplay || '00:00', meta: '' },
  { label: dashboard.i18n.t('music_stats_history_count', '歷史紀錄數'), value: String(stats.value.historyCount || 0), meta: '' },
])
</script>

<template>
  <div class="nr-stack">
    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_music', 'Music')" :title="dashboard.i18n.t('section_music', '音樂設定')" :description="dashboard.i18n.t('music_settings_lead', '集中管理自動離開、自動播放、循環模式與音樂指令頻道。')">
      <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('music')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button></template>
      <div class="nr-toggle-grid">
        <DashboardToggle :model-value="cfg.autoLeaveEnabled" :label="dashboard.i18n.t('m_autoLeaveEnabled', '閒置時自動離開')" :description="dashboard.i18n.t('dashboard_music_auto_leave_description', '沒有播放內容時自動離開語音頻道')" @update:model-value="cfg.autoLeaveEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.autoplayEnabled" :label="dashboard.i18n.t('m_autoplayEnabled', '啟用自動播放')" :description="dashboard.i18n.t('dashboard_music_autoplay_description', '佇列結束後繼續推薦歌曲')" @update:model-value="cfg.autoplayEnabled = $event; dirty()" />
      </div>
      <div class="nr-form-grid is-two nr-form-block">
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_autoLeaveMinutes', '自動離開分鐘數') }}</span><input v-model.number="cfg.autoLeaveMinutes" type="number" min="1" max="60" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_range_minutes', '允許範圍：{min}–{max} 分鐘', { min: 1, max: 60 }) }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_defaultRepeatMode', '預設循環模式') }}</span><select v-model="cfg.defaultRepeatMode" @change="dirty"><option value="OFF">{{ dashboard.i18n.t('repeat_mode_off', '關閉') }}</option><option value="SINGLE">{{ dashboard.i18n.t('repeat_mode_single', '單曲循環') }}</option><option value="ALL">{{ dashboard.i18n.t('repeat_mode_all', '佇列循環') }}</option></select></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_historyLimit', '歷史紀錄上限') }}</span><input v-model.number="cfg.historyLimit" type="number" min="1" max="500" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_range', '允許範圍：{min}–{max}', { min: 1, max: 500 }) }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('m_statsRetentionDays', '統計保留天數') }}</span><input v-model.number="cfg.statsRetentionDays" type="number" min="0" max="3650" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_zero_system_default', '0 代表使用系統預設') }}</small></label>
        <label class="nr-field is-full"><span>{{ dashboard.i18n.t('m_commandChannelId', '音樂指令頻道') }}</span><select v-model="cfg.commandChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_no_channel_restriction', '不限制頻道') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
      </div>
    </DashboardSection>
    <DashboardSection :title="dashboard.i18n.t('music_stats_title', '音樂統計')" :description="dashboard.i18n.t('dashboard_music_stats_description', '依目前伺服器的播放紀錄即時整理。')">
      <div class="nr-stat-grid"><article v-for="card in statCards" :key="card.label" class="nr-stat-card"><span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.meta }}</small></article></div>
    </DashboardSection>
  </div>
</template>
