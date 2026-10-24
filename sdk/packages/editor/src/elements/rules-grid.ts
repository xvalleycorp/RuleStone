// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { css, html, nothing, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import { tableVariables, valueTypeOf } from '../model/schema.js'
import {
  addRule, deleteRule, duplicateRule, moveRule, normalizeTable, setOutput, updateRule, type TableJson,
} from '../model/table.js'
import { expressionProblems, type Problem } from '../model/validate.js'
import { RulestoneElement, define, highlight } from './base.js'
import './cel-input.js'
import './condition-builder.js'
import type { RsCelInput } from './cel-input.js'

type ColumnKey = 'status' | 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'actions'

interface Column {
  readonly key: ColumnKey
  readonly output?: string
  readonly label: string
}

function cellKey(rule: number, field: string, output?: string): string {
  return `${rule}:${field}:${output ?? ''}`
}

/**
 * `<rulestone-rules-grid>`: the rules of a decision table as a spreadsheet-like grid, one rule
 * per row: id, condition, one column per output, reason code and description. Rules can be
 * added, duplicated, deleted and reordered by drag and drop or with Alt+Up and Alt+Down.
 * Every expression cell is type-checked with the engine as it is edited.
 *
 * @fires rulestone-change - detail `{ table }` after every edit
 * @csspart grid - the table element
 * @csspart row - a rule's row; also `row matched`, `row not-matched`, `row not-evaluated`
 * @csspart cell - a cell; also `cell invalid`
 * @csspart toolbar - the toolbar above the grid
 */
