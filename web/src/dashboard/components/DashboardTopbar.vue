<script setup lang="ts">
import { useDashboard } from '../composables/useDashboard'
import FontSizeSelector from '../../shared/components/FontSizeSelector.vue'

const dashboard = useDashboard()
</script>

<template>
  <header class="nr-topbar">
    <button type="button" class="nr-mobile-menu" :aria-label="dashboard.i18n.t('dashboard_open_menu', '開啟功能選單')" @click="dashboard.state.sidebarOpen = true">☰</button>
    <DashboardGuildSelect />

    <div class="nr-topbar-actions">
      <FontSizeSelector
        :group-label="dashboard.i18n.t('dashboard_font_size', '字體大小')"
        :small-label="dashboard.i18n.t('dashboard_font_size_small', '小')"
        :medium-label="dashboard.i18n.t('dashboard_font_size_medium', '中')"
        :large-label="dashboard.i18n.t('dashboard_font_size_large', '大')"
      />
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
