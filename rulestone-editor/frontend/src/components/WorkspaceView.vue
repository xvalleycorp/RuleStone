<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// All decisions in the rules directory, grouped by id, with their versions, periods and problems.
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { t } from '../i18n'
import { groupById, inForce, today } from '../model/table'
import { openTable } from '../store'
import type { TableSummary } from '../types'
import NewTableDialog from './NewTableDialog.vue'

const tables = ref<TableSummary[]>([])
const directory = ref('')
const loading = ref(true)
const error = ref<string | null>(null)
const filter = ref('')
const creating = ref(false)
const date = today()

async function load() {
  loading.value = true
  error.value = null
  try {
    const r = await api.tables()
    tables.value = r.tables
    directory.value = r.directory
  } catch (e) {
    error.value = String((e as Error).message ?? e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const groups = computed(() => {
  const f = filter.value.trim().toLowerCase()
  return groupById(tables.value).filter((g) => !f || g.id.toLowerCase().includes(f)
    || g.versions.some((v) => (v.description ?? '').toLowerCase().includes(f) || v.file.toLowerCase().includes(f)))
})

function period(v: TableSummary): string {
  if (!v.effectiveFrom && !v.effectiveTo) return t('period.always')
  if (!v.effectiveTo) return t('period.from', { date: v.effectiveFrom! })
  if (!v.effectiveFrom) return t('period.until', { date: v.effectiveTo })
  return t('period.range', { from: v.effectiveFrom, to: v.effectiveTo })
}
function hitPolicy(name?: string) {
  if (!name) return ''
  const key = `hitPolicies.${name}`
  const text = t(key)
  return text === key ? name : text
}
</script>

<template>
  <div class="workspace">
    <div class="head row">
      <div class="grow">
        <h1>{{ t('workspace.title') }}</h1>
        <p class="help">{{ t('workspace.intro') }}</p>
      </div>
      <input v-model="filter" type="search" class="filter" :placeholder="t('workspace.filter')" :aria-label="t('workspace.filter')">
      <button type="button" @click="load">{{ t('workspace.refresh') }}</button>
      <button type="button" class="primary" @click="creating = true">+ {{ t('workspace.new') }}</button>
    </div>

    <p v-if="error" class="notice error">{{ t('app.loadError') }} {{ error }}</p>
    <p v-else-if="loading" class="muted">{{ t('common.loading') }}</p>
    <p v-else-if="tables.length === 0" class="notice">{{ t('workspace.empty') }}</p>

    <section v-for="g in groups" :key="g.id" class="card decision">
      <header class="row">
        <h2 class="mono grow">{{ g.id }}</h2>
        <span class="help">{{ g.versions[g.versions.length - 1].description }}</span>
        <span class="badge">{{ t('workspace.versions', { n: g.versions.length }) }}</span>
      </header>
      <table class="data">
        <thead>
          <tr>
            <th scope="col">{{ t('workspace.version') }}</th>
            <th scope="col">{{ t('workspace.period') }}</th>
            <th scope="col">{{ t('workspace.hitPolicy') }}</th>
            <th scope="col">{{ t('workspace.rules') }}</th>
            <th scope="col">{{ t('workspace.problems') }}</th>
            <th scope="col">{{ t('workspace.file') }}</th>
            <th scope="col"><span class="sr-only">{{ t('workspace.open') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in g.versions" :key="v.file" :class="{ current: v.id && inForce(v, date) }">
            <td><span class="badge gold">v{{ v.version ?? '?' }}</span></td>
            <td>
              {{ period(v) }}
              <span v-if="v.id && inForce(v, date)" class="badge ok">{{ t('workspace.inForce') }}</span>
            </td>
            <td>{{ hitPolicy(v.hitPolicy) }}</td>
            <td>{{ v.ruleCount ?? '' }}</td>
            <td>
              <span v-if="v.problems.length === 0" class="badge ok">✓ {{ t('workspace.noProblems') }}</span>
              <span v-else class="badge error" :title="v.problems.map((p) => p.message).join('\n')">{{ t('workspace.problemCount', { n: v.problems.length }) }}</span>
            </td>
            <td class="mono muted">{{ v.file }}</td>
            <td class="open"><button type="button" class="small" :aria-label="`${t('workspace.open')} ${v.file}`" @click="openTable(v.file)">{{ t('workspace.open') }} →</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <NewTableDialog v-if="creating" @close="creating = false" />
  </div>
</template>

<style scoped>
.workspace { display: flex; flex-direction: column; gap: 14px; }
.head { align-items: flex-end; gap: 8px; }
.filter { width: 260px; }
.decision header { padding: 12px 16px; gap: 12px; }
.decision h2 { font-size: 15px; }
tr.current td { background: #fbf7ec; }
.open { text-align: right; width: 90px; }
</style>
