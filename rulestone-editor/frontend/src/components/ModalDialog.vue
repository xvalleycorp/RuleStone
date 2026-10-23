<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// A modal dialog on the native <dialog> element: focus stays inside, Escape closes it.
import { onMounted, ref } from 'vue'
import { t } from '../i18n'

withDefaults(defineProps<{ title: string; wide?: boolean }>(), { wide: false })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)

onMounted(() => dialog.value?.showModal())
</script>

<template>
  <dialog ref="dialog" :class="{ wide }" :aria-label="title" @cancel.prevent="emit('close')">
    <header class="row">
      <h2 class="grow">{{ title }}</h2>
      <button type="button" class="ghost icon" :aria-label="t('common.close')" @click="emit('close')">×</button>
    </header>
    <div class="body"><slot /></div>
    <footer class="row"><slot name="footer" /></footer>
  </dialog>
</template>

<style scoped>
dialog { border: 1px solid var(--border); border-radius: 10px; padding: 0; width: 560px; max-width: 95vw; box-shadow: 0 12px 48px rgba(0, 0, 0, 0.25); color: var(--text); }
dialog.wide { width: 980px; }
dialog::backdrop { background: rgba(36, 34, 31, 0.45); }
header { padding: 14px 18px; border-bottom: 1px solid var(--border); }
.body { padding: 16px 18px; display: flex; flex-direction: column; gap: 12px; max-height: 72vh; overflow: auto; }
footer { padding: 12px 18px; border-top: 1px solid var(--border); justify-content: flex-end; background: var(--surface-2); }
</style>
