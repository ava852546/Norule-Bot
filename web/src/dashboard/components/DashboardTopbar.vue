<script setup lang="ts">
import { useDashboard } from '../composables/useDashboard'

const dashboard = useDashboard()
</script>

<template>
  <header class="nr-topbar">
    <button type="button" class="nr-mobile-menu" :aria-label="dashboard.i18n.t('dashboard_open_menu', '開啟功能選單')" @click="dashboard.state.sidebarOpen = true">☰</button>
    <DashboardGuildSelect />

    <div class="nr-topbar-actions">
      <div class="nr-language-switch" :aria-label="dashboard.i18n.t('langLabel', '介面語言')">
        <button
          v-for="language in dashboard.i18n.state.uiLanguages"
          :key="language.code"
          type="button"
          :class="{ active: dashboard.i18n.state.language === language.code }"
          @click="dashboard.i18n.setLanguage(language.code)"
        >{{ language.code === 'zh-TW' ? '繁' : language.code === 'zh-CN' ? '简' : 'EN' }}</button>
      </div>
      <button type="button" class="nr-icon-button nr-directory-button" :aria-label="dashboard.i18n.t('dashboard_guild_list', '伺服器清單')" @click="dashboard.state.guildDirectoryOpen = true">◇</button>
      <a class="nr-discord-button" href="https://discord.com/app" target="_blank" rel="noopener">{{ dashboard.i18n.t('dashboard_open_discord', '開啟 Discord') }} ↗</a>
    </div>
  </header>
</template>
