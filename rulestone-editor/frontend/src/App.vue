<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import TableEditor from './components/TableEditor.vue'
import WorkspaceView from './components/WorkspaceView.vue'
import { type Locale, locale, setLocale, t } from './i18n'
import { loadMeta, meta, route } from './store'

const error = ref<string | null>(null)
onMounted(async () => {
  try {
    await loadMeta()
  } catch (e) {
    error.value = String((e as Error).message ?? e)
  }
})
</script>

<template>
  <header class="topbar">
    <a href="#/" class="brand" :aria-label="t('app.title')">
      <svg width="26" height="26" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="3" y="5" width="26" height="22" rx="4" fill="#3d3a35" />
        <rect x="7" y="10" width="18" height="3" rx="1.5" fill="#b8860b" />
        <rect x="7" y="16" width="12" height="3" rx="1.5" fill="#e6e3dd" />
        <rect x="7" y="21" width="15" height="3" rx="1.5" fill="#e6e3dd" />
      </svg>
      <span class="name">RuleStone</span>
      <span class="sub">{{ t('app.subtitle') }}</span>
    </a>
    <span class="grow" />
    <span v-if="meta" class="muted version">v{{ meta.editorVersion }}</span>
    <label class="lang">
      <span class="sr-only">{{ t('app.language') }}</span>
      <select :value="locale" :aria-label="t('app.language')" @change="setLocale(($event.target as HTMLSelectElement).value as Locale)">
        <option value="en">English</option>
        <option value="vi">Tiếng Việt</option>
      </select>
    </label>
  </header>
  <main>
    <p v-if="error" class="notice error">{{ t('app.loadError') }} {{ error }}</p>
    <template v-else-if="meta">
      <TableEditor v-if="route.view === 'edit'" :key="route.file" :file="route.file" />
      <WorkspaceView v-else />
    </template>
    <p v-else class="muted">{{ t('common.loading') }}</p>
  </main>
</template>

<style scoped>
.topbar { display: flex; align-items: center; gap: 12px; padding: 10px 24px; background: var(--stone); color: #fff; }
.brand { display: flex; align-items: center; gap: 10px; color: #fff; text-decoration: none; }
.brand .name { font-weight: 700; font-size: 16px; letter-spacing: 0.02em; }
.brand .sub { color: #d8cfa8; font-size: 13px; border-left: 1px solid #6b665e; padding-left: 10px; }
.brand svg rect:first-child { stroke: #6b665e; }
.version { color: #b9b3a9; font-size: 12px; }
.lang select { background: var(--stone-dark); color: #fff; border-color: #6b665e; }
main { padding: 20px 24px 40px; max-width: 1680px; margin: 0 auto; }
</style>
