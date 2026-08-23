<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { hasStatsQuery, shortUrlLoginPath } from '~/utils/routes'

const route = useRoute()
const sharedContent = useSharedContent()
const unlockError = ref('')
const unlocking = ref(false)

const code = computed(() => String(route.params.code || '').trim())
const statsMode = computed(() => hasStatsQuery(route.query))
const mediaContent = computed(() => sharedContent.ownerStats.value?.shareType === 'MEDIA_SHARE'
  ? sharedContent.ownerStats.value
  : sharedContent.publicContent.value)

watch([code, statsMode], async ([nextCode, nextStatsMode]) => {
  if (!nextCode) return
  await sharedContent.load(nextCode, nextStatsMode)
  if (nextStatsMode && sharedContent.errorStatus.value === 401 && import.meta.client) {
    window.location.href = shortUrlLoginPath(`${window.location.pathname}?stats`)
  }
}, { immediate: true })

async function unlock(password: string) {
  if (unlocking.value) return
  unlocking.value = true
  unlockError.value = ''
  try {
    await sharedContent.unlock(code.value, password)
    window.location.reload()
  } catch (caught) {
    unlockError.value = caught instanceof Error ? caught.message : '密碼驗證失敗。'
  } finally {
    unlocking.value = false
  }
}
</script>

<template>
  <div class="share-page">
    <NrNavbar brand="NoRule URL" :links="[{ label: '返回首頁', href: '/' }]">
      <template #action><ShortUrlSessionAction /></template>
    </NrNavbar>
    <main>
      <NrPageContainer>
        <div v-if="sharedContent.status.value === 'loading'" class="state-panel" aria-live="polite">
          <span>LOADING / {{ statsMode ? 'OWNER' : 'PUBLIC' }}</span><h1>正在載入分享內容</h1><i />
        </div>
        <div v-else-if="sharedContent.status.value === 'error'" class="state-panel is-error" role="alert">
          <span>{{ sharedContent.errorStatus.value || 500 }} / REQUEST FAILED</span>
          <h1>{{ sharedContent.error.value }}</h1>
          <a href="/">返回 NoRule URL</a>
        </div>
        <template v-else-if="mediaContent">
          <MediaShareView
            :content="mediaContent"
            :stats-mode="statsMode"
            :stats="sharedContent.ownerStats.value"
            @unlock="unlock"
          />
          <p v-if="unlockError" class="unlock-error" role="alert">{{ unlockError }}</p>
        </template>
        <section v-else-if="sharedContent.ownerStats.value?.shareType === 'SHORT_URL'" class="link-stats">
          <header><p>SHORT LINK / OWNER</p><h1>短連結資訊</h1><span>PRIVATE / 管理模式</span></header>
          <div class="link-stats__body">
            <div class="link-target">
              <span>DESTINATION</span>
              <p>{{ sharedContent.ownerStats.value.targetUrl }}</p>
              <a :href="sharedContent.ownerStats.value.targetUrl" target="_blank" rel="noopener">開啟原始網址 →</a>
            </div>
            <MediaStatsSidebar :stats="sharedContent.ownerStats.value" />
          </div>
        </section>
      </NrPageContainer>
    </main>
    <NrFooter brand="NoRule URL" />
  </div>
</template>

<style scoped>
.share-page main{min-height:calc(100vh - 9rem);padding:clamp(1.25rem,4vw,3.5rem) 0}.state-panel{display:grid;min-height:28rem;place-items:center;align-content:center;gap:.8rem;border:1px solid var(--nr-border-strong);text-align:center}.state-panel span,.link-stats header p{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.62rem;font-weight:750;letter-spacing:.08em}.state-panel h1{max-width:38rem;margin:0;font-family:var(--nr-font-display);font-size:clamp(1.5rem,4vw,2.8rem)}.state-panel i{width:3rem;height:2px;background:var(--nr-accent);animation:pulse 900ms ease-in-out infinite alternate}.state-panel a{margin-top:.75rem;padding:.65rem .9rem;border:1px solid var(--nr-border-strong);font-family:var(--nr-font-mono);font-size:.68rem;text-decoration:none}.state-panel.is-error span{color:#ff9d80}.unlock-error{margin:.8rem 0 0;padding:.75rem 1rem;border:1px solid #7b3c34;color:#ffb4a2;background:#211211;font-size:.75rem}.link-stats{border:1px solid var(--nr-border-strong);background:var(--nr-surface)}.link-stats>header{display:flex;align-items:end;gap:1rem;padding:1.35rem 1.5rem;border-bottom:1px solid var(--nr-border-strong)}.link-stats header p{margin:0 auto 0 0}.link-stats header h1{margin:0;font-family:var(--nr-font-display);font-size:1.8rem}.link-stats header span{color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.6rem}.link-stats__body{display:grid;grid-template-columns:minmax(0,1fr) minmax(15rem,19rem)}.link-target{display:grid;min-height:32rem;align-content:center;gap:.8rem;padding:clamp(1.5rem,5vw,4rem);background:#070b12}.link-target span{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.62rem}.link-target p{max-width:52rem;margin:0;font-family:var(--nr-font-display);font-size:clamp(1.25rem,3vw,2.4rem);overflow-wrap:anywhere}.link-target a{width:max-content;margin-top:.75rem;padding:.7rem .9rem;border:1px solid var(--nr-accent);font-family:var(--nr-font-mono);font-size:.68rem;text-decoration:none}@keyframes pulse{to{opacity:.25;transform:scaleX(.35)}}@media(max-width:820px){.link-stats__body{grid-template-columns:1fr}.link-stats>header{display:grid}.link-target{min-height:20rem}}
</style>
