// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { LitElement, html, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import { format, resolveMessages, type MessageKey, type MessageOverrides, type Messages } from '../i18n/index.js'
import { tokenize } from '../model/cel-tokens.js'
import { base, tokens } from './styles.js'

/**
 * Base of the RuleStone elements: locale and string overrides, read-only mode, theme, a
 * polite live region for announcements, and typed events.
 */
export class RulestoneElement extends LitElement {
  static override styles = [tokens, base]

  static override properties: PropertyDeclarations = {
    locale: { type: String, reflect: true },
    messages: { attribute: false },
    readonly: { type: Boolean, reflect: true },
    theme: { type: String, reflect: true },
    _announcement: { state: true },
  }

  /** 'en' or 'vi'. Default: the nearest lang attribute, else English. */
  declare locale: string | undefined
  /** Overrides of the built-in strings for this element (deep partial). */
  declare messages: MessageOverrides | undefined
  /** Shows the content without letting the user change it. */
  declare readonly: boolean
  /** 'light' or 'dark' to force a theme; by default it follows prefers-color-scheme. */
  declare theme: string | undefined
  declare _announcement: string

  private resolved: { key: string; messages: Messages } | undefined

  constructor() {
    super()
    this.readonly = false
    this._announcement = ''
  }

  /** Passes a forced theme on to the RuleStone elements this one renders. */
  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    for (const child of Array.from(this.renderRoot.querySelectorAll('*'))) {
      if (!(child instanceof RulestoneElement)) continue
      if (this.theme) {
        if (child.getAttribute('theme') !== this.theme) child.setAttribute('theme', this.theme)
      } else if (child.hasAttribute('theme')) child.removeAttribute('theme')
    }
  }

  /** The effective locale. */
  get effectiveLocale(): string {
    return this.locale ?? (this.closest('[lang]') as HTMLElement | null)?.lang ?? 'en'
  }

  protected get strings(): Messages {
    const key = `${this.effectiveLocale}|${this.messages ? JSON.stringify(this.messages) : ''}`
    if (this.resolved?.key !== key) this.resolved = { key, messages: resolveMessages(this.effectiveLocale, this.messages) }
    return this.resolved.messages
  }

  /** A string in the current locale, with {placeholders} filled in. */
  t(key: MessageKey, params?: Readonly<Record<string, string | number>>): string {
    return format(this.strings, key, params)
  }

  /** Says something to screen-reader users through the element's live region. */
  announce(text: string): void {
    // Clearing first makes a repeated message be read again.
    this._announcement = ''
    void this.updateComplete.then(() => {
      this._announcement = text
    })
  }

  protected liveRegion(): TemplateResult {
    return html`<div class="sr-only" role="status" aria-live="polite" aria-atomic="true">${this._announcement}</div>`
  }

  /** Dispatches a composed, bubbling CustomEvent. */
  protected emit<T>(name: string, detail: T): boolean {
    return this.dispatchEvent(new CustomEvent<T>(name, { detail, bubbles: true, composed: true, cancelable: true }))
  }

  /** Attributes to pass the shared settings on to a child element. */
  protected shared(): { locale: string; messages: MessageOverrides | undefined; theme: string | undefined } {
    return { locale: this.effectiveLocale, messages: this.messages, theme: this.theme }
  }
}

/** Tokens after which a line may break where there is no space: calls and lists. A field path such as applicant.age stays whole. */
const BREAK_AFTER = new Set(['(', '[', ','])

/**
 * CEL text as highlighted spans; marks the characters at the given UTF-16 indexes as errors.
 * With `wrap`, it also allows line breaks between tokens that have no space between them (before
 * and after operators, after `(`, `[` and `,`), so that wrapped text breaks at spaces and
 * operators rather than inside a name or a field path.
 */
export function highlight(text: string, errorAt: readonly number[] = [], wrap = false): TemplateResult {
  const tokens = tokenize(text)
  const marks = new Set(errorAt)
  const atEnd = errorAt.some((i) => i >= text.length)
  return html`${tokens.map((t, i) => {
    const end = t.start + t.text.length
    const err = [...marks].some((m) => m >= t.start && m < end)
    const token = t.kind === 'space' && !err ? t.text : html`<span class="tok-${t.kind}${err ? ' tok-err' : ''}">${t.text}</span>`
    const prev = tokens[i - 1]
    const soft = wrap && prev !== undefined && prev.kind !== 'space' && t.kind !== 'space' &&
      (t.kind === 'operator' || prev.kind === 'operator' || BREAK_AFTER.has(prev.text))
    return soft ? html`<wbr>${token}` : token
  })}${atEnd ? html`<span class="tok-err" aria-hidden="true">&nbsp;</span>` : ''}`
}

/** An identifier or code that may break after each underscore, e.g. SANCTIONS_<wbr>MATCH. */
export function breakAtUnderscores(text: string): TemplateResult {
  const parts = text.split('_')
  return html`${parts.map((part, i) => (i < parts.length - 1 ? html`${part}_<wbr>` : part))}`
}

/** Registers an element unless a tag of that name is already defined (for example twice bundled). */
export function define(name: string, element: CustomElementConstructor): void {
  if (typeof customElements !== 'undefined' && !customElements.get(name)) customElements.define(name, element)
}

/** Today's date in the browser's time zone, yyyy-MM-dd. */
export function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
