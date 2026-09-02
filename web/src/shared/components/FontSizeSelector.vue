<script setup lang="ts">
import { computed, ref } from 'vue'

type FontSize = 'small' | 'medium' | 'large'

const props = withDefaults(defineProps<{
  groupLabel?: string
  smallLabel?: string
  mediumLabel?: string
  largeLabel?: string
}>(), {
  groupLabel: '字體大小',
  smallLabel: '小',
  mediumLabel: '中',
  largeLabel: '大',
})

const STORAGE_KEY = 'norule-font-size'
const DEFAULT_FONT_SIZE: FontSize = 'medium'
const selected = ref<FontSize>(DEFAULT_FONT_SIZE)
const options = computed<Array<{ value: FontSize, label: string }>>(() => [
  { value: 'small', label: props.smallLabel },
  { value: 'medium', label: props.mediumLabel },
  { value: 'large', label: props.largeLabel },
])

function isFontSize(value: string | null): value is FontSize {
  return value === 'small' || value === 'medium' || value === 'large'
}

function applyFontSize(value: FontSize) {
  document.documentElement.dataset.fontSize = value
}

function readStoredFontSize(): FontSize {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return isFontSize(stored) ? stored : DEFAULT_FONT_SIZE
  } catch {
    // Browser storage may be unavailable; keep the preference for this page only.
    return DEFAULT_FONT_SIZE
  }
}

function selectFontSize(value: FontSize) {
  selected.value = value
  applyFontSize(value)
  try {
    window.localStorage.setItem(STORAGE_KEY, value)
  } catch {
    // The visual choice still applies even when browser storage is unavailable.
  }
}

function selectFromEvent(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (isFontSize(value)) selectFontSize(value)
}

if (import.meta.client) {
  selected.value = readStoredFontSize()
  applyFontSize(selected.value)
}
</script>

<template>
  <div class="font-size-selector" role="group" :aria-label="groupLabel">
    <span aria-hidden="true">A</span>
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      :aria-label="`${groupLabel}：${option.label}`"
      :aria-pressed="selected === option.value"
      :title="`${groupLabel}：${option.label}`"
      @click="selectFontSize(option.value)"
    >
      {{ option.label }}
    </button>
    <select :value="selected" :aria-label="groupLabel" :title="groupLabel" @change="selectFromEvent">
      <option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
  </div>
</template>

<style scoped>
:global(html[data-font-size="small"]){font-size:87.5%}
:global(html[data-font-size="medium"]){font-size:100%}
:global(html[data-font-size="large"]){font-size:112.5%}
.font-size-selector{display:flex;align-items:center;height:2.45rem;border:1px solid var(--nr-border-strong,var(--nr-line-strong));font-family:var(--nr-font-mono,var(--nr-mono))}
.font-size-selector>span{padding:0 .55rem;color:var(--nr-text-muted,var(--nr-muted));font-size:.62rem;font-weight:800}
button{align-self:stretch;min-width:2rem;padding:0 .45rem;border:0;border-left:1px solid var(--nr-border,var(--nr-line));color:var(--nr-text-muted,var(--nr-muted));background:transparent;font-size:.65rem;cursor:pointer}
button:hover{color:var(--nr-text);background:var(--nr-bg-elevated,var(--nr-paper-deep))}
button[aria-pressed="true"]{color:var(--nr-accent-ink,var(--nr-charcoal-deep));background:var(--nr-accent);font-weight:800}
select{display:none;height:100%;min-width:3.1rem;padding:0 .45rem;color:var(--nr-text);border:0;background:var(--nr-bg-elevated,var(--nr-paper-deep));font-size:.65rem;cursor:pointer}
@media(max-width:760px){.font-size-selector>span,.font-size-selector>button{display:none}select{display:block}}
</style>
