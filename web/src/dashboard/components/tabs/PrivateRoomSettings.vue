<script setup lang="ts">
import { computed } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.privateRoom)
const dirty = () => dashboard.markDirty('privateRoom')
</script>

<template>
  <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_private_rooms', 'Private Rooms')" :title="dashboard.i18n.t('section_privateRoom', '私人房間')" :description="dashboard.i18n.t('dashboard_private_room_description', '成員加入指定語音頻道後，Bot 會依目前規則建立可自行管理的私人房間。')">
    <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('privateRoom')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button></template>
    <div class="nr-toggle-grid">
      <DashboardToggle :model-value="cfg.enabled" :label="dashboard.i18n.t('p_enabled', '啟用私人房間')" :description="dashboard.i18n.t('dashboard_private_room_toggle_description', '允許成員透過觸發頻道建立私人語音空間')" @update:model-value="cfg.enabled = $event; dirty()" />
    </div>
    <div class="nr-form-grid is-two nr-form-block">
      <label class="nr-field"><span>{{ dashboard.i18n.t('p_triggerVoiceChannelId', '觸發語音頻道') }}</span><select v-model="cfg.triggerVoiceChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_not_configured', '尚未設定') }}</option><option v-for="channel in dashboard.state.voiceChannels" :key="channel.id" :value="channel.id">◉ {{ channel.name }}</option></select></label>
      <label class="nr-field"><span>{{ dashboard.i18n.t('p_userLimit', '預設人數上限') }}</span><input v-model.number="cfg.userLimit" type="number" min="0" max="99" @input="dirty"><small>{{ dashboard.i18n.t('dashboard_private_room_limit_hint', '0 代表不限制，最大 99 人') }}</small></label>
    </div>
  </DashboardSection>
</template>
