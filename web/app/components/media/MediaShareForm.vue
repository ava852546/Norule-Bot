<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { RequestStatus } from '~/types/api'
import { defaultPassword, isVideoFile, maxVideoDurationMinutes, readVideoDuration, RETENTION_PRESETS, toLocalDateTimeValue, validateCustomExpiration, validateMediaFile } from '~/utils/media'

const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const retention = ref('60')
const customExpiration = ref('')
const passwordProtected = ref(false)
const password = ref('')
const passwordVisible = ref(false)
const localError = ref('')
const { config, configLoading, status, progress, result, error, loadConfig, upload } = useMediaShare()
const clipboard = useClipboard()

const customMode = computed(() => retention.value === 'custom')
const disabled = computed(() => configLoading.value || !config.value.enabled)
const shownStatus = computed<RequestStatus>(() => localError.value ? 'error' : status.value)
const shownError = computed(() => localError.value || error.value)
const videoMinutes = computed(() => maxVideoDurationMinutes(config.value))
const fileHint = computed(() => `圖片最大 ${config.value.maxFileSizeMb} MB；影片最大 ${config.value.maxVideoFileSizeMb} MB、最長 ${videoMinutes.value} 分鐘。`)
const expirationHint = computed(() => `最多可設定 ${config.value.maxRetentionDays} 天，過期後連結將無法瀏覽。`)
const minExpiration = computed(() => toLocalDateTimeValue(Date.now() + 60_000))
const maxExpiration = computed(() => toLocalDateTimeValue(Date.now() + config.value.maxRetentionDays * 86_400_000))
const formattedExpiration = computed(() => result.value?.expiresAt ? new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(result.value.expiresAt)) : '')

watch(passwordProtected, (enabled) => {
  if (enabled && config.value.allowDateDefaultPassword && !password.value.trim()) password.value = defaultPassword()
  if (!enabled) passwordVisible.value = false
})

watch(customMode, (enabled) => {
  if (enabled && !customExpiration.value) customExpiration.value = toLocalDateTimeValue(Date.now() + config.value.defaultRetentionHours * 3_600_000)
})

onMounted(async () => {
  await loadConfig()
  const defaultMinutes = Math.max(1, Math.round(config.value.defaultRetentionHours * 60))
  retention.value = (RETENTION_PRESETS as readonly number[]).includes(defaultMinutes) ? String(defaultMinutes) : 'custom'
  if (retention.value === 'custom') customExpiration.value = toLocalDateTimeValue(Date.now() + defaultMinutes * 60_000)
})

function chooseFile() { fileInput.value?.click() }

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null
  localError.value = ''
}

async function submit() {
  localError.value = ''
  if (!file.value) {
    localError.value = '請先選擇圖片或影片檔案。'
    return
  }
  const fileError = validateMediaFile(file.value, config.value)
  if (fileError) {
    localError.value = fileError
    return
  }
  if (isVideoFile(file.value)) {
    try {
      const duration = await readVideoDuration(file.value)
      if (duration > config.value.maxVideoDurationSeconds) {
        localError.value = `影片長度不可超過 ${videoMinutes.value} 分鐘。`
        return
      }
    } catch {
      localError.value = '無法讀取影片長度，請確認檔案完整。'
      return
    }
  }
  if (passwordProtected.value && (password.value.length < config.value.minPasswordLength || password.value.length > config.value.maxPasswordLength)) {
    localError.value = `密碼需為 ${config.value.minPasswordLength}～${config.value.maxPasswordLength} 個字元。`
    return
  }
  const expiresAt = customMode.value
    ? validateCustomExpiration(customExpiration.value, config.value.maxRetentionDays)
    : undefined
  if (customMode.value && !expiresAt) {
    localError.value = `請選擇未來 ${config.value.maxRetentionDays} 天內的到期時間。`
    return
  }
  await upload({
    file: file.value,
    retentionMinutes: customMode.value ? undefined : Number(retention.value),
    expiresAt: expiresAt || undefined,
    passwordProtected: passwordProtected.value,
    password: passwordProtected.value ? password.value : undefined,
  })
}
</script>

