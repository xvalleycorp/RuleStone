<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// "Save as new version": copies the rules (with unsaved changes) into a new file with the next version
// and a new effective date, optionally ending the current version's period on that date.
import { computed, ref } from 'vue'
import { ApiError, api } from '../api'
import { t } from '../i18n'
import { addDays, cleanTable, fileNameFor, nextVersion, today } from '../model/table'
import { dirty, openTable } from '../store'
import type { Problem, TableJson } from '../types'
import ModalDialog from './ModalDialog.vue'

const props = defineProps<{ table: TableJson; file: string; etag: string }>()
const emit = defineEmits<{ close: [] }>()

const version = ref(nextVersion(props.table.version))
const effectiveFrom = ref(
  props.table.effectiveFrom && props.table.effectiveFrom >= today() ? addDays(props.table.effectiveFrom, 1) : addDays(today(), 1),
)
const closePrevious = ref(!props.table.effectiveTo || props.table.effectiveTo > effectiveFrom.value)
const fileName = computed(() => {
  const dir = props.file.includes('/') ? props.file.slice(0, props.file.lastIndexOf('/') + 1) : ''
  return dir + fileNameFor(props.table.id, version.value)
})
const problems = ref<Problem[]>([])
const error = ref<string | null>(null)
const busy = ref(false)

async function create() {
  busy.value = true
  error.value = null
  problems.value = []
  const table = cleanTable(structuredClone(props.table))
  table.version = version.value
  table.effectiveFrom = effectiveFrom.value
  if (table.effectiveTo && table.effectiveTo <= effectiveFrom.value) delete table.effectiveTo
  try {
    const result = await api.create({
      file: fileName.value,
      table: cleanTable(table),
      ...(closePrevious.value ? { closePrevious: { file: props.file, etag: props.etag } } : {}),
    })
    dirty.value = false
    emit('close')
    openTable(result.file ?? fileName.value)
  } catch (e) {
    if (e instanceof ApiError) {
      error.value = e.message
      problems.value = e.problems
    } else {
      error.value = String(e)
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <ModalDialog :title="t('newVersion.title')" @close="emit('close')">
    <p class="help">{{ t('newVersion.intro') }}</p>
    <div class="row">
      <div class="field">
        <label for="nv-version">{{ t('newVersion.version') }}</label>
        <input id="nv-version" v-model="version" class="mono">
      </div>
      <div class="field">
        <label for="nv-from">{{ t('newVersion.effectiveFrom') }}</label>
        <input id="nv-from" v-model="effectiveFrom" type="date">
      </div>
    </div>
    <div class="field">
      <label>{{ t('wizard.file') }}</label>
      <code>{{ fileName }}</code>
    </div>
    <label class="check row">
      <input v-model="closePrevious" type="checkbox">
      {{ t('newVersion.closePrevious', { version: table.version, date: effectiveFrom }) }}
    </label>
    <p class="help">{{ t('newVersion.closePreviousHelp', { version: table.version }) }}</p>
    <p class="help">{{ t('newVersion.unsaved') }}</p>
    <div v-if="error" class="notice error">
      {{ error }}
      <ul v-if="problems.length"><li v-for="p in problems" :key="p.detail">{{ p.message }}</li></ul>
    </div>
    <template #footer>
      <button type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
      <button type="button" class="primary" :disabled="busy || !version || !effectiveFrom" @click="create">{{ t('common.create') }}</button>
    </template>
  </ModalDialog>
</template>

<style scoped>
.check { font-weight: 500; gap: 8px; }
</style>
