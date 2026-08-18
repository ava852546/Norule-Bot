export type DashboardTab =
  | 'general'
  | 'notifications'
  | 'logs'
  | 'music'
  | 'privateRoom'
  | 'welcome'
  | 'numberChain'
  | 'ticket'

export interface UserProfile {
  id: string
  username: string
  avatarUrl: string
}

export interface BotProfile {
  id: string
  username: string
  avatarUrl: string
}

export interface GuildSummary {
  id: string
  name: string
  iconUrl: string
  botInGuild: boolean
  botCanManage: boolean
  manageUrl: string
  inviteUrl: string
}

export interface DiscordOption {
  id: string
  name: string
  mention?: string
}

export interface LanguageOption {
  code: string
  label: string
}

export interface I18nPayload {
  defaultLanguage: string
  uiLanguages: LanguageOption[]
  botLanguages: LanguageOption[]
  bundles: Record<string, Record<string, string>>
}

export interface NotificationSettings {
  enabled: boolean
  memberJoinEnabled: boolean
  memberLeaveEnabled: boolean
  voiceLogEnabled: boolean
  memberChannelId: string
  memberJoinChannelId: string
  memberLeaveChannelId: string
  voiceChannelId: string
  memberJoinTitle: string
  memberJoinMessage: string
  memberJoinThumbnailUrl: string
  memberJoinImageUrl: string
  memberLeaveMessage: string
  memberJoinColor: string
  memberLeaveColor: string
  voiceJoinMessage: string
  voiceLeaveMessage: string
  voiceMoveMessage: string
  voiceJoinColor: string
  voiceLeaveColor: string
  voiceMoveColor: string
}

export interface WelcomeSettings {
  enabled: boolean
  channelId: string
  title: string
  message: string
  thumbnailUrl: string
  imageUrl: string
}

export interface MessageLogSettings {
  enabled: boolean
  channelId: string
  messageLogChannelId: string
  commandUsageChannelId: string
  channelLifecycleChannelId: string
  roleLogChannelId: string
  moderationLogChannelId: string
  roleLogEnabled: boolean
  channelLifecycleLogEnabled: boolean
  moderationLogEnabled: boolean
  commandUsageLogEnabled: boolean
  ignoredMemberIds: string
  ignoredRoleIds: string
  ignoredChannelIds: string
  ignoredPrefixes: string
}

export interface MusicSettings {
  autoLeaveEnabled: boolean
  autoLeaveMinutes: number
  autoplayEnabled: boolean
  defaultRepeatMode: 'OFF' | 'SINGLE' | 'ALL'
  commandChannelId: string
  historyLimit: number
  statsRetentionDays: number
}

export interface MusicStats {
  topSongLabel: string
  topSongCount: number
  topRequesterDisplay: string
  topRequesterCount: number
  todayPlaybackMillis: number
  todayPlaybackDisplay: string
  historyCount: number
}

export interface PrivateRoomSettings {
  enabled: boolean
  triggerVoiceChannelId: string
  userLimit: number
}

export interface NumberChainSettings {
  enabled: boolean
  channelId: string
  nextNumber: number
  highestNumber: number
  topContributors: Array<{
    userId: string
    displayName: string
    avatarUrl: string
    count: number
  }>
}

export interface TicketOption {
  id: string
  label: string
  panelTitle: string
  panelDescription: string
  panelButtonStyle: 'PRIMARY' | 'SECONDARY' | 'SUCCESS' | 'DANGER'
  welcomeMessage: string
  preOpenFormEnabled: boolean
  preOpenFormTitle: string
  preOpenFormLabel: string
  preOpenFormPlaceholder: string
}

export interface TicketSettings {
  enabled: boolean
  panelChannelId: string
  autoCloseDays: number
  maxOpenPerUser: number
  openUiMode: 'BUTTONS' | 'SELECT'
  panelTitle: string
  panelDescription: string
  panelColor: string
  panelButtonStyle: 'PRIMARY' | 'SECONDARY' | 'SUCCESS' | 'DANGER'
  panelButtonLimit: number
  preOpenFormEnabled: boolean
  preOpenFormTitle: string
  preOpenFormLabel: string
  preOpenFormPlaceholder: string
  welcomeMessage: string
  optionLabels: string
  options: TicketOption[]
  supportRoleIds: string[]
  blacklistedUserIds: string
}

export interface DashboardSettings {
  language: string
  notifications: NotificationSettings
  welcome: WelcomeSettings
  messageLogs: MessageLogSettings
  music: MusicSettings
  musicStats: MusicStats
  privateRoom: PrivateRoomSettings
  numberChain: NumberChainSettings
  ticket: TicketSettings
}

export interface TicketTranscript {
  name: string
  size: number
  lastModifiedAt: number
  channelId: string
  url: string
}

export interface ToastMessage {
  id: number
  message: string
  tone: 'success' | 'error' | 'info'
}

export interface DashboardTabDefinition {
  id: DashboardTab
  labelKey: string
  fallback: string
  caption: string
  icon: string
}
