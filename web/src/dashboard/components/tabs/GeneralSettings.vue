<script setup lang="ts">
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()

function onLanguageChange() {
  dashboard.markDirty('general')
  dashboard.applyLanguageDefaults()
}
</script>

<template>
  <DashboardSection
    :eyebrow="dashboard.i18n.t('dashboard_eyebrow_general', 'General')"
    :title="dashboard.i18n.t('section_language', '語言設定')"
    :description="dashboard.i18n.t('dashboard_general_description', '設定 Bot 在此 Discord 伺服器使用的預設語言；通知與票券的預設文字也會跟著調整。')"
  >
    <template #actions>
      <button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('general')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button>
    </template>
    <div class="nr-form-grid is-two">
      <label class="nr-field">
        <span>{{ dashboard.i18n.t('label_s_language', 'Bot 語言') }}</span>
        <select v-model="dashboard.state.settings.language" @change="onLanguageChange">
          <option v-for="language in dashboard.i18n.state.botLanguages" :key="language.code" :value="language.code">{{ language.label }}</option>
        </select>
        <small>{{ dashboard.i18n.t('hint_s_language', '儲存後會同步此伺服器的語系設定。') }}</small>
      </label>
      <div class="nr-info-panel">
        <span class="nr-info-icon">文</span>
        <div><strong>{{ dashboard.i18n.t('dashboard_language_info_title', '介面語言與 Bot 語言分開管理') }}</strong><p>{{ dashboard.i18n.t('dashboard_language_info_description', '右上角只切換此管理介面的顯示語言；這裡的設定會影響 Bot 實際回覆與自動套用的預設文字。') }}</p></div>
      </div>
    </div>
  </DashboardSection>
</template>
