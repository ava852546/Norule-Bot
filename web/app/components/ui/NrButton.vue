<script setup lang="ts">
withDefaults(defineProps<{
  type?: 'button' | 'submit' | 'reset'
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  loading?: boolean
  disabled?: boolean
  block?: boolean
}>(), {
  type: 'button',
  variant: 'primary',
  loading: false,
  disabled: false,
  block: false,
})
</script>

<template>
  <button
    class="nr-button"
    :class="[`nr-button--${variant}`, { 'nr-button--block': block }]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading"
  >
    <span v-if="loading" class="nr-button__spinner" aria-hidden="true" />
    <span><slot>{{ loading ? '處理中…' : '確認' }}</slot></span>
  </button>
</template>

<style scoped>
.nr-button{display:inline-flex;min-height:var(--nr-control-height);align-items:center;justify-content:center;gap:.65rem;padding:.72rem 1.25rem;border:1px solid var(--nr-border-strong);border-radius:0;font-family:var(--nr-font-display);font-size:.88rem;font-weight:720;letter-spacing:.025em;line-height:1.1;cursor:pointer;transition:background 120ms ease,color 120ms ease}.nr-button:disabled{cursor:not-allowed;opacity:.44}.nr-button--block{width:100%}.nr-button--primary{color:var(--nr-accent-ink);background:var(--nr-accent)}.nr-button--primary:hover:not(:disabled){background:var(--nr-text)}.nr-button--secondary{color:var(--nr-text);background:transparent}.nr-button--secondary:hover:not(:disabled){color:var(--nr-accent-ink);background:var(--nr-text)}.nr-button--ghost{color:var(--nr-text-muted);border-color:var(--nr-border);background:transparent}.nr-button--ghost:hover:not(:disabled){border-color:var(--nr-border-strong);color:var(--nr-text)}.nr-button--danger{color:var(--nr-accent-ink);border-color:var(--nr-danger);background:var(--nr-danger)}.nr-button__spinner{width:1rem;height:1rem;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:nr-button-spin .7s linear infinite}@keyframes nr-button-spin{to{transform:rotate(360deg)}}
</style>
