<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Wizard for a new decision table: id, version, hit policy and effective dates. Creates the file at once,
// with one catch-all rule, and opens it.
import { computed, ref } from 'vue'
import { ApiError, api } from '../api'
import { t } from '../i18n'
import { fileNameFor, newTable } from '../model/table'
import { meta, openTable } from '../store'
import ModalDialog from './ModalDialog.vue'

const emit = defineEmits<{ close: [] }>()

const id = ref('')
const version = ref('1')
const hitPolicy = ref('FIRST')
const effectiveFrom = ref('')
const effectiveTo = ref('')
const fileEdited = ref(false)
const customFile = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

const file = computed({
  get: () => (fileEdited.value ? customFile.value : id.value ? fileNameFor(id.value, version.value) : ''),
  set: (v: string) => {
    fileEdited.value = true
    customFile.value = v
  },
})
const idError = computed(() => (id.value && !/^[A-Za-z][A-Za-z0-9_.-]*$/.test(id.value) ? t('wizard.invalidId') : null))
const versionError = computed(() => (!/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(version.value) ? t('wizard.invalidVersion') : null))
const fileError = computed(() => (file.value && !/^([A-Za-z0-9][A-Za-z0-9_.-]*\/)*[A-Za-z0-9][A-Za-z0-9_.-]*\.stone\.json$/.test(file.value)
  ? t('wizard.invalidFile') : null))
const datesError = computed(() => (effectiveFrom.value && effectiveTo.value && effectiveTo.value <= effectiveFrom.value ? t('wizard.datesOrder') : null))
const ok = computed(() => id.value && !idError.value && !versionError.value && !fileError.value && !datesError.value)

async function create() {
  busy.value = true
  error.value = null
  try {
    const result = await api.create({
      file: file.value,
      table: newTable(id.value, version.value, hitPolicy.value, effectiveFrom.value || undefined, effectiveTo.value || undefined),
    })
    emit('close')
    openTable(result.file ?? file.value)
  } catch (e) {
    error.value = e instanceof ApiError
      ? [e.message, ...e.problems.map((p) => p.message)].join(' — ')
      : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <ModalDialog :title="t('wizard.title')" @close="emit('close')">
    <div class="field">
      <label for="nt-id">{{ t('wizard.id') }}</label>
      <input id="nt-id" v-model="id" class="mono" :class="{ invalid: idError }" autocomplete="off" placeholder="loan_eligibility">
      <span class="help">{{ t('wizard.idHelp') }}</span>
      <span v-if="idError" class="error-text">{{ idError }}</span>
    </div>
    <div class="grid">
      <div class="field">
        <label for="nt-version">{{ t('wizard.version') }}</label>
        <input id="nt-version" v-model="version" class="mono" :class="{ invalid: versionError }">
        <span v-if="versionError" class="error-text">{{ versionError }}</span>
      </div>
      <div class="field">
        <label for="nt-hit">{{ t('wizard.hitPolicy') }}</label>
        <select id="nt-hit" v-model="hitPolicy">
          <option v-for="h in meta?.hitPolicies ?? []" :key="h.name" :value="h.name">{{ t(`hitPolicies.${h.name}`) }} ({{ h.name }})</option>
        </select>
        <span class="help">{{ meta?.hitPolicies.find((h) => h.name === hitPolicy)?.description }}</span>
      </div>
      <div class="field">
        <label for="nt-from">{{ t('wizard.effectiveFrom') }} <span class="muted">({{ t('common.optional') }})</span></label>
        <input id="nt-from" v-model="effectiveFrom" type="date">
      </div>
      <div class="field">
        <label for="nt-to">{{ t('wizard.effectiveTo') }} <span class="muted">({{ t('common.optional') }})</span></label>
        <input id="nt-to" v-model="effectiveTo" type="date" :class="{ invalid: datesError }">
        <span v-if="datesError" class="error-text">{{ datesError }}</span>
      </div>
    </div>
    <div class="field">
      <label for="nt-file">{{ t('wizard.file') }}</label>
      <input id="nt-file" v-model="file" class="mono" :class="{ invalid: fileError }">
      <span v-if="fileError" class="error-text">{{ fileError }}</span>
    </div>
    <p v-if="error" class="notice error">{{ error }}</p>
    <template #footer>
      <button type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
      <button type="button" class="primary" :disabled="!ok || busy" @click="create">{{ t('common.create') }}</button>
    </template>
  </ModalDialog>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
</style>
