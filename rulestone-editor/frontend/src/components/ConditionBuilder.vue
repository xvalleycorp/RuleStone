<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Edits a rule's `when` as a list of clauses (input, operator, typed value) joined by AND or OR.
// Expressions the builder cannot represent are shown read-only with an offer to edit them as CEL.
import { computed, reactive, ref } from 'vue'
import {
  BuildError, type Clause, type ClauseValue, type Condition, type Operator, buildCondition, defaultClause,
  operatorsFor, parseCondition, valueShape, valueType,
} from '../cel/builder'
import { codecFor } from '../cel/literals'
import { t } from '../i18n'
import { describe as describeType, inputPaths } from '../model/schema'
import { meta } from '../store'
import type { TypeSpec } from '../types'
import CelInput from './CelInput.vue'
import ModalDialog from './ModalDialog.vue'
import TagInput from './TagInput.vue'
import ValueInput from './ValueInput.vue'

const props = defineProps<{ when: string; ruleId: string; inputs: Record<string, TypeSpec> }>()
const emit = defineEmits<{ apply: [cel: string]; close: []; editRaw: [] }>()

const paths = computed(() => inputPaths(props.inputs, meta.value?.implicitInputs ?? []))
const parsed = parseCondition(props.when, paths.value)
const condition = reactive<Condition>(parsed.ok ? structuredClone(parsed.condition) : { combinator: 'and', clauses: [] })
const touched = ref(false)

const result = computed((): { cel: string } | { error: BuildError } => {
  try {
    return { cel: buildCondition(condition, paths.value) }
  } catch (e) {
    if (e instanceof BuildError) return { error: e }
    throw e
  }
})
const errorAt = (i: number) => {
  const r = result.value
  return 'error' in r && r.error.clauseIndex === i ? t(`cond.errors.${r.error.key}`) : null
}

function input(path: string) {
  return paths.value.find((p) => p.path === path)
}

function addClause() {
  const first = paths.value.find((p) => typeof p.type === 'string') ?? paths.value[0]
  if (first) condition.clauses.push(defaultClause(first))
}

function setPath(clause: Clause, path: string) {
  const i = input(path)
  if (!i) return
  const d = defaultClause(i)
  clause.path = d.path
  clause.op = d.op
  if (d.value) clause.value = d.value
  else delete clause.value
}

function setOp(clause: Clause, op: Operator) {
  const i = input(clause.path)
  clause.op = op
  const shape = valueShape(op)
  if (shape === 'none') {
    delete clause.value
  } else if (shape === 'list' && clause.value?.kind !== 'list' && clause.value?.kind !== 'path' && clause.value?.kind !== 'expr') {
    clause.value = { kind: 'list', items: [] }
  } else if (shape === 'list' && clause.value?.kind === 'literal') {
    clause.value = { kind: 'list', items: clause.value.text ? [clause.value.text] : [] }
  } else if (shape === 'single' && (!clause.value || clause.value.kind === 'list')) {
    const type = i ? valueType(i, op) : undefined
    clause.value = codecFor(type) ? { kind: 'literal', text: type === 'bool' ? 'true' : '' } : { kind: 'expr', cel: '' }
  }
}

function kindsFor(clause: Clause): ClauseValue['kind'][] {
  const i = input(clause.path)
  const type = i ? valueType(i, clause.op) : undefined
  const literal: ClauseValue['kind'] = valueShape(clause.op) === 'list' ? 'list' : 'literal'
  return codecFor(type) ? [literal, 'path', 'expr'] : ['path', 'expr']
}

function setKind(clause: Clause, kind: ClauseValue['kind']) {
  if (kind === 'literal') clause.value = { kind, text: '' }
  else if (kind === 'list') clause.value = { kind, items: [] }
  else if (kind === 'path') clause.value = { kind, path: '' }
  else clause.value = { kind: 'expr', cel: '' }
}

function comparablePaths(clause: Clause) {
  const i = input(clause.path)
  const type = i ? JSON.stringify(valueShape(clause.op) === 'list' ? [i.type] : valueType(i, clause.op)) : ''
  return paths.value.filter((p) => p.path !== clause.path && JSON.stringify(p.type) === type)
}

function kindLabel(kind: ClauseValue['kind']) {
  return t(`cond.kinds.${kind === 'list' ? 'literal' : kind}`)
}

function apply() {
  touched.value = true
  const r = result.value
  if ('cel' in r) emit('apply', r.cel)
}
</script>

