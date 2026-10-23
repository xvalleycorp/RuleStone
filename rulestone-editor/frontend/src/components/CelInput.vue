<!-- Copyright 2026 The RuleStone Authors. SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// A CEL expression field: CodeMirror with highlighting, autocomplete of inputs and functions,
// and the position of a validation error underlined.
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { syntaxHighlighting } from '@codemirror/language'
import { Compartment, EditorState } from '@codemirror/state'
import { Decoration, EditorView, keymap, placeholder as placeholderExt } from '@codemirror/view'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { celCompletions, celHighlight, celLanguage } from '../cel/language'
import { meta } from '../store'
import type { TypeSpec } from '../types'

const props = withDefaults(defineProps<{
  modelValue: string
  inputs: Record<string, TypeSpec>
  label: string
  readonly?: boolean
  placeholder?: string
  invalid?: boolean
  /** One-based line and column of an error to underline. */
  mark?: { line?: number; column?: number } | null
}>(), { readonly: false, placeholder: '', invalid: false, mark: null })

const emit = defineEmits<{ 'update:modelValue': [value: string]; escape: []; blur: [] }>()

const host = ref<HTMLElement | null>(null)
let view: EditorView | null = null
const readOnly = new Compartment()
const marks = new Compartment()

function markExtension() {
  const m = props.mark
  if (!view || !m?.line || !m.column) return []
  const doc = view.state.doc
  if (m.line > doc.lines) return []
  const line = doc.line(m.line)
  const from = Math.min(line.from + m.column - 1, line.to)
  const rest = doc.sliceString(from, line.to)
  const length = Math.max(1, /^[A-Za-z0-9_.']+/.exec(rest)?.[0].length ?? 1)
  const to = Math.min(from + length, line.to)
  if (to <= from) return []
  return EditorView.decorations.of(Decoration.set([Decoration.mark({ class: 'cm-problem-mark' }).range(from, to)]))
}

onMounted(() => {
  view = new EditorView({
    parent: host.value!,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        history(),
        closeBrackets(),
        celLanguage,
        syntaxHighlighting(celHighlight),
        autocompletion({
          override: [celCompletions(() => ({
            inputs: props.inputs,
            implicitInputs: meta.value?.implicitInputs ?? [],
            functions: meta.value?.functions ?? [],
          }))],
        }),
        keymap.of([
          ...closeBracketsKeymap,
          ...completionKeymap,
          { key: 'Escape', run: () => { emit('escape'); return true } },
          ...defaultKeymap,
          ...historyKeymap,
        ]),
        EditorView.lineWrapping,
        placeholderExt(props.placeholder),
        EditorView.contentAttributes.of({ 'aria-label': props.label, spellcheck: 'false' }),
        readOnly.of(EditorState.readOnly.of(props.readonly)),
        marks.of([]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
          if (update.focusChanged && !update.view.hasFocus) emit('blur')
        }),
      ],
    }),
  })
  view.dispatch({ effects: marks.reconfigure(markExtension()) })
})

watch(() => props.modelValue, (value) => {
  if (view && value !== view.state.doc.toString()) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
  }
})
watch(() => props.readonly, (value) => view?.dispatch({ effects: readOnly.reconfigure(EditorState.readOnly.of(value)) }))
watch(() => [props.mark?.line, props.mark?.column, props.modelValue], () => {
  view?.dispatch({ effects: marks.reconfigure(markExtension()) })
})

onBeforeUnmount(() => view?.destroy())

defineExpose({ focus: () => view?.focus() })
</script>

<template>
  <div ref="host" class="cel" :class="{ readonly, invalid }" />
</template>
