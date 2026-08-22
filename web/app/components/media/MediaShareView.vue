<script setup lang="ts">
import type { OwnerContentStats, PublicMediaContent } from '~/types/api'

const props = defineProps<{
  content: PublicMediaContent | OwnerContentStats
  statsMode: boolean
  stats?: OwnerContentStats | null
}>()

defineEmits<{ unlock: [password: string] }>()

function mediaType(): 'IMAGE' | 'VIDEO' {
  return props.content.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE'
}
</script>

<template>
  <section class="share-view">
    <header class="share-view__head">
      <div><p>{{ mediaType() === 'VIDEO' ? 'SHARED VIDEO' : 'SHARED IMAGE' }}</p><h1>分享{{ mediaType() === 'VIDEO' ? '影片' : '圖片' }}</h1><span>由 NoRule URL 安全託管的媒體內容</span></div>
      <b><i />{{ props.statsMode ? 'OWNER / 管理模式' : 'LIVE / 公開分享' }}</b>
    </header>
    <div class="share-view__body" :class="{ 'has-stats': props.statsMode && props.stats }">
      <MediaPreview
        :media-type="mediaType()"
        :content-url="props.content.contentUrl"
        :password-required="!props.statsMode && 'passwordRequired' in props.content ? props.content.passwordRequired : false"
        :active="'active' in props.content ? props.content.active : true"
        @unlock="$emit('unlock', $event)"
      />
      <MediaStatsSidebar v-if="props.statsMode && props.stats" :stats="props.stats" />
    </div>
  </section>
</template>

<style scoped>
.share-view{border:1px solid var(--nr-border-strong);background:var(--nr-surface)}.share-view__head{display:flex;align-items:end;justify-content:space-between;gap:2rem;padding:1.35rem 1.5rem;border-bottom:1px solid var(--nr-border-strong)}.share-view__head p{margin:0 0 .35rem;color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.6rem;font-weight:750;letter-spacing:.08em}.share-view__head h1{margin:0;font-family:var(--nr-font-display);font-size:clamp(1.7rem,3vw,2.6rem);line-height:1}.share-view__head span{display:block;margin-top:.55rem;color:var(--nr-text-muted);font-size:.72rem}.share-view__head b{display:flex;align-items:center;gap:.5rem;color:var(--nr-success);font-family:var(--nr-font-mono);font-size:.6rem;letter-spacing:.06em}.share-view__head i{width:.45rem;height:.45rem;background:currentColor}.share-view__body{display:grid;grid-template-columns:minmax(0,1fr)}.share-view__body.has-stats{grid-template-columns:minmax(0,1fr) minmax(15rem,19rem)}@media(max-width:820px){.share-view__head{align-items:start;padding:1rem}.share-view__head span{max-width:15rem}.share-view__head b{max-width:8rem;justify-content:flex-end;text-align:right}.share-view__body.has-stats{grid-template-columns:1fr}}
</style>
