<script setup lang="ts">
withDefaults(defineProps<{ id?: string; eyebrow?: string; title?: string; description?: string; align?: 'left' | 'center' }>(), { id: undefined, eyebrow: '', title: '', description: '', align: 'left' })
</script>

<template>
  <section :id="id" class="nr-section" :class="`nr-section--${align}`">
    <div v-if="eyebrow || title || description" class="nr-section__heading">
      <p v-if="eyebrow" class="nr-section__eyebrow">{{ eyebrow }}</p>
      <h2 v-if="title">{{ title }}</h2>
      <p v-if="description" class="nr-section__description">{{ description }}</p>
    </div>
    <slot />
  </section>
</template>

<style scoped>
.nr-section{padding:clamp(4rem,8vw,7rem) 0;border-top:1px solid var(--nr-border-strong)}.nr-section__heading{display:grid;max-width:46rem;gap:.8rem;margin-bottom:2.5rem}.nr-section--center .nr-section__heading{margin-right:auto;margin-left:auto;text-align:center}.nr-section__eyebrow{margin:0;color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.68rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase}.nr-section h2{margin:0;font-family:var(--nr-font-display);font-size:clamp(2.2rem,5vw,4rem);font-weight:700;line-height:1;letter-spacing:-.035em}.nr-section__description{margin:0;color:var(--nr-text-muted);font-size:clamp(.95rem,2vw,1.08rem)}
</style>
