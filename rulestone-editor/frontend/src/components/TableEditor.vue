<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Editing one .stone.json file: metadata, rules, schema, test and JSON tabs, live validation, save.
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiError, api } from '../api'
import { t } from '../i18n'
import { cleanTable, groupProblems, moveItem, nextRuleId, problemKey } from '../model/table'
import { dirty, meta, openWorkspace } from '../store'
import type { LoadedTable, Problem, TableJson } from '../types'
import JsonView from './JsonView.vue'
import NewVersionDialog from './NewVersionDialog.vue'
import ProblemsPanel from './ProblemsPanel.vue'
import RulesGrid from './RulesGrid.vue'
import SchemaEditor from './SchemaEditor.vue'
import TestPanel from './TestPanel.vue'

const props = defineProps<{ file: string }>()

const loaded = ref<LoadedTable | null>(null)
const loadError = ref<string | null>(null)
const draft = reactive<TableJson>({ id: '', version: '', hitPolicy: 'FIRST', inputs: {}, outputs: {}, rules: [] })
const rowKeys = ref<number[]>([])
let nextKey = 1
const revision = ref(0)
const savedJson = ref('')

const tab = ref<'rules' | 'schema' | 'test' | 'json'>('rules')
const problems = ref<Problem[]>([])
const validating = ref(false)
const formatted = ref('')
const trace = ref<Map<string, boolean> | null>(null)
const saving = ref(false)
const message = ref<{ kind: 'ok' | 'error' | 'warn'; text: string } | null>(null)
const conflict = ref(false)
const showNewVersion = ref(false)

const isDirty = computed(() => loaded.value !== null && JSON.stringify(cleanTable(draft)) !== savedJson.value)
const valid = computed(() => problems.value.length === 0)
const byCell = computed(() => groupProblems(problems.value))
watch(isDirty, (v) => (dirty.value = v), { immediate: true })

function reset(table: TableJson) {
  const copy = structuredClone(table)
  Object.assign(draft, { description: undefined, effectiveFrom: undefined, effectiveTo: undefined }, copy)
  draft.rules = copy.rules ?? []
  draft.inputs = copy.inputs ?? {}
  draft.outputs = copy.outputs ?? {}
  rowKeys.value = draft.rules.map(() => nextKey++)
  savedJson.value = JSON.stringify(cleanTable(draft))
  revision.value++
  trace.value = null
}

async function load() {
  loadError.value = null
  conflict.value = false
  try {
    const l = await api.load(props.file)
    loaded.value = l
    if (l.table) {
      reset(l.table)
      problems.value = l.problems
      await validateNow()
    }
  } catch (e) {
    loadError.value = e instanceof ApiError && e.status === 404 ? t('editor.notFound') : String((e as Error).message ?? e)
  }
}

// ---- live validation (debounced) -----------------------------------------------------------------------------
let timer: ReturnType<typeof setTimeout> | undefined
let sequence = 0
async function validateNow() {
  const seq = ++sequence
  const table = cleanTable(draft)
  validating.value = true
  try {
    const [v, f] = await Promise.all([api.validate(table, props.file), api.format(table)])
    if (seq !== sequence) return
    problems.value = v.problems
    formatted.value = f.content
  } catch (e) {
    if (seq === sequence) message.value = { kind: 'error', text: String((e as Error).message ?? e) }
  } finally {
    if (seq === sequence) validating.value = false
  }
}
watch(draft, () => {
  if (!loaded.value?.table) return
  clearTimeout(timer)
  timer = setTimeout(validateNow, 400)
}, { deep: true })

// ---- saving ---------------------------------------------------------------------------------------------------
async function save(force = false) {
  if (!loaded.value) return
  if (force && !confirm(t('editor.saveAnywayConfirm', { n: problems.value.length }))) return
  saving.value = true
  message.value = null
  try {
    const result = await api.save(props.file, cleanTable(draft), loaded.value.etag, force)
    const fresh = await api.load(props.file)
    loaded.value = { ...fresh, etag: result.etag ?? fresh.etag }
    savedJson.value = JSON.stringify(cleanTable(draft))
    message.value = { kind: 'ok', text: t('editor.saved') }
  } catch (e) {
    if (e instanceof ApiError && e.status === 412) {
      conflict.value = true
    } else if (e instanceof ApiError && e.problems.length > 0) {
      problems.value = e.problems
      message.value = { kind: 'error', text: t('editor.saveFailed', { message: e.message }) }
    } else {
      message.value = { kind: 'error', text: t('editor.saveFailed', { message: String((e as Error).message ?? e) }) }
    }
  } finally {
    saving.value = false
  }
}

function revert() {
  if (loaded.value?.table && confirm(t('editor.revertConfirm'))) {
    reset(loaded.value.table)
    void validateNow()
  }
}

