<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Every problem of the table, each with where it is and a button that shows the cell.
import { t } from '../i18n'
import type { Problem } from '../types'

defineProps<{ problems: Problem[] }>()
const emit = defineEmits<{ show: [problem: Problem] }>()

function where(p: Problem): string {
  const l = p.location
  const parts: string[] = []
  if (l.rule !== undefined) parts.push(t('problems.rule', { id: l.rule }))
  else parts.push(t('problems.table'))
  if (l.field) parts.push(l.field === 'then' && l.output ? l.output : l.field)
  if (l.input) parts.push(l.input)
  else if (l.output && l.field !== 'then') parts.push(l.output)
  if (l.file) parts.push(t('problems.otherFile', { file: l.file }))
  return parts.join(' · ')
}
</script>

<template>
  <section class="problems card" :aria-label="t('problems.title')">
    <h2>{{ t('problems.title') }} <span class="badge" :class="problems.length ? 'error' : 'ok'">{{ problems.length }}</span></h2>
    <p v-if="problems.length === 0" class="muted">{{ t('problems.none') }}</p>
    <ul v-else>
      <li v-for="(p, i) in problems" :key="i" class="row">
        <span class="where">{{ where(p) }}</span>
        <span class="message grow" :title="p.detail">{{ p.message }}</span>
        <button type="button" class="small" @click="emit('show', p)">{{ t('problems.show') }}</button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.problems { padding: 12px 16px; }
h2 { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; max-height: 180px; overflow: auto; }
li { padding: 4px 0; border-bottom: 1px solid var(--border); align-items: baseline; }
.where { font-weight: 600; font-size: 12.5px; min-width: 16em; color: var(--error); }
.message { font-family: var(--mono); font-size: 12.5px; }
</style>
