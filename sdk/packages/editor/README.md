# @rulestone/editor

Visual editors for RuleStone decision tables and decision flows, as Web Components. Banks embed them in their own web applications (Angular, React, Vue or plain HTML) so that analysts can build decision tables and rules visually, with drag and drop, instead of typing JSON.

> The package is private while its npm name is decided. Its name, `@rulestone/editor`, is provisional.

- **Web Components** built with [Lit](https://lit.dev) (BSD-3-Clause). They work in any framework, or none.
- **Instant feedback** from [`@rulestone/engine`](../engine): every expression is type-checked as it is typed, with the engine's own messages and positions, and the Try it panel runs the rules in the browser. The Java engine remains the engine of record.
- **The host owns persistence.** The elements make no network calls. They take data through properties and report every change through events. An optional `validator` hook lets the host add server-side checks.
- **Accessible** (WCAG 2.1 AA): every drag and drop has a keyboard alternative, moves are announced, the grid and the trees use ARIA roles, and focus is always visible.
- **English and Vietnamese** built in; every string can be overridden.
- **Themable** through CSS custom properties and `::part` names, with light and dark themes.
- **Strict CSP**: no inline scripts or styles, no `eval`, no `innerHTML` from user content.

## Elements

| Element | Module | Purpose |
|---|---|---|
| `<rulestone-table-editor>` | `@rulestone/editor/table-editor` | The complete decision table editor: metadata, schema designer, rules grid, problems panel and Try it panel. |
| `<rulestone-rules-grid>` | `@rulestone/editor/rules-grid` | A spreadsheet-like grid with one rule per row. |
| `<rulestone-condition-builder>` | `@rulestone/editor/condition-builder` | A condition as nested AND/OR blocks, or as CEL text. |
| `<rulestone-schema-designer>` | `@rulestone/editor/schema-designer` | A table's inputs and outputs as trees. |
| `<rulestone-flow-canvas>` | `@rulestone/editor/flow-canvas` | A decision-flow canvas (`.flow.json`, design only). |
| `<rulestone-try-it>` | `@rulestone/editor/try-it` | Runs a table with facts from a form or JSON. |
| `<rs-cel-input>` | `@rulestone/editor/cel-input` | A CEL text input with highlighting and autocomplete. |

Importing `@rulestone/editor` defines every element. Import an element's own module to load only that element and what it uses. The model (`@rulestone/editor/model`) has the same operations without any element: table, schema, condition and flow editing, and validation.

Every element has these attributes and properties:

| Attribute / property | Meaning |
|---|---|
| `locale` | `en` or `vi`. By default, the nearest `lang` attribute, else English. |
| `readonly` | Shows everything without allowing changes. |
| `theme` | `light` or `dark` to force a theme; by default it follows `prefers-color-scheme`. Passed on to nested elements. |
| `messages` (property) | Overrides of the built-in strings, as a deep partial object, e.g. `{ grid: { addRule: 'New rule' } }`. |

Events are `CustomEvent`s that bubble and cross shadow roots (`composed`).

### `<rulestone-table-editor>`

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table as parsed `.stone.json` (a plain object). The editor works on a copy and never changes the host's object. |
| `validator` | property | Optional `async (table, localResult) => HostProblem[]`, for server-side checks such as calling the bank's Java RuleStone service. A `HostProblem` is `{ message, severity?, target? }`. |
| `asOf` / `as-of` | property / attribute | The business date the Try it panel starts with. |
| `getTable()` | method | A copy of the table being edited. |
| `setTable(table)` | method | Replaces the table being edited. |
| `validate()` | method | Validates now; resolves with `{ valid, problems, canonical, ruleVersion }`. |
| `rulestone-change` | event | After each edit, once it is validated. `detail`: `{ table, canonical, problems, ruleVersion, valid }`. `canonical` is the canonical JSON that `ruleVersion` (`<version>@sha256:<hash>`, as in Java) is computed over. |
| `rulestone-validate` | event | After each validation. `detail`: `{ valid, problems, ruleVersion, canonical }`. |
| `rulestone-run` | event | After each Try it run (see `<rulestone-try-it>`). |

Problems are the engine's: the strict file reader's (unknown fields, bad types, dates), the compiler's (duplicate rule ids, reason code format, undeclared outputs, versions) and every expression's, each placed on its cell and, for expressions, at the line and column of the problem. A warning marks rules after a catch-all under `FIRST`, which can never match.

### `<rulestone-rules-grid>`

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table; the grid edits its rules. |
| `problems` | property | Problems found elsewhere, such as by the table editor or the host. The grid type-checks every expression cell itself. |
| `matchedRules`, `evaluatedRules` | property | Rule ids from a Try it run; the rows are marked matched, not matched or not evaluated, with text as well as colour. |
| `focusCell(rule, field, output?, index?)` | method | Moves focus to a cell; for an expression with a position, starts editing with the caret there. |
| `rulestone-change` | event | `detail`: `{ table }` after every edit. |

Columns: result, rule id, condition, one per output, reason code, description, actions. Rules can be added, duplicated, deleted and reordered by drag and drop or with the keyboard. The ⊞ action opens the condition builder below a rule.

### `<rulestone-condition-builder>`

| Member | Kind | Description |
|---|---|---|
| `value` | property / attribute | The condition as CEL. |
| `inputs` | property | The table's inputs, as in `.stone.json`, for the field picker. |
| `mode` | property / attribute | `blocks` or `cel`. |
| `rulestone-change` | event | `detail`: `{ value }`. |

The field picker lists inputs and nested struct fields. Lists are tested with blocks for `exists`, `all` and `exists_one`, whose items have their own fields. Operators depend on the type: comparisons for `int`, `decimal`, `money` and `date`; `in`; `startsWith`, `endsWith`, `contains` and `matches` for strings; `is set` (`has()`) for struct fields; `includes` for lists. Values are typed: money with a currency, dates, decimals, lists; the ƒx button switches a value to an expression.

**Round trip.** Unedited CEL comes back exactly as it was written, including spacing and comments. Edited parts are printed as canonical CEL with only the parentheses precedence needs. CEL that blocks cannot show (a calculation on the left of a comparison, `filter` or `map`, the conditional operator, a syntax error) stays as text in a CEL block, with the reason; it is never rewritten. The tests check every condition in `examples/`.

### `<rulestone-schema-designer>`

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table; the rules are needed to rename fields where they are used. |
| `rulestone-change` | event | `detail`: `{ table, rename? }`. |

Inputs and outputs are trees with every type: `bool`, `int`, `string`, `decimal`, `money`, `date`, `struct`, `list` and nested ones. Outputs cannot be structs. **Renaming a field updates every rule expression that uses it, through the type-checked syntax tree, never by text replacement**: an iteration variable or a field of another struct with the same name, or the name inside a string, is left alone. The designer reports each expression it changed, and refuses names that are invalid, reserved, already used, or that an iteration variable would capture. Expressions with a syntax error are listed as not updated.

### `<rulestone-flow-canvas>`

| Member | Kind | Description |
|---|---|---|
| `flow` | property | The flow, as `.flow.json` (the proposal in [docs/hybrid-ai.md](../../../docs/hybrid-ai.md) section 3). |
| `layout` | property | Node positions by id. The file format has no layout, so it is kept apart; missing positions are laid out automatically. |
| `tables` | property | Known decision ids; table nodes that use others are reported. |
| `fit()`, `toJson()`, `importText(text)`, `addAt(type)`, `connectNodes(from, to)` | methods | |
| `rulestone-change` | event | `detail`: `{ flow, layout, problems }`. |
| `rulestone-export` | event | `detail`: `{ flow, json }` when the user exports. Call `preventDefault()` to handle the file yourself; otherwise it is downloaded. |

The palette has the node types `table`, `switch`, `derive`, `model`, `split`, `end` and `flow`. Checks: cycles, nodes the start cannot reach, paths that never reach an end, model nodes without `onFailure` or a pinned version, split percentages that do not add up to 100, unknown table references, switches without a final case, and end nodes that do not set every output. **The engine cannot run flows yet; the canvas says so.**

### `<rulestone-try-it>`

| Member | Kind | Description |
|---|---|---|
| `table` | property | The table to run. |
| `asOf` / `as-of` | property / attribute | The business date (default today). |
| `facts` | property / attribute | Initial facts as JSON text. |
| `run()` | method | Runs the table; resolves with the event's detail. |
| `rulestone-run` | event | `detail`: `{ ok, result?, record?, recordJson?, error?, problems?, matchedRules, evaluatedRules }`. |

The form is generated from the inputs, with money (amount and currency), date, decimal, struct and list inputs; a JSON mode takes facts in the FactsJson forms. The result shows the outputs, reason codes, trace, rule version and the decision record JSON.

## Usage

### Plain HTML

```html
<script type="module">
  import '@rulestone/editor/table-editor'

  const editor = document.querySelector('rulestone-table-editor')
  editor.table = await (await fetch('/rules/loan_eligibility.stone.json')).json()   // the host loads
  editor.validator = async (table) => (await callMyRuleStoneService(table)).problems // optional
  editor.addEventListener('rulestone-change', (e) => save(e.detail.table, e.detail.ruleVersion))
</script>
<rulestone-table-editor locale="vi"></rulestone-table-editor>
```

### React

React 19 passes properties to custom elements and listens to their events directly:

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
const ref = useRef<RulestoneTableEditor>(null)
useEffect(() => {
  const el = ref.current!
  el.table = table
  const listener = (e: Event) => onSave((e as CustomEvent).detail.table)
  el.addEventListener('rulestone-change', listener)
  return () => el.removeEventListener('rulestone-change', listener)
}, [table])
return <rulestone-table-editor ref={ref} locale="vi" />
```

### Angular

```ts
import { CUSTOM_ELEMENTS_SCHEMA, Component } from '@angular/core'
import '@rulestone/editor/table-editor'

@Component({
  selector: 'app-rule-editor',
  standalone: true,
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `<rulestone-table-editor [table]="table" locale="vi" (rulestone-change)="save($event.detail.table)"></rulestone-table-editor>`,
})
export class RuleEditorComponent {
  table = /* loaded by the host */
  save(table: unknown) { /* … */ }
}
```

### Vue (including VitePress)

Tell Vue that `rulestone-` and `rs-` tags are custom elements, in `vite.config.ts` (in VitePress, under `vue` in `.vitepress/config.ts`):

```ts
vue({
  template: { compilerOptions: { isCustomElement: (tag) => tag.startsWith('rulestone-') || tag.startsWith('rs-') } },
})
```

Pass objects as properties with `.prop` (or Vue's automatic property binding for custom elements):

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
const table = ref(/* … */)
// In VitePress, import the elements on the client only: pages are also rendered at build time.
onMounted(() => import('@rulestone/editor/table-editor'))
</script>

<template>
  <rulestone-table-editor :table.prop="table" locale="vi" @rulestone-change="(e) => (table = e.detail.table)" />
</template>
```

## Keyboard

| Where | Keys |
|---|---|
| Rules grid | Arrow keys, Home, End, Page Up/Down move between cells; Enter or F2 edits; Enter saves and Escape cancels; Alt+↑ / Alt+↓ move the rule; on the actions cell, Enter reaches the buttons and Escape returns. |
| Condition builder | On a block's handle: Alt+↑ / Alt+↓ move it within its group, Alt+→ moves it into the group above, Alt+← out of its group. Every block also has move buttons. |
| Schema designer | ↑ / ↓ move between fields, → / ← show and hide nested fields, Alt+↑ / Alt+↓ reorder, Enter edits the name. |
| CEL input | Ctrl+Space shows suggestions; ↑ / ↓ choose, Enter or Tab accept, Escape closes. |
| Flow canvas | Palette buttons add a node. On the canvas: arrow keys pan, + and − zoom, 0 fits. On a node: arrow keys move it (Shift for larger steps), C starts a connection (F for a model's onFailure) and Enter on the target completes it, Delete removes it. Edges can also be set and removed in the properties panel. |

Moves and other changes are announced through a polite live region.

## Strings

```ts
import { registerMessages } from '@rulestone/editor'
registerMessages('vi', { grid: { addRule: 'Thêm luật mới' } })   // for every element on the page
editor.messages = { tryIt: { run: 'Chạy' } }                       // for one element
```

The Vietnamese strings follow [docs/vi/GLOSSARY.md](../../../docs/vi/GLOSSARY.md).

## Theming

Set the tokens on the elements or any ancestor. Each has a light and a dark default; to change a colour in dark mode only, set it inside your own `@media (prefers-color-scheme: dark)` rule or for `[theme='dark']`.

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
| `--rs-syntax-string` | `#0a3069` | `#a5d6ff` | CEL strings |
| `--rs-syntax-number` | `#0550ae` | `#79c0ff` | CEL numbers |
| `--rs-syntax-keyword` | `#8250df` | `#d2a8ff` | CEL keywords; OR groups |
| `--rs-syntax-function` | `#6639ba` | `#d2a8ff` | CEL functions |
| `--rs-syntax-field` | `#953800` | `#ffa657` | CEL field names |
| `--rs-syntax-variable` | `#1f2328` | `#e6edf3` | CEL variables |
| `--rs-syntax-operator` | `#cf222e` | `#ff7b72` | CEL operators |
| `--rs-syntax-comment` | `#59636e` | `#9da7b3` | CEL comments |
| `--rs-font-family` | system UI fonts | same | Text |
| `--rs-font-family-mono` | `ui-monospace, …` | same | CEL, ids, JSON |
| `--rs-font-size` | `14px` | same | Base size |
| `--rs-radius` | `6px` | same | Corner radius |
| `--rs-space` | `8px` | same | Base spacing |
| `--rs-focus-width` | `2px` | same | Focus outline width |

`::part` names: `metadata`, `tabs`, `problems`, `try-it` (table editor); `toolbar`, `grid`, `row` (also `row matched`, `row not-matched`, `row not-evaluated`), `cell` (also `cell invalid`) (rules grid); `toolbar`, `group`, `block` (condition builder); `tree`, `item`, `report` (schema designer); `form`, `result` (Try it); `banner`, `palette`, `canvas`, `panel`, `problems` (flow canvas); `input` (CEL input).

## Content Security Policy

The elements work under a strict policy such as the demo's:

```
default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'
```

- **Scripts:** no inline scripts, `eval` or `new Function`.
- **Styles:** Lit applies the elements' styles as constructable stylesheets (`adoptedStyleSheets`), which CSP does not restrict, so `style-src` needs no `'unsafe-inline'`. No `style` attributes are used; the flow canvas positions nodes with SVG attributes. In a browser without constructable stylesheets, Lit falls back to `<style>` elements: then allow them with a nonce by setting `window.litNonce` before the elements load, and adding that nonce to `style-src`.
- **Content:** user text is rendered as text by Lit templates, never through `innerHTML`.
- **Network:** none. Exporting a flow creates a `blob:` download link.

## Size

Minified and minified + gzipped with esbuild (`npm run size`), with Lit and the engine and without them. Both the English and the Vietnamese strings are included.

| Entry point | With Lit and the engine | Editor code only |
|---|---|---|
| `@rulestone/editor` (all elements) | 275.8 KiB min, 80.4 KiB gzip | 187.0 KiB min, 51.5 KiB gzip |
| `./table-editor` | 233.1 KiB min, 69.0 KiB gzip | 144.3 KiB min, 40.0 KiB gzip |
| `./rules-grid` | 153.2 KiB min, 47.2 KiB gzip | 97.5 KiB min, 27.8 KiB gzip |
| `./condition-builder` | 134.0 KiB min, 42.3 KiB gzip | 78.1 KiB min, 22.8 KiB gzip |
| `./schema-designer` | 110.9 KiB min, 36.5 KiB gzip | 55.0 KiB min, 17.2 KiB gzip |
| `./flow-canvas` | 92.0 KiB min, 28.0 KiB gzip | 77.0 KiB min, 22.4 KiB gzip |
| `./try-it` | 141.0 KiB min, 45.3 KiB gzip | 52.6 KiB min, 16.0 KiB gzip |
| `./cel-input` | 102.3 KiB min, 34.0 KiB gzip | 46.8 KiB min, 14.7 KiB gzip |

The flow canvas does not load the engine.

## Development

```sh
cd sdk
npm ci
npm run typecheck && npm run build && npm test
cd packages/editor
npm run demo          # Vite dev server: http://localhost:5173
npm run demo:build    # static build in demo/dist
npm run size
```

The demo (`demo/index.html`) loads the tables of `examples/`, the first case of their `.stone-test.json` for Try it, and the example flow of docs/hybrid-ai.md. Its query parameters select the view (`view=table|flow|builder`), `example`, `locale`, `theme`, `readonly=1` and `run=1`. It runs under a strict CSP and reports any violation in its status line.

The tests use Vitest with happy-dom: model operations (the condition ↔ CEL round trip over every condition in `examples/`, renaming, flow checks, keyboard moves) and the elements.

## Known limitations

- Flows are design-only until the engine implements them; the checks are those the editor can make without the engine (no name or type checks across nodes).
- The `derive` and `flow` node fields follow this editor's reading of the proposal (`values`, `flow`, `version`), which may change with the format.
- The rules grid is a table element; it does not virtualize rows, so very large tables (thousands of rules) render slowly.
