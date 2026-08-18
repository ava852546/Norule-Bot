<script setup lang="ts">
import { computed, useId } from 'vue'

const checked = defineModel<boolean>({ default: false })
const props = withDefaults(defineProps<{ id?: string; label: string; hint?: string; disabled?: boolean }>(), { hint: '', disabled: false })
const generatedId = useId()
const switchId = computed(() => props.id || generatedId)
</script>

<template>
  <label class="nr-switch" :class="{ 'nr-switch--disabled': disabled }" :for="switchId">
    <span><strong>{{ label }}</strong><small v-if="hint">{{ hint }}</small></span>
    <input :id="switchId" v-model="checked" type="checkbox" role="switch" :disabled="disabled">
    <span class="nr-switch__track" aria-hidden="true"><span /></span>
  </label>
</template>

<style scoped>
.nr-switch{display:flex;min-height:var(--nr-control-height);align-items:center;justify-content:space-between;gap:1rem;padding:.85rem 0;border-top:1px solid var(--nr-border);border-bottom:1px solid var(--nr-border);cursor:pointer}.nr-switch>span:first-child{display:grid;gap:.15rem}.nr-switch strong{font-size:.88rem;font-weight:700}.nr-switch small{color:var(--nr-text-muted);font-size:.75rem;line-height:1.45}.nr-switch input{position:absolute;width:1px;height:1px;opacity:0}.nr-switch__track{display:flex;width:3rem;height:1.55rem;align-items:center;padding:.17rem;border:1px solid var(--nr-text);border-radius:0;background:transparent;transition:background 120ms ease}.nr-switch__track span{width:1.05rem;height:1.05rem;background:var(--nr-text);transition:transform 120ms ease,background 120ms ease}.nr-switch input:checked+.nr-switch__track{background:var(--nr-accent)}.nr-switch input:checked+.nr-switch__track span{transform:translateX(1.5rem);background:var(--nr-accent-ink)}.nr-switch input:focus-visible+.nr-switch__track{outline:3px solid var(--nr-focus);outline-offset:3px}.nr-switch--disabled{cursor:not-allowed;opacity:.55}
</style>
