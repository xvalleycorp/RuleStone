<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// The rules of a table: one row per rule, one column per output. Keyboard: arrow keys move between
// cells, Enter edits the cell, Escape returns to the grid, Alt+Up/Down moves the rule.
import { computed, nextTick, ref } from 'vue'
import { type Condition, parseCondition } from '../cel/builder'
import { t } from '../i18n'
import { inputPaths } from '../model/schema'
import { meta } from '../store'
import type { Problem, TableJson, TypeSpec } from '../types'
import CelInput from './CelInput.vue'
import ConditionBuilder from './ConditionBuilder.vue'
import OutputCell from './OutputCell.vue'

const props = defineProps<{
  table: TableJson
  rowKeys: number[]
  problems: Map<string, Problem[]>
  /** Test result: rule id -> matched / not matched; rules absent were not evaluated. */
  trace: Map<string, boolean> | null
}>()
const emit = defineEmits<{
  add: []
  duplicate: [index: number]
  remove: [index: number]
  move: [from: number, to: number]
  clearTrace: []
}>()

const outputs = computed(() => Object.entries(props.table.outputs) as [string, TypeSpec][])
const paths = computed(() => inputPaths(props.table.inputs, meta.value?.implicitInputs ?? []))
const columns = computed(() => 4 + outputs.value.length + 1)

// ---- condition display ----------------------------------------------------------------------------------------
const rawMode = ref(new Set<number>())
const builderFor = ref<number | null>(null)

function condition(when: string): Condition | null {
  const r = parseCondition(when, paths.value)
  return r.ok ? r.condition : null
}
function clauseText(c: Condition['clauses'][number]): string {
  const op = t(`cond.ops.${c.op}`)
  const v = c.value
  const value = !v ? '' : v.kind === 'literal' ? v.text : v.kind === 'list' ? `[${v.items.join(', ')}]` : v.kind === 'path' ? v.path : v.cel
  return `${c.path} ${op} ${value}`.trim()
}
function showRaw(key: number, when: string) {
  return rawMode.value.has(key) || condition(when) === null
}
function toggleRaw(key: number) {
  const s = new Set(rawMode.value)
  if (s.has(key)) s.delete(key)
  else s.add(key)
  rawMode.value = s
}

// ---- problems -------------------------------------------------------------------------------------------------
function cellProblems(index: number, field: string, output?: string): Problem[] {
  return props.problems.get(output ? `rule:${index}:then:${output}` : `rule:${index}:${field}`) ?? []
}
function rowProblems(index: number): Problem[] {
  return props.problems.get(`rule:${index}:row`) ?? []
}
function markOf(list: Problem[]) {
  return list.find((p) => p.location.line)?.location ?? null
}

// ---- rows -----------------------------------------------------------------------------------------------------
function rowClass(id: string) {
  if (!props.trace) return ''
  if (!props.trace.has(id)) return 'not-evaluated'
  return props.trace.get(id) ? 'matched' : 'not-matched'
}
function setThen(index: number, output: string, value: string | undefined) {
  const rule = props.table.rules[index]
  const then = { ...(rule.then ?? {}) }
  if (value === undefined) delete then[output]
  else then[output] = value
  rule.then = then
}
function confirmRemove(index: number) {
  if (confirm(t('grid.deleteConfirm', { id: props.table.rules[index].id }))) emit('remove', index)
}

// ---- drag and drop --------------------------------------------------------------------------------------------
const dragFrom = ref<number | null>(null)
const dropAt = ref<number | null>(null)
function onDrop(index: number) {
  if (dragFrom.value !== null && dragFrom.value !== index) emit('move', dragFrom.value, index)
  dragFrom.value = null
  dropAt.value = null
}

// ---- keyboard navigation (roving tabindex over cells) ---------------------------------------------------------
const active = ref({ r: 0, c: 1 })
const grid = ref<HTMLTableElement | null>(null)

