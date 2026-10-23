<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// A type picker: a primitive from the server's list, a list of a type, or a structure of named fields.
import { computed } from 'vue'
import { t } from '../i18n'
import { type TypeNode, nameProblem } from '../model/schema'
import { meta } from '../store'

const props = withDefaults(defineProps<{ node: TypeNode; label: string; allowStruct?: boolean; depth?: number }>(), {
  allowStruct: true,
  depth: 0,
})
const emit = defineEmits<{ replace: [node: TypeNode] }>()

const primitives = computed(() => meta.value?.types.map((x) => x.name) ?? ['bool', 'int', 'string', 'decimal'])
const selected = computed(() => (props.node.kind === 'primitive' ? props.node.name : props.node.kind))

function typeLabel(name: string) {
  const key = `types.${name}`
  const text = t(key)
  return text === key ? name : text
}

function choose(value: string) {
  if (value === 'list') emit('replace', { kind: 'list', element: { kind: 'primitive', name: 'string' } })
  else if (value === 'struct') emit('replace', { kind: 'struct', fields: [{ name: 'field1', type: { kind: 'primitive', name: 'string' } }] })
  else emit('replace', { kind: 'primitive', name: value })
}

function fieldError(name: string, index: number): string | null {
  if (props.node.kind !== 'struct') return null
  const problem = nameProblem(name)
  if (problem === 'identifier') return t('schema.invalidName')
  if (problem === 'reserved') return t('schema.reservedName')
  if (props.node.fields.findIndex((f) => f.name === name) !== index) return t('schema.duplicateName')
  return null
}
</script>

<template>
  <div class="type-editor">
    <select :value="selected" :aria-label="label" @change="choose(($event.target as HTMLSelectElement).value)">
      <option v-for="p in primitives" :key="p" :value="p">{{ typeLabel(p) }}</option>
      <option value="list">{{ t('types.list') }}…</option>
      <option v-if="allowStruct" value="struct">{{ t('types.struct') }}…</option>
    </select>
    <span v-if="node.kind === 'list'" class="nested row">
      <span class="muted">{{ t('schema.elementType') }}</span>
      <TypeEditor
        :node="node.element" :label="`${label}: ${t('schema.elementType')}`" :allow-struct="allowStruct" :depth="depth + 1"
        @replace="(n) => { if (node.kind === 'list') node.element = n }"
      />
    </span>
    <div v-if="node.kind === 'struct'" class="fields" role="group" :aria-label="`${label}: ${t('schema.fields')}`">
      <div v-for="(field, i) in node.fields" :key="i" class="field-row">
        <input v-model="field.name" type="text" class="mono name" :aria-label="`${label}: ${t('schema.name')} ${i + 1}`" :class="{ invalid: !!fieldError(field.name, i) }">
        <TypeEditor
          :node="field.type" :label="`${label}.${field.name}`" :allow-struct="allowStruct" :depth="depth + 1"
          @replace="(n) => (field.type = n)"
        />
        <button type="button" class="ghost icon" :aria-label="`${t('common.remove')} ${field.name}`" :disabled="node.fields.length === 1" @click="node.fields.splice(i, 1)">×</button>
        <span v-if="fieldError(field.name, i)" class="error-text">{{ fieldError(field.name, i) }}</span>
      </div>
      <button type="button" class="small" @click="node.fields.push({ name: `field${node.fields.length + 1}`, type: { kind: 'primitive', name: 'string' } })">+ {{ t('schema.addField') }}</button>
    </div>
  </div>
</template>

<style scoped>
.type-editor { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.nested { gap: 6px; }
.fields { display: flex; flex-direction: column; gap: 6px; padding: 8px 10px; border-left: 3px solid var(--gold-soft); margin-left: 4px; background: var(--surface-2); border-radius: 0 var(--radius) var(--radius) 0; }
.field-row { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 6px; }
.field-row .error-text { flex-basis: 100%; }
.fields > button { align-self: flex-start; }
.name { width: 12em; }
</style>
