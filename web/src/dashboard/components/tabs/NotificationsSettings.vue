<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const editorOpen = ref(false)
const cfg = computed(() => dashboard.state.settings.notifications)

function dirty() {
  dashboard.markDirty('notifications')
}

function channelName(channelId: string) {
  return dashboard.state.textChannels.find((channel) => channel.id === channelId)?.name
    || dashboard.i18n.t('dashboard_not_selected', '尚未選擇')
}

function previewTemplate(value: string) {
  return String(value || '')
    .replaceAll('{user}', '@VoiceMember')
    .replaceAll('{channel}', '#General')
    .replaceAll('{from}', '#Lobby')
    .replaceAll('{to}', '#Gaming')
    .replaceAll('{guild}', dashboard.currentGuild.value?.name || 'NoRule')
    .replaceAll('{id}', '123456789012345678')
}

function notificationText(value: string, key: string) {
  return previewTemplate(value || dashboard.i18n.t(key, dashboard.i18n.t('dashboard_notification_preview_fallback', '通知內容預覽')))
}
</script>

<template>
  <div class="nr-stack">
    <DashboardSection
      :eyebrow="dashboard.i18n.t('dashboard_eyebrow_notifications', 'Notifications')"
      :title="dashboard.i18n.t('notifications_message_card_title', '通知設定')"
      :description="dashboard.i18n.t('notifications_message_lead', '設定成員與語音事件的通知頻道、範本及嵌入樣式。')"
    >
      <template #actions>
        <button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('notifications')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button>
        <button type="button" class="nr-button is-primary" @click="editorOpen = true">{{ dashboard.i18n.t('openNotificationEditorBtn', '編輯通知內容') }}</button>
      </template>

      <div class="nr-toggle-grid">
        <DashboardToggle :model-value="cfg.enabled" :label="dashboard.i18n.t('n_enabled', '啟用通知模組')" :description="dashboard.i18n.t('dashboard_notifications_master_description', '所有通知功能的總開關')" @update:model-value="cfg.enabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.voiceLogEnabled" :label="dashboard.i18n.t('n_voiceLogEnabled', '語音活動通知')" :description="dashboard.i18n.t('dashboard_notifications_voice_description', '加入、離開及移動語音頻道')" @update:model-value="cfg.voiceLogEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.memberJoinEnabled" :label="dashboard.i18n.t('n_memberJoinEnabled', '成員加入通知')" :description="dashboard.i18n.t('dashboard_notifications_join_description', '新成員加入伺服器時送出通知')" @update:model-value="cfg.memberJoinEnabled = $event; dirty()" />
        <DashboardToggle :model-value="cfg.memberLeaveEnabled" :label="dashboard.i18n.t('n_memberLeaveEnabled', '成員離開通知')" :description="dashboard.i18n.t('dashboard_notifications_leave_description', '成員離開伺服器時送出通知')" @update:model-value="cfg.memberLeaveEnabled = $event; dirty()" />
      </div>

      <div class="nr-form-grid is-two nr-form-block">
        <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberChannelId', '預設成員通知頻道') }}</span><select v-model="cfg.memberChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_default_or_not_configured', '沿用預設／未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('n_voiceChannelId', '語音通知頻道') }}</span><select v-model="cfg.voiceChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_default_or_not_configured', '沿用預設／未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberJoinChannelId', '成員加入頻道') }}</span><select v-model="cfg.memberJoinChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_default_or_not_configured', '沿用預設／未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
        <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberLeaveChannelId', '成員離開頻道') }}</span><select v-model="cfg.memberLeaveChannelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_default_or_not_configured', '沿用預設／未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
      </div>
    </DashboardSection>

    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_live_preview', 'Live Preview')" :title="dashboard.i18n.t('notification_preview_embed_title', '語音通知預覽')" :description="dashboard.i18n.t('dashboard_notification_preview_description', '預覽會即時套用目前的文字、色彩與替代變數。')">
      <div class="nr-preview-meta"><span># {{ channelName(cfg.voiceChannelId) }}</span><span :class="{ 'is-on': cfg.voiceLogEnabled }">● {{ cfg.voiceLogEnabled ? dashboard.i18n.t('enabledOption', '已啟用') : dashboard.i18n.t('disabledOption', '已停用') }}</span></div>
      <div class="nr-embed-grid">
        <article class="nr-discord-embed" :style="{ '--embed-color': cfg.voiceJoinColor }"><span>{{ dashboard.i18n.t('notification_preview_voice_join_title', '加入語音頻道') }}</span><p>{{ notificationText(cfg.voiceJoinMessage, 'notifications_default_voice_join') }}</p></article>
        <article class="nr-discord-embed" :style="{ '--embed-color': cfg.voiceLeaveColor }"><span>{{ dashboard.i18n.t('notification_preview_voice_leave_title', '離開語音頻道') }}</span><p>{{ notificationText(cfg.voiceLeaveMessage, 'notifications_default_voice_leave') }}</p></article>
        <article class="nr-discord-embed" :style="{ '--embed-color': cfg.voiceMoveColor }"><span>{{ dashboard.i18n.t('notification_preview_voice_move_title', '移動語音頻道') }}</span><p>{{ notificationText(cfg.voiceMoveMessage, 'notifications_default_voice_move') }}</p></article>
      </div>
    </DashboardSection>

    <DashboardModal :open="editorOpen" :title="dashboard.i18n.t('notificationEditorTitle', '設定通知嵌入內容')" :description="dashboard.i18n.t('dashboard_notification_editor_description', '設定 Discord 通知中的文字、圖片與色彩。')" @close="editorOpen = false">
      <div class="nr-stack">
        <DashboardSection :title="dashboard.i18n.t('notification_member_template_card_title', '成員通知')">
          <div class="nr-form-grid is-two">
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberJoinTitle', '加入通知標題') }}</span><input v-model="cfg.memberJoinTitle" type="text" @input="dirty"><small>{{ dashboard.i18n.t('n_memberJoinTitle_hint', '{user}, {guild}') }}</small></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberJoinMessage', '加入通知內容') }}</span><textarea v-model="cfg.memberJoinMessage" @input="dirty" /><small>{{ dashboard.i18n.t('n_memberJoinMessage_hint', '{user}, {username}, {guild}, {id}, {tag}, {createdAt}') }}</small></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberLeaveMessage', '離開通知內容') }}</span><textarea v-model="cfg.memberLeaveMessage" @input="dirty" /></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberJoinThumbnailUrl', '縮圖網址') }}</span><input v-model="cfg.memberJoinThumbnailUrl" type="url" placeholder="https://" @input="dirty"></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_memberJoinImageUrl', '大型圖片網址') }}</span><input v-model="cfg.memberJoinImageUrl" type="url" placeholder="https://" @input="dirty"></label>
            <div class="nr-color-grid"><label class="nr-color-field"><span>{{ dashboard.i18n.t('n_memberJoinColor', '加入色彩') }}</span><input v-model="cfg.memberJoinColor" type="color" @input="dirty"><b>{{ cfg.memberJoinColor }}</b></label><label class="nr-color-field"><span>{{ dashboard.i18n.t('n_memberLeaveColor', '離開色彩') }}</span><input v-model="cfg.memberLeaveColor" type="color" @input="dirty"><b>{{ cfg.memberLeaveColor }}</b></label></div>
          </div>
        </DashboardSection>
        <DashboardSection :title="dashboard.i18n.t('notification_voice_template_card_title', '語音通知')">
          <div class="nr-form-grid is-two">
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_voiceJoinMessage', '加入語音內容') }}</span><textarea v-model="cfg.voiceJoinMessage" @input="dirty" /></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('n_voiceLeaveMessage', '離開語音內容') }}</span><textarea v-model="cfg.voiceLeaveMessage" @input="dirty" /></label>
            <label class="nr-field is-full"><span>{{ dashboard.i18n.t('n_voiceMoveMessage', '移動語音內容') }}</span><textarea v-model="cfg.voiceMoveMessage" @input="dirty" /><small>{{ dashboard.i18n.t('n_voiceMoveMessage_hint', '{user}, {channel}, {from}, {to}') }}</small></label>
            <div class="nr-color-grid is-full"><label class="nr-color-field"><span>{{ dashboard.i18n.t('dashboard_voice_join', '加入') }}</span><input v-model="cfg.voiceJoinColor" type="color" @input="dirty"><b>{{ cfg.voiceJoinColor }}</b></label><label class="nr-color-field"><span>{{ dashboard.i18n.t('dashboard_voice_leave', '離開') }}</span><input v-model="cfg.voiceLeaveColor" type="color" @input="dirty"><b>{{ cfg.voiceLeaveColor }}</b></label><label class="nr-color-field"><span>{{ dashboard.i18n.t('dashboard_voice_move', '移動') }}</span><input v-model="cfg.voiceMoveColor" type="color" @input="dirty"><b>{{ cfg.voiceMoveColor }}</b></label></div>
          </div>
        </DashboardSection>
      </div>
      <template #footer><button type="button" class="nr-button" @click="editorOpen = false">{{ dashboard.i18n.t('dashboard_later_button', '稍後再說') }}</button><button type="button" class="nr-button is-primary" :disabled="dashboard.state.saving" @click="dashboard.saveSettings().then((saved) => { if (saved) editorOpen = false })">{{ dashboard.i18n.t('saveSettingsBtn', '儲存設定') }}</button></template>
    </DashboardModal>
  </div>
</template>
