// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { analyzeExpression, type ExpressionIssue, type ValueType } from '@rulestone/engine'
import { css, html, nothing, type PropertyDeclarations, type PropertyValues } from 'lit'
import { completionsAt, type Completion } from '../model/cel-text.js'
import { RulestoneElement, define, highlight } from './base.js'

let instances = 0

/**
 * `<rs-cel-input>`: a lightweight CEL text input with syntax highlighting, the engine's own
 * type checking and autocomplete. A plain textarea sits over a highlighted copy of its text, so
 * no editor library, inline style or innerHTML is needed.
 *
 * @fires rulestone-input - on every edit; detail `{ value }`
 * @fires rulestone-commit - on Enter (single line) or when focus leaves; detail `{ value }`
 * @fires rulestone-cancel - on Escape, when no suggestion list is open
 * @csspart input - the textarea
 */
export class RsCelInput extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    value: { type: String },
    label: { type: String },
    placeholder: { type: String },
    multiline: { type: Boolean, reflect: true },
    variables: { attribute: false },
    expected: { attribute: false },
    issues: { attribute: false },
    showProblems: { type: Boolean, attribute: 'show-problems' },
    _items: { state: true },
    _active: { state: true },
  }

  declare value: string
  /** The accessible name. */
  declare label: string
  declare placeholder: string
  /** Allows line breaks; Enter then inserts one and Ctrl+Enter commits. */
  declare multiline: boolean
  /** Typed variables in scope; when set, the text is type-checked and fields are suggested. */
  declare variables: ReadonlyMap<string, ValueType> | undefined
  /** The type the expression must produce, e.g. 'bool' for a condition. */
  declare expected: ValueType | undefined
  /** Problems to mark, instead of the ones the element finds itself. */
  declare issues: readonly Pick<ExpressionIssue, 'index' | 'message' | 'line' | 'column'>[] | undefined
  /** Lists the problems below the input. */
  declare showProblems: boolean
  declare _items: readonly Completion[]
  declare _active: number

  private readonly uid = `cel${++instances}`
  private range: { from: number; to: number } = { from: 0, to: 0 }

  static override styles = [
    ...RulestoneElement.styles,
    css`
      :host {
        display: block;
        position: relative;
        background: transparent;
      }
      .wrap {
        display: grid;
        position: relative;
        border: 1px solid var(--_color-border-strong);
        border-radius: var(--_radius);
        background: var(--_color-surface-raised);
      }
      .wrap.invalid {
        border-color: var(--_color-error);
      }
      .wrap:focus-within {
        outline: var(--_focus-width) solid var(--_color-focus);
        outline-offset: 1px;
      }
      pre,
      textarea {
        grid-area: 1 / 1;
        margin: 0;
        padding: 5px 7px;
        font-family: var(--_font-family-mono);
        font-size: 0.95em;
        line-height: 1.5;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        tab-size: 2;
        border: 0;
        min-height: 30px;
      }
      textarea {
        resize: none;
        overflow: hidden;
        background: transparent;
        color: transparent;
        caret-color: var(--_color-text);
        outline: none;
        border-radius: var(--_radius);
      }
      textarea::placeholder {
        color: var(--_color-text-muted);
      }
      textarea::selection {
        background: var(--_color-accent-subtle);
        color: transparent;
      }
      pre {
        pointer-events: none;
        color: var(--_color-text);
      }
      ul {
        position: absolute;
        z-index: 20;
        left: 0;
        top: 100%;
        margin: 2px 0 0;
        padding: 2px;
        list-style: none;
        min-width: 260px;
        max-width: min(480px, 90vw);
        max-height: 240px;
        overflow: auto;
        background: var(--_color-surface-raised);
        border: 1px solid var(--_color-border-strong);
        border-radius: var(--_radius);
        box-shadow: 0 4px 14px rgb(0 0 0 / 0.18);
      }
      li {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        padding: 3px 6px;
        border-radius: 4px;
        cursor: pointer;
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
      }
      li[aria-selected='true'] {
        background: var(--_color-accent);
        color: var(--_color-on-accent);
      }
      li .detail {
        opacity: 0.8;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .problems {
        margin: 4px 0 0;
        padding: 0;
        list-style: none;
        color: var(--_color-error);
        font-size: 0.9em;
      }
    `,
  ]

  constructor() {
    super()
    this.value = ''
    this.label = ''
    this.placeholder = ''
    this.multiline = false
    this.showProblems = false
    this._items = []
    this._active = 0
  }

  private get textarea(): HTMLTextAreaElement | null {
    return this.renderRoot.querySelector('textarea')
  }

  override focus(options?: FocusOptions): void {
    this.textarea?.focus(options)
  }

  /** Places the caret at a UTF-16 index and focuses the input. */
  focusAt(index: number): void {
    const textarea = this.textarea
    if (!textarea) return
    textarea.focus()
    textarea.setSelectionRange(index, index)
  }

  /** The problems shown: the given ones, or those the type checker finds. */
  get problems(): readonly Pick<ExpressionIssue, 'index' | 'message' | 'line' | 'column'>[] {
    if (this.issues) return this.issues
    if (!this.variables || this.value.trim() === '') return []
    return analyzeExpression(this.value, this.expected === undefined ? { variables: this.variables } : { variables: this.variables, expected: this.expected }).issues
  }

  protected override render() {
    const problems = this.problems
    const open = this._items.length > 0
    const errorsId = `${this.uid}-errors`
    return html`
      <div class="wrap ${problems.length > 0 ? 'invalid' : ''}">
        <pre aria-hidden="true">${highlight(this.value, problems.map((p) => p.index))}${'\n'}</pre>
        <textarea
          part="input"
          rows="1"
          spellcheck="false"
          autocapitalize="off"
          autocomplete="off"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded=${open ? 'true' : 'false'}
          aria-controls=${`${this.uid}-list`}
          aria-activedescendant=${open ? `${this.uid}-opt${this._active}` : nothing}
          aria-label=${this.label}
          aria-invalid=${problems.length > 0 ? 'true' : 'false'}
          aria-describedby=${problems.length > 0 ? errorsId : nothing}
          placeholder=${this.placeholder || nothing}
          ?readonly=${this.readonly}
          .value=${this.value}
          @input=${this.onInput}
          @keydown=${this.onKeyDown}
          @blur=${this.onBlur}
        ></textarea>
      </div>
      <ul id=${`${this.uid}-list`} role="listbox" aria-label=${this.t('cel.suggestions')} ?hidden=${!open}>
        ${this._items.map(
          (item, i) => html`<li
            id=${`${this.uid}-opt${i}`}
            role="option"
            aria-selected=${i === this._active ? 'true' : 'false'}
            @mousedown=${(e: Event) => {
              e.preventDefault()
              this.accept(item)
            }}
          >
            <span>${item.label}</span><span class="detail">${item.detail}</span>
          </li>`,
        )}
      </ul>
      <ul id=${errorsId} class="problems ${this.showProblems ? '' : 'sr-only'}">
        ${problems.map((p) => html`<li>${p.line}:${p.column} ${p.message.split('\n')[0]}</li>`)}
      </ul>
    `
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    const active = this.renderRoot.querySelector('li[aria-selected="true"]')
    active?.scrollIntoView?.({ block: 'nearest' })
  }

  private onInput(e: Event): void {
    const textarea = e.target as HTMLTextAreaElement
    this.value = textarea.value
    this.emit('rulestone-input', { value: this.value })
    const caret = textarea.selectionStart
    const typed = this.value[caret - 1]
    if (typed !== undefined && /[A-Za-z_.]/.test(typed)) this.suggest(caret)
    else this.close()
  }

  private suggest(caret: number): void {
    if (this.readonly) return
    const result = completionsAt(this.value, caret, this.variables ?? new Map())
    this.range = { from: result.from, to: result.to }
    this._items = result.items.slice(0, 50)
    this._active = 0
  }

  private close(): void {
    this._items = []
  }

  private accept(item: Completion): void {
    const { from, to } = this.range
    this.value = this.value.slice(0, from) + item.insert + this.value.slice(to)
    const caret = from + (item.caret ?? item.insert.length)
    this.close()
    void this.updateComplete.then(() => {
      const textarea = this.textarea
      if (!textarea) return
      textarea.value = this.value
      textarea.focus()
      textarea.setSelectionRange(caret, caret)
    })
    this.emit('rulestone-input', { value: this.value })
  }

  private onKeyDown(e: KeyboardEvent): void {
    const open = this._items.length > 0
    if (open) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const n = this._items.length
        this._active = (this._active + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
        return
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault()
        e.stopPropagation()
        this.accept(this._items[this._active]!)
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        this.close()
        return
      }
    }
    if (e.key === ' ' && e.ctrlKey) {
      e.preventDefault()
      this.suggest((e.target as HTMLTextAreaElement).selectionStart)
      return
    }
    if (e.key === 'Enter' && (!this.multiline || e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      e.stopPropagation()
      this.emit('rulestone-commit', { value: this.value })
      return
    }
    if (e.key === 'Escape') {
      e.stopPropagation()
      this.emit('rulestone-cancel', {})
    }
  }

  private onBlur(): void {
    this.close()
    this.emit('rulestone-commit', { value: this.value })
  }
}

define('rs-cel-input', RsCelInput)

declare global {
  interface HTMLElementTagNameMap {
    'rs-cel-input': RsCelInput
  }
}
