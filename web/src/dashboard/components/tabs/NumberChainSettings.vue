<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.numberChain)
const confirmingReset = ref(false)
const resetting = ref(false)
const dirty = () => dashboard.markDirty('numberChain')

async function confirmReset() {
  resetting.value = true
  try {
    await dashboard.resetNumberChainProgress()
    confirmingReset.value = false
  } finally {
    resetting.value = false
  }
}
</script>

<template>
  <div class="nr-stack">
    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_number_chain', 'Number Chain')" :title="dashboard.i18n.t('section_numberChain', '數字接龍')" :description="dashboard.i18n.t('dashboard_number_chain_description', '限制接龍遊戲的頻道，並檢視目前進度與歷史最高紀錄。')">
      <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('numberChain')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button></template>
      <div class="nr-toggle-grid"><DashboardToggle :model-value="cfg.enabled" :label="dashboard.i18n.t('nc_enabled', '啟用數字接龍')" :description="dashboard.i18n.t('dashboard_number_chain_toggle_description', '在指定頻道依序接續數字')" @update:model-value="cfg.enabled = $event; dirty()" /></div>
      <div class="nr-form-grid is-two nr-form-block">
        <label class="nr-field"><span>{{ dashboard.i18n.t('nc_channelId', '遊戲頻道') }}</span><select v-model="cfg.channelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_not_configured', '尚未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
        <div class="nr-progress-panel"><span>{{ dashboard.i18n.t('nc_nextNumber', '下一個數字') }}</span><strong>{{ cfg.nextNumber }}</strong><small>{{ dashboard.i18n.t('dashboard_number_chain_highest', '歷史最高：{count}', { count: cfg.highestNumber || 0 }) }}</small></div>
      </div>
      <div class="nr-reset-row">
        <button v-if="!confirmingReset" type="button" class="nr-button is-secondary" @click="confirmingReset = true">{{ dashboard.i18n.t('resetNumberChainProgressBtn', '重設遊戲進度') }}</button>
        <template v-else><span>{{ dashboard.i18n.t('dashboard_number_chain_reset_warning', '此操作會將下一個數字重設為 1。') }}</span><button type="button" class="nr-button" @click="confirmingReset = false">{{ dashboard.i18n.t('cancelResetNumberChainProgressBtn', '取消') }}</button><button type="button" class="nr-button is-danger" :disabled="resetting" @click="confirmReset">{{ resetting ? dashboard.i18n.t('dashboard_resetting', '重設中…') : dashboard.i18n.t('confirmResetNumberChainProgressBtn', '確認重設') }}</button></template>
      </div>
    </DashboardSection>
    <DashboardSection v-if="cfg.topContributors.length" :title="dashboard.i18n.t('dashboard_number_chain_contributors', '活躍貢獻者')" :description="dashboard.i18n.t('dashboard_number_chain_contributors_description', '依成功接續數字次數排序。')">
      <div class="nr-contributor-list"><div v-for="(member, index) in cfg.topContributors" :key="member.userId"><span>{{ index + 1 }}</span><strong>{{ member.userId }}</strong><b>{{ dashboard.i18n.t('dashboard_count_times', '{count} 次', { count: member.count }) }}</b></div></div>
    </DashboardSection>
  </div>
</template>
