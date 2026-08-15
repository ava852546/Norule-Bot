<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { useDashboard } from '../composables/useDashboard'

const dashboard = useDashboard()

const props = defineProps<{
  open: boolean
  title: string
  description?: string
}>()

const emit = defineEmits<{ close: [] }>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) emit('close')
}

watch(() => props.open, (open) => {
  if (!import.meta.client) return
  document.body.classList.toggle('nr-modal-open', open)
}, { immediate: true })

if (import.meta.client) window.addEventListener('keydown', onKeydown)
onBeforeUnmount(() => {
  if (!import.meta.client) return
  window.removeEventListener('keydown', onKeydown)
  document.body.classList.remove('nr-modal-open')
})
</script>

<template>
  <Teleport to="body">
    <Transition name="nr-modal">
      <div v-if="open" class="nr-modal-backdrop" @mousedown.self="emit('close')">
        <section class="nr-modal" role="dialog" aria-modal="true" :aria-label="title">
          <header class="nr-modal-head">
            <div><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div>
            <button type="button" class="nr-icon-button" :aria-label="dashboard.i18n.t('dashboard_close_named', '關閉 {name}', { name: title })" @click="emit('close')">×</button>
          </header>
          <div class="nr-modal-content"><slot /></div>
          <footer v-if="$slots.footer" class="nr-modal-footer"><slot name="footer" /></footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
