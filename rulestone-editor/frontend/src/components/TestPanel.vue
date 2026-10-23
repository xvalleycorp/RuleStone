<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Evaluates the table as edited (unsaved changes included) with facts from a generated form or raw JSON.
import { ref, watch } from 'vue'
import { ApiError, api } from '../api'
import { t } from '../i18n'
import { type FormValue, emptyForm, factsJson, formFromJson } from '../model/facts'
import { cleanTable, today } from '../model/table'
import type { EvaluationResult, TableJson } from '../types'
import FactInput from './FactInput.vue'

const props = defineProps<{ table: TableJson; valid: boolean }>()
const emit = defineEmits<{ trace: [trace: Map<string, boolean> | null] }>()

const mode = ref<'form' | 'json'>('form')
const form = ref<Record<string, FormValue>>(emptyForm(props.table.inputs))
const json = ref('{}')
const asOf = ref(props.table.effectiveFrom && props.table.effectiveFrom > today() ? props.table.effectiveFrom : today())
const result = ref<EvaluationResult | null>(null)
const error = ref<string | null>(null)
const running = ref(false)

// A new input schema means a new form; keep what matches by re-reading the JSON of the old one.
watch(() => JSON.stringify(props.table.inputs), () => {
  try {
    form.value = formFromJson(factsJson(form.value, props.table.inputs), props.table.inputs)
  } catch {
    form.value = emptyForm(props.table.inputs)
  }
})

function switchTo(next: 'form' | 'json') {
  if (next === mode.value) return
  if (next === 'json') {
    json.value = factsJson(form.value, props.table.inputs)
  } else {
    try {
      form.value = formFromJson(json.value, props.table.inputs)
    } catch {
      error.value = t('test.invalidJson')
      return
    }
  }
  mode.value = next
}

async function run() {
  error.value = null
  const facts = mode.value === 'form' ? factsJson(form.value, props.table.inputs) : json.value
  try {
    JSON.parse(facts)
  } catch {
    error.value = t('test.invalidJson')
    return
  }
  running.value = true
  try {
    result.value = await api.evaluate(cleanTable(props.table), facts, asOf.value || undefined)
    emit('trace', new Map(result.value.trace.map((s) => [s.ruleId, s.matched])))
  } catch (e) {
    result.value = null
    emit('trace', null)
    if (e instanceof ApiError) {
      error.value = e.code === 'invalid_table' ? t('test.invalidTable') : e.message
    } else {
      error.value = String(e)
    }
  } finally {
    running.value = false
  }
}

function show(value: unknown): string {
  if (Array.isArray(value)) return value.length === 0 ? '[]' : value.map((v) => show(v)).join(', ')
  return typeof value === 'string' ? value : JSON.stringify(value)
}
</script>

<template>
  <div class="test">
    <section class="card facts">
      <header class="row">
        <h2 class="grow">{{ t('editor.tabs.test') }}</h2>
        <div class="row" role="group" :aria-label="t('editor.tabs.test')">
          <button type="button" class="small" :aria-pressed="mode === 'form'" @click="switchTo('form')">{{ t('test.form') }}</button>
          <button type="button" class="small" :aria-pressed="mode === 'json'" @click="switchTo('json')">{{ t('test.json') }}</button>
        </div>
      </header>
      <p class="help">{{ t('test.intro') }}</p>
      <div class="field asof">
        <label for="test-asof">{{ t('test.asOf') }}</label>
        <input id="test-asof" v-model="asOf" type="date">
        <span class="help">{{ t('test.asOfHelp') }}</span>
      </div>
      <div v-if="mode === 'form'" class="form">
        <FactInput v-for="(type, name) in table.inputs" :key="name" :value="form[name]" :type="type" :label="String(name)" :name="String(name)" />
      </div>
      <textarea v-else v-model="json" rows="16" spellcheck="false" :aria-label="t('test.json')" />
      <div class="row">
        <button type="button" class="accent" :disabled="running || !valid" @click="run">▶ {{ t('test.run') }}</button>
        <span v-if="!valid" class="help">{{ t('test.invalidTable') }}</span>
      </div>
    </section>

    <section class="card outcome" aria-live="polite">
      <header><h2>{{ t('test.result') }}</h2></header>
      <p v-if="error" class="notice error"><strong>{{ t('test.error') }}:</strong> {{ error }}</p>
      <template v-else-if="result">
        <p v-if="result.effectiveOnAsOf === false" class="notice warn">{{ t('test.notEffective', { date: result.asOf ?? '' }) }}</p>
        <p v-if="!result.matched" class="notice">{{ t('test.noMatch') }}</p>
        <table class="data">
          <tbody>
            <tr v-for="(value, name) in result.output" :key="name">
              <th scope="row" class="mono">{{ name }}</th>
              <td class="mono value">{{ show(value) }}</td>
            </tr>
            <tr>
              <th scope="row">{{ t('test.reasonCodes') }}</th>
              <td>
                <span v-for="code in result.reasonCodes" :key="code" class="badge gold mono">{{ code }}</span>
                <span v-if="result.reasonCodes.length === 0" class="muted">{{ t('test.none') }}</span>
              </td>
            </tr>
            <tr>
              <th scope="row">{{ t('test.matchedRules') }}</th>
              <td>
                <span v-for="id in result.matchedRules" :key="id" class="badge ok">{{ id }}</span>
                <span v-if="result.matchedRules.length === 0" class="muted">{{ t('test.none') }}</span>
              </td>
            </tr>
            <tr>
              <th scope="row">{{ t('test.ruleVersion') }}</th>
              <td class="mono hash">{{ result.ruleVersion }}</td>
            </tr>
          </tbody>
        </table>
        <p class="help">{{ t('test.highlight') }}</p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.test { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
section { padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; }
.asof input { width: 12em; }
.form { display: flex; flex-direction: column; gap: 8px; }
textarea { width: 100%; }
.data th { width: 12em; background: transparent; color: var(--text); text-transform: none; }
.value { font-size: 14px; font-weight: 600; }
.hash { word-break: break-all; font-size: 11.5px; }
.badge + .badge { margin-left: 4px; }
</style>