function back() {
  if (!isDirty.value || confirm(t('editor.leaveConfirm'))) {
    dirty.value = false
    openWorkspace()
  }
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    if (valid.value && isDirty.value) void save()
  }
}
function onBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) e.preventDefault()
}
onMounted(() => {
  void load()
  window.addEventListener('keydown', onKey)
  window.addEventListener('beforeunload', onBeforeUnload)
})
onBeforeUnmount(() => {
  clearTimeout(timer)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('beforeunload', onBeforeUnload)
  dirty.value = false
})

// ---- rules ----------------------------------------------------------------------------------------------------
function addRule() {
  draft.rules.push({ id: nextRuleId(draft.rules), when: 'true' })
  rowKeys.value.push(nextKey++)
}
function duplicateRule(i: number) {
  const copy = structuredClone(draft.rules[i])
  copy.id = nextRuleId(draft.rules)
  draft.rules.splice(i + 1, 0, copy)
  rowKeys.value.splice(i + 1, 0, nextKey++)
}
function removeRule(i: number) {
  draft.rules.splice(i, 1)
  rowKeys.value.splice(i, 1)
}
function moveRule(from: number, to: number) {
  moveItem(draft.rules, from, to)
  moveItem(rowKeys.value, from, to)
}

// ---- problems -------------------------------------------------------------------------------------------------
async function showProblem(p: Problem) {
  const key = problemKey(p)
  if (key.startsWith('rule:')) {
    tab.value = 'rules'
    await nextTick()
    const index = p.location.ruleIndex!
    const outputs = Object.keys(draft.outputs)
    const field = p.location.field
    const column = field === 'when' ? 3 : field === 'then' && p.location.output ? 4 + Math.max(0, outputs.indexOf(p.location.output))
      : field === 'reasonCode' ? 4 + outputs.length : field === 'description' ? 2 : 1
    const cell = document.querySelector<HTMLElement>(`td[data-r="${index}"][data-c="${column}"]`)
    cell?.scrollIntoView({ block: 'center' })
    cell?.focus()
  } else if (key.startsWith('input:') || key.startsWith('output:') || p.location.field === 'inputs' || p.location.field === 'outputs') {
    tab.value = 'schema'
  } else {
    document.getElementById(`meta-${p.location.field}`)?.focus()
  }
}
const metaProblems = (field: string) => byCell.value.get(`table:${field}`) ?? []
const otherProblems = computed(() => problems.value.filter((p) => {
  const k = problemKey(p)
  return k === 'table:' || k === 'table:rules' || k === 'table:hitPolicy'
}))

function hitPolicyLabel(name: string) {
  const key = `hitPolicies.${name}`
  const text = t(key)
  return text === key ? name : `${text} (${name})`
}
</script>

