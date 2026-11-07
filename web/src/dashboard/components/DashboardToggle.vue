<script setup lang="ts">
defineProps<{
  modelValue: boolean
  label: string
  description?: string
  note?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <label class="nr-toggle-row" :class="{ 'is-disabled': disabled }">
    <span class="nr-toggle-copy">
      <strong>{{ label }}</strong>
      <small v-if="description">{{ description }}</small>
      <small v-if="note">{{ note }}</small>
    </span>
    <input
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      @change="!disabled && emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="nr-switch" aria-hidden="true"><i /></span>
  </label>
</template>
