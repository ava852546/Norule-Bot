<script setup lang="ts">
import type { OwnedContentItem } from '~/types/api'

defineProps<{ items: OwnedContentItem[] }>()
</script>

<template>
  <div v-if="items.length" class="content-list">
    <MyContentRow v-for="item in items" :key="`${item.shareType}:${item.code}`" :item="item" />
  </div>
  <div v-else class="content-empty">
    <span>NO CONTENT</span><h2>這個分類目前沒有內容</h2><p>登入狀態下建立的短連結與媒體分享會出現在這裡。</p><a href="/">建立新內容 →</a>
  </div>
</template>

<style scoped>
.content-list{border-top:1px solid var(--nr-border-strong)}.content-empty{display:grid;min-height:20rem;place-items:center;align-content:center;gap:.5rem;border-top:1px solid var(--nr-border-strong);text-align:center}.content-empty span{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.6rem}.content-empty h2{margin:0;font-family:var(--nr-font-display);font-size:1.4rem}.content-empty p{margin:0;color:var(--nr-text-muted);font-size:.75rem}.content-empty a{margin-top:.8rem;padding:.65rem .8rem;border:1px solid var(--nr-accent);font-family:var(--nr-font-mono);font-size:.65rem;text-decoration:none}
</style>
