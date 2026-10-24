// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { currencyTable, type ValueType } from '@rulestone/engine'
import { css, html, nothing, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import {
  defaultValue, fieldSpec, findNode, findParent, indent, insertNode, moveBy, moveNode, newGroup, newLeaf, newQuantifier,
  newRaw, numberValue, operatorsFor, outdent, parseCondition, printCondition, printValue, removeNode, takesValue, updateNode,
  type CondNode, type Group, type Leaf, type Operator, type Quantifier, type Raw, type ValueNode,
} from '../model/condition.js'
import { describeType, fieldsOf, tableVariables, valueTypeOf } from '../model/schema.js'
import type { TypeSpec } from '../model/table.js'
import { RulestoneElement, define } from './base.js'
import './cel-input.js'

type Scope = ReadonlyMap<string, TypeSpec>

const CURRENCIES = [...currencyTable().keys()].sort()

function containsRaw(node: CondNode): boolean {
  if (node.kind === 'raw') return true
  if (node.kind === 'group') return node.children.some(containsRaw)
  if (node.kind === 'quantifier') return containsRaw(node.body)
  return false
}

/**
 * `<rulestone-condition-builder>`: edits a rule's condition as nested AND/OR blocks, with a
 * field picker from the schema, operators for the field's type and typed value inputs, or as
 * CEL text. Unedited CEL is kept exactly; CEL that blocks cannot show stays as text.
 *
 * @fires rulestone-change - detail `{ value }`: the condition as CEL
 * @csspart toolbar - the mode switch
 * @csspart group - an AND/OR group
 * @csspart block - one condition block
 */
export class RulestoneConditionBuilder extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    value: { type: String },
    inputs: { attribute: false },
    mode: { type: String, reflect: true },
    label: { type: String },
    _model: { state: true },
    _drop: { state: true },
    _invalid: { state: true },
  }

  /** The condition as CEL. */
  declare value: string
  /** The table's inputs, as in .stone.json. */
  declare inputs: Record<string, TypeSpec>
  /** 'blocks' or 'cel'. */
  declare mode: 'blocks' | 'cel'
  /** The accessible name of the builder. */
  declare label: string
  declare _model: Group
  declare _drop: { group: string; index: number } | undefined
  declare _invalid: ReadonlyMap<string, string>

  private parsedValue: string | undefined
  private dragId: string | undefined
  private focusAfter: string | undefined

  static override styles = [
    ...RulestoneElement.styles,
    css`
      :host {
        background: transparent;
      }
      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
        margin-bottom: 6px;
      }
      .toolbar button {
        flex: none;
      }
      .toolbar .hint {
        flex: 1 1 16em;
        margin-left: 8px;
        font-size: 0.88em;
      }
      .group {
        border: 1px solid var(--_color-border);
        border-left: 3px solid var(--_color-accent);
        border-radius: var(--_radius);
        padding: 6px 8px;
        background: var(--_color-surface);
      }
      .group.or {
        border-left-color: var(--_syntax-keyword);
      }
      .group .group {
        background: var(--_color-surface-raised);
      }
      .head,
      .block-row {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
      }
      ol {
        list-style: none;
        margin: 6px 0;
        padding: 0 0 0 4px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      li.drop-before {
        box-shadow: 0 -3px 0 var(--_color-focus);
      }
      li.drop-end {
        min-height: 8px;
        box-shadow: 0 -3px 0 var(--_color-focus);
      }
      .block {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 6px;
        background: var(--_color-surface-raised);
      }
      .block.raw {
        border-style: dashed;
      }
      .handle {
        cursor: grab;
        font-family: var(--_font-family-mono);
        padding: 2px 6px;
      }
      .controls {
        margin-left: auto;
        display: flex;
        gap: 2px;
      }
      .controls button {
        padding: 0 6px;
        min-width: 28px;
        min-height: 28px;
      }
      .value {
        display: inline-flex;
        gap: 4px;
        align-items: center;
        flex-wrap: wrap;
      }
      .value rs-cel-input {
        min-width: 200px;
        flex: 1;
      }
      .reason {
        font-size: 0.88em;
        margin: 0 0 4px;
      }
      .adds {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .adds button {
        font-size: 0.9em;
      }
      .quantifier-body {
        margin-top: 6px;
      }
      input.number {
        width: 9em;
      }
      input.currency {
        width: 5.5em;
        text-transform: uppercase;
      }
      .invalid-msg {
        color: var(--_color-error);
        font-size: 0.88em;
      }
      select.field {
        max-width: 16em;
      }
    `,
  ]

  constructor() {
    super()
    this.value = 'true'
    this.inputs = {}
    this.mode = 'blocks'
    this.label = ''
    this._model = parseCondition('true')
    this._drop = undefined
    this._invalid = new Map()
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('value') && this.value !== this.parsedValue) {
      this._model = parseCondition(this.value)
      this.parsedValue = this.value
    }
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    if (this.focusAfter) {
      const target = this.renderRoot.querySelector<HTMLElement>(`[data-handle="${this.focusAfter}"]`)
      this.focusAfter = undefined
      target?.focus()
    }
  }

  /** The condition model shown in blocks mode. */
  get model(): Group {
    return this._model
  }

  private rootScope(): Map<string, TypeSpec> {
    return new Map<string, TypeSpec>([...Object.entries(this.inputs), ['businessDate', 'date']])
  }

  private variablesOf(scope: Scope): Map<string, ValueType> {
    const variables = tableVariables(this.inputs)
    for (const [name, spec] of scope) {
      if (!variables.has(name)) {
        const type = valueTypeOf(spec)
        if (type) variables.set(name, type)
      }
    }
    return variables
  }

  private commit(model: Group, announcement?: string): void {
    this._model = model
    const value = printCondition(model)
    this.parsedValue = value
    this.value = value
    this.emit('rulestone-change', { value })
    if (announcement) this.announce(announcement)
  }

  private setMode(mode: 'blocks' | 'cel'): void {
    if (mode === this.mode) return
    if (mode === 'blocks') {
      this._model = parseCondition(this.value)
      this.parsedValue = this.value
    }
    this.mode = mode
  }

  protected override render() {
    return html`
      <div part="toolbar" class="toolbar" role="group" aria-label=${this.t('builder.modeLabel')}>
        <button type="button" aria-pressed=${this.mode === 'blocks' ? 'true' : 'false'} @click=${() => this.setMode('blocks')}>
          ${this.t('builder.modeBlocks')}
        </button>
        <button type="button" aria-pressed=${this.mode === 'cel' ? 'true' : 'false'} @click=${() => this.setMode('cel')}>
          ${this.t('builder.modeCel')}
        </button>
        <span class="hint muted">${this.mode === 'blocks' ? this.t('builder.blockHelp') : this.t('builder.celHint')}</span>
      </div>
      <div role="region" aria-label=${this.label || this.t('builder.label')}>
        ${this.mode === 'cel'
          ? html`<rs-cel-input
              multiline
              show-problems
              .value=${this.value}
              .variables=${this.variablesOf(this.rootScope())}
              .expected=${'bool'}
              .label=${this.label || this.t('builder.label')}
              .locale=${this.effectiveLocale}
              .messages=${this.messages}
              ?readonly=${this.readonly}
              @rulestone-input=${(e: CustomEvent<{ value: string }>) => {
                e.stopPropagation()
                this.value = e.detail.value
                this.emit('rulestone-change', { value: this.value })
              }}
              @rulestone-commit=${(e: Event) => e.stopPropagation()}
            ></rs-cel-input>`
          : this.renderGroup(this._model, this.rootScope(), true)}
      </div>
      ${this.liveRegion()}
    `
  }

  // --- Groups and blocks ------------------------------------------------------------------------

  private renderGroup(group: Group, scope: Scope, root: boolean): TemplateResult {
    const label = group.op === 'and' ? this.t('builder.and') : this.t('builder.or')
    const drop = this._drop?.group === group.id ? this._drop.index : -1
    return html`
      <div part="group" class="group ${group.op}" role="group" aria-label=${(group.negated ? this.t('builder.not') + ': ' : '') + label}>
        <div class="head">
          ${root ? nothing : this.handle(group)}
          <select
            aria-label=${this.t('builder.operator')}
            ?disabled=${this.readonly}
            .value=${group.op}
            @change=${(e: Event) => this.commit(updateNode(this._model, group.id, (g) => ({ ...(g as Group), op: (e.target as HTMLSelectElement).value as 'and' | 'or' })))}
          >
            <option value="and" ?selected=${group.op === 'and'}>${this.t('builder.and')}</option>
            <option value="or" ?selected=${group.op === 'or'}>${this.t('builder.or')}</option>
          </select>
          <button
            type="button"
            title=${this.t('builder.notHelp')}
            aria-pressed=${group.negated ? 'true' : 'false'}
            ?disabled=${this.readonly}
            @click=${() => this.commit(updateNode(this._model, group.id, (g) => ({ ...(g as Group), negated: !(g as Group).negated, parens: false })))}
          >
            ${this.t('builder.not')}
          </button>
          ${root ? nothing : this.controls(group)}
        </div>
        <ol @dragover=${(e: DragEvent) => this.onDragOver(e, group)} @drop=${(e: DragEvent) => this.onDrop(e, group)} @dragleave=${() => (this._drop = undefined)}>
          ${group.children.length === 0
            ? html`<li class="muted ${drop === 0 ? 'drop-before' : ''}">${this.t('builder.emptyGroup', { value: group.op === 'and' !== group.negated ? 'true' : 'false' })}</li>`
            : group.children.map((child, i) => html`<li class=${drop === i ? 'drop-before' : ''} data-index=${i}>${this.renderNode(child, scope)}</li>`)}
          ${drop === group.children.length && group.children.length > 0 ? html`<li class="drop-end" aria-hidden="true"></li>` : nothing}
        </ol>
        ${this.readonly ? nothing : this.adds(group, scope)}
      </div>
    `
  }

  private adds(group: Group, scope: Scope): TemplateResult {
    const fields = fieldsOf(scope).filter((f) => f.expression !== 'businessDate')
    const scalar = fields.find((f) => typeof f.spec === 'string') ?? fields[0]
    const list = fields.find((f) => Array.isArray(f.spec))
    const add = (node: CondNode) => {
      const only = group.children.length === 1 ? group.children[0] : undefined
      // A lone "always" condition is replaced rather than joined with &&.
      const model = only?.kind === 'leaf' && only.op === 'always' ? insertNode(removeNode(this._model, only.id), group.id, node) : insertNode(this._model, group.id, node)
      this.focusAfter = node.id
      this.commit(model)
    }
    return html`<div class="adds">
      <button type="button" @click=${() => {
        const spec = scalar?.spec
        const op = operatorsFor(spec, scalar?.optional ?? false)[0] ?? '=='
        const value = defaultValue(spec, op)
        add(newLeaf(scalar?.expression ?? 'true', scalar ? op : 'always', value))
      }}>+ ${this.t('builder.addCondition')}</button>
      <button type="button" @click=${() => add(newGroup(group.op === 'and' ? 'or' : 'and'))}>+ ${this.t('builder.addGroup')}</button>
      ${list ? html`<button type="button" @click=${() => add(newQuantifier(list.expression, 'exists', this.freeName(scope)))}>+ ${this.t('builder.addList')}</button>` : nothing}
      <button type="button" @click=${() => add(newRaw('true'))}>+ ${this.t('builder.addCel')}</button>
    </div>`
  }

  private freeName(scope: Scope): string {
    for (const name of ['x', 'y', 'z', 'item', 'item2', 'item3']) if (!scope.has(name)) return name
    return `item${scope.size}`
  }

  private renderNode(node: CondNode, scope: Scope): TemplateResult {
    switch (node.kind) {
      case 'group':
        return this.renderGroup(node, scope, false)
      case 'leaf':
        return this.renderLeaf(node, scope)
      case 'quantifier':
        return this.renderQuantifier(node, scope)
      case 'raw':
        return this.renderRaw(node, scope)
    }
  }

  private handle(node: CondNode): TemplateResult {
    if (this.readonly) return html``
    return html`<button
      type="button"
      class="handle icon"
      draggable="true"
      data-handle=${node.id}
      aria-label=${this.t('common.dragHandle')}
      title=${this.t('builder.blockHelp')}
      @dragstart=${(e: DragEvent) => {
        this.dragId = node.id
        e.dataTransfer?.setData('text/plain', node.id)
        if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
      }}
      @dragend=${() => {
        this.dragId = undefined
        this._drop = undefined
      }}
      @keydown=${(e: KeyboardEvent) => this.onHandleKey(e, node)}
    >⋮⋮</button>`
  }

  private controls(node: CondNode): TemplateResult {
    if (this.readonly) return html``
    return html`<span class="controls">
      <button type="button" class="icon" aria-label=${this.t('common.moveUp')} title=${this.t('common.moveUp')} @click=${() => this.move(node, 'up')}>↑</button>
      <button type="button" class="icon" aria-label=${this.t('common.moveDown')} title=${this.t('common.moveDown')} @click=${() => this.move(node, 'down')}>↓</button>
      <button type="button" class="icon" aria-label=${this.t('builder.indent')} title=${this.t('builder.indent')} @click=${() => this.move(node, 'in')}>→</button>
      <button type="button" class="icon" aria-label=${this.t('builder.outdent')} title=${this.t('builder.outdent')} @click=${() => this.move(node, 'out')}>←</button>
      <button type="button" class="icon" aria-label=${this.t('builder.removeBlock')} title=${this.t('builder.removeBlock')} @click=${() => this.commit(removeNode(this._model, node.id))}>✕</button>
    </span>`
  }

  private onHandleKey(e: KeyboardEvent, node: CondNode): void {
    if (!e.altKey) return
    const direction = { ArrowUp: 'up', ArrowDown: 'down', ArrowRight: 'in', ArrowLeft: 'out' }[e.key] as 'up' | 'down' | 'in' | 'out' | undefined
    if (!direction) return
    e.preventDefault()
    this.move(node, direction)
  }

  /** Moves a block with the keyboard or the move buttons, and says where it went. */
  move(node: CondNode, direction: 'up' | 'down' | 'in' | 'out'): void {
    const before = this._model
    const after =
      direction === 'up' ? moveBy(before, node.id, -1) : direction === 'down' ? moveBy(before, node.id, 1) : direction === 'in' ? indent(before, node.id) : outdent(before, node.id)
    if (after === before) {
      this.announce(this.t('builder.cannotMove'))
      return
    }
    const where = findParent(after, node.id)!
    const message =
      direction === 'in'
        ? this.t('builder.movedInto')
        : direction === 'out'
          ? this.t('builder.movedOut')
          : this.t('builder.moved', { position: where.index + 1, count: where.parent.children.length })
    this.focusAfter = node.id
    this.commit(after, message)
  }

  private onDragOver(e: DragEvent, group: Group): void {
    if (!this.dragId || this.readonly) return
    const dragged = findNode(this._model, this.dragId)
    if (!dragged || findNode(dragged, group.id)) return
    e.preventDefault()
    e.stopPropagation()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
    const items = [...(e.currentTarget as HTMLElement).children].filter((c) => (c as HTMLElement).dataset['index'] !== undefined) as HTMLElement[]
    let index = items.length
    for (const item of items) {
      const rect = item.getBoundingClientRect()
      if (e.clientY < rect.top + rect.height / 2) {
        index = Number(item.dataset['index'])
        break
      }
    }
    this._drop = { group: group.id, index }
  }

  private onDrop(e: DragEvent, group: Group): void {
    const id = this.dragId
    const drop = this._drop
    this._drop = undefined
    this.dragId = undefined
    if (!id || !drop || drop.group !== group.id) return
    e.preventDefault()
    e.stopPropagation()
    const from = findParent(this._model, id)
    // The index counts positions before the block is taken out of the same group.
    const index = from && from.parent.id === group.id && from.index < drop.index ? drop.index - 1 : drop.index
    const after = moveNode(this._model, id, group.id, index)
    if (after === this._model) return
    const where = findParent(after, id)!
    this.commit(after, this.t('builder.moved', { position: where.index + 1, count: where.parent.children.length }))
  }

  // --- Leaves -----------------------------------------------------------------------------------

  private renderLeaf(leaf: Leaf, scope: Scope): TemplateResult {
    if (leaf.op === 'always') {
      return html`<div part="block" class="block"><div class="block-row">${this.handle(leaf)}<span>${this.t('builder.alwaysMatches')}</span>${this.controls(leaf)}</div></div>`
    }
    const fields = fieldsOf(scope)
    const known = fields.find((f) => f.expression === leaf.field)
    const spec = known?.spec ?? fieldSpec(scope, leaf.field)
    const ops = operatorsFor(spec, known?.optional ?? leaf.field.includes('.'))
    if (!ops.includes(leaf.op)) ops.push(leaf.op)
    return html`<div part="block" class="block">
      <div class="block-row">
        ${this.handle(leaf)}
        <select
          class="field"
          aria-label=${this.t('builder.field')}
          ?disabled=${this.readonly}
          @change=${(e: Event) => this.changeField(leaf, (e.target as HTMLSelectElement).value, scope)}
        >
          ${known ? nothing : html`<option selected value=${leaf.field}>${this.t('builder.unknownField', { field: leaf.field })}</option>`}
          ${fields.map((f) => html`<option value=${f.expression} ?selected=${f.expression === leaf.field}>${f.expression} · ${describeType(f.spec)}</option>`)}
        </select>
        <select
          aria-label=${this.t('builder.operator')}
          ?disabled=${this.readonly}
          @change=${(e: Event) => this.changeOperator(leaf, (e.target as HTMLSelectElement).value as Operator, spec)}
        >
          ${ops.map((op) => html`<option value=${op} ?selected=${op === leaf.op}>${this.t(`builder.ops.${op}`)}</option>`)}
        </select>
        ${takesValue(leaf.op) ? this.renderValue(leaf, spec, scope) : nothing}
        ${this.controls(leaf)}
      </div>
    </div>`
  }

  private changeField(leaf: Leaf, field: string, scope: Scope): void {
    const known = fieldsOf(scope).find((f) => f.expression === field)
    const ops = operatorsFor(known?.spec, known?.optional ?? false)
    const op = ops.includes(leaf.op) && leaf.op !== 'has' ? leaf.op : ops[0] ?? '=='
    const sameType = describeType(known?.spec) === describeType(fieldSpec(scope, leaf.field))
    const value = sameType && leaf.value ? leaf.value : defaultValue(known?.spec, op)
    this.commit(updateNode(this._model, leaf.id, () => (value === undefined ? newLeafWith(leaf.id, field, op) : { ...newLeafWith(leaf.id, field, op), value })))
  }

  private changeOperator(leaf: Leaf, op: Operator, spec: TypeSpec | undefined): void {
    const listOp = (o: Operator) => o === 'in' || o === 'notIn'
    const keep = leaf.value && takesValue(op) && listOp(op) === listOp(leaf.op)
    const value = keep ? leaf.value : defaultValue(spec, op)
    this.commit(updateNode(this._model, leaf.id, () => (value === undefined ? newLeafWith(leaf.id, leaf.field, op) : { ...newLeafWith(leaf.id, leaf.field, op), value })))
  }

  private setValue(leaf: Leaf, value: ValueNode): void {
    const invalid = new Map(this._invalid)
    invalid.delete(leaf.id)
    this._invalid = invalid
    this.commit(updateNode(this._model, leaf.id, (n) => ({ ...(n as Leaf), value })))
  }

  private markInvalid(leaf: Leaf, message: string): void {
    this._invalid = new Map(this._invalid).set(leaf.id, message)
  }

  private renderValue(leaf: Leaf, spec: TypeSpec | undefined, scope: Scope): TemplateResult {
    const value = leaf.value ?? defaultValue(spec, leaf.op) ?? { kind: 'expr', text: '' }
    const element = Array.isArray(spec) ? spec[0] : spec
    const expression = value.kind === 'expr'
    const invalid = this._invalid.get(leaf.id)
    const label = this.t('builder.value')
    const toggle = this.readonly
      ? nothing
      : html`<button
          type="button"
          class="icon"
          aria-pressed=${expression ? 'true' : 'false'}
          aria-label=${this.t('builder.valueMode')}
          title=${this.t('builder.valueMode')}
          @click=${() => this.setValue(leaf, expression ? literalOf(value.text, element, leaf.op) : { kind: 'expr', text: printValue(value) })}
        >ƒx</button>`
    let editor: TemplateResult
    if (expression) {
      editor = html`<rs-cel-input
        .value=${value.text}
        .variables=${this.variablesOf(scope)}
        .label=${label}
        .locale=${this.effectiveLocale}
        .messages=${this.messages}
        ?readonly=${this.readonly}
        @rulestone-commit=${(e: CustomEvent<{ value: string }>) => {
          e.stopPropagation()
          if (e.detail.value !== value.text) this.setValue(leaf, { kind: 'expr', text: e.detail.value })
        }}
        @rulestone-input=${(e: Event) => e.stopPropagation()}
      ></rs-cel-input>`
    } else if (value.kind === 'list') {
      const text = value.items.map((i) => listItemText(i)).join(', ')
      editor = html`<input
        aria-label=${this.t('builder.listValues')}
        title=${this.t('builder.listValues')}
        .value=${text}
        ?readonly=${this.readonly}
        aria-invalid=${invalid ? 'true' : 'false'}
        @change=${(e: Event) => {
          const items = (e.target as HTMLInputElement).value.split(',').map((s) => s.trim()).filter((s) => s !== '')
          const converted = items.map((s) => scalarOf(s, element, '=='))
          if (converted.some((c) => c === undefined)) this.markInvalid(leaf, this.t(element === 'int' ? 'builder.invalid.int' : 'builder.invalid.decimal'))
          else this.setValue(leaf, { kind: 'list', items: converted as ValueNode[] })
        }}
      />`
    } else if (element === 'bool' || value.kind === 'bool') {
      const current = value.kind === 'bool' ? value.value : true
      editor = html`<select aria-label=${label} ?disabled=${this.readonly} @change=${(e: Event) => this.setValue(leaf, { kind: 'bool', value: (e.target as HTMLSelectElement).value === 'true' })}>
        <option value="true" ?selected=${current}>${this.t('common.trueValue')}</option>
        <option value="false" ?selected=${!current}>${this.t('common.falseValue')}</option>
      </select>`
    } else if (element === 'money' || value.kind === 'money') {
      const money = value.kind === 'money' ? value : { kind: 'money' as const, amount: '0', currency: 'VND', form: 'int' as const }
      editor = html`<input
          class="number"
          inputmode="decimal"
          aria-label=${this.t('builder.amount')}
          .value=${money.amount}
          ?readonly=${this.readonly}
          aria-invalid=${invalid ? 'true' : 'false'}
          @change=${(e: Event) => {
            const amount = (e.target as HTMLInputElement).value.trim()
            if (!/^-?[0-9]+(\.[0-9]+)?$/.test(amount)) return this.markInvalid(leaf, this.t('builder.invalid.decimal'))
            this.setValue(leaf, { kind: 'money', amount, currency: money.currency, form: /^-?[0-9]+$/.test(amount) ? 'int' : 'string' })
          }}
        /><input
          class="currency"
          list=${`${leaf.id}-ccy`}
          aria-label=${this.t('builder.currency')}
          .value=${money.currency}
          ?readonly=${this.readonly}
          @change=${(e: Event) => {
            const currency = (e.target as HTMLInputElement).value.trim().toUpperCase()
            if (!CURRENCIES.includes(currency)) return this.markInvalid(leaf, this.t('builder.invalid.currency'))
            this.setValue(leaf, { kind: 'money', amount: money.amount, currency, form: money.form })
          }}
        /><datalist id=${`${leaf.id}-ccy`}>${['VND', 'USD', 'EUR', 'JPY', 'CNY', 'SGD', 'KRW', 'THB'].map((c) => html`<option value=${c}></option>`)}</datalist>`
    } else if (element === 'date' || value.kind === 'date') {
      const date = value.kind === 'date' ? value.text : ''
      editor = html`<input
        type="date"
        aria-label=${label}
        .value=${date}
        ?readonly=${this.readonly}
        @change=${(e: Event) => {
          const text = (e.target as HTMLInputElement).value
          if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return this.markInvalid(leaf, this.t('builder.invalid.date'))
          this.setValue(leaf, { kind: 'date', text })
        }}
      />`
    } else if (element === 'int' || element === 'decimal' || value.kind === 'int' || value.kind === 'decimal') {
      const text = value.kind === 'int' || value.kind === 'decimal' ? value.text : ''
      editor = html`<input
        class="number"
        inputmode=${element === 'int' ? 'numeric' : 'decimal'}
        aria-label=${label}
        .value=${text}
        ?readonly=${this.readonly}
        aria-invalid=${invalid ? 'true' : 'false'}
        @change=${(e: Event) => {
          const input = (e.target as HTMLInputElement).value.trim()
          const node = scalarOf(input, element ?? (value.kind === 'int' ? 'int' : 'decimal'), leaf.op)
          if (!node) return this.markInvalid(leaf, this.t(element === 'int' ? 'builder.invalid.int' : 'builder.invalid.decimal'))
          // Keep how an unchanged-kind decimal was written: decimal(5) stays decimal(5).
          this.setValue(leaf, node.kind === 'decimal' && value.kind === 'decimal' && /^-?[0-9]+$/.test(input) ? { ...node, form: value.form } : node)
        }}
      />`
    } else {
      const text = value.kind === 'string' ? value.text : ''
      editor = html`<input aria-label=${label} .value=${text} ?readonly=${this.readonly} @change=${(e: Event) => this.setValue(leaf, { kind: 'string', text: (e.target as HTMLInputElement).value })} />`
    }
    return html`<span class="value">${editor}${toggle}${invalid ? html`<span class="invalid-msg" role="alert">${invalid}</span>` : nothing}</span>`
  }

  // --- Blocks over lists and raw CEL -------------------------------------------------------------

  private renderQuantifier(q: Quantifier, scope: Scope): TemplateResult {
    const lists = fieldsOf(scope).filter((f) => Array.isArray(f.spec))
    const listSpec = fieldSpec(scope, q.field)
    const element = Array.isArray(listSpec) ? listSpec[0] : undefined
    const inner = new Map(scope)
    if (element !== undefined) inner.set(q.variable, element)
    return html`<div part="block" class="block">
      <div class="block-row">
        ${this.handle(q)}
        <select aria-label=${this.t('builder.listField')} ?disabled=${this.readonly} @change=${(e: Event) => this.commit(updateNode(this._model, q.id, (n) => ({ ...(n as Quantifier), field: (e.target as HTMLSelectElement).value })))}>
          ${lists.some((l) => l.expression === q.field) ? nothing : html`<option selected value=${q.field}>${this.t('builder.unknownField', { field: q.field })}</option>`}
          ${lists.map((l) => html`<option value=${l.expression} ?selected=${l.expression === q.field}>${l.expression} · ${describeType(l.spec)}</option>`)}
        </select>
        <select aria-label=${this.t('builder.operator')} ?disabled=${this.readonly} @change=${(e: Event) => this.commit(updateNode(this._model, q.id, (n) => ({ ...(n as Quantifier), macro: (e.target as HTMLSelectElement).value as Quantifier['macro'] })))}>
          ${(['exists', 'all', 'exists_one'] as const).map((m) => html`<option value=${m} ?selected=${m === q.macro}>${this.t(`builder.${m}`)}</option>`)}
        </select>
        <label>${this.t('builder.itemName')}
          <input class="currency" .value=${q.variable} ?readonly=${this.readonly} @change=${(e: Event) => {
            const name = (e.target as HTMLInputElement).value.trim()
            if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) this.commit(updateNode(this._model, q.id, (n) => ({ ...(n as Quantifier), variable: name })))
          }} />
        </label>
        ${this.controls(q)}
      </div>
      <div class="quantifier-body">${this.renderGroup(q.body, inner, false)}</div>
    </div>`
  }

  private renderRaw(raw: Raw, scope: Scope): TemplateResult {
    const asBlocks = parseCondition(raw.text)
    const convertible = !containsRaw(asBlocks)
    return html`<div part="block" class="block raw">
      <p class="reason muted">${this.t(`builder.rawReasons.${raw.reason}`)}</p>
      <div class="block-row">
        ${this.handle(raw)}
        <rs-cel-input
          .value=${raw.text}
          .variables=${this.variablesOf(scope)}
          .expected=${'bool'}
          .label=${this.t('builder.modeCel')}
          .locale=${this.effectiveLocale}
          .messages=${this.messages}
          ?readonly=${this.readonly}
          @rulestone-commit=${(e: CustomEvent<{ value: string }>) => {
            e.stopPropagation()
            if (e.detail.value !== raw.text) this.commit(updateNode(this._model, raw.id, (n) => ({ ...(n as Raw), text: e.detail.value })))
          }}
          @rulestone-input=${(e: Event) => e.stopPropagation()}
        ></rs-cel-input>
        ${convertible && !this.readonly
          ? html`<button type="button" @click=${() => {
              const replacement: CondNode = asBlocks.children.length === 1 ? asBlocks.children[0]! : asBlocks
              this.commit(updateNode(this._model, raw.id, () => replacement))
            }}>${this.t('builder.tryBlocks')}</button>`
          : nothing}
        ${this.controls(raw)}
      </div>
    </div>`
  }
}

