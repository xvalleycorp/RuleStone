<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// One `then` cell: a typed value widget for literals, or a CEL expression. Empty = the rule does not set it.
import { computed, ref, watch } from 'vue'
import { type OutputValue, codecFor, encodeOutput, parseOutput } from '../cel/literals'
import { t } from '../i18n'
import type { TypeSpec } from '../types'
import CelInput from './CelInput.vue'
import TagInput from './TagInput.vue'
import ValueInput from './ValueInput.vue'

const props = withDefaults(defineProps<{
  modelValue: string | undefined
  type: TypeSpec
  inputs: Record<string, TypeSpec>
  label: string
  invalid?: boolean
  mark?: { line?: number; column?: number } | null
}>(), { invalid: false, mark: null })
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined]; escape: [] }>()

const literalPossible = computed(() => !!codecFor(props.type) || (Array.isArray(props.type) && !!codecFor(props.type[0])))
const state = ref<OutputValue>(parseOutput(props.modelValue, props.type))
const exprMode = ref(state.value.mode === 'expr' || !literalPossible.value)
const localError = ref<string | null>(null)

watch(() => [props.modelValue, props.type], () => {
  const current = encodeOutput(state.value, props.type)
  if (!('cel' in current) || current.cel !== props.modelValue) {
    state.value = parseOutput(props.modelValue, props.type)
    exprMode.value = state.value.mode === 'expr' || !literalPossible.value
    localError.value = null
  }
})

const text = computed({
  get: () => (state.value.mode === 'literal' ? state.value.text : ''),
  set: (v: string) => update(v === '' ? { mode: 'empty' } : { mode: 'literal', text: v }),
})
const items = computed({
  get: () => (state.value.mode === 'list' ? state.value.items : []),
  set: (v: string[]) => update({ mode: 'list', items: v }),
})
const cel = computed({
  get: () => (state.value.mode === 'expr' ? state.value.cel : props.modelValue ?? ''),
  set: (v: string) => update(v.trim() === '' ? { mode: 'empty' } : { mode: 'expr', cel: v }),
})

function update(value: OutputValue) {
  state.value = value
  const encoded = encodeOutput(value, props.type)
  if ('error' in encoded) {
    localError.value = t(`cond.errors.invalid.${encoded.error}`)
    return
  }
  localError.value = null
  emit('update:modelValue', encoded.cel)
}

function toggle() {
  if (exprMode.value) {
    const parsed = parseOutput(props.modelValue, props.type)
    if (parsed.mode === 'expr') return
    state.value = parsed
    exprMode.value = false
  } else {
    state.value = props.modelValue ? { mode: 'expr', cel: props.modelValue } : { mode: 'empty' }
    exprMode.value = true
  }
}
const canToggleToValue = computed(() => literalPossible.value && parseOutput(props.modelValue, props.type).mode !== 'expr')
</script>

<template>
  <div class="output">
    <CelInput
      v-if="exprMode"
      v-model="cel"
      :inputs="inputs"
      :label="label"
      :invalid="invalid"
      :mark="mark"
      :placeholder="t('output.notSet')"
      @escape="emit('escape')"
    />
    <TagInput v-else-if="Array.isArray(type)" v-model="items" :label="label" :invalid="invalid || !!localError" />
    <ValueInput v-else v-model="text" :type="type" :label="label" :invalid="invalid || !!localError" allow-empty />
    <button
      v-if="literalPossible"
      type="button"
      class="ghost icon small mode"
      :disabled="exprMode && !canToggleToValue"
      :title="exprMode ? t('output.toValue') : t('output.toExpr')"
      :aria-label="`${label}: ${exprMode ? t('output.toValue') : t('output.toExpr')}`"
      @click="toggle"
    >{{ exprMode ? 'abc' : 'ƒx' }}</button>
    <span v-if="localError" class="error-text">{{ localError }}</span>
  </div>
</template>

<style scoped>
.output { display: flex; align-items: flex-start; gap: 2px; flex-wrap: wrap; }
.output > :first-child { flex: 1; min-width: 7em; }
.mode { font-style: italic; color: var(--text-muted); font-size: 11.5px; }
.error-text { flex-basis: 100%; }
</style>
