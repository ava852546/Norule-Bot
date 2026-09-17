<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { CUSTOM_SHORT_CODE_MAX_LENGTH, normalizeCustomShortCode, validateCustomShortCode } from '~/utils/shortCode'

const targetUrl = ref('')
const customCode = ref('')
const clientError = ref('')
const host = ref('此網域')
const { status, result, error, create } = useShortUrl()
const clipboard = useClipboard()
const { authenticated, turnstileEnabled, turnstileSiteKey } = useShortUrlSession()
const verificationToken = ref('')
const verificationAttempt = ref(0)
const verificationRequired = computed(() => !authenticated.value && turnstileEnabled.value)

const shownStatus = computed(() => clientError.value ? 'idle' : status.value)
const shownError = computed(() => error.value)
const codePrefix = computed(() => `${host.value}/`)
const normalizedCustomCode = computed(() => normalizeCustomShortCode(customCode.value))
const customCodeError = computed(() => validateCustomShortCode(customCode.value) || '')
const customCodeHint = computed(() => normalizedCustomCode.value
  ? `建立後：${codePrefix.value}${normalizedCustomCode.value}。3～32 字元，可使用英文、數字、-、_`
  : '3～32 字元，可使用英文、數字、-、_')

onMounted(() => { host.value = window.location.host })
watch(targetUrl, () => { clientError.value = '' })
watch(authenticated, () => { customCode.value = ''; verificationToken.value = '' })

async function submit() {
  clientError.value = ''
  const value = targetUrl.value.trim()
  if (!value) {
    clientError.value = '請輸入要縮短的網址。'
    return
  }
  try {
    const parsed = new URL(value)
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error()
  } catch {
    clientError.value = '請輸入完整的 http:// 或 https:// 網址。'
    return
  }
  if (authenticated.value && customCodeError.value) return
  if (verificationRequired.value && !verificationToken.value) return
  await create(value, authenticated.value ? normalizedCustomCode.value : '', verificationToken.value)
  verificationToken.value = ''
  verificationAttempt.value++
}
</script>

<template>
  <div class="short-form">
    <form novalidate @submit.prevent="submit">
      <div class="short-form-card__primary">
        <NrInput id="target-url" v-model="targetUrl" label="要縮短的網址" type="url" placeholder="https://example.com/very/long/url" autocomplete="url" required :error="clientError" />
        <NrButton type="submit" :loading="status === 'loading'" :disabled="!targetUrl.trim() || (authenticated && Boolean(customCodeError)) || (verificationRequired && !verificationToken)">縮短</NrButton>
      </div>
      <NrInput v-if="authenticated" id="custom-code" v-model="customCode" label="自訂短碼" placeholder="好記的短碼" :maxlength="CUSTOM_SHORT_CODE_MAX_LENGTH" :hint="customCodeHint" :error="customCodeError">
        <template #prefix>{{ codePrefix }}</template>
      </NrInput>
      <p v-else>匿名建立會自動產生短碼。<a href="/api/short/session/login">登入後可自訂短碼及查看自己的統計。</a></p>
      <ShortUrlVerification v-if="verificationRequired" :key="verificationAttempt" :site-key="turnstileSiteKey" @verified="verificationToken = $event" />
    </form>
    <NrResultCard :status="shownStatus" title="短網址建立完成" :url="result?.shortUrl" :meta="result ? `前往 ${result.targetUrl}` : ''" :error="shownError" :copy-state="clipboard.state.value" @copy="result && clipboard.copy(result.shortUrl)" />
  </div>
</template>

<style scoped>
.short-form{border-top:1px solid var(--nr-border-strong)}form{display:grid;gap:1.4rem;padding-top:1.5rem}.short-form-card__primary{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:end;gap:0}.short-form-card__primary :deep(.nr-button){min-width:8rem;margin-left:-1px}.short-form :deep(.nr-result){margin-top:1.75rem}@media(max-width:640px){.short-form-card__primary{grid-template-columns:1fr;gap:.7rem}.short-form-card__primary :deep(.nr-button){width:100%;margin-left:0}}
</style>
