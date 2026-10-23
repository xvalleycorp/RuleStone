<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Inputs and outputs of a table, with nested structures and lists. Types come from the server.
import { reactive, watch } from 'vue'
import { t } from '../i18n'
import { type Field, fieldsOf, nameProblem, recordOf } from '../model/schema'
import type { Problem, TableJson } from '../types'
import TypeEditor from './TypeEditor.vue'

const props = defineProps<{ table: TableJson; problems: Map<string, Problem[]> }>()

const inputs = reactive<Field[]>(fieldsOf(props.table.inputs))
const outputs = reactive<Field[]>(fieldsOf(props.table.outputs))

function nameError(list: Field[], name: string, index: number): string | null {
  const problem = nameProblem(name)
  if (problem === 'identifier') return t('schema.invalidName')
  if (problem === 'reserved') return t('schema.reservedName')
  if (list.findIndex((f) => f.name === name) !== index) return t('schema.duplicateName')
  return null
}

// Write back to the table whenever the names are unambiguous (duplicates would lose a field).
watch(inputs, (list) => {
  if (new Set(list.map((f) => f.name)).size === list.length) props.table.inputs = recordOf(list)
}, { deep: true })
watch(outputs, (list) => {
  if (new Set(list.map((f) => f.name)).size === list.length) props.table.outputs = recordOf(list)
}, { deep: true })

function serverProblems(kind: 'input' | 'output', name: string): Problem[] {
  const out: Problem[] = []
  for (const [key, list] of props.problems) {
    if (key === `${kind}:${name}` || key.startsWith(`${kind}:${name}.`) || key.startsWith(`${kind}:${name}[`)) out.push(...list)
  }
  return out
}

function add(list: Field[], prefix: string) {
  let n = list.length + 1
  while (list.some((f) => f.name === `${prefix}${n}`)) n++
  list.push({ name: `${prefix}${n}`, type: { kind: 'primitive', name: 'string' } })
}
</script>

<template>
  <div class="schema">
    <section v-for="section in (['inputs', 'outputs'] as const)" :key="section" class="card">
      <header>
        <h2>{{ t(`schema.${section}`) }}</h2>
        <p class="help">{{ t(`schema.${section}Help`) }}</p>
        <p v-if="section === 'inputs'" class="help">{{ t('schema.implicit') }}</p>
      </header>
      <table class="data">
        <thead>
          <tr><th>{{ t('schema.name') }}</th><th>{{ t('schema.type') }}</th><th><span class="sr-only">{{ t('grid.actions') }}</span></th></tr>
        </thead>
        <tbody>
          <tr v-for="(field, i) in (section === 'inputs' ? inputs : outputs)" :key="i">
            <td class="name-cell">
              <input
                v-model="field.name" type="text" class="mono" :aria-label="`${t(`schema.${section}`)}: ${t('schema.name')} ${i + 1}`"
                :class="{ invalid: !!nameError(section === 'inputs' ? inputs : outputs, field.name, i) || serverProblems(section === 'inputs' ? 'input' : 'output', field.name).length > 0 }"
              >
              <span v-if="nameError(section === 'inputs' ? inputs : outputs, field.name, i)" class="error-text">{{ nameError(section === 'inputs' ? inputs : outputs, field.name, i) }}</span>
              <span v-for="p in serverProblems(section === 'inputs' ? 'input' : 'output', field.name)" :key="p.detail" class="error-text">{{ p.message }}</span>
            </td>
            <td>
              <TypeEditor
                :node="field.type" :label="`${field.name}: ${t('schema.type')}`" :allow-struct="section === 'inputs'"
                @replace="(n) => (field.type = n)"
              />
            </td>
            <td class="actions">
              <button type="button" class="ghost icon danger" :aria-label="`${t('common.remove')} ${field.name}`" @click="(section === 'inputs' ? inputs : outputs).splice(i, 1)">🗑</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="footer row">
        <button type="button" @click="section === 'inputs' ? add(inputs, 'input') : add(outputs, 'output')">
          + {{ section === 'inputs' ? t('schema.addInput') : t('schema.addOutput') }}
        </button>
        <span class="help">{{ t('schema.renameHelp') }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.schema { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
section header { padding: 14px 16px 6px; }
.name-cell { width: 14em; }
.name-cell input { width: 100%; }
.actions { width: 48px; text-align: right; }
.footer { padding: 10px 16px; gap: 12px; }
</style>
