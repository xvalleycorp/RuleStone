<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// The file as it will be saved (read-only, with copy) and a line diff against the file as loaded.
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { diffLines, withContext } from '../model/diff'

const props = defineProps<{ formatted: string; saved: string }>()
const copied = ref(false)

const diff = computed(() => diffLines(props.saved, props.formatted))
const changed = computed(() => diff.value.some((l) => l.kind !== 'same'))
const shown = computed(() => withContext(diff.value, 3))
const counts = computed(() => ({
  added: diff.value.filter((l) => l.kind === 'added').length,
  removed: diff.value.filter((l) => l.kind === 'removed').length,
}))

async function copy() {
  await navigator.clipboard.writeText(props.formatted)
  copied.value = true
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="json">
    <section class="card">
      <header class="row">
        <h2 class="grow">JSON</h2>
        <span class="help">{{ t('json.intro') }}</span>
        <button type="button" class="small" @click="copy">{{ copied ? t('common.copied') : t('json.copy') }}</button>
      </header>
      <pre class="code" tabindex="0" aria-label="JSON">{{ formatted }}</pre>
    </section>
    <section class="card">
      <header class="row">
        <h2 class="grow">{{ t('json.diff') }}</h2>
        <span v-if="changed" class="help">{{ t('json.lines', counts) }}</span>
      </header>
      <p v-if="!changed" class="notice">{{ t('json.noChanges') }}</p>
      <pre v-else class="code diff" tabindex="0" :aria-label="t('json.diff')"><template v-for="(line, i) in shown" :key="i"><span v-if="line === null" class="gap">⋯
</span><span v-else :class="line.kind"><span class="ln">{{ line.oldLine ?? '' }}</span><span class="ln">{{ line.newLine ?? '' }}</span><span class="sign">{{ line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' ' }}</span>{{ line.text }}
</span></template></pre>
    </section>
  </div>
</template>

<style scoped>
.json { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
section { padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.code { margin: 0; padding: 10px 12px; background: var(--surface-2); border: 1px solid var(--border); border-radius: var(--radius); font-family: var(--mono); font-size: 12px; line-height: 1.5; overflow: auto; max-height: 68vh; white-space: pre; }
.diff .added { background: var(--ok-soft); display: block; }
.diff .removed { background: var(--error-soft); display: block; }
.diff .same { display: block; }
.ln { display: inline-block; width: 3em; color: var(--text-muted); text-align: right; padding-right: 6px; user-select: none; }
.sign { display: inline-block; width: 1.4em; font-weight: 700; user-select: none; }
.gap { display: block; color: var(--text-muted); text-align: center; }
</style>
