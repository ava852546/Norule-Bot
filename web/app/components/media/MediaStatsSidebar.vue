<script setup lang="ts">
import type { OwnerContentStats } from '~/types/api'

const props = defineProps<{ stats: OwnerContentStats }>()

function formatDate(value: number): string {
  if (!value) return '尚無紀錄'
  return new Intl.DateTimeFormat('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
}

function formatFileSize(bytes = 0): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${Math.max(0, bytes)} B`
}
</script>

<template>
  <aside class="stats-sidebar" aria-label="擁有者統計資訊">
    <header><span>OWNER STATISTICS</span><b>PRIVATE</b></header>
    <dl>
      <div><dt>短網址代碼</dt><dd class="is-code">{{ props.stats.code }}</dd></div>
      <div><dt>{{ props.stats.shareType === 'MEDIA_SHARE' ? '瀏覽次數' : '點擊次數' }}</dt><dd class="is-number">{{ props.stats.viewCount.toLocaleString() }}</dd></div>
      <div><dt>到期時間</dt><dd>{{ formatDate(props.stats.expiresAt) }}</dd></div>
      <template v-if="props.stats.shareType === 'MEDIA_SHARE'">
        <div><dt>媒體格式</dt><dd>{{ props.stats.contentType || '—' }}</dd></div>
        <div><dt>檔案大小</dt><dd>{{ formatFileSize(props.stats.fileSize) }}</dd></div>
      </template>
      <div><dt>建立時間</dt><dd>{{ formatDate(props.stats.createdAt) }}</dd></div>
      <div><dt>最後存取</dt><dd>{{ formatDate(props.stats.lastAccessedAt) }}</dd></div>
      <div><dt>狀態</dt><dd :class="props.stats.active ? 'is-live' : 'is-expired'">{{ props.stats.active ? '使用中' : '已過期' }}</dd></div>
    </dl>
  </aside>
</template>

<style scoped>
.stats-sidebar{min-width:0;border-left:1px solid var(--nr-border-strong);background:var(--nr-surface)}.stats-sidebar header{display:flex;min-height:3.5rem;align-items:center;justify-content:space-between;gap:1rem;padding:.85rem 1rem;border-bottom:1px solid var(--nr-border-strong);font-family:var(--nr-font-mono);font-size:.58rem;letter-spacing:.08em}.stats-sidebar header span{color:var(--nr-accent)}.stats-sidebar header b{color:var(--nr-text-muted)}dl{margin:0}dl div{padding:1rem;border-bottom:1px solid var(--nr-border)}dt{margin-bottom:.45rem;color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.58rem;letter-spacing:.06em}dd{margin:0;font-size:.76rem;line-height:1.5;overflow-wrap:anywhere}.is-code{font-family:var(--nr-font-mono);font-size:1rem;font-weight:750}.is-number{color:var(--nr-accent);font-family:var(--nr-font-display);font-size:2.2rem;font-weight:750;line-height:1}.is-live{color:var(--nr-success)}.is-expired{color:#ff9d80}@media(max-width:820px){.stats-sidebar{border-top:1px solid var(--nr-border-strong);border-left:0}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}dl div:nth-child(odd){border-right:1px solid var(--nr-border)}}@media(max-width:520px){dl{grid-template-columns:1fr}dl div:nth-child(odd){border-right:0}}
</style>
