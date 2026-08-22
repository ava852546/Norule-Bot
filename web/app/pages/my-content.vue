<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { ApiErrorResponse, OwnedContentPage } from '~/types/api'
import { readJson } from '~/utils/api'
import { shortUrlLoginPath } from '~/utils/routes'

type ContentFilter = 'ALL' | 'SHORT_URL' | 'MEDIA_SHARE'

const authenticated = ref<boolean | null>(null)
const loading = ref(true)
const error = ref('')
const filter = ref<ContentFilter>('ALL')
const result = ref<OwnedContentPage>({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
const filters: Array<{ value: ContentFilter, label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'SHORT_URL', label: '短連結' },
  { value: 'MEDIA_SHARE', label: '分享媒體' },
]

onMounted(initialize)

async function initialize() {
  try {
    const sessionResponse = await fetch('/api/short/session', { credentials: 'same-origin' })
    const session = sessionResponse.ok ? await sessionResponse.json() as { authenticated?: boolean } : null
    authenticated.value = session?.authenticated === true
    if (authenticated.value) await loadPage(0)
  } catch {
    authenticated.value = false
  } finally {
    loading.value = false
  }
}

async function selectFilter(next: ContentFilter) {
  filter.value = next
  await loadPage(0)
}

async function loadPage(page: number) {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`/api/short/mine?type=${filter.value}&page=${page}&size=20`, {
      credentials: 'same-origin', headers: { Accept: 'application/json' },
    })
    const payload = await readJson<OwnedContentPage & ApiErrorResponse>(response)
    if (!response.ok || !payload) {
      if (response.status === 401) authenticated.value = false
      throw new Error(payload?.error || '無法載入你的內容。')
    }
    result.value = payload
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '無法載入你的內容。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="content-page">
    <NrNavbar brand="NoRule URL" :links="[{ label: '返回首頁', href: '/' }]">
      <template #action><ShortUrlSessionAction /></template>
    </NrNavbar>
    <main>
      <NrPageContainer>
        <section class="content-shell">
          <header class="content-head"><div><p>OWNER LIBRARY / 01</p><h1>我的內容</h1><span>只顯示由目前 Discord 帳號建立的短連結與媒體分享。</span></div><b v-if="authenticated"><i /> AUTHENTICATED</b></header>
          <div v-if="authenticated" class="content-toolbar">
            <div role="tablist" aria-label="內容類型">
              <button v-for="item in filters" :key="item.value" type="button" role="tab" :aria-selected="filter === item.value" @click="selectFilter(item.value)">{{ item.label }}</button>
            </div>
            <span>{{ result.totalItems.toLocaleString() }} ITEMS / NEWEST FIRST</span>
          </div>
          <div v-if="loading" class="content-state">正在讀取內容…</div>
          <div v-else-if="authenticated === false" class="content-state is-auth">
            <span>AUTHENTICATION REQUIRED</span><h2>登入後查看你的內容</h2><p>匿名建立的舊資料不會自動綁定到帳號。</p><a :href="shortUrlLoginPath('/my-content')">使用 Discord 登入 →</a>
          </div>
          <div v-else-if="error" class="content-state is-error" role="alert">{{ error }}<button type="button" @click="loadPage(result.page)">重新載入</button></div>
          <MyContentList v-else :items="result.items" />
          <nav v-if="authenticated && !loading && result.totalPages > 1" class="pagination" aria-label="內容分頁">
            <button type="button" :disabled="result.page <= 0" @click="loadPage(result.page - 1)">← 上一頁</button><span>{{ result.page + 1 }} / {{ result.totalPages }}</span><button type="button" :disabled="result.page + 1 >= result.totalPages" @click="loadPage(result.page + 1)">下一頁 →</button>
          </nav>
        </section>
      </NrPageContainer>
    </main>
    <NrFooter brand="NoRule URL" description="管理目前帳號建立的分享內容。" />
  </div>
</template>

<style scoped>
.content-page main{min-height:calc(100vh - 9rem);padding:clamp(1.5rem,4vw,3.5rem) 0}.content-shell{border:1px solid var(--nr-border-strong);padding:clamp(1rem,3vw,2rem);background:var(--nr-surface)}.content-head{display:flex;align-items:end;justify-content:space-between;gap:2rem;padding-bottom:1.5rem;border-bottom:1px solid var(--nr-border-strong)}.content-head p{margin:0 0 .45rem;color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.6rem;font-weight:750;letter-spacing:.08em}.content-head h1{margin:0;font-family:var(--nr-font-display);font-size:clamp(2rem,4vw,3.4rem);line-height:1}.content-head span{display:block;margin-top:.65rem;color:var(--nr-text-muted);font-size:.75rem}.content-head b{display:flex;align-items:center;gap:.45rem;color:var(--nr-success);font-family:var(--nr-font-mono);font-size:.58rem}.content-head b i{width:.45rem;height:.45rem;background:currentColor}.content-toolbar{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:1rem 0}.content-toolbar div{display:flex}.content-toolbar button{min-height:2.45rem;padding:0 .9rem;border:1px solid var(--nr-border-strong);border-radius:0;color:var(--nr-text-muted);background:transparent;font-size:.7rem;cursor:pointer}.content-toolbar button+button{border-left:0}.content-toolbar button[aria-selected="true"]{border-color:var(--nr-accent);color:var(--nr-accent)}.content-toolbar>span{color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.56rem}.content-state{display:grid;min-height:18rem;place-items:center;align-content:center;gap:.65rem;border-top:1px solid var(--nr-border-strong);color:var(--nr-text-muted);font-size:.8rem}.content-state span{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.6rem}.content-state h2{margin:0;color:var(--nr-text);font-family:var(--nr-font-display);font-size:1.6rem}.content-state p{margin:0}.content-state a,.content-state button{margin-top:.7rem;padding:.65rem .8rem;border:1px solid var(--nr-accent);border-radius:0;color:var(--nr-text);background:transparent;font-family:var(--nr-font-mono);font-size:.65rem;text-decoration:none;cursor:pointer}.content-state.is-error{color:#ffb4a2}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:1rem;padding-top:1.25rem}.pagination button{padding:.55rem .7rem;border:1px solid var(--nr-border-strong);border-radius:0;color:var(--nr-text);background:transparent;font-size:.66rem;cursor:pointer}.pagination button:disabled{opacity:.35;cursor:not-allowed}.pagination span{font-family:var(--nr-font-mono);font-size:.6rem}@media(max-width:650px){.content-head{display:grid}.content-toolbar{display:grid}.content-toolbar div{width:100%}.content-toolbar button{flex:1}.pagination{justify-content:space-between}}
</style>