function cell(r: number, c: number): HTMLElement | null {
  return grid.value?.querySelector(`td[data-r="${r}"][data-c="${c}"]`) ?? null
}
function focusCell(r: number, c: number) {
  const rows = props.table.rules.length
  if (rows === 0) return
  const rr = Math.max(0, Math.min(rows - 1, r))
  const cc = Math.max(0, Math.min(columns.value, c))
  active.value = { r: rr, c: cc }
  nextTick(() => cell(rr, cc)?.focus())
}
function onCellKey(e: KeyboardEvent, r: number, c: number) {
  const target = e.target as HTMLElement
  const onCell = target.tagName === 'TD'
  if (!onCell) {
    if (e.key === 'Escape') {
      e.preventDefault()
      focusCell(r, c)
    }
    return
  }
  const moves: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }
  if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
    e.preventDefault()
    const to = r + (e.key === 'ArrowUp' ? -1 : 1)
    if (to >= 0 && to < props.table.rules.length) {
      emit('move', r, to)
      focusCell(to, c)
    }
  } else if (moves[e.key]) {
    e.preventDefault()
    focusCell(r + moves[e.key][0], c + moves[e.key][1])
  } else if (e.key === 'Home') {
    e.preventDefault()
    focusCell(r, 0)
  } else if (e.key === 'End') {
    e.preventDefault()
    focusCell(r, columns.value)
  } else if (e.key === 'Enter' || e.key === 'F2') {
    e.preventDefault()
    const editable = target.querySelector<HTMLElement>('input, select, textarea, .cm-content, button')
    editable?.focus()
  }
}
function onCellFocus(r: number, c: number) {
  active.value = { r, c }
}
const tab = (r: number, c: number) => (active.value.r === r && active.value.c === c ? 0 : -1)
</script>

