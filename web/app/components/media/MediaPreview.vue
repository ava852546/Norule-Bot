<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(defineProps<{
  mediaType?: 'IMAGE' | 'VIDEO'
  contentUrl?: string
  passwordRequired?: boolean
  active?: boolean
}>(), {
  mediaType: 'IMAGE',
  contentUrl: '',
  passwordRequired: false,
  active: true,
})

const emit = defineEmits<{ unlock: [password: string] }>()
const password = ref('')

function submitPassword() {
  if (password.value.trim()) emit('unlock', password.value)
}
</script>

<template>
  <div class="media-preview">
    <div v-if="!props.active" class="media-preview__notice">
      <span>410 / EXPIRED</span>
      <h2>此分享已到期</h2>
      <p>管理資訊仍然保留，但原始媒體已不再提供公開存取。</p>
    </div>
    <form v-else-if="props.passwordRequired" class="media-preview__notice" @submit.prevent="submitPassword">
      <span>PROTECTED MEDIA</span>
      <h2>這份分享需要密碼</h2>
      <p>輸入分享者提供的密碼後即可查看內容。</p>
      <label>
        <b>存取密碼</b>
        <input v-model="password" type="password" autocomplete="current-password" required>
      </label>
      <button type="submit">解鎖媒體 →</button>
    </form>
    <video v-else-if="props.mediaType === 'VIDEO' && props.contentUrl" controls preload="metadata" playsinline>
      <source :src="props.contentUrl">
      您的瀏覽器不支援影片播放。
    </video>
    <img v-else-if="props.contentUrl" :src="props.contentUrl" alt="分享圖片">
    <div v-else class="media-preview__notice">
      <span>NO PREVIEW</span>
      <h2>沒有可顯示的媒體</h2>
    </div>
  </div>
</template>

<style scoped>
.media-preview{display:grid;min-height:min(68vh,45rem);place-items:center;overflow:hidden;background:#070b12}.media-preview img,.media-preview video{display:block;width:100%;height:100%;max-height:min(74vh,52rem);object-fit:contain}.media-preview__notice{display:grid;width:min(27rem,calc(100% - 2rem));gap:.85rem;padding:2rem;border:1px solid var(--nr-border-strong);background:var(--nr-surface)}.media-preview__notice span{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.62rem;font-weight:700;letter-spacing:.08em}.media-preview__notice h2{margin:0;font-family:var(--nr-font-display);font-size:1.7rem}.media-preview__notice p{margin:0;color:var(--nr-text-muted);font-size:.8rem;line-height:1.65}.media-preview__notice label{display:grid;gap:.45rem;margin-top:.5rem}.media-preview__notice label b{font-size:.7rem}.media-preview__notice input{min-height:2.8rem;padding:.65rem .75rem;border:1px solid var(--nr-border-strong);border-radius:0;color:var(--nr-text);background:var(--nr-bg)}.media-preview__notice button{min-height:2.8rem;border:1px solid var(--nr-accent);border-radius:0;color:var(--nr-accent-ink);background:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.68rem;font-weight:800;cursor:pointer}@media(max-width:700px){.media-preview{min-height:55vh}.media-preview__notice{padding:1.35rem}}
</style>
