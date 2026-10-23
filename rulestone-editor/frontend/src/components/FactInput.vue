<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// One input of the test form, shaped by its type: a field, a fieldset of struct fields, or a list of items.
import { t } from '../i18n'
import { type FormValue, emptyValue } from '../model/facts'
import type { TypeSpec } from '../types'
import ValueInput from './ValueInput.vue'

defineProps<{ value: FormValue; type: TypeSpec; label: string; name: string }>()
</script>

<template>
  <fieldset v-if="value.kind === 'struct' && typeof type === 'object' && !Array.isArray(type)" class="struct">
    <legend>{{ name }}</legend>
    <div v-for="(fieldType, field) in type" :key="field" class="line">
      <FactInput :value="value.fields[field]" :type="fieldType" :label="`${label}.${field}`" :name="String(field)" />
    </div>
  </fieldset>
  <div v-else-if="value.kind === 'list' && Array.isArray(type)" class="list">
    <span class="name mono">{{ name }}</span>
    <div v-for="(item, i) in value.items" :key="i" class="item row">
      <FactInput :value="item" :type="type[0]" :label="`${label}[${i}]`" :name="`[${i}]`" />
      <button type="button" class="ghost icon" :aria-label="`${t('test.removeItem')} ${label}[${i}]`" @click="value.items.splice(i, 1)">×</button>
    </div>
    <button type="button" class="small" @click="value.items.push(emptyValue(type[0]))">+ {{ t('test.addItem') }}</button>
  </div>
  <div v-else-if="value.kind === 'scalar'" class="scalar row">
    <span class="name mono" aria-hidden="true">{{ name }}</span>
    <ValueInput v-model="value.text" :type="type" :label="label" allow-empty />
    <span class="muted type">{{ typeof type === 'string' ? type : '' }}</span>
  </div>
</template>

<style scoped>
.struct { border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 12px; margin: 0; display: flex; flex-direction: column; gap: 6px; }
legend { font-family: var(--mono); font-weight: 600; padding: 0 4px; }
.scalar .name { width: 11em; margin: 0; font-weight: 500; }
.type { font-size: 12px; }
.list { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.list .name { font-weight: 500; }
.item { align-items: flex-start; }
</style>