<template>
  <div class="grid-wrap">
    <p class="help">{{ t('grid.keyboardHelp') }}</p>
    <p v-if="outputs.length === 0" class="notice">{{ t('grid.noOutputs') }}</p>
    <div v-if="trace" class="row trace-bar">
      <span class="badge ok">■ {{ t('grid.matched') }}</span>
      <span class="badge">■ {{ t('grid.notMatched') }}</span>
      <span class="badge muted-badge">■ {{ t('grid.notEvaluated') }}</span>
      <button type="button" class="small" @click="emit('clearTrace')">{{ t('grid.clearResult') }}</button>
    </div>
    <table ref="grid" class="rules" role="grid" :aria-label="t('grid.caption')" :aria-rowcount="table.rules.length + 1">
      <thead>
        <tr>
          <th class="handle-col" scope="col"><span class="sr-only">#</span></th>
          <th class="id-col" scope="col">{{ t('grid.id') }}</th>
          <th class="desc-col" scope="col">{{ t('grid.description') }}</th>
          <th class="when-col" scope="col">{{ t('grid.when') }}</th>
          <th v-for="[name, type] in outputs" :key="name" scope="col" class="out-col">
            {{ name }} <span class="type">{{ Array.isArray(type) ? `list<${type[0]}>` : type }}</span>
          </th>
          <th class="reason-col" scope="col">{{ t('grid.reasonCode') }}</th>
          <th class="actions-col" scope="col">{{ t('grid.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(rule, r) in table.rules"
          :key="rowKeys[r]"
          :class="[rowClass(rule.id), { 'drop-target': dropAt === r, 'has-problem': rowProblems(r).length > 0 }]"
          @dragover.prevent="dropAt = r"
          @dragleave="dropAt = null"
          @drop.prevent="onDrop(r)"
        >
          <td
            :data-r="r" data-c="0" :tabindex="tab(r, 0)" class="handle"
            draggable="true"
            :aria-label="t('grid.drag', { id: rule.id })"
            @dragstart="dragFrom = r"
            @keydown="onCellKey($event, r, 0)" @focus="onCellFocus(r, 0)"
          >
            <span aria-hidden="true">⠿</span><span class="num">{{ r + 1 }}</span>
            <span v-if="trace" class="trace-dot" :title="rowClass(rule.id)" />
          </td>
          <td :data-r="r" data-c="1" :tabindex="tab(r, 1)" @keydown="onCellKey($event, r, 1)" @focus="onCellFocus(r, 1)">
            <input
              v-model="rule.id" type="text" class="id" :aria-label="`${t('grid.id')} ${r + 1}`" tabindex="-1"
              :class="{ invalid: cellProblems(r, 'id').length > 0 || rowProblems(r).length > 0 }"
            >
            <span v-for="p in [...cellProblems(r, 'id'), ...rowProblems(r)]" :key="p.detail" class="error-text">{{ p.message }}</span>
          </td>
          <td :data-r="r" data-c="2" :tabindex="tab(r, 2)" @keydown="onCellKey($event, r, 2)" @focus="onCellFocus(r, 2)">
            <textarea
              :value="rule.description ?? ''" rows="2" class="desc" :aria-label="`${t('grid.description')} ${rule.id}`" tabindex="-1"
              @input="rule.description = ($event.target as HTMLTextAreaElement).value"
            />
          </td>
          <td :data-r="r" data-c="3" :tabindex="tab(r, 3)" class="when" @keydown="onCellKey($event, r, 3)" @focus="onCellFocus(r, 3)">
            <div class="when-tools row">
              <button type="button" class="small" tabindex="-1" @click="builderFor = r">{{ t('cond.builder') }}…</button>
              <button
                v-if="condition(rule.when) !== null" type="button" class="ghost small" tabindex="-1"
                :aria-pressed="showRaw(rowKeys[r], rule.when)" @click="toggleRaw(rowKeys[r])"
              >{{ t('cond.raw') }}</button>
            </div>
            <CelInput
              v-if="showRaw(rowKeys[r], rule.when)"
              v-model="rule.when"
              :inputs="table.inputs"
              :label="`${t('grid.when')} ${rule.id}`"
              :invalid="cellProblems(r, 'when').length > 0"
              :mark="markOf(cellProblems(r, 'when'))"
              @escape="focusCell(r, 3)"
            />
            <button v-else type="button" class="summary" tabindex="-1" :aria-label="`${t('cond.edit')}: ${rule.id}`" @click="builderFor = r">
              <template v-if="condition(rule.when)!.clauses.length === 0"><span class="badge gold">{{ t('cond.always') }}</span></template>
              <template v-for="(c, k) in condition(rule.when)!.clauses" :key="k">
                <span v-if="k > 0" class="joiner">{{ condition(rule.when)!.combinator === 'and' ? 'AND' : 'OR' }}</span>
                <span class="chip">{{ clauseText(c) }}</span>
              </template>
            </button>
            <span v-for="p in cellProblems(r, 'when')" :key="p.detail" class="error-text" :title="p.detail">{{ p.message }}</span>
          </td>
          <td
            v-for="([name, type], k) in outputs" :key="name"
            :data-r="r" :data-c="4 + k" :tabindex="tab(r, 4 + k)"
            @keydown="onCellKey($event, r, 4 + k)" @focus="onCellFocus(r, 4 + k)"
          >
            <OutputCell
              :model-value="rule.then?.[name]"
              :type="type"
              :inputs="table.inputs"
              :label="`${name} ${rule.id}`"
              :invalid="cellProblems(r, 'then', name).length > 0"
              :mark="markOf(cellProblems(r, 'then', name))"
              @update:model-value="setThen(r, name, $event)"
              @escape="focusCell(r, 4 + k)"
            />
            <span v-for="p in cellProblems(r, 'then', name)" :key="p.detail" class="error-text" :title="p.detail">{{ p.message }}</span>
          </td>
          <td
            :data-r="r" :data-c="4 + outputs.length" :tabindex="tab(r, 4 + outputs.length)"
            @keydown="onCellKey($event, r, 4 + outputs.length)" @focus="onCellFocus(r, 4 + outputs.length)"
          >
            <input
              :value="rule.reasonCode ?? ''" type="text" class="reason mono" tabindex="-1"
              :aria-label="`${t('grid.reasonCode')} ${rule.id}`"
              :class="{ invalid: cellProblems(r, 'reasonCode').length > 0 }"
              placeholder="—"
              @input="rule.reasonCode = ($event.target as HTMLInputElement).value.toUpperCase() || undefined"
            >
            <span v-for="p in cellProblems(r, 'reasonCode')" :key="p.detail" class="error-text">{{ p.message }}</span>
          </td>
          <td
            :data-r="r" :data-c="5 + outputs.length" :tabindex="tab(r, 5 + outputs.length)" class="actions"
            @keydown="onCellKey($event, r, 5 + outputs.length)" @focus="onCellFocus(r, 5 + outputs.length)"
          >
            <button type="button" class="ghost icon" tabindex="-1" :disabled="r === 0" :aria-label="`${t('common.moveUp')} ${rule.id}`" :title="t('common.moveUp')" @click="emit('move', r, r - 1)">↑</button>
            <button type="button" class="ghost icon" tabindex="-1" :disabled="r === table.rules.length - 1" :aria-label="`${t('common.moveDown')} ${rule.id}`" :title="t('common.moveDown')" @click="emit('move', r, r + 1)">↓</button>
            <button type="button" class="ghost icon" tabindex="-1" :aria-label="`${t('common.duplicate')} ${rule.id}`" :title="t('common.duplicate')" @click="emit('duplicate', r)">⧉</button>
            <button type="button" class="ghost icon danger" tabindex="-1" :disabled="table.rules.length === 1" :aria-label="`${t('common.delete')} ${rule.id}`" :title="t('common.delete')" @click="confirmRemove(r)">🗑</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="add"><button type="button" @click="emit('add')">+ {{ t('grid.addRule') }}</button></div>

    <ConditionBuilder
      v-if="builderFor !== null && table.rules[builderFor]"
      :when="table.rules[builderFor].when"
      :rule-id="table.rules[builderFor].id"
      :inputs="table.inputs"
      @close="builderFor = null"
      @apply="(cel) => { table.rules[builderFor!].when = cel; builderFor = null }"
      @edit-raw="() => { const k = rowKeys[builderFor!]; builderFor = null; if (!rawMode.has(k)) toggleRaw(k) }"
    />
  </div>
</template>

<style scoped>
.grid-wrap { display: flex; flex-direction: column; gap: 8px; }
table.rules { width: 100%; border-collapse: separate; border-spacing: 0; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
th { position: sticky; top: 0; z-index: 1; text-align: left; font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.03em; padding: 8px; background: var(--surface-2); border-bottom: 1px solid var(--border); white-space: nowrap; }
th .type { text-transform: none; font-weight: 400; font-family: var(--mono); color: var(--gold-strong); letter-spacing: 0; }
td { padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: top; }
td:focus { outline: 2px solid var(--focus); outline-offset: -2px; }
tr:last-child td { border-bottom: none; }
tr:hover td { background: #fbfaf8; }
.handle-col { width: 54px; }
.id-col { width: 90px; }
.desc-col { width: 190px; }
.when-col { min-width: 300px; }
.out-col { min-width: 160px; }
.reason-col { width: 150px; }
.actions-col { width: 140px; }
.handle { cursor: grab; color: var(--text-muted); white-space: nowrap; user-select: none; }
.handle .num { margin-left: 6px; font-variant-numeric: tabular-nums; }
input.id { width: 100%; font-weight: 600; }
textarea.desc { width: 100%; resize: vertical; font-family: inherit; font-size: 13px; }
input.reason { width: 100%; }
.when-tools { justify-content: flex-end; margin-bottom: 4px; gap: 4px; }
.summary { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; width: 100%; text-align: left; white-space: normal; padding: 6px; background: var(--surface); border: 1px dashed var(--border-strong); }
.chip { font-family: var(--mono); font-size: 12px; background: var(--surface-2); border: 1px solid var(--border); border-radius: 4px; padding: 1px 6px; }
.joiner { font-size: 11px; font-weight: 700; color: var(--gold-strong); }
.actions { white-space: nowrap; }
.error-text { display: block; margin-top: 3px; }
tr.matched td { background: var(--ok-soft); }
tr.matched td.handle { box-shadow: inset 4px 0 0 var(--ok); }
tr.not-matched td { background: var(--surface); }
tr.not-evaluated td { opacity: 0.55; }
tr.drop-target td { border-top: 2px solid var(--gold); }
tr.has-problem td.handle { box-shadow: inset 4px 0 0 var(--error); }
.trace-bar { gap: 6px; }
.muted-badge { opacity: 0.6; }
.add { padding-top: 2px; }
</style>