<template>
  <ModalDialog :title="t('cond.title', { id: ruleId })" wide @close="emit('close')">
    <template v-if="!parsed.ok">
      <p class="notice warn">{{ t('cond.readOnly') }} {{ t(`cond.reasons.${parsed.reason}`) }}</p>
      <CelInput :model-value="when" :inputs="inputs" :label="t('cond.preview')" readonly />
    </template>
    <template v-else>
      <div class="row" role="radiogroup" :aria-label="t('cond.preview')">
        <button type="button" :aria-pressed="condition.combinator === 'and'" @click="condition.combinator = 'and'">{{ t('cond.all') }}</button>
        <button type="button" :aria-pressed="condition.combinator === 'or'" @click="condition.combinator = 'or'">{{ t('cond.any') }}</button>
      </div>

      <p v-if="condition.clauses.length === 0" class="notice">{{ t('cond.alwaysHelp') }}</p>

      <ol class="clauses">
        <li v-for="(clause, i) in condition.clauses" :key="i" class="clause">
          <span class="joiner" aria-hidden="true">{{ i === 0 ? '' : condition.combinator === 'and' ? 'AND' : 'OR' }}</span>
          <select :value="clause.path" :aria-label="`${t('cond.input')} ${i + 1}`" class="path" @change="setPath(clause, ($event.target as HTMLSelectElement).value)">
            <option v-for="p in paths" :key="p.path" :value="p.path">{{ p.path }} · {{ describeType(p.type) }}</option>
          </select>
          <select :value="clause.op" :aria-label="`${t('cond.operator')} ${i + 1}`" @change="setOp(clause, ($event.target as HTMLSelectElement).value as Operator)">
            <option v-for="op in operatorsFor(input(clause.path)!)" :key="op" :value="op">{{ t(`cond.ops.${op}`) }}</option>
          </select>

          <template v-if="clause.value">
            <select
              v-if="kindsFor(clause).length > 1"
              :value="clause.value.kind"
              :aria-label="`${t('cond.valueKind')} ${i + 1}`"
              class="kind"
              @change="setKind(clause, ($event.target as HTMLSelectElement).value as ClauseValue['kind'])"
            >
              <option v-for="k in kindsFor(clause)" :key="k" :value="k">{{ kindLabel(k) }}</option>
            </select>
            <ValueInput
              v-if="clause.value.kind === 'literal'"
              v-model="clause.value.text"
              :type="valueType(input(clause.path)!, clause.op)"
              :label="`${t('cond.value')} ${i + 1}`"
              :invalid="!!errorAt(i)"
            />
            <TagInput v-else-if="clause.value.kind === 'list'" v-model="clause.value.items" :label="`${t('cond.value')} ${i + 1}`" :invalid="!!errorAt(i)" />
            <select v-else-if="clause.value.kind === 'path'" v-model="clause.value.path" :aria-label="`${t('cond.value')} ${i + 1}`" :class="{ invalid: !!errorAt(i) }">
              <option value="" disabled>—</option>
              <option v-for="p in comparablePaths(clause)" :key="p.path" :value="p.path">{{ p.path }}</option>
            </select>
            <CelInput v-else v-model="clause.value.cel" :inputs="inputs" :label="`${t('cond.value')} ${i + 1}`" class="expr" :invalid="!!errorAt(i)" />
          </template>

          <button type="button" class="ghost icon" :aria-label="`${t('common.remove')} ${i + 1}`" @click="condition.clauses.splice(i, 1)">×</button>
          <span v-if="errorAt(i) && (touched || clause.value)" class="error-text full">{{ errorAt(i) }}</span>
        </li>
      </ol>
      <div><button type="button" :disabled="paths.length === 0" @click="addClause">+ {{ t('cond.addClause') }}</button></div>

      <div class="field">
        <h3 id="cond-preview">{{ t('cond.preview') }}</h3>
        <code class="preview" aria-labelledby="cond-preview" aria-live="polite">{{ 'cel' in result ? result.cel : '…' }}</code>
      </div>
    </template>

    <template #footer>
      <button type="button" class="ghost" @click="emit('editRaw')">{{ t('cond.editRaw') }}</button>
      <span class="grow" />
      <button type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
      <button v-if="parsed.ok" type="button" class="primary" :disabled="'error' in result" @click="apply">{{ t('common.apply') }}</button>
    </template>
  </ModalDialog>
</template>

<style scoped>
.clauses { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.clause { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-2); }
.joiner { width: 2.6em; font-size: 11px; font-weight: 700; color: var(--gold-strong); }
.path { max-width: 22em; }
.expr { min-width: 18em; flex: 1; }
.full { flex-basis: 100%; padding-left: 3em; }
.preview { display: block; padding: 8px 10px; background: var(--surface-2); border: 1px solid var(--border); border-radius: var(--radius); white-space: pre-wrap; word-break: break-word; }
</style>
