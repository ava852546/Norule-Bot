<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import type { DashboardTab } from '../types/dashboard'
import { useDashboard } from '../composables/useDashboard'
import GeneralSettings from '../components/tabs/GeneralSettings.vue'
import NotificationsSettings from '../components/tabs/NotificationsSettings.vue'
import LogsSettings from '../components/tabs/LogsSettings.vue'
import MusicSettings from '../components/tabs/MusicSettings.vue'
import PrivateRoomSettings from '../components/tabs/PrivateRoomSettings.vue'
import WelcomeSettings from '../components/tabs/WelcomeSettings.vue'
import NumberChainSettings from '../components/tabs/NumberChainSettings.vue'
import TicketSettings from '../components/tabs/TicketSettings.vue'

const dashboard = useDashboard()

const components = {
  general: GeneralSettings,
  notifications: NotificationsSettings,
  logs: LogsSettings,
  music: MusicSettings,
  privateRoom: PrivateRoomSettings,
  welcome: WelcomeSettings,
  numberChain: NumberChainSettings,
  ticket: TicketSettings,
}

const tabMeta = computed<Record<DashboardTab, { title: string; description: string }>>(() => ({
  general: { title: dashboard.i18n.t('tabs_general', '一般設定'), description: dashboard.i18n.t('dashboard_tab_general_description', '管理語言與這個伺服器的基礎偏好。') },
  notifications: { title: dashboard.i18n.t('tabs_notifications', '通知設定'), description: dashboard.i18n.t('dashboard_tab_notifications_description', '整理成員與語音事件的頻道、訊息及樣式。') },
  logs: { title: dashboard.i18n.t('tabs_logs', '日誌紀錄'), description: dashboard.i18n.t('dashboard_tab_logs_description', '設定管理事件的記錄位置與排除規則。') },
  music: { title: dashboard.i18n.t('tabs_music', '音樂設定'), description: dashboard.i18n.t('dashboard_tab_music_description', '調整播放行為、指令頻道並查看使用統計。') },
  privateRoom: { title: dashboard.i18n.t('tabs_privateRoom', '私人房間'), description: dashboard.i18n.t('dashboard_tab_private_room_description', '建立成員可自行管理的動態語音空間。') },
  welcome: { title: dashboard.i18n.t('tabs_welcome', '歡迎訊息'), description: dashboard.i18n.t('dashboard_tab_welcome_description', '設計新成員加入時看到的第一則訊息。') },
  numberChain: { title: dashboard.i18n.t('tabs_numberChain', '數字接龍'), description: dashboard.i18n.t('dashboard_tab_number_chain_description', '管理遊戲頻道、啟用狀態與目前進度。') },
  ticket: { title: dashboard.i18n.t('tabs_ticket', '票券系統'), description: dashboard.i18n.t('dashboard_tab_ticket_description', '設定客服票券面板、類型、權限與歷史紀錄。') },
}))

const activeMeta = computed(() => tabMeta.value[dashboard.state.currentTab])
const activeComponent = computed(() => components[dashboard.state.currentTab])
const configurableSectionCount = Object.keys(components).length
const enabledFeatureCount = computed(() => [
  dashboard.state.settings.notifications.enabled,
  dashboard.state.settings.messageLogs.enabled,
  dashboard.state.settings.privateRoom.enabled,
  dashboard.state.settings.welcome.enabled,
  dashboard.state.settings.numberChain.enabled,
  dashboard.state.settings.ticket.enabled,
].filter(Boolean).length)

const quickSettings = computed(() => [
  { tab: 'general' as DashboardTab, icon: '⌂', tone: 'violet', title: dashboard.i18n.t('tabs_general', '一般設定'), description: dashboard.i18n.t('dashboard_tab_general_description', '管理語言與這個伺服器的基礎偏好。') },
  { tab: 'welcome' as DashboardTab, icon: '✦', tone: 'violet', title: dashboard.i18n.t('tabs_welcome', '歡迎訊息'), description: dashboard.i18n.t('dashboard_quick_welcome_description', '設定新成員加入時的歡迎頻道與內容。') },
  { tab: 'logs' as DashboardTab, icon: '▤', tone: 'blue', title: dashboard.i18n.t('dashboard_quick_logs_title', '管理日誌'), description: dashboard.i18n.t('dashboard_quick_logs_description', '記錄訊息、頻道、身分組與管理操作。') },
  { tab: 'notifications' as DashboardTab, icon: '◉', tone: 'amber', title: dashboard.i18n.t('dashboard_quick_notifications_title', '通知中心'), description: dashboard.i18n.t('dashboard_quick_notifications_description', '集中管理成員與語音活動通知。') },
])

