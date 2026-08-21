<script setup lang="ts">
export interface NavbarLink { label: string; href: string }
withDefaults(defineProps<{ brand?: string; links?: NavbarLink[] }>(), { brand: 'NoRule', links: () => [] })
defineEmits<{ navigate: [link: NavbarLink] }>()
</script>

<template>
  <header class="nr-navbar">
    <NrPageContainer>
      <nav aria-label="主要導覽">
        <a class="nr-navbar__brand" href="#top" aria-label="回到頁面頂端"><span aria-hidden="true">NR</span><strong>{{ brand }}</strong></a>
        <div class="nr-navbar__links">
          <a v-for="link in links" :key="link.href" :href="link.href" @click="$emit('navigate', link)">{{ link.label }}</a>
        </div>
        <slot name="action" />
      </nav>
    </NrPageContainer>
  </header>
</template>

<style scoped>
.nr-navbar{position:sticky;z-index:20;top:0;border-bottom:1px solid var(--nr-border-strong);background:var(--nr-bg)}nav{display:grid;min-height:4.55rem;grid-template-columns:minmax(12rem,1fr) auto auto;align-items:center;gap:2rem}.nr-navbar__brand{display:inline-flex;width:max-content;align-items:center;gap:.7rem;text-decoration:none}.nr-navbar__brand span{display:grid;width:2.15rem;height:1.55rem;place-items:center;color:var(--nr-accent-ink);background:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.65rem;font-weight:800;letter-spacing:.08em}.nr-navbar__brand strong{font-family:var(--nr-font-display);font-size:.95rem;font-weight:720;letter-spacing:.04em;text-transform:uppercase}.nr-navbar__links{display:flex;min-width:0;align-items:stretch;align-self:stretch}.nr-navbar__links a{display:flex;align-items:center;padding:0 clamp(.7rem,1.6vw,1.35rem);border-left:1px solid var(--nr-border);color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.72rem;text-decoration:none;transition:color 120ms ease,background 120ms ease}.nr-navbar__links a:hover{color:var(--nr-accent-ink);background:var(--nr-text)}@media(max-width:640px){nav{min-height:4rem;grid-template-columns:auto 1fr auto;gap:.5rem}.nr-navbar__brand strong{display:none}.nr-navbar__links{justify-content:flex-end}.nr-navbar__links a{padding:0 .45rem;border-left:0;font-size:.6rem}.nr-navbar__links a:first-child{display:none}}
</style>
