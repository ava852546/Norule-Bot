<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useDashboard } from '../composables/useDashboard'

const dashboard = useDashboard()
const open = ref(false)
const root = ref<HTMLElement | null>(null)
const selectedGuild = computed(() => dashboard.currentGuild.value)

function initials(name?: string) {
  return String(name || 'NR').slice(0, 2).toUpperCase()
}

function toggleMenu() {
  if (dashboard.state.loadingSection || dashboard.state.saving) return
  open.value = !open.value
}

async function selectGuild(guildId: string) {
  await dashboard.selectGuild(guildId)
  open.value = false
}

function onDocumentPointerDown(event: PointerEvent) {
  if (root.value && !root.value.contains(event.target as Node)) open.value = false
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <div ref="root" class="nr-server-select" :class="{ 'is-open': open }">
    <button
      type="button"
      class="nr-server-select-trigger"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :disabled="dashboard.state.loadingSection || dashboard.state.saving"
      @click="toggleMenu"
    >
      <img v-if="selectedGuild?.iconUrl" class="nr-server-icon is-image" :src="selectedGuild.iconUrl" :alt="selectedGuild.name" referrerpolicy="no-referrer">
      <span v-else class="nr-server-icon">{{ initials(selectedGuild?.name) }}</span>
      <span class="nr-server-select-copy"><small>{{ dashboard.i18n.t('dashboard_current_guild', '目前管理的伺服器') }}</small><strong>{{ selectedGuild?.name || dashboard.i18n.t('dashboard_select_guild', '選擇伺服器') }}</strong></span>
      <i class="nr-server-select-chevron">⌄</i>
    </button>

    <Transition name="nr-server-menu">
      <div v-if="open" class="nr-server-menu" role="listbox" :aria-label="dashboard.i18n.t('dashboard_select_guild_aria', '選擇要管理的伺服器')">
        <p>{{ dashboard.i18n.t('dashboard_guild_management', '伺服器管理') }}</p>
        <button
          v-for="guild in dashboard.manageableGuilds.value"
          :key="guild.id"
          type="button"
          class="nr-server-option"
          :class="{ active: guild.id === dashboard.state.selectedGuildId }"
          role="option"
          :aria-selected="guild.id === dashboard.state.selectedGuildId"
          @click="selectGuild(guild.id)"
        >
          <img v-if="guild.iconUrl" class="nr-server-option-icon" :src="guild.iconUrl" :alt="guild.name" referrerpolicy="no-referrer">
          <span v-else class="nr-server-option-icon">{{ initials(guild.name) }}</span>
          <span><strong>{{ guild.name }}</strong><small>{{ guild.botCanManage ? dashboard.i18n.t('badgeManageable', 'Bot 已就緒') : dashboard.i18n.t('badgeMissingPerm', 'Bot 權限可能不足') }}</small></span>
          <i>{{ guild.id === dashboard.state.selectedGuildId ? '✓' : '→' }}</i>
        </button>
      </div>
    </Transition>
  </div>
</template>
