<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>(), {
  description: '',
  eyebrow: '',
})
</script>

<template>
  <section class="nr-section-card">
    <header class="nr-section-head">
      <span class="nr-section-index" aria-hidden="true" />
      <div>
        <span v-if="eyebrow" class="nr-eyebrow">{{ eyebrow }}</span>
        <h2>{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="nr-section-actions"><slot name="actions" /></div>
    </header>
    <div class="nr-section-body"><slot /></div>
  </section>
</template>
