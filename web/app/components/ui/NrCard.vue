<script setup lang="ts">
withDefaults(defineProps<{ as?: string; tone?: 'default' | 'soft' | 'strong'; padding?: 'none' | 'sm' | 'md' | 'lg' }>(), { as: 'div', tone: 'default', padding: 'md' })
</script>

<template><component :is="as" class="nr-card" :class="[`nr-card--${tone}`, `nr-card--${padding}`]"><slot /></component></template>

<style scoped>
.nr-card{position:relative;border-top:1px solid var(--nr-border-strong);border-bottom:1px solid var(--nr-border);border-radius:0;box-shadow:none}.nr-card--default{background:transparent}.nr-card--soft{background:var(--nr-surface-soft)}.nr-card--strong{background:var(--nr-surface)}.nr-card--none{padding:0}.nr-card--sm{padding:var(--nr-space-5)}.nr-card--md{padding:var(--nr-space-8)}.nr-card--lg{padding:clamp(1.5rem,4vw,2.5rem)}
</style>
