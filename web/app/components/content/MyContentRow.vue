<script setup lang="ts">
import type { OwnedContentItem } from '~/types/api'

const props = defineProps<{ item: OwnedContentItem }>()

function formatDate(value: number): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value))
}

function formatFileSize(bytes = 0): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${Math.max(0, bytes)} B`
}
</script>

<template>
  <article class="content-row">
    <div class="content-row__identity">
      <span class="content-row__preview">{{ props.item.shareType === 'MEDIA_SHARE' ? (props.item.mediaType === 'VIDEO' ? '▶' : '▧') : '↗' }}</span>
      <div><p>{{ props.item.shareType === 'MEDIA_SHARE' ? `${props.item.mediaType === 'VIDEO' ? '影片' : '圖片'}分享` : '短連結' }}</p><h2>{{ props.item.code }}</h2><small v-if="props.item.shareType === 'MEDIA_SHARE'">{{ props.item.contentType }} · {{ formatFileSize(props.item.fileSize) }}</small><small v-else>{{ props.item.targetUrl }}</small></div>
    </div>
    <dl>
      <div><dt>建立時間</dt><dd>{{ formatDate(props.item.createdAt) }}</dd></div>
      <div><dt>到期時間</dt><dd>{{ formatDate(props.item.expiresAt) }}</dd></div>
      <div><dt>{{ props.item.shareType === 'MEDIA_SHARE' ? '瀏覽' : '點擊' }}</dt><dd>{{ props.item.viewCount.toLocaleString() }}</dd></div>
      <div><dt>狀態</dt><dd :class="props.item.active ? 'is-live' : 'is-expired'">{{ props.item.active ? '使用中' : '已過期' }}</dd></div>
    </dl>
    <div class="content-row__actions">
      <a v-if="props.item.active" :href="props.item.publicUrl" target="_blank" rel="noopener">{{ props.item.shareType === 'MEDIA_SHARE' ? '查看分享' : '開啟' }}</a>
      <span v-else>公開連結已失效</span>
      <a class="is-primary" :href="props.item.statsUrl">查看資訊</a>
    </div>
  </article>
</template>

<style scoped>
.content-row{display:grid;grid-template-columns:minmax(15rem,1.25fr) minmax(25rem,1fr) auto;gap:1.5rem;align-items:center;padding:1.15rem 0;border-bottom:1px solid var(--nr-border)}.content-row__identity{display:grid;min-width:0;grid-template-columns:3.75rem minmax(0,1fr);gap:1rem;align-items:center}.content-row__preview{display:grid;width:3.75rem;height:3.75rem;place-items:center;border:1px solid var(--nr-border-strong);color:var(--nr-accent);background:#070b12;font-family:var(--nr-font-mono);font-size:1.1rem}.content-row__identity p{margin:0 0 .2rem;color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.58rem;letter-spacing:.06em}.content-row__identity h2{margin:0;font-family:var(--nr-font-display);font-size:1.15rem}.content-row__identity small{display:block;margin-top:.3rem;color:var(--nr-text-muted);font-size:.68rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin:0}dl div{min-width:0;padding:0 .75rem;border-left:1px solid var(--nr-border)}dt{margin-bottom:.3rem;color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.54rem}dd{margin:0;font-size:.68rem;overflow-wrap:anywhere}.is-live{color:var(--nr-success)}.is-expired{color:#ff9d80}.content-row__actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:.5rem}.content-row__actions a{padding:.55rem .7rem;border:1px solid var(--nr-border-strong);font-family:var(--nr-font-mono);font-size:.6rem;text-decoration:none}.content-row__actions a.is-primary{border-color:var(--nr-accent);color:var(--nr-accent)}.content-row__actions span{align-self:center;color:var(--nr-text-muted);font-size:.6rem}@media(max-width:1050px){.content-row{grid-template-columns:1fr auto}.content-row dl{grid-column:1/-1;grid-row:2}.content-row__actions{grid-column:2;grid-row:1}}@media(max-width:680px){.content-row{grid-template-columns:1fr}.content-row__actions,.content-row dl{grid-column:1;grid-row:auto}.content-row dl{grid-template-columns:1fr 1fr;gap:.75rem}.content-row dl div{padding:.4rem 0;border-left:0}.content-row__actions{justify-content:flex-start}}
</style>
