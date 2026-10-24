---
layout: page
title: Playground
description: Edit and run RuleStone decision tables in your browser with the embeddable editor components, build conditions, and design a decision flow.
---

<script setup>
import Playground from './.vitepress/playground/Playground.vue'
</script>

<div class="rs-playground-page">
<div class="vp-doc">

# Playground

Edit the example decision tables, run them on sample facts, build conditions with blocks and design a decision flow. These are the components of the [editor SDK](/docs/editor-sdk), which banks embed in their own web applications.

::: info Everything runs in your browser
- Nothing you do here is sent to a server. Tables, facts and results stay in this browser. A share link carries the table in the part of the address after `#`, which browsers do not send to the server.
- **Try it** runs the rules with the browser engine, which gives the same results as the Java engine on everything its conformance suite covers. The Java engine remains the engine of record: evaluate production decisions with it.
- **Decision flows are design-only for now.** The canvas checks and exports flows, but no engine runs them yet.
:::

</div>

<Playground />

<div class="vp-doc">

## Embed it

The same components work in any web application, with any framework or none. The packages are not on npm yet: [build them from the repository](/docs/editor-sdk#install-and-build) until they are published. Each snippet loads the table editor, gives it a table and saves every change.

::: code-group

```html [HTML]
<!-- With a bundler such as Vite, which resolves the package import. -->
<rulestone-table-editor locale="vi"></rulestone-table-editor>
<script type="module">
  import '@rulestone/editor/table-editor'

  const editor = document.querySelector('rulestone-table-editor')
  editor.table = await (await fetch('/rules/loan_eligibility.stone.json')).json()
  editor.addEventListener('rulestone-change', (e) => save(e.detail.table))
</script>
```

```tsx [React]
// React 19 sets properties and listens to events on custom elements.
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }) {
  return (
    <rulestone-table-editor
      table={table}
      locale="vi"
      onrulestone-change={(e) => onSave(e.detail.table)}
    />
  )
}
```

```ts [Angular]
import { CUSTOM_ELEMENTS_SCHEMA, Component, input } from '@angular/core'
import '@rulestone/editor/table-editor'

@Component({
  selector: 'app-rule-editor',
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `<rulestone-table-editor [table]="table()" locale="vi"
    (rulestone-change)="save($event.detail.table)"></rulestone-table-editor>`,
})
export class RuleEditorComponent {
  table = input.required<object>()
  save(table: object) { /* the host stores it */ }
}
```

```vue [Vue]
<script setup lang="ts">
import { onMounted } from 'vue'
const props = defineProps<{ table: object }>()
const emit = defineEmits<{ save: [table: object] }>()
// Client only: load the element after mounting (also under SSR, e.g. VitePress).
onMounted(() => import('@rulestone/editor/table-editor'))
</script>

<template>
  <rulestone-table-editor :table.prop="props.table" locale="vi"
    @rulestone-change="(e) => emit('save', e.detail.table)" />
</template>
```

:::

The [editor SDK guide](/docs/editor-sdk) describes every element with its properties, events and methods, server-side validation, theming, keyboard use, the Content Security Policy it needs and its bundle sizes.

</div>
</div>
