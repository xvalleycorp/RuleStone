# Editor SDK

The editor SDK puts RuleStone's visual editors inside a bank's own web applications. Analysts build decision tables, rules and conditions with a grid and drag-and-drop blocks instead of typing JSON, see every problem on the cell while they type, and try the rules on sample facts, all in the browser. The bank's application keeps control: it loads and saves the tables, and it can add checks from its own RuleStone service.

Try the components in the [playground](https://rulestone.dxa.io.vn/playground) on the website.

- [What the SDK is](#what-the-sdk-is)
- [Install and build](#install-and-build)
- [Use it in your application](#use-it-in-your-application)
- [Elements](#elements)
- [Server-side checks: the validator hook](#server-side-checks-the-validator-hook)
- [Persistence](#persistence)
- [Theming](#theming)
- [Languages](#languages)
- [Accessibility and keyboard](#accessibility-and-keyboard)
- [Content Security Policy](#content-security-policy)
- [Bundle sizes](#bundle-sizes)
- [The browser engine and the Java engine](#the-browser-engine-and-the-java-engine)
- [Limitations](#limitations)

## What the SDK is

The SDK is two npm packages in the [`sdk/`](../sdk/) folder of the repository:

| Package | Content |
|---|---|
| `@rulestone/engine` | The rules engine in TypeScript: the strict `.stone.json` reader, type-checked CEL with exact `decimal`, `money` and `date` types, versioned evaluation and decision records. It has no runtime dependencies. |
| `@rulestone/editor` | The editors as Web Components built with [Lit](https://lit.dev): the table editor, rules grid, condition builder, schema designer, Try it panel, CEL input and the decision-flow canvas. It uses the engine for type checks and Try it. |

Web Components work in every framework (React, Angular, Vue) and without one. The elements make no network calls: the host page gives them data through properties and receives every change through events.

The SDK is for embedding. The [web editor](editor.md) (`rulestone-editor`) is a separate, complete application with its own Java server that edits the `.stone.json` files of a directory. Use the web editor to edit a rules repository directly; use the SDK to put editing into an existing system, such as a loan origination or product configuration application, with its own storage, sign-in and approval workflow.

Both packages are licensed under Apache-2.0. They are private for now, and their names are provisional until they are published to npm.

## Install and build

Until the packages are published, build them from the repository. You need Node.js 22 or later.

```sh
git clone https://github.com/xvalleycorp/RuleStone.git
cd RuleStone/sdk
npm ci
npm run build    # the engine, then the editor, into packages/*/dist
npm pack --workspace @rulestone/engine --workspace @rulestone/editor
```

`npm pack` writes `rulestone-engine-0.1.0.tgz` and `rulestone-editor-0.1.0.tgz`. Install both in your application in one command, so that the editor finds the engine:

```sh
npm install ../RuleStone/sdk/rulestone-engine-0.1.0.tgz ../RuleStone/sdk/rulestone-editor-0.1.0.tgz
```

The packages are ES modules with TypeScript types, for a bundler such as Vite, webpack, esbuild or the Angular CLI. Each element has its own entry point, so an application that only needs the condition builder does not load the rest:

| Import | Defines |
|---|---|
| `@rulestone/editor` | Every element, and exports the classes, the model and the strings |
| `@rulestone/editor/table-editor` | `<rulestone-table-editor>` and the elements it contains |
| `@rulestone/editor/rules-grid` | `<rulestone-rules-grid>` |
| `@rulestone/editor/condition-builder` | `<rulestone-condition-builder>` |
| `@rulestone/editor/schema-designer` | `<rulestone-schema-designer>` |
| `@rulestone/editor/try-it` | `<rulestone-try-it>` |
| `@rulestone/editor/cel-input` | `<rs-cel-input>` |
| `@rulestone/editor/flow-canvas` | `<rulestone-flow-canvas>` |
| `@rulestone/editor/model` | No element: table, schema, condition and flow operations and validation, for your own user interface or tests |

## Use it in your application

Every example below shows the table editor with a table the host has loaded, and saves the table after each change.

### Plain HTML

```html
<rulestone-table-editor locale="vi"></rulestone-table-editor>

<script type="module">
  import '@rulestone/editor/table-editor'

  const editor = document.querySelector('rulestone-table-editor')
  editor.table = await (await fetch('/rules/loan_eligibility.stone.json')).json()
  editor.addEventListener('rulestone-change', (e) => {
    if (e.detail.valid) save(e.detail.table, e.detail.ruleVersion)
  })
</script>
```

`save` stands for your application's own code.

### React

React 19 sets properties on custom elements and listens to their events:

```tsx
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }: Props) {
  return (
    <rulestone-table-editor
      table={table}
      locale="vi"
      onrulestone-change={(e: CustomEvent) => onSave(e.detail.table)}
    />
  )
}
```

With React 18, set the property and the listener through a ref:

```tsx
import { useEffect, useRef } from 'react'
import type { RulestoneTableEditor } from '@rulestone/editor'
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }: Props) {
  const ref = useRef<RulestoneTableEditor>(null)
  useEffect(() => {
    const el = ref.current!
    el.table = table
    const listener = (e: Event) => onSave((e as CustomEvent).detail.table)
    el.addEventListener('rulestone-change', listener)
    return () => el.removeEventListener('rulestone-change', listener)
  }, [table])
  return <rulestone-table-editor ref={ref} locale="vi" />
}
```

In TypeScript, declare the tags you use in `JSX.IntrinsicElements`.

### Angular

```ts
import { CUSTOM_ELEMENTS_SCHEMA, Component } from '@angular/core'
import '@rulestone/editor/table-editor'

@Component({
  selector: 'app-rule-editor',
  standalone: true,
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `<rulestone-table-editor [table]="table" locale="vi"
    (rulestone-change)="save($event.detail.table)"></rulestone-table-editor>`,
})
export class RuleEditorComponent {
  table: object = { /* loaded by the host */ }
  save(table: object) { /* stored by the host */ }
}
```

Angular binds `[table]` to the element's property, and `(rulestone-change)` to its event.

### Vue

Tell Vue that the `rulestone-` and `rs-` tags are custom elements, in `vite.config.ts`, or under `vue` in the configuration of a framework built on Vite (in VitePress, `.vitepress/config.ts`):

```ts
vue({
  template: { compilerOptions: { isCustomElement: (tag) => tag.startsWith('rulestone-') || tag.startsWith('rs-') } },
})
```

Pass objects as properties with `.prop`. With server-side rendering (Nuxt, VitePress), import the elements on the client only, after the component is mounted:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
const table = ref({ /* loaded by the host */ })
onMounted(() => import('@rulestone/editor/table-editor'))
</script>

<template>
  <rulestone-table-editor :table.prop="table" locale="vi"
    @rulestone-change="(e) => (table = e.detail.table)" />
</template>
```

The website's [playground](https://rulestone.dxa.io.vn/playground) is a VitePress page built this way, with the elements loaded only on that page.

## Elements

Every element has these attributes and properties:

| Attribute / property | Meaning |
|---|---|
| `locale` | `en` or `vi`. By default, the `lang` of the nearest ancestor that has one, else English. |
| `readonly` | Shows everything without allowing changes. |
| `theme` | `light` or `dark` to choose the theme; by default the element follows `prefers-color-scheme`. Elements pass it on to the elements inside them. |
| `messages` (property) | Overrides of the built-in strings, as a deep partial object, for example `{ grid: { addRule: 'New rule' } }`. |

Events are `CustomEvent`s that bubble and cross shadow roots (`composed`). Properties that take objects (`table`, `inputs`, `flow`) have no attribute: set them from script or through your framework's property binding.

### Table editor

`<rulestone-table-editor>` is the complete decision table editor: the table's metadata, the rules grid, the schema designer, the [parameters](decision-tables.md#parameters) (`params`), the [local values](decision-tables.md#local-values) (`let`), a JSON view, the problems panel and the Try it panel.

The **Parameters** tab edits the table's `params` declaration: the parameter set, an optional pinned version, and each parameter the rules read with its type (a primitive or a list of one). Next to each parameter it shows the value in every version of the set given in `paramSets`. Rules read the parameters as `params.<name>`, which the rules grid and the condition builder offer.

The **Local values** tab lists each local value as a name and a CEL expression, type-checked as you type against the inputs and the other local values. Add, rename and delete them there; renaming a local value does not change the rules that read it, which then report the old name.

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table as parsed `.stone.json`, a plain object. The editor works on a copy and never changes the host's object. |
| `paramSets` | property | The parameter sets the table may read: every version of each, as `.params.json` text or parsed JSON. The table's `params` are checked against them as the engine checks them, the Parameters tab shows their values, and Try it uses the version in force on its date. Without them, a table that declares `params` reports its set as not registered. |
| `validator` | property | Optional `async (table, localResult) => HostProblem[]` for server-side checks. See [the validator hook](#server-side-checks-the-validator-hook). |
| `asOf` / `as-of` | property / attribute | The business date the Try it panel starts with (`yyyy-MM-dd`). Default: today. |
| `facts` | property / attribute | Facts for the Try it panel as JSON text, in the forms of a `.stone-test.json` case. |
| `getTable()` | method | A copy of the table being edited. |
| `setTable(table)` | method | Replaces the table being edited. |
| `validate()` | method | Validates now; resolves with `{ valid, problems, canonical, ruleVersion }`. |
| `run()` | method | Runs the table in the Try it panel with its current facts; resolves with the `rulestone-run` detail. |
| `rulestone-change` | event | After each edit, once it is validated. `detail`: `{ table, canonical, problems, ruleVersion, valid }`. |
| `rulestone-validate` | event | After each validation, also when the host sets `table`. `detail`: `{ valid, problems, ruleVersion, canonical }`. |
| `rulestone-run` | event | After each Try it run; see the Try it panel. |

`ruleVersion` is the rule version the engine will report for the table, `<version>@sha256:<hash>`, computed over `canonical`, the table's canonical JSON, exactly as the Java engine computes it. Both are absent while the strict reader cannot read the table.

The problems come from the engine: the strict file reader (unknown fields, wrong types, dates), the compiler (duplicate rule ids, reason code format, undeclared outputs, versions) and the type checker for every expression. Each problem is shown on its cell, at the line and column the engine reports. A warning marks rules after a catch-all under `FIRST`, which can never match.

### Rules grid

`<rulestone-rules-grid>` shows the rules as a spreadsheet-like grid, one rule per row, with columns for the result of the last run, the rule id, the condition, each output, the reason code, the description and actions.

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table; the grid edits its rules. |
| `problems` | property | Problems found elsewhere, for example by the table editor or the host. The grid type-checks every expression cell itself. |
| `matchedRules`, `evaluatedRules` | property | Rule ids from a Try it run. Rows are marked as matched, not matched or not evaluated, with a symbol and text as well as colour. |
| `focusCell(rule, field, output?, index?)` | method | Moves focus to a cell. For an expression with a position, starts editing with the caret there. |
| `move(from, to)` | method | Moves a rule and announces its new position. |
| `rulestone-change` | event | `detail`: `{ table }` after every edit. |

Rules can be added, duplicated, deleted and reordered by drag and drop or with the keyboard. The ⊞ action opens the condition builder below a rule. Long conditions wrap at spaces and operators; a name is broken only when it is wider than its column.

### Condition builder

`<rulestone-condition-builder>` shows a condition as nested AND/OR blocks, or as CEL text.

| Member | Kind | Description |
|---|---|---|
| `value` | property / attribute | The condition as CEL. |
| `inputs` | property | The table's inputs, as in `.stone.json`, for the field picker. |
| `locals` | property | The table's local values (`let`), name to CEL expression. They are offered in the field picker with their inferred types. |
| `params` | property | The parameters the table declares (`params.values`), name to type. They are offered in the field picker as fields of `params`. |
| `mode` | property / attribute | `blocks` or `cel`. |
| `label` | property / attribute | The builder's accessible name. |
| `rulestone-change` | event | `detail`: `{ value }`, the new CEL. |

The field picker lists the inputs, the local values, the declared parameters (under `params`) and the fields of structs. Lists are tested with `exists`, `all` and `exists_one` blocks, whose items have their own fields. The operators depend on the type: comparisons for `int`, `decimal`, `money` and `date`; `in`; `startsWith`, `endsWith`, `contains` and `matches` for strings; `is set` (`has()`) for struct fields; `includes` for lists. Values are typed (money with a currency, dates, decimals, lists), and the ƒx button turns a value into an expression.

CEL that is not edited comes back exactly as it was written, including spacing and comments. Edited parts are printed as canonical CEL with only the parentheses that precedence needs. CEL that blocks cannot show (a calculation on the left of a comparison, `filter` or `map`, the conditional operator, a syntax error) stays as text in a CEL block, with the reason. It is never rewritten.

### Schema designer

`<rulestone-schema-designer>` shows a table's inputs and outputs as trees with every type: `bool`, `int`, `string`, `decimal`, `money`, `date`, `struct`, `list` and nested types. Outputs cannot be structs.

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table. The rules are needed to rename fields where they are used. |
| `rulestone-change` | event | `detail`: `{ table, rename? }`. `rename` reports which expressions a rename changed, and which it could not update. |

Renaming a field updates every rule expression that uses it through the type-checked syntax tree, never by replacing text. An iteration variable, a field of another struct with the same name, or the name inside a string is left alone. The designer refuses names that are invalid, reserved, already used, or that an iteration variable would capture.

### Try it panel

`<rulestone-try-it>` runs a table in the browser with `@rulestone/engine`.

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table to run. |
| `paramSets` | property | The parameter sets the table reads, as `.params.json` text or parsed JSON; the version in force on `asOf` is used, and the record lists it. |
| `asOf` / `as-of` | property / attribute | The business date. Default: today, in the browser's time zone. |
| `facts` | property / attribute | Initial facts as JSON text. |
| `factsText` | property (read only) | The facts as they would be run, as JSON text. |
| `run()` | method | Runs the table; resolves with the event's detail. |
| `rulestone-run` | event | `detail`: `{ ok, result?, record?, recordJson?, error?, problems?, matchedRules, evaluatedRules }`. |

The form is generated from the inputs, with fields for money (amount and currency), dates, decimals, structs and lists. A JSON mode takes the facts in the same forms as `.stone-test.json` files. The result shows the outputs, reason codes, the trace, the rule version and the decision record.

### CEL input

`<rs-cel-input>` is a CEL text input with syntax highlighting, type checking and autocomplete.

| Member | Kind | Description |
|---|---|---|
| `value` | property / attribute | The expression. |
| `variables` | property | The typed variables in scope. When set, the text is type-checked and fields are suggested. |
| `expected` | property | The type the expression must produce, for example `bool` for a condition. |
| `multiline` | property / attribute | Allows line breaks; Enter then inserts one and Ctrl+Enter commits. |
| `label`, `placeholder` | property / attribute | The accessible name and the placeholder. |
| `issues` | property | Problems to mark instead of those the element finds itself. |
| `showProblems` / `show-problems` | property / attribute | Lists the problems below the input. |
| `focusAt(index)` | method | Focuses the input with the caret at a UTF-16 index. |
| `rulestone-input`, `rulestone-commit`, `rulestone-cancel` | events | On every edit and on commit (`detail`: `{ value }`), and on Escape. |

### Flow canvas

`<rulestone-flow-canvas>` designs a decision flow (`.flow.json`, the proposal in [Hybrid AI decisioning](hybrid-ai.md#3-pattern-b-models-as-nodes-in-a-decision-flow)) on a canvas, with a palette of node types, edges, pan and zoom, a properties panel and the checks the editor can make without the engine.

| Member | Kind | Description |
|---|---|---|
| `flow` | property | The flow, as parsed `.flow.json`. |
| `layout` | property | Node positions by id. The file format has no layout, so it is kept apart. Missing positions are laid out automatically. |
| `tables` | property | Known decision ids; table nodes that use other ids are reported. |
| `problems` | property (read only) | The result of the checks for the current flow. |
| `fit()` | method | Shows the flow at a readable size: whole if it fits, otherwise from the top left, where the start node is. |
| `arrange()` | method | Lays out every node again for the canvas's size. |
| `toJson()`, `importText(text)` | methods | The flow as `.flow.json` text; replace the flow with one read from text (returns `false` and shows the error if it cannot be read). |
| `addAt(type, at?)`, `connectNodes(from, to, failure?)` | methods | Add a node (returns its id); connect two nodes, or set a model node's `onFailure`. |
| `rulestone-change` | event | `detail`: `{ flow, layout, problems }`. |
| `rulestone-export` | event | `detail`: `{ flow, json }` when the user exports. Call `preventDefault()` to handle the file yourself; otherwise the browser downloads it. |

The palette has the node types `table`, `switch`, `derive`, `model`, `split`, `end` and `flow`. The checks find cycles, nodes the start cannot reach, paths that never reach an end, model nodes without `onFailure` or a pinned version, split percentages that do not add up to 100, unknown table references, switches without a final case, and end nodes that do not set every output.

Until someone moves a node, the canvas arranges the flow for its own size: a long chain of nodes wraps into rows, like lines of text, so that the whole flow is readable without panning where it fits. Fitting never zooms out below the size at which node text stays legible (80%); a larger flow is then reached by dragging the background, the arrow keys, or the zoom buttons.

**Flows are design-only in the browser.** The Java engine runs [decision flows](flows.md), but the browser engine (`@rulestone/engine`) does not run them yet, and the canvas says so.

## Server-side checks: the validator hook

The table editor checks everything the browser engine can check. A bank's own RuleStone service can check more: for example, that a new version does not overlap the versions already saved, or rules of the bank's own. The `validator` property connects such checks:

```ts
type TableValidator = (table: TableJson, local: ValidationResult) => Promise<readonly HostProblem[]>

interface HostProblem {
  message: string
  severity?: 'error' | 'warning'     // default 'error'
  target?: ProblemTarget             // default: the table as a whole
}

type ProblemTarget =
  | { kind: 'table'; field?: string }
  | { kind: 'schema'; side: 'inputs' | 'outputs'; name?: string }
  | { kind: 'let'; name?: string }
  | { kind: 'rule'; rule: number; field: 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'rule'; output?: string }
```

The editor calls the validator after its own checks, each time it validates: when the host sets a table, and 150 ms after the last edit. `local` is the editor's own result, so the validator can skip a table that is already invalid. The editor shows the host's problems with its own, marked as coming from the host, and includes them in `rulestone-change` and `rulestone-validate`. A result that arrives after a newer edit is ignored. If the validator throws, the message is shown as a warning, and editing goes on.

This example asks the [web editor's API](editor.md#post-apivalidate), a Java RuleStone service, through a route of the host application. It keeps only the problems that involve another saved file, because the browser already reports the others:

```ts
editor.validator = async (table, local) => {
  if (local.ruleVersion === undefined) return []    // not readable yet
  const response = await fetch('/rules-api/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(table),
  })
  if (!response.ok) throw new Error(`Rule service: HTTP ${response.status}`)
  const result = await response.json()
  return result.problems
    .filter((p) => p.location?.file)
    .map((p) => ({ message: `${p.message} (${p.location.file})` }))
}
```

## Persistence

The host owns persistence. The elements never fetch or store anything:

- **Loading:** set `table` (or `flow` and `layout`) with what your application loaded. The editor works on a copy.
- **Saving:** listen to `rulestone-change`. Its `detail.table` is the edited table as a plain object, ready for `JSON.stringify`; `detail.valid` says whether the engine accepts it, and `detail.ruleVersion` is the version the engine will report for it. Save only valid tables, or save drafts and publish valid ones, as your workflow requires.
- **Reading the current state:** call `getTable()` at any time, and `validate()` to wait for a fresh result.
- **Flows:** save `flow` as the `.flow.json` file and `layout` next to it if you want positions to stay; `rulestone-change` carries both.

To write the file, use `tableText(table)` from `@rulestone/editor/model`. It writes the fields in the order of the `.stone.json` format, two spaces per level, so that saved files diff cleanly in Git.

## Theming

The elements use CSS custom properties, which the host sets on the elements or on any ancestor. Each colour has a light and a dark default. The `theme` attribute chooses light or dark; without it, the elements follow `prefers-color-scheme`. To change a colour for one theme only, set it inside your own `@media (prefers-color-scheme: dark)` rule or for `[theme='dark']`.

| Token | Light | Dark | Used for |
|---|---|---|---|
| `--rs-color-bg` | `#ffffff` | `#0f1217` | Background of each element |
| `--rs-color-surface` | `#f6f8fa` | `#161b22` | Panels, toolbars, table headers |
| `--rs-color-surface-raised` | `#ffffff` | `#1c2129` | Inputs, blocks, nodes |
| `--rs-color-text` | `#1f2328` | `#e6edf3` | Text |
| `--rs-color-text-muted` | `#59636e` | `#9da7b3` | Secondary text, hints |
| `--rs-color-border` | `#d1d9e0` | `#3d444d` | Light borders, grid lines |
| `--rs-color-border-strong` | `#818b98` | `#656c76` | Borders of controls |
| `--rs-color-accent` | `#0b5cad` | `#4493f8` | Primary buttons, AND groups |
| `--rs-color-on-accent` | `#ffffff` | `#0f1217` | Text on the accent colour |
| `--rs-color-accent-subtle` | `#ddf4ff` | `#12263f` | Pressed toggles, selection |
| `--rs-color-focus` | `#0969da` | `#58a6ff` | Focus outline |
| `--rs-color-error` | `#b42318` | `#ff7b72` | Errors, invalid cells |
| `--rs-color-error-subtle` | `#ffebe9` | `#3a1517` | Background of invalid cells |
| `--rs-color-warning` | `#7d4e00` | `#d29922` | Warnings, the design-only notice |
| `--rs-color-warning-subtle` | `#fff8c5` | `#33270b` | Background of warnings |
| `--rs-color-success` | `#116329` | `#3fb950` | Valid status, matched marks, end nodes |
| `--rs-color-match` | `#dafbe1` | `#12361f` | Rows that matched in Try it |
| `--rs-color-nomatch` | `#f6f8fa` | `#161b22` | Rows that did not match |
| `--rs-syntax-string`, `-number`, `-keyword`, `-function`, `-field`, `-variable`, `-operator`, `-comment` | | | CEL highlighting |
| `--rs-font-family`, `--rs-font-family-mono` | system fonts | | Text; CEL, ids and JSON |
| `--rs-font-size` | `14px` | | Base size |
| `--rs-radius`, `--rs-space`, `--rs-focus-width` | `6px`, `8px`, `2px` | | Corners, spacing, focus outline |
| `--rs-flow-canvas-height` | about two thirds of the window | | Height of the flow canvas |

The defaults meet the WCAG 2.1 AA contrast ratios. If you change colours, check the contrast of text against its background again. The playground, for example, takes the site's colours:

```css
.rule-editor {
  --rs-color-bg: var(--vp-c-bg);
  --rs-color-surface: var(--vp-c-bg-soft);
  --rs-color-text: var(--vp-c-text-1);
  --rs-color-text-muted: var(--vp-c-text-2);
  --rs-color-border: var(--vp-c-divider);
  --rs-color-accent: var(--vp-c-brand-1);
}
```

For finer control, style the parts the elements expose with `::part()`:

| Element | Parts |
|---|---|
| Table editor | `metadata`, `tabs`, `problems`, `try-it` |
| Rules grid | `toolbar`, `grid`, `row` (also `row matched`, `row not-matched`, `row not-evaluated`), `cell` (also `cell invalid`) |
| Condition builder | `toolbar`, `group`, `block` |
| Schema designer | `tree`, `item`, `report` |
| Try it panel | `form`, `result` |
| Flow canvas | `banner`, `palette`, `canvas`, `panel`, `problems` |
| CEL input | `input` |

## Languages

The elements have English and Vietnamese strings built in. `locale` chooses one; without it, an element uses the `lang` attribute of its nearest ancestor that has one, so `<html lang="vi">` is enough for a Vietnamese page. The Vietnamese strings follow the project's [glossary](../docs/vi/GLOSSARY.md).

Override strings for the whole page with `registerMessages`, or for one element with its `messages` property:

```ts
import { registerMessages } from '@rulestone/editor'
registerMessages('vi', { grid: { addRule: 'Thêm luật mới' } })   // for every element on the page
editor.messages = { tryIt: { run: 'Chạy' } }                       // for one element
```

The keys are those of the exported `en` object. Problems that come from the engine, such as type errors, are the engine's own messages, in English, as the Java engine reports them.

## Accessibility and keyboard

The elements are built to WCAG 2.1 AA. Every drag and drop has a keyboard alternative, moves and other changes are announced through a polite live region, the grid and the trees use ARIA roles, focus is always visible, and the result of a run is shown with symbols and text as well as colour.

| Where | Keys |
|---|---|
| Rules grid | Arrow keys, Home, End, Page Up and Page Down move between cells; Enter or F2 edits; Enter saves and Escape cancels; Alt+↑ and Alt+↓ move the rule; on the actions cell, Enter reaches the buttons and Escape returns. |
| Condition builder | On a block's handle: Alt+↑ and Alt+↓ move it within its group, Alt+→ moves it into the group above, Alt+← out of its group. Every block also has move buttons. |
| Schema designer | ↑ and ↓ move between fields, → and ← show and hide nested fields, Alt+↑ and Alt+↓ reorder, Enter edits the name. |
| CEL input | Ctrl+Space shows suggestions; ↑ and ↓ choose, Enter or Tab accept, Escape closes. |
| Flow canvas | Palette buttons add a node. On the canvas: arrow keys pan, + and − zoom, 0 fits; Ctrl or ⌘ with the mouse wheel also zooms, and the wheel alone scrolls the page. On a touch screen, one finger pans and two fingers pinch to zoom. On a node: arrow keys move it (Shift for larger steps), C starts a connection (F for a model's `onFailure`) and Enter on the target completes it, Delete removes it. Edges can also be set and removed in the properties panel. |

## Content Security Policy

The elements work under a strict policy such as:

```
default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'
```

- **Scripts:** no inline scripts, `eval` or `new Function`.
- **Styles:** Lit applies the elements' styles as constructable stylesheets (`adoptedStyleSheets`), which CSP does not restrict, so `style-src` needs no `'unsafe-inline'`. The elements use no `style` attributes; the flow canvas positions nodes with SVG attributes. In a browser without constructable stylesheets, Lit falls back to `<style>` elements: allow them with a nonce by setting `window.litNonce` before the elements load, and adding that nonce to `style-src`.
- **Content:** user text is rendered as text by Lit templates, never through `innerHTML`.
- **Network:** none. Exporting a flow creates a `blob:` download link.
- **Hashing:** the engine computes rule versions and fact hashes with WebCrypto (`crypto.subtle`), which browsers offer only on secure pages (HTTPS, or `localhost` during development).

The editor's demo (`sdk/packages/editor/demo/`) runs under this policy and reports any violation.

## Bundle sizes

Minified, and minified and gzipped, with esbuild (`npm run size` in each package). Both the English and the Vietnamese strings are included.

| Entry point | With Lit and the engine | Editor code only |
|---|---|---|
| `@rulestone/editor` (all elements) | 312.5 KiB, 91.1 KiB gzipped | 208.1 KiB, 57.6 KiB gzipped |
| `./table-editor` | 264.5 KiB, 77.5 KiB gzipped | 160.1 KiB, 44.0 KiB gzipped |
| `./rules-grid` | 175.6 KiB, 53.8 KiB gzipped | 103.7 KiB, 29.7 KiB gzipped |
| `./condition-builder` | 155.1 KiB, 48.5 KiB gzipped | 83.0 KiB, 24.3 KiB gzipped |
| `./schema-designer` | 131.9 KiB, 42.6 KiB gzipped | 60.0 KiB, 18.7 KiB gzipped |
| `./flow-canvas` | 100.9 KiB, 30.9 KiB gzipped | 85.9 KiB, 25.3 KiB gzipped |
| `./try-it` | 160.7 KiB, 51.0 KiB gzipped | 57.4 KiB, 17.4 KiB gzipped |
| `./cel-input` | 108.7 KiB, 35.8 KiB gzipped | 50.9 KiB, 15.9 KiB gzipped |

`@rulestone/engine` alone is 101.1 KiB, 32.5 KiB gzipped. The flow canvas does not load the engine. Lit is shared when several elements are on a page, and a bundler splits what each page loads.

## The browser engine and the Java engine

The Java engine remains the engine of record: evaluate production decisions with it. The browser engine is for instant feedback while editing and trying rules, and it is built to give exactly the same answers.

A conformance suite in `sdk/packages/engine/test/` compares the two. Its fixtures are written by the Java engine (`SdkFixturesTest` in `rulestone-test`), and the Maven build fails if they are out of date:

| Fixture | Checked |
|---|---|
| `java-hashes.json` | The canonical form and rule version of every table in `examples/` and in the test tables, and the canonical form and version of every parameter set. |
| `java-records.json` | The decision record and trace of every case of every `examples/*.stone-test.json` file. |
| `java-probes.json` | The result type and value, or the exact error, of every expression in `probes.json`: operators, macros, iteration limits, error positions, decimal arithmetic, money, dates and fuzzy matching. |
| `java-invalid-tables.json` | The problems reported for every invalid table and parameter set in `invalid-tables.json`. |

The suite also checks every `expression` → result example in [Expression language](expressions.md) and the worked examples in [Fuzzy matching](fuzzy-matching.md). Decimals are exact: `Decimal` keeps a `BigInt` and a scale, and implements `BigDecimal`'s rules for scale, rounding and division.

Known differences:

- **Not supported:** map literals (`{'a': 1}`), `bytes`, `type()`, `timestamp()`, `duration()` and `string(double)`. RuleStone rules do not use them. The browser engine rejects them at compile time with a message that says so; Java accepts them.
- **Syntax errors** are reported at the same position, in other words than ANTLR's.
- **Regular expressions:** `matches()` translates RE2 syntax to JavaScript regular expressions. Common syntax behaves the same; exotic RE2 constructs may differ.
- **Unicode:** `normalizeName` and the fuzzy functions use the Unicode tables of the browser, and Java those of the JDK. They differ only for characters added in recent Unicode versions.
- **Decision records** name the engine `rulestone-js 0.1.0`, have millisecond precision in `evaluatedAt`, and hash facts with plain SHA-256 (Java's HMAC option is not offered).
- **Today:** without `asOf`, "today" is the date in the browser's time zone. Set `asOf` for reproducible results.

The [engine's README](../sdk/packages/engine/README.md) lists its API and every difference in detail.

## Limitations

- The packages are not on npm yet, and their names may change when they are published.
- The browser engine does not run flows; only the Java engine does (see [Decision flows](flows.md)). The canvas checks only what it can check without the engine: there are no name or type checks across nodes.
- The canvas reads a `derive` node's values as `values` (name to expression), while the Java engine's format declares them with types in `outputs` and sets them with `set`. Its `flow` node fields (`flow`, `version`) follow the proposal; the Java engine does not support `flow` nodes yet.
- The rules grid does not virtualize rows, so tables with thousands of rules render slowly.
