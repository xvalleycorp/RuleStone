<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// A list of values as removable tags; type a value and press Enter (or comma) to add it.
import { ref } from 'vue'
import { t } from '../i18n'

const props = withDefaults(defineProps<{ modelValue: string[]; label: string; invalid?: boolean }>(), { invalid: false })
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const draft = ref('')

function add() {
  const v = draft.value.trim()
  if (v) emit('update:modelValue', [...props.modelValue, v])
  draft.value = ''
}
function remove(i: number) {
  emit('update:modelValue', props.modelValue.filter((_, k) => k !== i))
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault()
    add()
  } else if (e.key === 'Backspace' && draft.value === '' && props.modelValue.length > 0) {
    remove(props.modelValue.length - 1)
  }
}
</script>

<template>
  <div class="tags" :class="{ invalid }" role="group" :aria-label="label">
    <span v-for="(tag, i) in modelValue" :key="i" class="tag">
      {{ tag }}
      <button type="button" class="ghost icon small" :aria-label="t('value.removeTag', { value: tag })" @click="remove(i)">×</button>
    </span>
    <input v-model="draft" type="text" :aria-label="`${label}: ${t('value.addTag')}`" :placeholder="t('value.addTag')" @keydown="onKey" @blur="add">
  </div>
</template>

<style scoped>
.tags { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 4px; padding: 3px; border: 1px solid var(--border-strong); border-radius: var(--radius); background: var(--surface); min-width: 16em; }
.tag { display: inline-flex; align-items: center; gap: 2px; padding: 0 0 0 8px; background: var(--gold-soft); color: var(--gold-strong); border-radius: 4px; font-size: 13px; }
.tag button { padding: 0 5px; min-width: 0; color: inherit; }
input { border: none; flex: 1; min-width: 10em; padding: 3px 4px; }
input:focus { outline: none; }
</style>
