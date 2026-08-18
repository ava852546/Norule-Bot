<script setup lang="ts">
import { useDashboard } from '../composables/useDashboard'

const dashboard = useDashboard()
</script>

<template>
  <nav class="nr-external-links" :aria-label="dashboard.i18n.t('dashboard_external_links', 'NoRule Bot 相關連結')">
    <a href="https://www.norule.me/bot/" target="_blank" rel="noopener noreferrer">{{ dashboard.i18n.t('dashboard_official_site', '官網') }}</a>
    <a href="https://www.norule.me/bot/terms-of-service/" target="_blank" rel="noopener noreferrer">{{ dashboard.i18n.t('dashboard_terms_of_service', '服務條款') }}</a>
    <a href="https://www.norule.me/bot/privacy-policy/" target="_blank" rel="noopener noreferrer">{{ dashboard.i18n.t('dashboard_privacy_policy', '隱私權政策') }}</a>
  </nav>
</template>