function initializeFromHash() {
  const hash = window.location.hash.replace('#', '')
  if (!hash || hash === 'home') {
    dashboard.state.atHome = true
    return
  }
  if (hash in components) {
    dashboard.state.currentTab = hash as DashboardTab
    dashboard.state.atHome = false
  }
}

onMounted(async () => {
  initializeFromHash()
  window.addEventListener('beforeunload', dashboard.beforeUnload)
  await dashboard.initialize()
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', dashboard.beforeUnload))
</script>

<template>
  <div class="nr-dashboard">
    <div class="nr-stars" aria-hidden="true"><i /><i /><i /></div>

    <template v-if="dashboard.state.authenticating">
      <main class="nr-auth-page"><section class="nr-auth-card nr-loading-card"><span class="nr-brand-mark"><img v-if="dashboard.state.bot?.avatarUrl" :src="dashboard.state.bot.avatarUrl" :alt="dashboard.state.bot.username" referrerpolicy="no-referrer"><template v-else>N</template><i /></span><div class="nr-loading-line" /><div class="nr-loading-line is-short" /><div class="nr-loading-block" /></section></main>
    </template>

    <template v-else-if="!dashboard.state.authenticated">
      <main class="nr-auth-page">
        <section class="nr-auth-card">
          <div class="nr-brand is-centered"><span class="nr-brand-mark"><img v-if="dashboard.state.bot?.avatarUrl" :src="dashboard.state.bot.avatarUrl" :alt="dashboard.state.bot.username" referrerpolicy="no-referrer"><template v-else>N</template><i /></span><span><strong>{{ dashboard.state.bot?.username || 'NoRule' }}</strong><small>{{ dashboard.i18n.t('dashboard_brand_console', 'BOT 控制台') }}</small></span></div>
          <span class="nr-auth-kicker">{{ dashboard.i18n.t('dashboard_auth_kicker', 'DISCORD SERVER MANAGEMENT') }}</span>
          <h1>{{ dashboard.i18n.t('dashboard_auth_title', '掌握伺服器，') }}<em>{{ dashboard.i18n.t('dashboard_auth_title_emphasis', '不必迷路。') }}</em></h1>
          <p>{{ dashboard.i18n.t('dashboard_auth_description', '以 Discord 安全登入後，集中管理 NoRule Bot 的通知、音樂、票券、歡迎訊息與自動化設定。') }}</p>
          <a class="nr-button is-primary is-large" href="/auth/login">{{ dashboard.i18n.t('loginBtn', '使用 Discord 登入') }} <span>→</span></a>
          <small class="nr-auth-note">{{ dashboard.i18n.t('dashboard_auth_note', '只有具備「管理伺服器」權限的伺服器會出現在控制台。') }}</small>
        </section>
      </main>
    </template>

    <template v-else>
      <DashboardSidebar />
      <div class="nr-workspace">
        <DashboardTopbar />
        <main class="nr-content">
          <template v-if="dashboard.manageableGuilds.value.length && dashboard.state.selectedGuildId">
            <header class="nr-page-head">
              <div><p class="nr-breadcrumb"><button type="button" :aria-current="dashboard.state.atHome ? 'page' : undefined" @click="dashboard.openHome">{{ dashboard.i18n.t('dashboard_breadcrumb', '控制台') }}</button><template v-if="!dashboard.state.atHome"> / <span>{{ activeMeta.title }}</span></template></p><h1>{{ dashboard.state.atHome ? dashboard.i18n.t('dashboard_home_title', '控制台首頁') : activeMeta.title }}</h1><h2>{{ dashboard.state.atHome ? dashboard.i18n.t('dashboard_home_description', '集中掌握伺服器狀態，快速前往常用管理功能。') : activeMeta.description }}</h2></div>
              <div v-if="dashboard.state.atHome" class="nr-page-head-actions"><button type="button" class="nr-button is-secondary" :disabled="dashboard.state.loadingGuilds" @click="dashboard.loadGuilds">↻ {{ dashboard.state.loadingGuilds ? dashboard.i18n.t('dashboard_refreshing', '重新整理中…') : dashboard.i18n.t('dashboard_refresh_guilds', '重新整理伺服器') }}</button></div>
              <div v-else class="nr-page-head-actions"><button type="button" class="nr-button is-secondary" :disabled="dashboard.state.loadingSection" @click="dashboard.loadSection(dashboard.state.currentTab, true)">↻ {{ dashboard.i18n.t('dashboard_reload_section', '重新載入') }}</button><button type="button" class="nr-button is-primary" :disabled="dashboard.state.saving || !dashboard.hasDirtyChanges.value" @click="dashboard.saveSettings">{{ dashboard.state.saving ? dashboard.i18n.t('dashboard_saving', '儲存中…') : dashboard.i18n.t('dashboard_save_changes', '儲存變更') }}</button></div>
            </header>

            <section class="nr-status-card">
              <div class="nr-bot-avatar"><img v-if="dashboard.state.bot?.avatarUrl" :src="dashboard.state.bot.avatarUrl" :alt="dashboard.state.bot.username" referrerpolicy="no-referrer"><template v-else>N</template><i /></div>
              <div class="nr-bot-info"><div><h3>{{ dashboard.state.bot?.username || 'NoRule Bot' }}</h3><span>● {{ dashboard.i18n.t('dashboard_bot_operational', '運作正常') }}</span></div><p>{{ dashboard.i18n.t('dashboard_bot_connected', '已連線至 {guild}，可使用控制面板管理現有模組設定。', { guild: dashboard.currentGuild.value?.name || '' }) }}</p></div>
              <div class="nr-status-stats"><div><small>{{ dashboard.i18n.t('dashboard_manageable_guilds', '可管理伺服器') }}</small><strong>{{ dashboard.manageableGuilds.value.length }} <em>{{ dashboard.i18n.t('dashboard_guild_unit', '個') }}</em></strong></div><div><small>{{ dashboard.i18n.t('dashboard_config_sections', '設定分類') }}</small><strong>{{ configurableSectionCount }} <em>{{ dashboard.i18n.t('dashboard_item_unit', '項') }}</em></strong></div><div><small>{{ dashboard.i18n.t('dashboard_enabled_features', '啟用中功能') }}</small><strong>{{ enabledFeatureCount }} <em>{{ dashboard.i18n.t('dashboard_item_unit', '項') }}</em></strong></div></div>
            </section>

            <section v-if="dashboard.state.atHome" class="nr-quick-area">
              <header class="nr-subsection-head"><div><h2>{{ dashboard.i18n.t('dashboard_quick_settings', '快速設定') }}</h2><p>{{ dashboard.i18n.t('dashboard_quick_settings_description', '直接前往最常使用的伺服器功能') }}</p></div></header>
              <div class="nr-quick-grid">
                <button v-for="item in quickSettings" :key="item.tab" type="button" class="nr-quick-card" @click="dashboard.switchTab(item.tab)">
                  <span class="nr-setting-icon" :class="`is-${item.tone}`">{{ item.icon }}</span><i>{{ dashboard.i18n.t('dashboard_open_settings', '前往設定') }} →</i><h3>{{ item.title }}</h3><p>{{ item.description }}</p>
                </button>
              </div>
            </section>

            <div v-else class="nr-settings-region" :class="{ 'is-loading': dashboard.state.loadingSection }">
              <div v-if="dashboard.state.loadingSection" class="nr-section-loader"><span /><p>{{ dashboard.i18n.t('dashboard_loading_section', '正在讀取 {section}…', { section: activeMeta.title }) }}</p></div>
              <component :is="activeComponent" v-else />
            </div>

            <div v-if="dashboard.hasDirtyChanges.value" class="nr-save-dock">
              <span><i /> {{ dashboard.i18n.t('dashboard_unsaved_sections', '有 {count} 個分頁包含未儲存的變更', { count: dashboard.state.dirtySections.size }) }}</span>
              <button type="button" class="nr-button is-primary" :disabled="dashboard.state.saving" @click="dashboard.saveSettings">{{ dashboard.state.saving ? dashboard.i18n.t('dashboard_saving', '儲存中…') : dashboard.i18n.t('dashboard_save_all_changes', '儲存全部變更') }}</button>
            </div>
          </template>

          <section v-else class="nr-no-guilds">
            <span class="nr-setting-icon is-violet">◇</span><h1>{{ dashboard.i18n.t('dashboard_no_guild_title', '還沒有可管理的 NoRule 伺服器') }}</h1><p>{{ dashboard.i18n.t('dashboard_no_guild_description', '你擁有管理權限的伺服器會列在下方。先邀請 Bot，完成後再重新整理清單。') }}</p>
            <button type="button" class="nr-button is-secondary" @click="dashboard.loadGuilds">{{ dashboard.i18n.t('dashboard_refresh_guilds', '重新整理伺服器') }}</button>
            <div class="nr-invite-grid"><article v-for="guild in dashboard.invitationalGuilds.value" :key="guild.id"><span class="nr-guild-mini">{{ guild.name.slice(0, 2).toUpperCase() }}</span><div><strong>{{ guild.name }}</strong><small>{{ dashboard.i18n.t('badgeBotMissing', 'Bot 尚未加入') }}</small></div><a class="nr-button is-primary" :href="guild.inviteUrl" target="_blank" rel="noopener">{{ dashboard.i18n.t('inviteBot', '邀請 Bot') }}</a></article></div>
          </section>
        </main>
      </div>

      <DashboardModal :open="dashboard.state.guildDirectoryOpen" :title="dashboard.i18n.t('dashboard_guild_directory_title', '你的 Discord 伺服器')" :description="dashboard.i18n.t('dashboard_guild_directory_description', '管理已加入 NoRule Bot 的伺服器，或將 Bot 邀請到其他伺服器。')" @close="dashboard.state.guildDirectoryOpen = false">
        <div class="nr-guild-directory">
          <article v-for="guild in dashboard.state.guilds" :key="guild.id" :class="{ active: guild.id === dashboard.state.selectedGuildId }">
            <img v-if="guild.iconUrl" :src="guild.iconUrl" :alt="guild.name" referrerpolicy="no-referrer" /><span v-else class="nr-guild-mini">{{ guild.name.slice(0, 2).toUpperCase() }}</span>
            <div><strong>{{ guild.name }}</strong><small>{{ guild.botInGuild ? guild.botCanManage ? dashboard.i18n.t('badgeManageable', 'Bot 已就緒') : dashboard.i18n.t('badgeMissingPerm', 'Bot 權限可能不足') : dashboard.i18n.t('badgeBotMissing', 'Bot 尚未加入') }}</small></div>
            <button v-if="guild.botInGuild" type="button" class="nr-button" :class="{ 'is-primary': guild.id !== dashboard.state.selectedGuildId }" @click="dashboard.selectGuild(guild.id)">{{ guild.id === dashboard.state.selectedGuildId ? dashboard.i18n.t('dashboard_managing', '管理中') : dashboard.i18n.t('manage', '管理') }}</button>
            <a v-else class="nr-button is-primary" :href="guild.inviteUrl" target="_blank" rel="noopener">{{ dashboard.i18n.t('inviteBot', '邀請 Bot') }}</a>
          </article>
        </div>
        <template #footer><button type="button" class="nr-button" @click="dashboard.state.guildDirectoryOpen = false">{{ dashboard.i18n.t('closeBtn', '關閉') }}</button><button type="button" class="nr-button is-secondary" :disabled="dashboard.state.loadingGuilds" @click="dashboard.loadGuilds">{{ dashboard.state.loadingGuilds ? dashboard.i18n.t('dashboard_refreshing', '重新整理中…') : dashboard.i18n.t('dashboard_refresh_list', '重新整理清單') }}</button></template>
      </DashboardModal>
    </template>
  </div>
</template>
