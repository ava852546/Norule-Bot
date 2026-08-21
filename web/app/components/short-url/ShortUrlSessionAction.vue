<script setup lang="ts">
import { onMounted, ref } from 'vue'

const authenticated = ref(false)
const checking = ref(true)
const loggingOut = ref(false)

onMounted(loadSession)

async function loadSession() {
  try {
    const response = await fetch('/api/short/session', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (response.ok) {
      const session = await response.json() as { authenticated?: boolean }
      authenticated.value = session.authenticated === true
    }
  } catch {
    authenticated.value = false
  } finally {
    checking.value = false
  }
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    const response = await fetch('/api/short/session/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (response.ok) authenticated.value = false
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="session-action" :aria-busy="checking">
    <button v-if="authenticated" type="button" :disabled="loggingOut" @click="logout">
      <span class="session-action__state"><i aria-hidden="true" />已登入</span>
      <span>{{ loggingOut ? '登出中' : '登出' }}</span>
    </button>
    <a v-else href="/api/short/session/login">
      <span class="session-action__full">使用 Discord 登入</span>
      <span class="session-action__compact">登入</span>
    </a>
  </div>
</template>

<style scoped>
.session-action{display:flex;height:100%;align-items:stretch;border-left:1px solid var(--nr-border)}.session-action a,.session-action button{display:flex;min-height:2.45rem;align-items:center;justify-content:center;gap:.7rem;margin:auto 0;padding:.65rem 1rem;border:1px solid var(--nr-accent);border-radius:0;color:var(--nr-text);background:transparent;font-family:var(--nr-font-mono);font-size:.64rem;font-weight:700;letter-spacing:.035em;text-decoration:none;cursor:pointer;transition:color 120ms ease,background 120ms ease}.session-action a:hover,.session-action button:hover:not(:disabled){color:var(--nr-accent-ink);background:var(--nr-accent)}.session-action a:focus-visible,.session-action button:focus-visible{outline:2px solid var(--nr-text);outline-offset:3px}.session-action button:disabled{cursor:wait;opacity:.65}.session-action__state{display:flex;align-items:center;gap:.4rem;color:var(--nr-text-muted)}.session-action__state i{width:.4rem;height:.4rem;background:var(--nr-success)}.session-action__compact{display:none}@media(max-width:640px){.session-action{border-left:0}.session-action a,.session-action button{min-height:2.25rem;padding:.55rem .65rem;font-size:.6rem}.session-action__full{display:none}.session-action__compact{display:inline}.session-action__state{display:none}}
</style>
