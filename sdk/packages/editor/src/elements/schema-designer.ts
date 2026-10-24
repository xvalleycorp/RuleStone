// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { css, html, nothing, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import {
  ELEMENT, addField, kindOf, moveField, nameProblem, newFieldName, removeField, renameInput, renameOutput, setFieldType,
  specAt, specOfKind, type FieldPath, type RenameResult, type TypeKind,
} from '../model/schema.js'
import { PRIMITIVES, normalizeTable, orderTable, type TableJson, type TypeSpec } from '../model/table.js'
import { RulestoneElement, define } from './base.js'

type Side = 'inputs' | 'outputs'

interface Item {
  readonly side: Side
  readonly path: FieldPath
  readonly key: string
  readonly depth: number
  readonly spec: TypeSpec
  readonly parent: string | undefined
  readonly expandable: boolean
}

function keyOf(side: Side, path: FieldPath): string {
  return `${side}:${path.join('.')}`
}

/** The struct a path's children live in: the field itself, or its list's element. */
function childStruct(spec: TypeSpec, path: FieldPath): { spec: Record<string, TypeSpec>; path: FieldPath } | undefined {
  let s = spec
  let p = path
  while (Array.isArray(s)) {
    s = s[0]!
    p = [...p, ELEMENT]
  }
  return typeof s === 'object' ? { spec: s as Record<string, TypeSpec>, path: p } : undefined
}

/**
 * `<rulestone-schema-designer>`: a table's inputs and outputs as a tree, with every type (bool,
 * int, string, decimal, money, date, struct, list, nested). Fields can be added, retyped,
 * reordered by drag and drop or Alt+Up and Alt+Down, and renamed: renaming an input updates
 * every rule expression that uses it, through the syntax tree, and reports what changed.
 *
 * @fires rulestone-change - detail `{ table, rename? }`; `rename` is the RenameResult of a rename
 * @csspart tree - each of the two trees
 * @csspart item - one field's row
 * @csspart report - the report of the last rename
 */