<template>
  <div class="media-form">
    <form novalidate @submit.prevent="submit">
      <div class="media-form-card__upload">
        <div>
          <span class="media-form-card__label">圖片或影片</span>
          <strong>{{ file?.name || '尚未選擇檔案' }}</strong>
          <small>{{ fileHint }}</small>
        </div>
        <input ref="fileInput" class="media-form-card__native-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp,video/mp4,video/webm" aria-label="選擇要分享的圖片或影片" :disabled="disabled || status === 'loading'" @change="onFileChange">
        <NrButton variant="secondary" :disabled="disabled || status === 'loading'" @click="chooseFile">選擇檔案</NrButton>
      </div>

      <div class="media-form-card__options">
        <div class="media-form-card__option-surface">
          <NrSelect v-model="retention" label="到期時間" :hint="expirationHint" :disabled="disabled || status === 'loading'">
            <option value="5">5 分鐘</option><option value="10">10 分鐘</option><option value="30">30 分鐘</option><option value="60">1 小時</option><option value="180">3 小時</option><option value="360">6 小時</option><option value="720">12 小時</option><option value="1440">24 小時</option><option value="custom">自訂時間</option>
          </NrSelect>
        </div>
        <NrInput v-if="customMode" v-model="customExpiration" label="自訂到期時間" type="datetime-local" :min="minExpiration" :max="maxExpiration" required :disabled="disabled || status === 'loading'" />
        <NrSwitch v-model="passwordProtected" label="密碼保護" hint="開啟後需輸入密碼才能查看" :disabled="disabled || status === 'loading'" />
        <NrInput v-if="passwordProtected" v-model="password" label="分享密碼" :type="passwordVisible ? 'text' : 'password'" :hint="config.allowDateDefaultPassword ? '預設為今天日期 MMDD，可自行修改。' : `請輸入 ${config.minPasswordLength}～${config.maxPasswordLength} 個字元。`" autocomplete="new-password" required :disabled="disabled || status === 'loading'">
          <template #suffix><button class="media-form-card__visibility" type="button" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? '隱藏' : '顯示' }}</button></template>
        </NrInput>
      </div>

      <NrButton type="submit" :loading="status === 'loading'" :disabled="disabled || !file" block>建立媒體分享連結</NrButton>
    </form>
    <p v-if="configLoading" class="media-form-card__config" role="status">正在載入伺服器限制…</p>
    <NrResultCard :status="disabled ? 'disabled' : shownStatus" title="媒體分享連結建立完成" :url="result?.shortUrl" :meta="formattedExpiration ? `到期時間：${formattedExpiration}` : ''" :error="shownError" :progress="progress" :copy-state="clipboard.state.value" @copy="result && clipboard.copy(result.shortUrl)" />
  </div>
</template>

<style scoped>
.media-form{border-top:1px solid var(--nr-border-strong)}form{display:grid;gap:1.5rem;padding-top:1.5rem}.media-form-card__upload{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:end;gap:1.2rem;padding:1.25rem 0;border-bottom:1px solid var(--nr-border)}.media-form-card__upload>div{display:grid;min-width:0;gap:.3rem}.media-form-card__label{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.65rem;font-weight:760;letter-spacing:.08em}.media-form-card__upload strong{overflow:hidden;font-family:var(--nr-font-display);font-size:1.25rem;text-overflow:ellipsis;white-space:nowrap}.media-form-card__upload small{max-width:35rem;color:var(--nr-text-muted);font-size:.74rem}.media-form-card__native-file{position:absolute;width:1px;height:1px;opacity:0}.media-form-card__options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));align-items:start;gap:1.3rem 1.8rem}.media-form-card__option-surface{display:grid;align-content:start}.media-form-card__visibility{min-height:2.35rem;padding:0 .7rem;color:var(--nr-accent);border:0;background:transparent;font-size:.75rem;font-weight:700;cursor:pointer}.media-form-card__visibility:hover{text-decoration:underline}.media-form-card__config{margin:1rem 0 0;color:var(--nr-text-muted);font-size:.78rem}@media(max-width:640px){.media-form-card__upload{grid-template-columns:1fr}.media-form-card__upload :deep(.nr-button){width:100%}.media-form-card__options{grid-template-columns:1fr}}
</style>
