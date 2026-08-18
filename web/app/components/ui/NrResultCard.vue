<script setup lang="ts">
import { computed } from 'vue'
import type { RequestStatus } from '~/types/api'
import { resultStatusMessage } from '~/utils/result'

const props = withDefaults(defineProps<{ status?: RequestStatus; title?: string; url?: string; meta?: string; error?: string; progress?: number | null; copyState?: 'idle' | 'copying' | 'copied' | 'error' }>(), { status: 'idle', title: '建立完成', url: '', meta: '', error: '', progress: null, copyState: 'idle' })
defineEmits<{ copy: [] }>()
const message = computed(() => resultStatusMessage(props.status, props.progress, props.error))
</script>

<template>
  <div v-if="status !== 'idle'" class="nr-result" :class="`nr-result--${status}`" :role="status === 'error' ? 'alert' : 'status'" aria-live="polite" :aria-busy="status === 'loading'">
    <template v-if="status === 'success'">
      <div><NrBadge tone="success">成功</NrBadge><h3>{{ title }}</h3><p v-if="meta">{{ meta }}</p></div>
      <a class="nr-result__url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a>
      <NrButton variant="secondary" :loading="copyState === 'copying'" @click="$emit('copy')">{{ copyState === 'copied' ? '已複製' : '複製網址' }}</NrButton>
      <p v-if="copyState === 'error'" class="nr-result__copy-error">無法自動複製，請手動選取網址。</p>
    </template>
    <template v-else>
      <span v-if="status === 'loading'" class="nr-result__spinner" aria-hidden="true" />
      <span v-else class="nr-result__status" aria-hidden="true">{{ status === 'error' ? '!' : '—' }}</span>
      <p>{{ message }}</p>
      <div v-if="status === 'loading' && progress !== null" class="nr-result__progress" aria-hidden="true"><span :style="{ width: `${progress}%` }" /></div>
    </template>
  </div>
</template>

<style scoped>
.nr-result{display:grid;gap:1rem;margin-top:1rem;padding:1.2rem 0;border-top:2px solid var(--nr-text);border-bottom:1px solid var(--nr-border)}.nr-result--success{grid-template-columns:minmax(0,1fr) auto;align-items:end;border-top-color:var(--nr-success)}.nr-result h3{margin:.55rem 0 0;font-family:var(--nr-font-display);font-size:1.2rem}.nr-result p{margin:.2rem 0 0;color:var(--nr-text-muted);font-size:.8rem}.nr-result__url{grid-column:1/-1;overflow:hidden;padding:.85rem 0;border-top:1px solid var(--nr-border);border-bottom:1px solid var(--nr-border);color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.8rem;font-weight:650;text-decoration:none;text-overflow:ellipsis;white-space:nowrap}.nr-result__spinner{width:1.2rem;height:1.2rem;border:2px solid var(--nr-text-muted);border-top-color:var(--nr-accent);border-radius:50%;animation:nr-result-spin .8s linear infinite}.nr-result__status{display:grid;width:1.7rem;height:1.7rem;place-items:center;color:var(--nr-danger);border:1px solid var(--nr-danger);font-weight:800}.nr-result--loading,.nr-result--error,.nr-result--disabled{grid-template-columns:auto 1fr;align-items:center}.nr-result__progress{grid-column:1/-1;height:.3rem;overflow:hidden;background:var(--nr-border)}.nr-result__progress span{display:block;height:100%;background:var(--nr-accent);transition:width 160ms ease}.nr-result__copy-error{grid-column:1/-1;color:var(--nr-danger)!important}@keyframes nr-result-spin{to{transform:rotate(360deg)}}@media(max-width:640px){.nr-result--success{grid-template-columns:1fr}.nr-result--success :deep(.nr-button){width:100%}}
</style>