<template>
  <div class="editor">
    <div v-if="loadError" class="notice error">{{ loadError }} <button type="button" @click="openWorkspace">{{ t('common.back') }}</button></div>
    <div v-else-if="!loaded" class="muted">{{ t('common.loading') }}</div>
    <div v-else-if="!loaded.table" class="card pad">
      <p class="notice error">{{ t('editor.notJson') }}</p>
      <ul><li v-for="p in loaded.problems" :key="p.detail" class="mono">{{ p.detail }}</li></ul>
      <button type="button" @click="openWorkspace">{{ t('common.back') }}</button>
    </div>
    <template v-else>
      <div class="toolbar row">
        <button type="button" class="ghost" @click="back">← {{ t('common.back') }}</button>
        <h1 class="grow"><span class="mono">{{ draft.id }}</span> <span class="badge gold">v{{ draft.version }}</span></h1>
        <span class="mono muted file">{{ file }}</span>
        <span v-if="validating" class="badge">{{ t('editor.validating') }}</span>
        <span v-else-if="valid" class="badge ok">✓ {{ t('editor.valid') }}</span>
        <span v-else class="badge error">{{ t('editor.invalid', { n: problems.length }) }}</span>
        <span class="badge" :class="isDirty ? 'warn' : ''">{{ isDirty ? t('editor.unsaved') : t('editor.upToDate') }}</span>
        <button type="button" class="ghost" :disabled="!isDirty" @click="revert">{{ t('editor.revert') }}</button>
        <button type="button" @click="showNewVersion = true">{{ t('editor.saveAsNewVersion') }}</button>
        <button v-if="!valid" type="button" class="danger" :disabled="saving || !isDirty" @click="save(true)">{{ t('editor.saveAnyway') }}</button>
        <button type="button" class="primary" :disabled="saving || !valid || !isDirty || validating" @click="save()">{{ t('editor.save') }}</button>
      </div>

      <p v-if="conflict" class="notice warn row">{{ t('editor.modified') }} <button type="button" @click="load">{{ t('editor.reload') }}</button></p>
      <p v-if="message" class="notice" :class="message.kind === 'error' ? 'error' : ''" role="status">{{ message.text }}</p>

      <section class="card meta">
        <div class="field id">
          <label for="meta-id">{{ t('editor.id') }}</label>
          <input id="meta-id" v-model="draft.id" class="mono" :class="{ invalid: metaProblems('id').length }">
          <span v-for="p in metaProblems('id')" :key="p.detail" class="error-text">{{ p.message }}</span>
        </div>
        <div class="field version">
          <label for="meta-version">{{ t('editor.version') }}</label>
          <input id="meta-version" v-model="draft.version" class="mono" :class="{ invalid: metaProblems('version').length }">
          <span v-for="p in metaProblems('version')" :key="p.detail" class="error-text">{{ p.message }}</span>
        </div>
        <div class="field">
          <label for="meta-hitPolicy">{{ t('editor.hitPolicy') }}</label>
          <select id="meta-hitPolicy" v-model="draft.hitPolicy" :title="meta?.hitPolicies.find((h) => h.name === draft.hitPolicy)?.description">
            <option v-for="h in meta?.hitPolicies ?? []" :key="h.name" :value="h.name">{{ hitPolicyLabel(h.name) }}</option>
          </select>
        </div>
        <div class="field">
          <label for="meta-effectiveFrom">{{ t('editor.effectiveFrom') }}</label>
          <input id="meta-effectiveFrom" v-model="draft.effectiveFrom" type="date" :class="{ invalid: metaProblems('effectiveFrom').length }">
          <span v-for="p in metaProblems('effectiveFrom')" :key="p.detail" class="error-text">{{ p.message }}</span>
        </div>
        <div class="field">
          <label for="meta-effectiveTo">{{ t('editor.effectiveTo') }}</label>
          <input id="meta-effectiveTo" v-model="draft.effectiveTo" type="date" :class="{ invalid: metaProblems('effectiveTo').length }">
          <span v-for="p in metaProblems('effectiveTo')" :key="p.detail" class="error-text">{{ p.message }}</span>
        </div>
        <div class="field description">
          <label for="meta-description">{{ t('editor.description') }}</label>
          <input id="meta-description" v-model="draft.description">
        </div>
      </section>

      <div class="tabs" role="tablist" :aria-label="t('editor.tabs.rules')">
        <button
          v-for="name in (['rules', 'schema', 'test', 'json'] as const)" :id="`tab-${name}`" :key="name"
          type="button" role="tab" :aria-selected="tab === name" :aria-controls="`panel-${name}`" class="tab"
          :tabindex="tab === name ? 0 : -1" @click="tab = name"
        >
          {{ t(`editor.tabs.${name}`) }}
          <span v-if="name === 'rules'" class="count">{{ draft.rules.length }}</span>
        </button>
      </div>

      <div :id="`panel-${tab}`" role="tabpanel" :aria-labelledby="`tab-${tab}`" class="panel">
        <RulesGrid
          v-if="tab === 'rules'"
          :table="draft" :row-keys="rowKeys" :problems="byCell" :trace="trace"
          @add="addRule" @duplicate="duplicateRule" @remove="removeRule" @move="moveRule" @clear-trace="trace = null"
        />
        <SchemaEditor v-else-if="tab === 'schema'" :key="revision" :table="draft" :problems="byCell" />
        <TestPanel v-else-if="tab === 'test'" :table="draft" :valid="valid" @trace="trace = $event" />
        <JsonView v-else :formatted="formatted" :saved="loaded.content" />
      </div>

      <p v-for="p in otherProblems" :key="p.detail" class="notice error">{{ p.message }}</p>
      <ProblemsPanel :problems="problems" @show="showProblem" />

      <NewVersionDialog
        v-if="showNewVersion"
        :table="draft" :file="file"
        @close="showNewVersion = false"
      />
    </template>
  </div>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 12px; }
.toolbar { gap: 8px; flex-wrap: wrap; }
.toolbar h1 { display: flex; align-items: center; gap: 8px; }
.file { font-size: 12px; }
.pad { padding: 16px; }
.meta { display: grid; grid-template-columns: 1.3fr 0.6fr 1fr 0.9fr 0.9fr 2.4fr; gap: 12px; padding: 12px 16px; }
.meta input, .meta select { width: 100%; }
.tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); }
.tab { border: none; border-radius: var(--radius) var(--radius) 0 0; background: transparent; padding: 8px 16px; color: var(--text-muted); font-weight: 500; border-bottom: 3px solid transparent; }
.tab[aria-selected='true'] { color: var(--text); border-bottom-color: var(--gold); background: var(--surface); }
.count { font-size: 11px; background: var(--surface-3); border-radius: 999px; padding: 0 6px; }
.panel { min-height: 200px; }
</style>