function newLeafWith(id: string, field: string, op: Operator): Leaf {
  return { kind: 'leaf', id, field, op }
}

function listItemText(value: ValueNode): string {
  switch (value.kind) {
    case 'string':
    case 'int':
    case 'decimal':
    case 'date':
      return value.text
    default:
      return printValue(value)
  }
}

/** A literal from text typed for a field of a type, or undefined if it is not valid. */
function scalarOf(text: string, element: TypeSpec | undefined, op: Operator): ValueNode | undefined {
  switch (element) {
    case 'int':
      return /^-?[0-9]+$/.test(text) ? { kind: 'int', text } : undefined
    case 'decimal':
      return /^-?[0-9]+(\.[0-9]+)?$/.test(text) ? (op === '==' ? { kind: 'decimal', text, form: 'string' } : numberValue('decimal', op, text)) : undefined
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(text) ? { kind: 'date', text } : undefined
    default:
      return { kind: 'string', text }
  }
}

/** Back from an expression to a typed value, when the expression is a literal; else a default. */
function literalOf(text: string, element: TypeSpec | undefined, op: Operator): ValueNode {
  const parsed = parseCondition(`x == ${text}`).children[0]
  const value = parsed?.kind === 'leaf' ? parsed.value : undefined
  if (value && value.kind !== 'expr') {
    const { source: _source, ...rest } = value
    return rest as ValueNode
  }
  return defaultValue(element, op === 'in' || op === 'notIn' ? op : '==') ?? { kind: 'string', text: '' }
}

define('rulestone-condition-builder', RulestoneConditionBuilder)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-condition-builder': RulestoneConditionBuilder
  }
}