export class RulestoneRulesGrid extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    table: { attribute: false },
    problems: { attribute: false },
    matchedRules: { attribute: false },
    evaluatedRules: { attribute: false },
    _row: { state: true },
    _col: { state: true },
    _editing: { state: true },
    _interact: { state: true },
    _expanded: { state: true },
    _dropIndex: { state: true },
  }

  /** The table, as parsed .stone.json. Only its rules and outputs are edited here. */
  declare table: TableJson
  /** Problems found elsewhere (the table editor's full validation, the host's validator). */
  declare problems: readonly Problem[]
  /** Ids of the rules that matched in the last Try it run; undefined for no run. */
  declare matchedRules: readonly string[] | undefined
  /** Ids of the rules evaluated in the last run, in order (the trace). */
  declare evaluatedRules: readonly string[] | undefined
  declare _row: number
  declare _col: number
  declare _editing: boolean
  declare _interact: boolean
  declare _expanded: ReadonlySet<string>
  declare _dropIndex: number | undefined

  private focusPending: 'cell' | 'button' | undefined
  private caretAt: number | undefined
  private dragFrom: number | undefined

  static override styles = [
    ...RulestoneElement.styles,
    css`
      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: center;
        margin-bottom: 8px;
      }
      .scroll {
        overflow-x: auto;
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
      }
      table {
        border-collapse: collapse;
        width: 100%;
        min-width: 640px;
      }
      th,
      td {
        border-bottom: 1px solid var(--_color-border);
        border-right: 1px solid var(--_color-border);
        padding: 4px 6px;
        text-align: left;
        vertical-align: top;
      }
      th {
        position: sticky;
        top: 0;
        background: var(--_color-surface);
        font-weight: 600;
        font-size: 0.92em;
        white-space: nowrap;
      }
      th .type {
        font-weight: 400;
        color: var(--_color-text-muted);
      }
      td.expr {
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        min-width: 140px;
      }
      td.when {
        min-width: 240px;
      }
      td:focus {
        outline: var(--_focus-width) solid var(--_color-focus);
        outline-offset: -2px;
      }
      td.invalid {
        background: var(--_color-error-subtle);
      }
      td.warning {
        background: var(--_color-warning-subtle);
      }
      td .problem {
        display: block;
        font-family: var(--_font-family);
        font-size: 0.85em;
        color: var(--_color-error);
        white-space: normal;
      }
      td .problem.warn {
        color: var(--_color-warning);
      }
      td.status {
        white-space: nowrap;
        width: 1%;
      }
      td.actions {
        white-space: nowrap;
        width: 1%;
      }
      td.actions button {
        padding: 0 6px;
        min-width: 28px;
        min-height: 28px;
      }
      .unset {
        color: var(--_color-text-muted);
        font-family: var(--_font-family);
      }
      tr.matched > td {
        background: var(--_color-match);
      }
      tr.not-evaluated > td:not(.invalid) {
        color: var(--_color-text-muted);
      }
      tr.drop-before > td {
        box-shadow: inset 0 3px 0 var(--_color-focus);
      }
      tr.drop-after > td {
        box-shadow: inset 0 -3px 0 var(--_color-focus);
      }
      .handle {
        cursor: grab;
        user-select: none;
        padding: 0 4px;
        color: var(--_color-text-muted);
      }
      .badge {
        display: inline-block;
        min-width: 1.4em;
        text-align: center;
        font-weight: 700;
      }
      .badge.matched {
        color: var(--_color-success);
      }
      tr.builder > td {
        background: var(--_color-surface);
      }
      td input {
        width: 100%;
        min-width: 8em;
      }
      .empty {
        padding: 12px;
      }
    `,
  ]

  constructor() {
    super()
    this.table = normalizeTable({ id: '', version: '', hitPolicy: 'FIRST', inputs: {}, outputs: {}, rules: [] })
    this.problems = []
    this.matchedRules = undefined
    this.evaluatedRules = undefined
    this._row = 0
    this._col = 1
    this._editing = false
    this._interact = false
    this._expanded = new Set()
    this._dropIndex = undefined
  }

  private columns(): Column[] {
    return [
      { key: 'status', label: this.t('grid.status') },
      { key: 'id', label: this.t('grid.id') },
      { key: 'when', label: this.t('grid.when') },
      ...Object.keys(this.table.outputs).map((output) => ({ key: 'then' as const, output, label: output })),
      { key: 'reasonCode', label: this.t('grid.reasonCode') },
      { key: 'description', label: this.t('grid.description') },
      { key: 'actions', label: this.t('grid.actions') },
    ]
  }

  /** Problems by cell: the grid's own expression checks plus the given non-expression ones. */
  private cellProblems(): Map<string, Problem[]> {
    const map = new Map<string, Problem[]>()
    const all = [...expressionProblems(this.table), ...this.problems.filter((p) => p.source !== 'expression')]
    for (const p of all) {
      if (p.target.kind !== 'rule') continue
      const field = p.target.field === 'rule' ? 'id' : p.target.field
      const key = cellKey(p.target.rule, field, p.target.output)
      const list = map.get(key) ?? []
      if (!list.some((q) => q.message === p.message && q.issue?.pos === p.issue?.pos)) list.push(p)
      map.set(key, list)
    }
    return map
  }

  private change(table: TableJson, announcement?: string): void {
    this.table = table
    this.emit('rulestone-change', { table })
    if (announcement) this.announce(announcement)
  }

  /** Moves focus to a cell; for an expression with a problem position, starts editing there. */
  focusCell(rule: number, field: 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'rule', output?: string, index?: number): void {
    const columns = this.columns()
    const col = columns.findIndex((c) => c.key === (field === 'rule' ? 'id' : field) && (field !== 'then' || c.output === output))
    this._row = Math.max(0, Math.min(rule, this.table.rules.length - 1))
    this._col = col < 0 ? 1 : col
    this._interact = false
    if (index !== undefined && (field === 'when' || field === 'then') && !this.readonly) {
      this._editing = true
      this.caretAt = index
    } else {
      this._editing = false
      this.focusPending = 'cell'
    }
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('table')) {
      const rows = this.table.rules.length
      if (this._row >= rows) this._row = Math.max(0, rows - 1)
      const cols = this.columns().length
      if (this._col >= cols) this._col = cols - 1
    }
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    if (this._editing) {
      const editor = this.renderRoot.querySelector<HTMLElement>('td.editing rs-cel-input, td.editing input')
      if (editor && this.caretAt !== undefined) {
        const at = this.caretAt
        this.caretAt = undefined
        void (editor as RsCelInput).updateComplete?.then(() => (editor as RsCelInput).focusAt(at))
      } else if (editor && !editor.matches(':focus-within')) {
        void (editor as RsCelInput).updateComplete?.then(() => editor.focus())
        if (!(editor as RsCelInput).updateComplete) editor.focus()
      }
    } else if (this.focusPending) {
      const cell = this.renderRoot.querySelector<HTMLElement>(`td[data-row="${this._row}"][data-col="${this._col}"]`)
      const target = this.focusPending === 'button' ? cell?.querySelector<HTMLElement>('button') : cell
      this.focusPending = undefined
      target?.focus()
    }
  }

  protected override render() {
    const columns = this.columns()
    const problems = this.cellProblems()
    const rules = this.table.rules
    return html`
      <div part="toolbar" class="toolbar">
        ${this.readonly
          ? nothing
          : html`<button type="button" class="primary" @click=${() => this.add()}>+ ${this.t('grid.addRule')}</button>`}
        <span class="muted">${this.table.hitPolicy === 'FIRST' ? this.t('grid.firstHint') : this.t(`hitPolicies.${this.table.hitPolicy as 'FIRST'}`)}</span>
      </div>
      <p id="grid-help" class="sr-only">${this.t('grid.help')}</p>
      ${Object.keys(this.table.outputs).length === 0 ? html`<p class="muted">${this.t('grid.noOutputs')}</p>` : nothing}
      <div class="scroll">
        <table
          part="grid"
          role="grid"
          aria-label=${this.t('grid.label')}
          aria-describedby="grid-help"
          aria-rowcount=${rules.length + 1}
          aria-colcount=${columns.length}
          @keydown=${this.onKeyDown}
        >
          <thead>
            <tr role="row" aria-rowindex="1">
              ${columns.map(
                (c, i) => html`<th role="columnheader" scope="col" aria-colindex=${i + 1}>
                  ${c.key === 'status' ? html`<span class="sr-only">${c.label}</span>` : c.label}
                  ${c.key === 'then' ? html`<span class="type">${typeLabel(this.table.outputs[c.output!])}</span>` : nothing}
                </th>`,
              )}
            </tr>
          </thead>
          <tbody>
            ${rules.length === 0
              ? html`<tr><td class="empty muted" colspan=${columns.length}>${this.t('grid.noRules')}</td></tr>`
              : rules.map((_, r) => this.renderRow(r, columns, problems))}
          </tbody>
        </table>
      </div>
      ${this.liveRegion()}
    `
  }

  private rowState(id: string): 'matched' | 'not-matched' | 'not-evaluated' | undefined {
    if (this.evaluatedRules === undefined) return undefined
    if (this.matchedRules?.includes(id)) return 'matched'
    return this.evaluatedRules.includes(id) ? 'not-matched' : 'not-evaluated'
  }

  private renderRow(r: number, columns: readonly Column[], problems: Map<string, Problem[]>): TemplateResult {
    const rule = this.table.rules[r]!
    const state = this.rowState(rule.id)
    const drop = this._dropIndex === undefined || this.dragFrom === undefined ? '' : this._dropIndex === r ? (this._dropIndex > this.dragFrom ? 'drop-after' : 'drop-before') : ''
    const expanded = this._expanded.has(String(r))
    const row = html`<tr
      part="row ${state ?? ''}"
      role="row"
      class="${state ?? ''} ${drop}"
      aria-rowindex=${r + 2}
      @dragover=${(e: DragEvent) => this.onDragOver(e, r)}
      @drop=${(e: DragEvent) => this.onDrop(e)}
    >
      ${columns.map((c, i) => this.renderCell(r, i, c, problems))}
    </tr>`
    if (!expanded) return row
    return html`${row}
      <tr class="builder">
        <td colspan=${columns.length}>
          <rulestone-condition-builder
            .label=${this.t('grid.builderFor', { id: rule.id })}
            .value=${rule.when}
            .inputs=${this.table.inputs}
            .locale=${this.effectiveLocale}
            .messages=${this.messages}
            ?readonly=${this.readonly}
            @rulestone-change=${(e: CustomEvent<{ value: string }>) => {
              e.stopPropagation()
              this.change(updateRule(this.table, r, { when: e.detail.value }))
            }}
          ></rulestone-condition-builder>
        </td>
      </tr>`
  }

  private renderCell(r: number, c: number, column: Column, problems: Map<string, Problem[]>): TemplateResult {
    const active = r === this._row && c === this._col
    const editing = active && this._editing
    const field = column.key === 'status' || column.key === 'actions' ? undefined : column.key
    const cellProblems = field ? problems.get(cellKey(r, field, column.output)) ?? [] : []
    const errors = cellProblems.filter((p) => p.severity === 'error')
    const problemId = `p-${r}-${c}`
    const classes = [column.key, column.key === 'when' || column.key === 'then' ? 'expr' : '', editing ? 'editing' : '', errors.length > 0 ? 'invalid' : cellProblems.length > 0 ? 'warning' : ''].join(' ')
    return html`<td
      part="cell${errors.length > 0 ? ' invalid' : ''}"
      role="gridcell"
      class=${classes}
      data-row=${r}
      data-col=${c}
      aria-colindex=${c + 1}
      tabindex=${active && !editing && !this._interact ? 0 : -1}
      aria-invalid=${field ? (errors.length > 0 ? 'true' : 'false') : nothing}
      aria-describedby=${cellProblems.length > 0 ? problemId : nothing}
      @click=${() => this.activate(r, c)}
      @dblclick=${() => this.startEdit(r, c)}
      @focusin=${() => {
        if (this._row !== r || this._col !== c) {
          this._row = r
          this._col = c
        }
      }}
    >
      ${editing ? this.renderEditor(r, column) : this.renderDisplay(r, column, cellProblems)}
      ${cellProblems.length > 0
        ? html`<span id=${problemId} class="problem ${errors.length === 0 ? 'warn' : ''}">${cellProblems.map((p) =>
            p.issue ? this.t('grid.cellProblem', { line: p.issue.line, column: p.issue.column, message: p.message.split('\n')[0]! }) : p.message,
          ).join(' · ')}</span>`
        : nothing}
    </td>`
  }

  private renderDisplay(r: number, column: Column, problems: readonly Problem[]): TemplateResult {
    const rule = this.table.rules[r]!
    switch (column.key) {
      case 'status': {
        const state = this.rowState(rule.id)
        const badge =
          state === 'matched' ? html`<span class="badge matched" aria-hidden="true">✓</span><span class="sr-only">${this.t('grid.matched')}</span>`
          : state === 'not-matched' ? html`<span class="badge" aria-hidden="true">✗</span><span class="sr-only">${this.t('grid.notMatched')}</span>`
          : state === 'not-evaluated' ? html`<span class="badge" aria-hidden="true">–</span><span class="sr-only">${this.t('grid.notEvaluated')}</span>`
          : nothing
        return html`${this.readonly
          ? nothing
          : html`<span
              class="handle"
              draggable="true"
              title=${this.t('common.dragHandle')}
              aria-hidden="true"
              @dragstart=${(e: DragEvent) => {
                this.dragFrom = r
                e.dataTransfer?.setData('text/plain', rule.id)
                if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
              }}
              @dragend=${() => {
                this.dragFrom = undefined
                this._dropIndex = undefined
              }}
            >⋮⋮</span>`}<span class="muted">${r + 1}</span> ${badge}`
      }
      case 'id':
        return html`${rule.id}`
      case 'when':
        return highlight(rule.when, problems.flatMap((p) => (p.issue ? [p.issue.index] : [])))
      case 'then': {
        const expression = rule.then?.[column.output!]
        if (expression === undefined) return html`<span class="unset">—<span class="sr-only">${this.t('common.notSet')}</span></span>`
        return highlight(expression, problems.flatMap((p) => (p.issue ? [p.issue.index] : [])))
      }
      case 'reasonCode':
        return html`<span class="mono">${rule.reasonCode ?? ''}</span>`
      case 'description':
        return html`${rule.description ?? ''}`
      case 'actions':
        return this.renderActions(r)
    }
  }

  private renderActions(r: number): TemplateResult {
    const rule = this.table.rules[r]!
    const tab = r === this._row && this._col === this.columns().length - 1 && this._interact ? 0 : -1
    const expanded = this._expanded.has(String(r))
    return html`<button
        type="button"
        tabindex=${tab}
        aria-pressed=${expanded ? 'true' : 'false'}
        title=${expanded ? this.t('grid.hideBlocks') : this.t('grid.blocks')}
        aria-label=${`${expanded ? this.t('grid.hideBlocks') : this.t('grid.blocks')}: ${rule.id}`}
        @click=${() => {
          const next = new Set(this._expanded)
          if (expanded) next.delete(String(r))
          else next.add(String(r))
          this._expanded = next
        }}
      >⊞</button>${this.readonly
        ? nothing
        : html`<button type="button" tabindex=${tab} title=${this.t('common.moveUp')} aria-label=${`${this.t('common.moveUp')}: ${rule.id}`} ?disabled=${r === 0} @click=${() => this.move(r, r - 1)}>↑</button
            ><button type="button" tabindex=${tab} title=${this.t('common.moveDown')} aria-label=${`${this.t('common.moveDown')}: ${rule.id}`} ?disabled=${r === this.table.rules.length - 1} @click=${() => this.move(r, r + 1)}>↓</button
            ><button type="button" tabindex=${tab} title=${this.t('common.duplicate')} aria-label=${`${this.t('common.duplicate')}: ${rule.id}`} @click=${() => this.duplicate(r)}>⧉+</button
            ><button type="button" tabindex=${tab} title=${this.t('common.delete')} aria-label=${`${this.t('common.delete')}: ${rule.id}`} @click=${() => this.deleteAt(r)}>✕</button>`}`
  }

  private renderEditor(r: number, column: Column): TemplateResult {
    const rule = this.table.rules[r]!
    const label = this.t('grid.edit', { column: column.label, id: rule.id })
    if (column.key === 'when' || column.key === 'then') {
      const output = column.output
      const spec = output === undefined ? undefined : this.table.outputs[output]
      const type = spec === undefined ? undefined : valueTypeOf(spec)
      const current = column.key === 'when' ? rule.when : rule.then?.[output!] ?? ''
      return html`<rs-cel-input
        multiline
        .value=${current}
        .variables=${tableVariables(this.table.inputs)}
        .expected=${column.key === 'when' ? 'bool' : type}
        .label=${label}
        .locale=${this.effectiveLocale}
        .messages=${this.messages}
        @rulestone-commit=${(e: CustomEvent<{ value: string }>) => this.commitEdit(r, column, e.detail.value)}
        @rulestone-cancel=${() => this.stopEdit()}
      ></rs-cel-input>`
    }
    const value = column.key === 'id' ? rule.id : column.key === 'reasonCode' ? rule.reasonCode ?? '' : rule.description ?? ''
    return html`<input
      aria-label=${label}
      .value=${value}
      class=${column.key === 'reasonCode' ? 'mono' : ''}
      @keydown=${(e: KeyboardEvent) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          e.stopPropagation()
          this.commitEdit(r, column, (e.target as HTMLInputElement).value)
        } else if (e.key === 'Escape') {
          e.stopPropagation()
          this.stopEdit()
        }
      }}
      @blur=${(e: FocusEvent) => this.commitEdit(r, column, (e.target as HTMLInputElement).value)}
    />`
  }

  private commitEdit(r: number, column: Column, value: string): void {
    if (!this._editing) return
    const rule = this.table.rules[r]
    this._editing = false
    this.focusPending = 'cell'
    if (!rule) return
    let table = this.table
    switch (column.key) {
      case 'id':
        if (value.trim() !== rule.id) table = updateRule(table, r, { id: value.trim() })
        break
      case 'when':
        if (value !== rule.when) table = updateRule(table, r, { when: value })
        break
      case 'then': {
        const current = rule.then?.[column.output!]
        const next = value.trim() === '' ? undefined : value
        if (next !== current) table = setOutput(table, r, column.output!, next)
        break
      }
      case 'reasonCode':
        if (value.trim() !== (rule.reasonCode ?? '')) table = updateRule(table, r, { reasonCode: value.trim() })
        break
      case 'description':
        if (value !== (rule.description ?? '')) table = updateRule(table, r, { description: value })
        break
      default:
        break
    }
    if (table !== this.table) this.change(table)
  }

  private stopEdit(): void {
    this._editing = false
    this.focusPending = 'cell'
  }

  private activate(r: number, c: number): void {
    if (this._editing && (r !== this._row || c !== this._col)) this._editing = false
    this._row = r
    this._col = c
  }

  private startEdit(r: number, c: number): void {
    const key = this.columns()[c]?.key
    if (this.readonly || key === 'status' || key === 'actions') return
    this._row = r
    this._col = c
    this._editing = true
  }

  private onKeyDown(e: KeyboardEvent): void {
    if (this._editing) return
    const columns = this.columns()
    const rows = this.table.rules.length
    if (rows === 0) return
    const actions = columns[this._col]?.key === 'actions'
    if (this._interact) {
      if (e.key === 'Escape') {
        e.preventDefault()
        this._interact = false
        this.focusPending = 'cell'
      }
      return
    }
    if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault()
      if (!this.readonly) this.move(this._row, this._row + (e.key === 'ArrowUp' ? -1 : 1))
      return
    }
    let row = this._row
    let col = this._col
    switch (e.key) {
      case 'ArrowUp':
        row = Math.max(0, row - 1)
        break
      case 'ArrowDown':
        row = Math.min(rows - 1, row + 1)
        break
      case 'ArrowLeft':
        col = Math.max(0, col - 1)
        break
      case 'ArrowRight':
        col = Math.min(columns.length - 1, col + 1)
        break
      case 'Home':
        col = 0
        if (e.ctrlKey) row = 0
        break
      case 'End':
        col = columns.length - 1
        if (e.ctrlKey) row = rows - 1
        break
      case 'PageUp':
        row = Math.max(0, row - 10)
        break
      case 'PageDown':
        row = Math.min(rows - 1, row + 10)
        break
      case 'Enter':
      case 'F2':
        e.preventDefault()
        if (actions) {
          this._interact = true
          this.focusPending = 'button'
        } else this.startEdit(row, col)
        return
      default:
        return
    }
    e.preventDefault()
    this._row = row
    this._col = col
    this.focusPending = 'cell'
  }

  private add(): void {
    const { table, index } = addRule(this.table)
    this._row = index
    this._col = 2
    this.focusPending = 'cell'
    this.change(table, this.t('grid.added', { id: table.rules[index]!.id, position: index + 1 }))
  }

  private duplicate(r: number): void {
    const { table, index } = duplicateRule(this.table, r)
    this._row = index
    this.focusPending = this._interact ? 'button' : 'cell'
    this.change(table, this.t('grid.duplicated', { id: this.table.rules[r]!.id, copy: table.rules[index]!.id }))
  }

  private deleteAt(r: number): void {
    const id = this.table.rules[r]!.id
    this._interact = false
    this._row = Math.max(0, r - 1)
    this.focusPending = 'cell'
    this.change(deleteRule(this.table, r), this.t('grid.deleted', { id }))
  }

  /** Moves a rule and keeps focus on it, announcing its new position. */
  move(from: number, to: number): void {
    const count = this.table.rules.length
    if (to < 0 || to >= count || from === to) return
    const id = this.table.rules[from]!.id
    const expanded = this._expanded.has(String(from))
    if (expanded) {
      const next = new Set(this._expanded)
      next.delete(String(from))
      next.add(String(to))
      this._expanded = next
    }
    this._row = to
    this.focusPending = this._interact ? 'button' : 'cell'
    this.change(moveRule(this.table, from, to), this.t('grid.moved', { id, position: to + 1, count }))
  }

  private onDragOver(e: DragEvent, r: number): void {
    if (this.dragFrom === undefined) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
    if (this._dropIndex !== r) this._dropIndex = r
  }

  private onDrop(e: DragEvent): void {
    e.preventDefault()
    const from = this.dragFrom
    const to = this._dropIndex
    this.dragFrom = undefined
    this._dropIndex = undefined
    if (from !== undefined && to !== undefined) this.move(from, to)
  }
}

function typeLabel(spec: unknown): string {
  if (typeof spec === 'string') return ` · ${spec}`
  if (Array.isArray(spec)) return ` · list<${typeof spec[0] === 'string' ? spec[0] : '…'}>`
  return ''
}

define('rulestone-rules-grid', RulestoneRulesGrid)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-rules-grid': RulestoneRulesGrid
  }
}
