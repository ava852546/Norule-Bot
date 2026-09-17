<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'

const props = defineProps<{ siteKey: string }>()
const emit = defineEmits<{ verified: [token: string] }>()
const container = ref<HTMLElement>()
const failed = ref(false)
type Turnstile = {
  render: (element: HTMLElement, options: Record<string, unknown>) => string
  remove: (id: string) => void
}
const api = () => (window as Window & { turnstile?: Turnstile }).turnstile
let widget: string | undefined
let script: HTMLScriptElement | undefined
let disposed = false

function render() {
  if (disposed || !container.value) return
  const turnstile = api()
  if (!turnstile) { failed.value = true; return }
  widget = turnstile.render(container.value, {
    sitekey: props.siteKey,
    callback: (token: string) => emit('verified', token),
    'expired-callback': () => emit('verified', ''),
    'error-callback': () => { emit('verified', ''); failed.value = true },
  })
}

onMounted(() => {
  if (api()) { render(); return }
  script = document.createElement('script')
  script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
  script.async = true
  script.onload = render
  script.onerror = () => { failed.value = true }
  document.head.appendChild(script)
})
onBeforeUnmount(() => {
  disposed = true
  if (widget) api()?.remove(widget)
  if (script) { script.onload = null; script.onerror = null; script.remove() }
})
</script>

<template>
  <div><div ref="container" /><p v-if="failed" role="alert">驗證載入失敗，請重新整理頁面後再試。</p></div>
</template>
