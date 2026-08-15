<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDashboard } from '../../composables/useDashboard'

const dashboard = useDashboard()
const cfg = computed(() => dashboard.state.settings.welcome)
const editorOpen = ref(false)
const sending = ref(false)
const dirty = () => dashboard.markDirty('welcome')

function applyTemplate(value: string) {
  return String(value || '')
    .replaceAll('{user}', '@NewMember')
    .replaceAll('{username}', 'NewMember')
    .replaceAll('{guild}', dashboard.currentGuild.value?.name || 'NoRule')
    .replaceAll('{id}', '123456789012345678')
    .replaceAll('{tag}', 'NewMember#0001')
    .replaceAll('{isBot}', 'false')
    .replaceAll('{createdAt}', '2026-04-01 12:00')
    .replaceAll('{accountAgeDays}', '5')
}

function safeImageUrl(value: string) {
  return /^https?:\/\//i.test(String(value || '').trim()) ? value.trim() : ''
}

async function sendPreview() {
  sending.value = true
  try { await dashboard.sendWelcomePreview() } finally { sending.value = false }
}
</script>

<template>
  <div class="nr-stack">
    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_welcome', 'Welcome')" :title="dashboard.i18n.t('welcome_message_card_title', '歡迎訊息')" :description="dashboard.i18n.t('welcome_message_lead', '設定新成員加入時使用的頻道、標題、內容與媒體。')">
      <template #actions><button type="button" class="nr-button is-danger-subtle" @click="dashboard.resetSection('welcome')">{{ dashboard.i18n.t('resetSectionBtn', '重設此區') }}</button><button type="button" class="nr-button is-primary" @click="editorOpen = true">{{ dashboard.i18n.t('openWelcomeEditorBtn', '編輯歡迎訊息') }}</button></template>
      <div class="nr-toggle-grid">
        <DashboardToggle :model-value="cfg.enabled" :label="dashboard.i18n.t('w_enabled', '啟用歡迎訊息')" :description="dashboard.i18n.t('dashboard_welcome_toggle_description', '新成員加入時自動送出 Discord 嵌入訊息')" @update:model-value="cfg.enabled = $event; dirty()" />
      </div>
      <div class="nr-form-grid is-two nr-form-block">
        <label class="nr-field"><span>{{ dashboard.i18n.t('w_channelId', '歡迎訊息頻道') }}</span><select v-model="cfg.channelId" @change="dirty"><option value="">{{ dashboard.i18n.t('dashboard_not_configured', '尚未設定') }}</option><option v-for="channel in dashboard.state.textChannels" :key="channel.id" :value="channel.id"># {{ channel.name }}</option></select></label>
        <div class="nr-inline-actions"><button type="button" class="nr-button is-secondary" :disabled="sending || !cfg.channelId" @click="sendPreview">{{ sending ? dashboard.i18n.t('dashboard_sending', '傳送中…') : dashboard.i18n.t('sendWelcomePreviewBtn', '傳送預覽訊息') }}</button></div>
      </div>
    </DashboardSection>

    <DashboardSection :eyebrow="dashboard.i18n.t('dashboard_eyebrow_discord_preview', 'Discord Preview')" :title="dashboard.i18n.t('welcome_preview_embed_title', '歡迎訊息預覽')" :description="dashboard.i18n.t('dashboard_welcome_preview_description', '替代變數會以範例成員與目前伺服器名稱呈現。')">
      <div class="nr-preview-meta"><span># {{ dashboard.state.textChannels.find((channel) => channel.id === cfg.channelId)?.name || dashboard.i18n.t('dashboard_not_selected', '尚未選擇') }}</span><span :class="{ 'is-on': cfg.enabled }">● {{ cfg.enabled ? dashboard.i18n.t('enabledOption', '已啟用') : dashboard.i18n.t('disabledOption', '已停用') }}</span></div>
      <article class="nr-welcome-preview">
        <div class="nr-welcome-preview-copy"><strong>{{ applyTemplate(cfg.title) || dashboard.i18n.t('dashboard_welcome_preview_title_fallback', '歡迎加入伺服器') }}</strong><p>{{ applyTemplate(cfg.message) || dashboard.i18n.t('dashboard_welcome_preview_message_fallback', '歡迎訊息內容會顯示在這裡。') }}</p></div>
        <img v-if="safeImageUrl(cfg.thumbnailUrl)" class="nr-welcome-thumb" :src="safeImageUrl(cfg.thumbnailUrl)" :alt="dashboard.i18n.t('dashboard_welcome_thumbnail_alt', '歡迎訊息縮圖')" referrerpolicy="no-referrer" />
        <img v-if="safeImageUrl(cfg.imageUrl)" class="nr-welcome-image" :src="safeImageUrl(cfg.imageUrl)" :alt="dashboard.i18n.t('dashboard_welcome_image_alt', '歡迎訊息圖片')" referrerpolicy="no-referrer" />
        <div v-else class="nr-welcome-empty">{{ dashboard.i18n.t('welcome_preview_no_image', '尚未設定大型圖片') }}</div>
        <footer><span class="nr-guild-mini">{{ dashboard.currentGuild.value?.name?.slice(0, 2).toUpperCase() || 'NR' }}</span>{{ dashboard.currentGuild.value?.name || 'NoRule' }} ・{{ dashboard.i18n.t('welcome_preview_now', '現在') }}</footer>
      </article>
    </DashboardSection>

    <DashboardModal :open="editorOpen" :title="dashboard.i18n.t('welcomeEditorTitle', '設定歡迎訊息')" :description="dashboard.i18n.t('dashboard_welcome_editor_description', '所有欄位會同步反映在背景的 Discord 預覽。')" @close="editorOpen = false">
      <div class="nr-stack">
        <DashboardSection :title="dashboard.i18n.t('welcome_message_card_title', '訊息內容')">
          <div class="nr-form-grid is-two">
            <label class="nr-field"><span>{{ dashboard.i18n.t('w_title', '標題') }}</span><input v-model="cfg.title" type="text" @input="dirty"><small>{{ dashboard.i18n.t('w_title_hint', '{user}, {guild}') }}</small></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('w_message', '訊息內容') }}</span><textarea v-model="cfg.message" @input="dirty" /><small>{{ dashboard.i18n.t('w_message_hint', '{user}, {username}, {guild}, {id}, {tag}, {createdAt}') }}</small></label>
          </div>
        </DashboardSection>
        <DashboardSection :title="dashboard.i18n.t('welcome_media_card_title', '歡迎媒體')">
          <div class="nr-form-grid is-two">
            <label class="nr-field"><span>{{ dashboard.i18n.t('w_thumbnailUrl', '縮圖網址') }}</span><input v-model="cfg.thumbnailUrl" type="url" placeholder="https://" @input="dirty"><small>{{ dashboard.i18n.t('w_thumbnailUrl_hint', '顯示於訊息右上角的小型圖片') }}</small></label>
            <label class="nr-field"><span>{{ dashboard.i18n.t('w_imageUrl', '大型圖片網址') }}</span><input v-model="cfg.imageUrl" type="url" placeholder="https://" @input="dirty"><small>{{ dashboard.i18n.t('w_imageUrl_hint', '顯示於訊息內容下方的大型圖片') }}</small></label>
          </div>
        </DashboardSection>
      </div>
      <template #footer><button type="button" class="nr-button" @click="editorOpen = false">{{ dashboard.i18n.t('dashboard_later_button', '稍後再說') }}</button><button type="button" class="nr-button is-primary" :disabled="dashboard.state.saving" @click="dashboard.saveSettings().then((saved) => { if (saved) editorOpen = false })">{{ dashboard.i18n.t('saveSettingsBtn', '儲存設定') }}</button></template>
    </DashboardModal>
  </div>
</template>