export class RulestoneSchemaDesigner extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    table: { attribute: false },
    _active: { state: true },
    _collapsed: { state: true },
    _report: { state: true },
    _nameErrors: { state: true },
    _drop: { state: true },
  }

  /** The table; its rules are needed to rename fields everywhere they are used. */
  declare table: TableJson
  declare _active: string | undefined
  declare _collapsed: ReadonlySet<string>
  declare _report: { result: RenameResult; from: string; to: string } | undefined
  declare _nameErrors: ReadonlyMap<string, string>
  declare _drop: string | undefined

  private focusKey: string | undefined
  private focusName = false
  private drag: Item | undefined

  static override styles = [
    ...RulestoneElement.styles,
    css`
      .sides {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
        gap: 16px;
      }
      section {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 8px 10px;
        background: var(--_color-surface);
        min-width: 0;
      }
      h3 {
        margin: 0 0 2px;
        font-size: 1.05em;
      }
      .help {
        margin: 0 0 8px;
        font-size: 0.9em;
      }
      ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      ul[role='group'] {
        padding-left: 18px;
        border-left: 1px dashed var(--_color-border-strong);
        margin-left: 10px;
      }
      li[role='treeitem'] {
        margin: 3px 0;
      }
      li[role='treeitem']:focus {
        outline: none;
      }
      li[role='treeitem']:focus > .item {
        outline: var(--_focus-width) solid var(--_color-focus);
        outline-offset: 1px;
      }
      .item {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
        background: var(--_color-surface-raised);
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 3px 4px;
      }
      .item.drop {
        box-shadow: 0 -3px 0 var(--_color-focus);
      }
      .item input.name {
        width: 11em;
        font-family: var(--_font-family-mono);
      }
      .item select {
        max-width: 12em;
      }
      .item .buttons {
        margin-left: auto;
        display: flex;
        gap: 2px;
      }
      .item .buttons button,
      .toggle {
        padding: 0 6px;
        min-width: 28px;
        min-height: 28px;
      }
      .toggle {
        border: 0;
        background: transparent;
      }
      .spacer {
        display: inline-block;
        width: 28px;
      }
      .handle {
        cursor: grab;
        color: var(--_color-text-muted);
        user-select: none;
        padding: 0 2px;
      }
      .name-error {
        flex-basis: 100%;
        color: var(--_color-error);
        font-size: 0.88em;
      }
      .report {
        margin-top: 12px;
        border: 1px solid var(--_color-border);
        border-left: 3px solid var(--_color-success);
        border-radius: var(--_radius);
        padding: 6px 10px;
      }
      .report.failed {
        border-left-color: var(--_color-error);
      }
      .report ul {
        list-style: disc;
        padding-left: 20px;
      }
      .report code {
        font-family: var(--_font-family-mono);
        font-size: 0.9em;
      }
      .implicit {
        margin-top: 6px;
        font-size: 0.88em;
      }
    `,
  ]

  constructor() {
    super()
    this.table = normalizeTable({})
    this._active = undefined
    this._collapsed = new Set()
    this._report = undefined
    this._nameErrors = new Map()
    this._drop = undefined
  }

  /** The visible tree items in display order. */
  private items(): Item[] {
    const out: Item[] = []
    const visit = (side: Side, fields: Record<string, TypeSpec>, base: FieldPath, depth: number, parent: string | undefined) => {
      for (const [name, spec] of Object.entries(fields)) {
        const path = [...base, name]
        const key = keyOf(side, path)
        const child = childStruct(spec, path)
        out.push({ side, path, key, depth, spec, parent, expandable: child !== undefined })
        if (child && !this._collapsed.has(key)) visit(side, child.spec, child.path, depth + 1, key)
      }
    }
    visit('inputs', this.table.inputs, [], 1, undefined)
    visit('outputs', this.table.outputs, [], 1, undefined)
    return out
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    if (this.focusKey) {
      const li = this.renderRoot.querySelector<HTMLElement>(`li[data-key="${this.focusKey}"]`)
      const target = this.focusName ? li?.querySelector<HTMLElement>('input.name') : li
      this.focusKey = undefined
      this.focusName = false
      target?.focus()
    }
  }

  private change(table: TableJson, extra: { rename?: RenameResult } = {}, announcement?: string): void {
    this.table = table
    this.emit('rulestone-change', { table, ...extra })
    if (announcement) this.announce(announcement)
  }

  private specs(side: Side): Record<string, TypeSpec> {
    return side === 'inputs' ? this.table.inputs : this.table.outputs
  }

  private withSpecs(side: Side, specs: Record<string, TypeSpec>): TableJson {
    return orderTable({ ...this.table, [side]: specs })
  }

  protected override render() {
    const items = this.items()
    const active = this._active ?? items[0]?.key
    return html`
      <p id="tree-help" class="sr-only">${this.t('schema.help')}</p>
      <div class="sides">
        ${(['inputs', 'outputs'] as const).map((side) => this.renderSide(side, items, active))}
      </div>
      ${this.renderReport()}
      ${this.liveRegion()}
    `
  }

  private renderSide(side: Side, items: readonly Item[], active: string | undefined): TemplateResult {
    const title = side === 'inputs' ? this.t('schema.inputs') : this.t('schema.outputs')
    const top = items.filter((i) => i.side === side && i.depth === 1)
    return html`<section aria-labelledby=${`h-${side}`}>
      <h3 id=${`h-${side}`}>${title}</h3>
      <p class="help muted">${side === 'inputs' ? this.t('schema.inputsHelp') : this.t('schema.outputsHelp')}</p>
      <ul part="tree" role="tree" aria-labelledby=${`h-${side}`} aria-describedby="tree-help" @keydown=${(e: KeyboardEvent) => this.onKeyDown(e, items)}>
        ${top.map((item) => this.renderItem(item, items, active))}
      </ul>
      ${this.readonly
        ? nothing
        : html`<button type="button" @click=${() => this.addAt(side, [])}>+ ${side === 'inputs' ? this.t('schema.addInput') : this.t('schema.addOutput')}</button>`}
      ${side === 'inputs' ? html`<p class="implicit muted">businessDate · date</p>` : nothing}
    </section>`
  }

  private renderItem(item: Item, items: readonly Item[], active: string | undefined): TemplateResult {
    const children = items.filter((i) => i.parent === item.key)
    const collapsed = this._collapsed.has(item.key)
    const siblings = items.filter((i) => i.side === item.side && i.parent === item.parent)
    const index = siblings.findIndex((s) => s.key === item.key)
    const name = item.path.at(-1)!
    const error = this._nameErrors.get(item.key)
    const nameId = `n-${item.key}`
    return html`<li
      role="treeitem"
      data-key=${item.key}
      aria-level=${item.depth}
      aria-setsize=${siblings.length}
      aria-posinset=${index + 1}
      aria-expanded=${item.expandable ? (collapsed ? 'false' : 'true') : nothing}
      aria-selected=${item.key === active ? 'true' : 'false'}
      aria-label=${`${item.path.filter((s) => s !== ELEMENT).join('.')} · ${describe(item.spec)}`}
      tabindex=${item.key === active ? 0 : -1}
      @focus=${(e: FocusEvent) => {
        if (e.target === e.currentTarget) this._active = item.key
      }}
    >
      <div
        part="item"
        class="item ${this._drop === item.key ? 'drop' : ''}"
        @dragover=${(e: DragEvent) => {
          if (this.drag && this.drag.parent === item.parent && this.drag.side === item.side) {
            e.preventDefault()
            this._drop = item.key
          }
        }}
        @drop=${(e: DragEvent) => {
          e.preventDefault()
          const dragged = this.drag
          this.drag = undefined
          this._drop = undefined
          if (dragged && dragged.key !== item.key) this.moveTo(dragged, index, siblings.length)
        }}
      >
        ${this.readonly
          ? nothing
          : html`<span
              class="handle"
              draggable="true"
              aria-hidden="true"
              title=${this.t('common.dragHandle')}
              @dragstart=${(e: DragEvent) => {
                this.drag = item
                e.dataTransfer?.setData('text/plain', item.key)
              }}
              @dragend=${() => {
                this.drag = undefined
                this._drop = undefined
              }}
            >⋮⋮</span>`}
        ${item.expandable
          ? html`<button
              type="button"
              class="toggle"
              tabindex="-1"
              aria-label=${collapsed ? this.t('schema.expand') : this.t('schema.collapse')}
              @click=${() => this.toggle(item.key)}
            >${collapsed ? '▸' : '▾'}</button>`
          : html`<span class="spacer"></span>`}
        <input
          class="name"
          id=${nameId}
          aria-label=${this.t('schema.name')}
          .value=${name}
          ?readonly=${this.readonly}
          aria-invalid=${error ? 'true' : 'false'}
          aria-describedby=${error ? `${nameId}-err` : nothing}
          @input=${(e: Event) => this.checkName(item, (e.target as HTMLInputElement).value)}
          @change=${(e: Event) => this.rename(item, e.target as HTMLInputElement)}
          @keydown=${(e: KeyboardEvent) => {
            if (e.key === 'Escape') {
              ;(e.target as HTMLInputElement).value = name
              this.checkName(item, name)
              this.focusKey = item.key
              this.requestUpdate()
            }
          }}
        />
        ${this.typeControls(item, item.spec, [])}
        ${this.readonly
          ? nothing
          : html`<span class="buttons">
              ${childStruct(item.spec, item.path)
                ? html`<button type="button" title=${this.t('schema.addField')} aria-label=${`${this.t('schema.addField')}: ${name}`} @click=${() => this.addAt(item.side, childStruct(item.spec, item.path)!.path)}>+</button>`
                : nothing}
              <button type="button" title=${this.t('common.moveUp')} aria-label=${`${this.t('common.moveUp')}: ${name}`} ?disabled=${index === 0} @click=${() => this.moveTo(item, index - 1, siblings.length)}>↑</button>
              <button type="button" title=${this.t('common.moveDown')} aria-label=${`${this.t('common.moveDown')}: ${name}`} ?disabled=${index === siblings.length - 1} @click=${() => this.moveTo(item, index + 1, siblings.length)}>↓</button>
              <button type="button" title=${this.t('schema.deleteField', { name })} aria-label=${this.t('schema.deleteField', { name })} @click=${() => this.deleteItem(item)}>✕</button>
            </span>`}
        ${error ? html`<span class="name-error" id=${`${nameId}-err`} role="alert">${error}</span>` : nothing}
      </div>
      ${item.expandable && !collapsed ? html`<ul role="group">${children.map((c) => this.renderItem(c, items, active))}</ul>` : nothing}
    </li>`
  }

  /** The type select, and for a list the select of its item type, recursively. */
  private typeControls(item: Item, spec: TypeSpec, listDepth: readonly string[]): TemplateResult {
    const kind = kindOf(spec)
    const kinds: TypeKind[] = item.side === 'outputs' ? (listDepth.length > 0 ? [...PRIMITIVES] : [...PRIMITIVES, 'list']) : [...PRIMITIVES, 'struct', 'list']
    const label = listDepth.length === 0 ? this.t('schema.type') : this.t('schema.elementType')
    const select = html`<select
      aria-label=${label}
      title=${label}
      ?disabled=${this.readonly}
      @change=${(e: Event) => this.retype(item, listDepth, specOfKind((e.target as HTMLSelectElement).value as TypeKind))}
    >
      ${kinds.map((k) => html`<option value=${k} ?selected=${k === kind}>${this.t(`types.${k}`)}</option>`)}
    </select>`
    if (kind === 'list') return html`${select}${this.typeControls(item, (spec as TypeSpec[])[0]!, [...listDepth, ELEMENT])}`
    return select
  }

  private renderReport(): TemplateResult {
    const report = this._report
    if (!report) return html``
    const { result } = report
    if (!result.ok) {
      return html`<div part="report" class="report failed" role="status">
        ${this.t(`schema.nameProblems.${result.reason}`, { rule: result.ruleId ?? '' })}
      </div>`
    }
    return html`<div part="report" class="report" role="status">
      <strong>${this.t('schema.renamed', { from: report.from, to: report.to })}</strong>
      ${result.changes.length === 0
        ? html` ${this.t('schema.renameNone')}`
        : html`<div>${this.t('schema.renameChanges', { n: result.changes.reduce((n, c) => n + c.occurrences, 0) })}</div>
            <ul>
              ${result.changes.map((c) => html`<li>${this.t('schema.cell', { rule: c.ruleId, cell: c.cell })}: <code>${c.after}</code></li>`)}
            </ul>`}
      ${result.skipped.length > 0
        ? html`<div class="warning-text">${this.t('schema.renameSkipped')}</div>
            <ul>${result.skipped.map((s) => html`<li>${this.t('schema.cell', { rule: s.ruleId, cell: s.cell })}</li>`)}</ul>`
        : nothing}
    </div>`
  }

  // --- Operations ------------------------------------------------------------------------------

  private toggle(key: string): void {
    const next = new Set(this._collapsed)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    this._collapsed = next
  }

  private siblingsOf(side: Side, path: FieldPath): string[] {
    const parent = path.length === 1 ? this.specs(side) : specAt(this.specs(side), path.slice(0, -1))
    return parent && typeof parent === 'object' && !Array.isArray(parent) ? Object.keys(parent).filter((n) => n !== path.at(-1)) : []
  }

  private checkName(item: Item, name: string): void {
    const problem = nameProblem(name, this.siblingsOf(item.side, item.path), item.side === 'inputs' && item.path.length === 1)
    const errors = new Map(this._nameErrors)
    if (problem && name !== item.path.at(-1)) errors.set(item.key, this.t(`schema.nameProblems.${problem}`, { rule: '' }))
    else errors.delete(item.key)
    this._nameErrors = errors
  }

  private rename(item: Item, input: HTMLInputElement): void {
    const from = item.path.filter((s) => s !== ELEMENT).join('.')
    const to = input.value.trim()
    if (to === item.path.at(-1)) return
    const result = item.side === 'inputs' ? renameInput(this.table, item.path, to) : item.path.length === 1 ? renameOutput(this.table, item.path[0]!, to) : renameStructOutput()
    const display = [...item.path.slice(0, -1).filter((s) => s !== ELEMENT), to].join('.')
    this._report = { result, from, to: display }
    if (!result.ok) {
      input.value = item.path.at(-1)!
      this.checkName(item, input.value)
      this.announce(this.t(`schema.nameProblems.${result.reason}`, { rule: result.ruleId ?? '' }))
      return
    }
    const errors = new Map(this._nameErrors)
    errors.delete(item.key)
    this._nameErrors = errors
    const newKey = keyOf(item.side, [...item.path.slice(0, -1), to])
    this._active = newKey
    this.change(result.table, { rename: result }, `${this.t('schema.renamed', { from, to: display })} ${this.t('schema.renameChanges', { n: result.changes.length })}`)
  }

  private retype(item: Item, listDepth: readonly string[], spec: TypeSpec): void {
    // listDepth points inside the list types of the field: replace the type at that depth.
    const replace = (current: TypeSpec, depth: number): TypeSpec =>
      depth === 0 ? spec : [replace((current as TypeSpec[])[0]!, depth - 1)]
    const next = replace(item.spec, listDepth.length)
    this.change(this.withSpecs(item.side, setFieldType(this.specs(item.side), item.path, next)))
  }

  private addAt(side: Side, parent: FieldPath): void {
    const fields = parent.length === 0 ? this.specs(side) : (specAt(this.specs(side), parent) as Record<string, TypeSpec>)
    const name = newFieldName(fields, parent.length === 0 ? (side === 'inputs' ? 'input' : 'output') : 'field')
    const path = [...parent, name]
    this.focusKey = keyOf(side, path)
    this.focusName = true
    this._active = this.focusKey
    this.change(this.withSpecs(side, addField(this.specs(side), parent, name, 'string')), {}, this.t('schema.added', { name }))
  }

  private deleteItem(item: Item): void {
    const name = item.path.filter((s) => s !== ELEMENT).join('.')
    this._active = item.parent
    this.focusKey = item.parent
    this.change(this.withSpecs(item.side, removeField(this.specs(item.side), item.path)), {}, this.t('schema.deleted', { name }))
  }

  private moveTo(item: Item, to: number, count: number): void {
    if (to < 0 || to >= count) return
    this.focusKey = item.key
    this._active = item.key
    this.change(
      this.withSpecs(item.side, moveField(this.specs(item.side), item.path, to)),
      {},
      this.t('schema.moved', { name: item.path.at(-1)!, position: to + 1, count }),
    )
  }

  private onKeyDown(e: KeyboardEvent, items: readonly Item[]): void {
    const li = (e.target as HTMLElement).closest('li[role="treeitem"]') as HTMLElement | null
    if (!li || e.target !== li) return
    const index = items.findIndex((i) => i.key === li.dataset['key'])
    const item = items[index]
    if (!item) return
    const go = (target: Item | undefined) => {
      if (!target) return
      e.preventDefault()
      this._active = target.key
      this.focusKey = target.key
      this.requestUpdate()
    }
    if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault()
      if (this.readonly) return
      const siblings = items.filter((i) => i.side === item.side && i.parent === item.parent)
      const at = siblings.findIndex((s) => s.key === item.key)
      this.moveTo(item, at + (e.key === 'ArrowUp' ? -1 : 1), siblings.length)
      return
    }
    switch (e.key) {
      case 'ArrowDown':
        return go(items[index + 1])
      case 'ArrowUp':
        return go(items[index - 1])
      case 'Home':
        return go(items[0])
      case 'End':
        return go(items.at(-1))
      case 'ArrowRight':
        if (item.expandable && this._collapsed.has(item.key)) {
          e.preventDefault()
          this.toggle(item.key)
        } else if (item.expandable) go(items[index + 1])
        return
      case 'ArrowLeft':
        if (item.expandable && !this._collapsed.has(item.key)) {
          e.preventDefault()
          this.toggle(item.key)
        } else go(items.find((i) => i.key === item.parent))
        return
      case 'Enter':
      case 'F2':
        e.preventDefault()
        li.querySelector<HTMLElement>('input.name')?.focus()
        return
      default:
        return
    }
  }
}

/** Outputs cannot be structs, so an output never has nested fields to rename. */
function renameStructOutput(): RenameResult {
  return { ok: false, reason: 'notFound' }
}

function describe(spec: TypeSpec): string {
  if (typeof spec === 'string') return spec
  if (Array.isArray(spec)) return `list<${describe(spec[0]!)}>`
  return 'struct'
}

define('rulestone-schema-designer', RulestoneSchemaDesigner)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-schema-designer': RulestoneSchemaDesigner
  }
}
