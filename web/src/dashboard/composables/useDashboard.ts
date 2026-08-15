import { computed, reactive } from 'vue'
import type {
  BotProfile,
  DashboardSettings,
  DashboardTab,
  DiscordOption,
  GuildSummary,
  TicketOption,
  TicketTranscript,
  ToastMessage,
  UserProfile,
} from '../types/dashboard'
import { dashboardApi } from './useDashboardApi'
import { useDashboardI18n } from './useDashboardI18n'

function createDefaultTicketOption(overrides: Partial<TicketOption> = {}): TicketOption {
  return {
    id: overrides.id || `option-${Date.now().toString(36)}`,
    label: overrides.label || '',
    panelTitle: overrides.panelTitle || '',
    panelDescription: overrides.panelDescription || '',
    panelButtonStyle: overrides.panelButtonStyle || 'PRIMARY',
    welcomeMessage: overrides.welcomeMessage || '',
    preOpenFormEnabled: overrides.preOpenFormEnabled || false,
    preOpenFormTitle: overrides.preOpenFormTitle || '',
    preOpenFormLabel: overrides.preOpenFormLabel || '',
    preOpenFormPlaceholder: overrides.preOpenFormPlaceholder || '',
  }
}

function createDefaultSettings(): DashboardSettings {
  return {
    language: 'zh-TW',
    notifications: {
      enabled: true,
      memberJoinEnabled: true,
      memberLeaveEnabled: true,
      voiceLogEnabled: true,
      memberChannelId: '',
      memberJoinChannelId: '',
      memberLeaveChannelId: '',
      voiceChannelId: '',
      memberJoinTitle: '',
      memberJoinMessage: '',
      memberJoinThumbnailUrl: '',
      memberJoinImageUrl: '',
      memberLeaveMessage: '',
      memberJoinColor: '#2ECC71',
      memberLeaveColor: '#E74C3C',
      voiceJoinMessage: '',
      voiceLeaveMessage: '',
      voiceMoveMessage: '',
      voiceJoinColor: '#2ECC71',
      voiceLeaveColor: '#E74C3C',
      voiceMoveColor: '#5865F2',
    },
    welcome: {
      enabled: false,
      channelId: '',
      title: '',
      message: '',
      thumbnailUrl: '',
      imageUrl: '',
    },
    messageLogs: {
      enabled: true,
      channelId: '',
      messageLogChannelId: '',
      commandUsageChannelId: '',
      channelLifecycleChannelId: '',
      roleLogChannelId: '',
      moderationLogChannelId: '',
      roleLogEnabled: true,
      channelLifecycleLogEnabled: true,
      moderationLogEnabled: true,
      commandUsageLogEnabled: true,
      ignoredMemberIds: '',
      ignoredRoleIds: '',
      ignoredChannelIds: '',
      ignoredPrefixes: '',
    },
    music: {
      autoLeaveEnabled: true,
      autoLeaveMinutes: 5,
      autoplayEnabled: true,
      defaultRepeatMode: 'OFF',
      commandChannelId: '',
      historyLimit: 50,
      statsRetentionDays: 0,
    },
    musicStats: {
      topSongLabel: '',
      topSongCount: 0,
      topRequesterDisplay: '',
      topRequesterCount: 0,
      todayPlaybackMillis: 0,
      todayPlaybackDisplay: '00:00',
      historyCount: 0,
    },
    privateRoom: {
      enabled: true,
      triggerVoiceChannelId: '',
      userLimit: 0,
    },
    numberChain: {
      enabled: false,
      channelId: '',
      nextNumber: 1,
      highestNumber: 0,
      topContributors: [],
    },
    ticket: {
      enabled: false,
      panelChannelId: '',
      autoCloseDays: 3,
      maxOpenPerUser: 1,
      openUiMode: 'BUTTONS',
      panelTitle: '',
      panelDescription: '',
      panelColor: '#5865F2',
      panelButtonStyle: 'PRIMARY',
      panelButtonLimit: 3,
      preOpenFormEnabled: false,
      preOpenFormTitle: '',
      preOpenFormLabel: '',
      preOpenFormPlaceholder: '',
      welcomeMessage: '',
      optionLabels: '',
      options: [createDefaultTicketOption({ id: 'general' })],
      supportRoleIds: [],
      blacklistedUserIds: '',
    },
  }
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function listText(value: unknown): string {
  return Array.isArray(value) ? value.join(', ') : String(value ?? '')
}

function normalizeTicketOption(value: Partial<TicketOption>, index: number): TicketOption {
  const style = String(value.panelButtonStyle || 'PRIMARY').toUpperCase()
  return createDefaultTicketOption({
    ...value,
    id: String(value.id || `option-${index + 1}`),
    panelButtonStyle: ['PRIMARY', 'SECONDARY', 'SUCCESS', 'DANGER'].includes(style)
      ? style as TicketOption['panelButtonStyle']
      : 'PRIMARY',
  })
}

function createDashboardStore() {
  const i18n = useDashboardI18n()
  const state = reactive({
    initialized: false,
    authenticating: true,
    authenticated: false,
    loadingGuilds: false,
    loadingSection: false,
    saving: false,
    sidebarOpen: false,
    guildDirectoryOpen: false,
    atHome: true,
    currentTab: 'general' as DashboardTab,
    bot: null as BotProfile | null,
    user: null as UserProfile | null,
    guilds: [] as GuildSummary[],
    selectedGuildId: '',
    textChannels: [] as DiscordOption[],
    voiceChannels: [] as DiscordOption[],
    roles: [] as DiscordOption[],
    settings: createDefaultSettings(),
    loadedSections: new Set<DashboardTab>(),
    dirtySections: new Set<DashboardTab>(),
    status: '',
    toasts: [] as ToastMessage[],
    toastSequence: 0,
    ticketHistory: [] as TicketTranscript[],
    ticketHistoryLoaded: false,
    ticketHistoryRetentionDays: 90,
    ticketHistoryCleaned: 0,
    pendingTranscriptDelete: '',
    selectedTicketOptionId: 'general',
  })

  const manageableGuilds = computed(() => state.guilds.filter((guild) => guild.botInGuild))
  const invitationalGuilds = computed(() => state.guilds.filter((guild) => !guild.botInGuild))
  const currentGuild = computed(() => state.guilds.find((guild) => guild.id === state.selectedGuildId) || null)
  const hasDirtyChanges = computed(() => state.dirtySections.size > 0)

  function toast(message: string, tone: ToastMessage['tone'] = 'info') {
    if (!message) return
    const id = ++state.toastSequence
    state.toasts.push({ id, message, tone })
    if (import.meta.client) {
      window.setTimeout(() => dismissToast(id), 4200)
    }
  }

  function dismissToast(id: number) {
    const index = state.toasts.findIndex((item) => item.id === id)
    if (index >= 0) state.toasts.splice(index, 1)
  }

  function setStatus(message: string) {
    state.status = message
  }

  function markDirty(section: DashboardTab) {
    if (!state.loadedSections.has(section)) return
    state.dirtySections.add(section)
  }

  function markClean(section: DashboardTab) {
    state.dirtySections.delete(section)
  }

  function confirmDiscard(sections: DashboardTab[] = [...state.dirtySections]) {
    const dirtySections = sections.filter((section) => state.dirtySections.has(section))
    if (dirtySections.length === 0) return true
    const tabNames = dirtySections.map((section) => i18n.t(`tabs_${section}`, section)).join(', ')
    const message = dirtySections.length === 1
      ? i18n.t('unsavedChangesConfirmSingle', '「{tab}」分頁有尚未儲存的變更，確定要捨棄嗎？', { tab: tabNames })
      : i18n.t('unsavedChangesConfirmMulti', '目前有 {count} 個分頁尚未儲存：{tabs}。確定要全部捨棄嗎？', { count: dirtySections.length, tabs: tabNames })
    return window.confirm(message)
  }

  function mergeSettings(data: Partial<DashboardSettings>) {
    if (typeof data.language === 'string') state.settings.language = data.language
    if (data.notifications) Object.assign(state.settings.notifications, data.notifications)
    if (data.welcome) Object.assign(state.settings.welcome, data.welcome)
    if (data.messageLogs) {
      Object.assign(state.settings.messageLogs, data.messageLogs, {
        ignoredMemberIds: listText(data.messageLogs.ignoredMemberIds),
        ignoredRoleIds: listText(data.messageLogs.ignoredRoleIds),
        ignoredChannelIds: listText(data.messageLogs.ignoredChannelIds),
        ignoredPrefixes: listText(data.messageLogs.ignoredPrefixes),
      })
    }
    if (data.music) Object.assign(state.settings.music, data.music)
    if (data.musicStats) Object.assign(state.settings.musicStats, data.musicStats)
    if (data.privateRoom) Object.assign(state.settings.privateRoom, data.privateRoom)
    if (data.numberChain) Object.assign(state.settings.numberChain, data.numberChain)
    if (data.ticket) {
      const options = Array.isArray(data.ticket.options)
        ? data.ticket.options.map(normalizeTicketOption)
        : []
      const mode = String(data.ticket.openUiMode || 'BUTTONS').toUpperCase()
      Object.assign(state.settings.ticket, data.ticket, {
        openUiMode: mode === 'SELECT' ? 'SELECT' : 'BUTTONS',
        optionLabels: listText(data.ticket.optionLabels),
        supportRoleIds: Array.isArray(data.ticket.supportRoleIds)
          ? data.ticket.supportRoleIds.map(String)
          : listText(data.ticket.supportRoleIds).split(',').map((item) => item.trim()).filter(Boolean),
        blacklistedUserIds: listText(data.ticket.blacklistedUserIds),
        options: options.length ? options : [createDefaultTicketOption({ id: 'general' })],
      })
      state.selectedTicketOptionId = state.settings.ticket.options[0]?.id || 'general'
    }
  }

  function notificationDefault(key: string) {
    const language = state.settings.language || i18n.state.language
    return i18n.bundleText(language, key)
      || i18n.bundleText(i18n.state.language, key)
      || i18n.bundleText('zh-TW', key)
      || ''
  }

  function applyLanguageDefaults() {
    const mappings: Array<[keyof DashboardSettings['notifications'], string]> = [
      ['memberJoinTitle', 'notifications_default_member_join_title'],
      ['memberJoinMessage', 'notifications_default_member_join'],
      ['memberLeaveMessage', 'notifications_default_member_leave'],
      ['voiceJoinMessage', 'notifications_default_voice_join'],
      ['voiceLeaveMessage', 'notifications_default_voice_leave'],
      ['voiceMoveMessage', 'notifications_default_voice_move'],
    ]
    for (const [field, key] of mappings) {
      const current = String(state.settings.notifications[field] || '').trim()
      const knownDefaults = new Set(['zh-TW', 'zh-CN', 'en'].map((lang) => i18n.bundleText(lang, key)).filter(Boolean))
      if (!current || knownDefaults.has(current)) {
        state.settings.notifications[field] = notificationDefault(key) as never
      }
    }
    const ticketDefaults: Array<[keyof DashboardSettings['ticket'], string]> = [
      ['panelTitle', 'ticket_default_panel_title'],
      ['panelDescription', 'ticket_default_panel_desc'],
    ]
    for (const [field, key] of ticketDefaults) {
      const current = String(state.settings.ticket[field] || '').trim()
      const knownDefaults = new Set(['zh-TW', 'zh-CN', 'en'].map((lang) => i18n.bundleText(lang, key)).filter(Boolean))
      if (!current || knownDefaults.has(current)) {
        state.settings.ticket[field] = notificationDefault(key) as never
      }
    }
  }

  async function loadInitialSettings() {
    if (!state.selectedGuildId) return
    const guildId = state.selectedGuildId
    const section = state.currentTab
    state.loadingSection = true
    try {
      const data = await dashboardApi<Partial<DashboardSettings>>(`/api/guild/${guildId}/settings`)
      if (guildId !== state.selectedGuildId) return
      mergeSettings(data)
      state.loadedSections.add(section)
      markClean(section)
      applyLanguageDefaults()
      setStatus(i18n.t('settingsLoaded', '設定已載入'))
    } catch (error) {
      const message = error instanceof Error ? error.message : i18n.t('dashboard_load_settings_failed', '載入設定失敗')
      setStatus(message)
      toast(message, 'error')
    } finally {
      if (guildId === state.selectedGuildId) state.loadingSection = false
    }
  }

  async function initialize() {
    if (state.initialized) return
    await i18n.initializeI18n()
    try {
      try {
        state.bot = await dashboardApi<BotProfile>('/api/bot')
      } catch {
        state.bot = null
      }
      try {
        state.user = await dashboardApi<UserProfile>('/api/me')
        state.authenticated = true
      } catch {
        state.authenticated = false
      }
      if (state.authenticated) {
        try {
          await loadGuilds()
        } catch (error) {
          const message = error instanceof Error ? error.message : i18n.t('dashboard_load_guilds_failed', '伺服器清單載入失敗')
          setStatus(message)
          toast(message, 'error')
        }
      }
    } finally {
      state.authenticating = false
      state.initialized = true
    }
  }

  async function loadGuilds() {
    state.loadingGuilds = true
    try {
      const response = await dashboardApi<{ guilds: GuildSummary[] }>('/api/guilds')
      state.guilds = response.guilds || []
      const requestedGuild = new URLSearchParams(window.location.search).get('guild') || ''
      const firstGuild = manageableGuilds.value.find((guild) => guild.id === requestedGuild)
        || manageableGuilds.value[0]
      if (firstGuild) await selectGuild(firstGuild.id, { confirm: false })
    } finally {
      state.loadingGuilds = false
    }
  }

  async function loadGuildMetadata() {
    if (!state.selectedGuildId) return
    const guildId = state.selectedGuildId
    const [channelsResult, rolesResult] = await Promise.allSettled([
      dashboardApi<{ textChannels: DiscordOption[]; voiceChannels: DiscordOption[] }>(`/api/guild/${guildId}/channels`),
      dashboardApi<{ roles: DiscordOption[] }>(`/api/guild/${guildId}/roles`),
    ])
    if (guildId !== state.selectedGuildId) return
    if (channelsResult.status === 'fulfilled') {
      state.textChannels = channelsResult.value.textChannels || []
      state.voiceChannels = channelsResult.value.voiceChannels || []
    }
    if (rolesResult.status === 'fulfilled') {
      state.roles = rolesResult.value.roles || []
    }
    const failed = [channelsResult, rolesResult].find((result) => result.status === 'rejected')
    if (failed?.status === 'rejected') throw failed.reason
  }

  async function selectGuild(guildId: string, options: { confirm?: boolean } = {}) {
    if (state.loadingSection || state.saving) return
    if (!guildId || guildId === state.selectedGuildId && state.loadedSections.size > 0) return
    if (options.confirm !== false && !confirmDiscard()) return
    state.selectedGuildId = guildId
    state.settings = createDefaultSettings()
    state.loadedSections.clear()
    state.dirtySections.clear()
    resetTicketHistory()
    const url = new URL(window.location.href)
    url.searchParams.set('guild', guildId)
    history.replaceState(null, '', url)
    await loadGuildMetadata().catch((error: Error) => setStatus(error.message))
    await loadInitialSettings()
    state.guildDirectoryOpen = false
  }

  async function switchTab(tab: DashboardTab) {
    if (state.loadingSection || state.saving) return
    state.atHome = false
    state.currentTab = tab
    state.sidebarOpen = false
    history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${tab}`)
    if (state.selectedGuildId && !state.loadedSections.has(tab)) {
      await loadSection(tab)
    }
    if (tab === 'ticket' && !state.ticketHistoryLoaded) {
      await loadTicketHistory().catch(() => undefined)
    }
  }

  function openHome() {
    state.atHome = true
    state.sidebarOpen = false
    history.replaceState(null, '', `${window.location.pathname}${window.location.search}#home`)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function loadSection(section: DashboardTab, force = false) {
    if (!state.selectedGuildId || state.loadingSection) return
    if (!force && state.loadedSections.has(section)) return
    if (force && state.dirtySections.has(section) && !confirmDiscard([section])) return
    state.loadingSection = true
    try {
      const data = await dashboardApi<Partial<DashboardSettings>>(`/api/guild/${state.selectedGuildId}/settings/${section}`)
      mergeSettings(data)
      state.loadedSections.add(section)
      markClean(section)
      applyLanguageDefaults()
      setStatus(i18n.t('settingsLoaded', '設定已載入'))
    } catch (error) {
      const message = error instanceof Error ? error.message : i18n.t('dashboard_load_settings_failed', '載入設定失敗')
      setStatus(message)
      toast(message, 'error')
    } finally {
      state.loadingSection = false
    }
  }

  function buildSavePayload(): Partial<DashboardSettings> {
    const payload: Partial<DashboardSettings> = {}
    for (const section of state.loadedSections) {
      if (section === 'general') payload.language = state.settings.language
      if (section === 'notifications') payload.notifications = clone(state.settings.notifications)
      if (section === 'welcome') payload.welcome = clone(state.settings.welcome)
      if (section === 'logs') payload.messageLogs = clone(state.settings.messageLogs)
      if (section === 'music') payload.music = clone(state.settings.music)
      if (section === 'privateRoom') payload.privateRoom = clone(state.settings.privateRoom)
      if (section === 'numberChain') {
        payload.numberChain = {
          ...clone(state.settings.numberChain),
          nextNumber: state.settings.numberChain.nextNumber,
        }
      }
      if (section === 'ticket') payload.ticket = clone(state.settings.ticket)
    }
    return payload
  }

  async function saveSettings(): Promise<boolean> {
    if (!state.selectedGuildId || state.saving) return false
    state.saving = true
    try {
      await dashboardApi(`/api/guild/${state.selectedGuildId}/settings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildSavePayload()),
      })
      const loaded = [...state.loadedSections]
      state.loadedSections.clear()
      state.dirtySections.clear()
      for (const section of loaded) await loadSection(section, true)
      const message = i18n.t('settingsSaved', '設定已儲存')
      setStatus(message)
      toast(message, 'success')
      return true
    } catch (error) {
      const message = error instanceof Error ? error.message : i18n.t('dashboard_save_failed', '儲存失敗')
      setStatus(message)
      toast(message, 'error')
      return false
    } finally {
      state.saving = false
    }
  }

  function resetSection(section: DashboardTab) {
    const defaults = createDefaultSettings()
    if (section === 'general') state.settings.language = defaults.language
    if (section === 'notifications') Object.assign(state.settings.notifications, defaults.notifications)
    if (section === 'welcome') Object.assign(state.settings.welcome, defaults.welcome)
    if (section === 'logs') Object.assign(state.settings.messageLogs, defaults.messageLogs)
    if (section === 'music') Object.assign(state.settings.music, defaults.music)
    if (section === 'privateRoom') Object.assign(state.settings.privateRoom, defaults.privateRoom)
    if (section === 'numberChain') Object.assign(state.settings.numberChain, defaults.numberChain)
    if (section === 'ticket') {
      Object.assign(state.settings.ticket, defaults.ticket)
      state.selectedTicketOptionId = 'general'
    }
    state.loadedSections.add(section)
    markDirty(section)
    applyLanguageDefaults()
    toast(i18n.t('sectionResetDone', '此區設定已重設，請儲存以套用。'), 'success')
  }

  async function sendWelcomePreview() {
    if (!state.selectedGuildId) return
    try {
      const result = await dashboardApi<{ message?: string }>(`/api/guild/${state.selectedGuildId}/welcome/preview`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ welcome: state.settings.welcome }),
      })
      toast(result.message || i18n.t('welcomePreviewSent', '歡迎訊息預覽已送出'), 'success')
    } catch (error) {
      toast(error instanceof Error ? error.message : i18n.t('welcomePreviewSendFailed', '預覽傳送失敗'), 'error')
    }
  }

  async function resetNumberChainProgress() {
    if (!state.selectedGuildId) return
    try {
      const result = await dashboardApi<{ nextNumber: number }>(`/api/guild/${state.selectedGuildId}/number-chain/reset`, { method: 'POST' })
      state.settings.numberChain.nextNumber = result.nextNumber ?? 1
      toast(i18n.t('numberChainResetSuccess', '數字接龍進度已重設'), 'success')
    } catch (error) {
      toast(error instanceof Error ? error.message : i18n.t('numberChainResetFailed', '重設失敗'), 'error')
    }
  }

  async function sendTicketPanel() {
    if (!state.selectedGuildId) return
    try {
      const result = await dashboardApi<{ message?: string }>(`/api/guild/${state.selectedGuildId}/ticket/panel`, { method: 'POST' })
      toast(result.message || i18n.t('ticketPanelSent', '票券面板已送出'), 'success')
    } catch (error) {
      toast(error instanceof Error ? error.message : i18n.t('ticketPanelSendFailed', '票券面板傳送失敗'), 'error')
    }
  }

  function resetTicketHistory() {
    state.ticketHistory = []
    state.ticketHistoryLoaded = false
    state.ticketHistoryRetentionDays = 90
    state.ticketHistoryCleaned = 0
    state.pendingTranscriptDelete = ''
  }

  async function loadTicketHistory(force = false) {
    if (!state.selectedGuildId || state.ticketHistoryLoaded && !force) return
    try {
      const result = await dashboardApi<{ files: TicketTranscript[]; retentionDays: number; cleaned: number }>(
        `/api/guild/${state.selectedGuildId}/ticket/transcripts`,
      )
      state.ticketHistory = result.files || []
      state.ticketHistoryRetentionDays = Number(result.retentionDays || 90)
      state.ticketHistoryCleaned = Number(result.cleaned || 0)
      state.ticketHistoryLoaded = true
    } catch (error) {
      toast(error instanceof Error ? error.message : i18n.t('ticket_history_load_failed', '歷史紀錄載入失敗'), 'error')
      throw error
    }
  }

  async function deleteTranscript(file: TicketTranscript) {
    if (!state.selectedGuildId || !file.name) return
    try {
      const result = await dashboardApi<{ message?: string }>(
        `/api/guild/${state.selectedGuildId}/ticket/transcript/${encodeURIComponent(file.name)}`,
        { method: 'DELETE' },
      )
      state.ticketHistory = state.ticketHistory.filter((item) => item.name !== file.name)
      state.pendingTranscriptDelete = ''
      toast(result.message || i18n.t('ticket_history_delete_success', '歷史紀錄已刪除'), 'success')
    } catch (error) {
      toast(error instanceof Error ? error.message : i18n.t('ticket_history_delete_failed', '刪除失敗'), 'error')
    }
  }

  function addTicketOption() {
    const option = createDefaultTicketOption({
      welcomeMessage: i18n.t('ticket_default_welcome_message', ''),
    })
    state.settings.ticket.options.push(option)
    state.selectedTicketOptionId = option.id
    markDirty('ticket')
  }

  function deleteTicketOption(optionId: string) {
    if (state.settings.ticket.options.length <= 1) {
      toast(i18n.t('ticket_option_delete_blocked', '至少需要保留一個票券類型'), 'error')
      return
    }
    state.settings.ticket.options = state.settings.ticket.options.filter((option) => option.id !== optionId)
    state.selectedTicketOptionId = state.settings.ticket.options[0]?.id || ''
    markDirty('ticket')
    toast(i18n.t('ticket_option_deleted', '票券類型已刪除'), 'success')
  }

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!hasDirtyChanges.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  return {
    state,
    i18n,
    manageableGuilds,
    invitationalGuilds,
    currentGuild,
    hasDirtyChanges,
    initialize,
    loadGuilds,
    loadGuildMetadata,
    selectGuild,
    switchTab,
    openHome,
    loadSection,
    saveSettings,
    resetSection,
    markDirty,
    applyLanguageDefaults,
    confirmDiscard,
    sendWelcomePreview,
    resetNumberChainProgress,
    sendTicketPanel,
    loadTicketHistory,
    deleteTranscript,
    addTicketOption,
    deleteTicketOption,
    toast,
    dismissToast,
    beforeUnload,
  }
}

let dashboardStore: ReturnType<typeof createDashboardStore> | null = null

export function useDashboard() {
  if (!dashboardStore) dashboardStore = createDashboardStore()
  return dashboardStore
}
