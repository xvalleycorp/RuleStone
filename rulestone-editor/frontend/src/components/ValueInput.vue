<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// One typed value as the user types it: "18", "0.5", "RETAIL", "1500000 VND", "2026-11-01", "true".
import { computed } from 'vue'
import { t } from '../i18n'
import type { TypeSpec } from '../types'

const props = withDefaults(defineProps<{
  modelValue: string
  type: TypeSpec | undefined
  label: string
  invalid?: boolean
  /** For optional values (test form): an empty choice for booleans. */
  allowEmpty?: boolean
}>(), { invalid: false, allowEmpty: false })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const kind = computed(() => (typeof props.type === 'string' ? props.type : 'other'))
const value = computed({ get: () => props.modelValue, set: (v: string) => emit('update:modelValue', v) })

const amount = computed({
  get: () => props.modelValue.split(' ')[0] ?? '',
  set: (v: string) => emit('update:modelValue', `${v.trim()} ${currency.value}`.trim()),
})
const currency = computed({
  get: () => props.modelValue.split(' ')[1] ?? '',
  set: (v: string) => emit('update:modelValue', `${amount.value} ${v.trim().toUpperCase()}`.trim()),
})
</script>

<template>
  <select v-if="kind === 'bool'" v-model="value" :aria-label="label" :class="{ invalid }">
    <option v-if="allowEmpty" value="">—</option>
    <option value="true">{{ t('value.true') }}</option>
    <option value="false">{{ t('value.false') }}</option>
  </select>
  <input
    v-else-if="kind === 'int' || kind === 'decimal'"
    v-model="value"
    type="text"
    :inputmode="kind === 'int' ? 'numeric' : 'decimal'"
    :aria-label="label"
    :class="{ invalid }"
    class="number"
    :placeholder="kind === 'int' ? '0' : '0.00'"
  >
  <span v-else-if="kind === 'money'" class="money">
    <input v-model="amount" type="text" inputmode="decimal" :aria-label="`${label}: ${t('value.amount')}`" :class="{ invalid }" class="number" placeholder="1500000">
    <input v-model="currency" type="text" maxlength="3" :aria-label="`${label}: ${t('value.currency')}`" :class="{ invalid }" class="currency" placeholder="VND">
  </span>
  <input v-else-if="kind === 'date'" v-model="value" type="date" :aria-label="label" :class="{ invalid }">
  <input v-else v-model="value" type="text" :aria-label="label" :class="{ invalid }">
</template>

<style scoped>
.number { text-align: right; font-variant-numeric: tabular-nums; width: 9em; }
.money { display: inline-flex; gap: 4px; }
.currency { width: 4.5em; text-transform: uppercase; }
</style>
