<script setup lang="ts">
import { computed } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.messageLogs)
const dirty = () => dashboard.markDirty('logs')
</script>

<template>
  <div class="nr-stack">
    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_logs', 'Logs')" :title="dashboard.i18n.t('section_logs', '日誌設定')" :description="dashboard.i18n.t('dashboard_logs_description', '指定各類事件的記錄頻道；未個別指定時會使用預設日誌頻道。')">
      <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('logs')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button></template>
      <div class="nr-toggle-grid">
        <DashboardToggle :model-value="cfg.enabled" :label="dashboard.i18n.t('l_enabled', '啟用日誌模組')" :description="dashboard.i18n.t('dashboard_logs_master_description', '所有伺服器管理日誌的總開關')" @update:model-value="cfg.enabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.roleLogEnabled" :label="dashboard.i18n.t('l_roleLogEnabled', '身分組異動')" @update:model-value="cfg.roleLogEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.channelLifecycleLogEnabled" :label="dashboard.i18n.t('l_channelLifecycleLogEnabled', '頻道生命週期')" @update:model-value="cfg.channelLifecycleLogEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.moderationLogEnabled" :label="dashboard.i18n.t('l_moderationLogEnabled', '管理操作')" @update:model-value="cfg.moderationLogEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.commandUsageLogEnabled" :label="dashboard.i18n.t('l_commandUsageLogEnabled', '指令使用')" @update:model-value="cfg.commandUsageLogEnabled = $event; dirty()" />
      </div>
      <div class="nr-form-grid is-three nr-form-block">
        <label v-for="field in [
          ['channelId', 'l_channelId', '預設日誌頻道'],
          ['messageLogChannelId', 'l_messageLogChannelId', '訊息日誌頻道'],
          ['commandUsageChannelId', 'l_commandUsageChannelId', '指令使用頻道'],
          ['channelLifecycleChannelId', 'l_channelLifecycleChannelId', '頻道異動頻道'],
          ['roleLogChannelId', 'l_roleLogChannelId', '身分組日誌頻道'],
          ['moderationLogChannelId', 'l_moderationLogChannelId', '管理日誌頻道'],
        ]" :key="field[0]" class="nr-field">
          <span>{{ dashboard.i18n.t(field[1], field[2]) }}</span>
          <select v-model="cfg[field[0] as keyof typeof cfg]" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_default_or_not_configured', '沿用預設／未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select>
        </label>
      </div>
    </DashboardSection>

    <DashboardSection :title="dashboard.i18n.t('logs_ignore_group_title', '日誌排除規則')" :description="dashboard.i18n.t('logs_ignore_group_lead', '符合使用者、身分組、頻道或指令前綴的事件將不寫入日誌。')">
      <div class="nr-form-grid is-two">
        <label class="nr-field"><span>{{ dashboard.i18n.t('l_ignoredMemberIds', '排除的成員 ID') }}</span><input v-model="cfg.ignoredMemberIds" type="text" @input="dirty"><small>{{ dashboard.i18n.t('l_ignoredMemberIds_hint', '多個 ID 請以逗號分隔') }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('l_ignoredRoleIds', '排除的身分組 ID') }}</span><input v-model="cfg.ignoredRoleIds" type="text" @input="dirty"><small>{{ dashboard.i18n.t('l_ignoredRoleIds_hint', '多個 ID 請以逗號分隔') }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('l_ignoredChannelIds', '排除的頻道 ID') }}</span><input v-model="cfg.ignoredChannelIds" type="text" @input="dirty"><small>{{ dashboard.i18n.t('l_ignoredChannelIds_hint', '多個 ID 請以逗號分隔') }}</small></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('l_ignoredPrefixes', '排除的指令前綴') }}</span><input v-model="cfg.ignoredPrefixes" type="text" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_prefix_examples', '例如：!, ?, /debug') }}</small></label>
      </div>
    </DashboardSection>
  </div>
</template>
