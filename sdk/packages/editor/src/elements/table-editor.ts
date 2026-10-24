// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { css, html, nothing, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import { HIT_POLICIES, cloneJson, normalizeTable, tableText, updateTable, type TableJson } from '../model/table.js'
import { sortProblems, validateTable, type Problem, type ProblemTarget, type ValidationResult } from '../model/validate.js'
import { RulestoneElement, define } from './base.js'
import './rules-grid.js'
import './schema-designer.js'
import './try-it.js'
import type { RulestoneRulesGrid } from './rules-grid.js'
import type { RulestoneTryIt, RunDetail } from './try-it.js'

/** A problem reported by the host's validator; it is shown with the editor's own. */
export interface HostProblem {
  readonly message: string
  readonly severity?: 'error' | 'warning'
  readonly target?: ProblemTarget
}

/**
 * Optional server-side validation, for example a call to the bank's Java RuleStone service.
 * It receives the table and the editor's own result, and returns more problems. The editor
 * makes no network calls itself.
 */
export type TableValidator = (table: TableJson, local: ValidationResult) => Promise<readonly HostProblem[]>

/** The detail of rulestone-change. */
export interface TableChangeDetail {
  readonly table: TableJson
  /** The canonical form, when the table can be read; the rule version is computed over it. */
  readonly canonical: string | undefined
  readonly problems: readonly Problem[]
  readonly ruleVersion: string | undefined
  readonly valid: boolean
}

type Tab = 'rules' | 'schema' | 'json'

/**
 * `<rulestone-table-editor>`: the complete decision table editor: metadata, the schema
 * designer, the rules grid, a problems panel and a Try it panel. The host owns persistence:
 * the editor takes the table through the `table` property and reports every change.
 *
 * @fires rulestone-change - detail TableChangeDetail, after each edit has been validated
 * @fires rulestone-validate - detail `{ valid, problems, ruleVersion, canonical }`, after each validation
 * @fires rulestone-run - detail RunDetail, after each Try it run
 * @csspart metadata - the table's id, version, dates and hit policy
 * @csspart tabs - the tab list
 * @csspart problems - the problems panel
 * @csspart try-it - the Try it panel
 */
export class RulestoneTableEditor extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    table: { attribute: false },
    validator: { attribute: false },
    asOf: { type: String, attribute: 'as-of' },
    facts: { type: String },
    _tab: { state: true },
    _result: { state: true },
    _validating: { state: true },
    _run: { state: true },
  }

  /** The table as parsed .stone.json. Setting it replaces the table being edited. */
  declare table: TableJson
  /** Optional async server-side validation; see TableValidator. */
  declare validator: TableValidator | undefined
  /** The business date the Try it panel starts with. */
  declare asOf: string | undefined
  /** Facts for the Try it panel as JSON text, for example from a .stone-test.json case. */
  declare facts: string | undefined
  declare _tab: Tab
  declare _result: (ValidationResult & { problems: readonly Problem[] }) | undefined
  declare _validating: boolean
  declare _run: RunDetail | undefined

  private sequence = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  /** Set while the editor itself changes `table`, so that the change is not taken as the host's. */
  private ownChange = false

  static override styles = [
    ...RulestoneElement.styles,
    css`
      :host {
        container-type: inline-size;
      }
      .meta {
        display: flex;
        flex-wrap: wrap;
        gap: 8px 12px;
        align-items: end;
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 8px 10px;
        background: var(--_color-surface);
        margin: 0 0 10px;
      }
      .meta legend {
        font-weight: 600;
        padding: 0 4px;
      }
      .meta input.id {
        width: min(14em, 100%);
      }
      .meta input.version {
        width: 6em;
      }
      .meta label.description {
        flex: 1 1 18em;
      }
      .meta label.description input {
        width: 100%;
      }
      .meta label {
        min-width: 0;
        max-width: 100%;
      }
      .meta .status {
        margin-left: auto;
        font-size: 0.9em;
        text-align: right;
      }
      .meta .version-hash {
        font-family: var(--_font-family-mono);
        font-size: 0.85em;
        overflow-wrap: anywhere;
      }
      .layout {
        display: grid;
        grid-template-columns: minmax(0, 1fr);
        gap: 12px;
      }
      @container (min-width: 1280px) {
        .layout {
          grid-template-columns: minmax(0, 1fr) minmax(320px, 400px);
        }
      }
      [role='tablist'] {
        display: flex;
        gap: 2px;
        border-bottom: 1px solid var(--_color-border-strong);
        margin-bottom: 8px;
        flex-wrap: wrap;
      }
      [role='tab'] {
        border: 1px solid transparent;
        border-bottom: 0;
        border-radius: var(--_radius) var(--_radius) 0 0;
        background: transparent;
        padding: 6px 12px;
      }
      [role='tab'][aria-selected='true'] {
        border-color: var(--_color-border-strong);
        background: var(--_color-bg);
        font-weight: 600;
        margin-bottom: -1px;
      }
      .side {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 8px 10px;
        background: var(--_color-surface);
        align-self: start;
        min-width: 0;
      }
      .side h2,
      .problems h2 {
        font-size: 1.05em;
        margin: 0 0 4px;
      }
      .problems {
        margin-top: 12px;
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 8px 10px;
      }
      .problems ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .problems li {
        display: flex;
        gap: 8px;
        align-items: baseline;
        padding: 3px 0;
        border-top: 1px solid var(--_color-border);
      }
      .problems li:first-child {
        border-top: 0;
      }
      .problems .where {
        font-weight: 600;
        white-space: nowrap;
      }
      .problems .msg {
        flex: 1;
        overflow-wrap: anywhere;
      }
      .problems button {
        padding: 0 8px;
        min-height: 26px;
      }
      .sev-error {
        color: var(--_color-error);
        font-weight: 700;
      }
      .sev-warning {
        color: var(--_color-warning);
        font-weight: 700;
      }
      .ok {
        color: var(--_color-success);
      }
      rulestone-try-it {
        background: transparent;
      }
    `,
  ]

  constructor() {
    super()
    this.table = normalizeTable({ id: 'new_decision', version: '1', hitPolicy: 'FIRST', inputs: {}, outputs: {}, rules: [{ id: 'R1', when: 'true' }] })
    this.validator = undefined
    this.asOf = undefined
    this.facts = undefined
    this._tab = 'rules'
    this._result = undefined
    this._validating = false
    this._run = undefined
  }

  /** A copy of the table being edited, as parsed .stone.json. */
  getTable(): TableJson {
    return cloneJson(this.table)
  }

  /** Replaces the table being edited (a copy is taken) and validates it. */
  setTable(table: unknown): void {
    this.table = normalizeTable(table)
  }

  /** Validates the table now, with the host's validator if there is one. */
  async validate(): Promise<ValidationResult> {
    clearTimeout(this.timer)
    this.timer = undefined
    return this.runValidation(false)
  }

  /** Runs the table in the Try it panel with its current facts; resolves with the rulestone-run detail. */
  async run(): Promise<RunDetail> {
    await this.updateComplete
    const tryIt = this.renderRoot.querySelector<RulestoneTryIt>('rulestone-try-it')
    if (!tryIt) throw new Error('the Try it panel is not rendered')
    await tryIt.updateComplete
    return tryIt.run()
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('table')) {
      // A table set by the host is taken over as a copy, so the host's object is never changed.
      if (!this.ownChange) {
        this.table = normalizeTable(this.table)
        this._run = undefined
        this.schedule(false)
      }
      this.ownChange = false
    }
  }

  private edit(table: TableJson): void {
    this.ownChange = true
    this.table = table
    this._run = undefined
    this.schedule(true)
  }

  private schedule(changed: boolean): void {
    clearTimeout(this.timer)
    this._validating = true
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.runValidation(changed)
    }, 150)
  }

  private async runValidation(changed: boolean): Promise<ValidationResult> {
    const sequence = ++this.sequence
    const table = this.table
    this._validating = true
    const local = await validateTable(table)
    let problems = [...local.problems]
    if (this.validator) {
      try {
        const extra = await this.validator(table, local)
        problems = [
          ...problems,
          ...extra.map((p): Problem => ({ severity: p.severity ?? 'error', message: p.message, target: p.target ?? { kind: 'table' }, source: 'host' })),
        ]
      } catch (e) {
        problems.push({ severity: 'warning', message: e instanceof Error ? e.message : String(e), target: { kind: 'table' }, source: 'host' })
      }
    }
    const result = { ...local, problems, valid: !problems.some((p) => p.severity === 'error') }
    if (sequence !== this.sequence) return result
    this._result = result
    this._validating = false
    this.emit('rulestone-validate', { valid: result.valid, problems, ruleVersion: result.ruleVersion, canonical: result.canonical })
    if (changed) {
      const detail: TableChangeDetail = { table: cloneJson(table), canonical: result.canonical, problems, ruleVersion: result.ruleVersion, valid: result.valid }
      this.emit('rulestone-change', detail)
    }
    return result
  }

  private get grid(): RulestoneRulesGrid | null {
    return this.renderRoot.querySelector('rulestone-rules-grid')
  }

  protected override render() {
    const result = this._result
    const errors = result?.problems.filter((p) => p.severity === 'error').length ?? 0
    return html`
      ${this.renderMeta(result, errors)}
      <div class="layout">
        <div>
          ${this.renderTabs()}
          ${this.renderPanel()}
          ${this.renderProblems(result)}
        </div>
        <aside part="try-it" class="side" aria-labelledby="try-heading">
          <h2 id="try-heading">${this.t('tryIt.label')}</h2>
          <rulestone-try-it
            .table=${this.table}
            .asOf=${this.asOf ?? ''}
            .facts=${this.facts}
            .locale=${this.effectiveLocale}
            .messages=${this.messages}
            .theme=${this.theme}
            @rulestone-run=${(e: CustomEvent<RunDetail>) => {
              this._run = e.detail
              this._tab = 'rules'
            }}
          ></rulestone-try-it>
        </aside>
      </div>
      ${this.liveRegion()}
    `
  }

  private renderMeta(result: ValidationResult | undefined, errors: number): TemplateResult {
    const t = this.table
    const set = (patch: Parameters<typeof updateTable>[1]) => this.edit(updateTable(this.table, patch))
    const field = (key: string) => (result?.problems ?? []).filter((p) => p.target.kind === 'table' && p.target.field === key)
    const invalid = (key: string) => (field(key).some((p) => p.severity === 'error') ? 'true' : 'false')
    return html`<fieldset part="metadata" class="meta" aria-label=${this.t('table.metadata')}>
      <legend>${this.t('table.metadata')}</legend>
      <label>${this.t('table.id')}
        <input class="id mono" data-field="id" .value=${t.id} ?readonly=${this.readonly} aria-invalid=${invalid('id')} @change=${(e: Event) => set({ id: (e.target as HTMLInputElement).value.trim() })} />
      </label>
      <label>${this.t('table.version')}
        <input class="version mono" data-field="version" .value=${t.version} ?readonly=${this.readonly} aria-invalid=${invalid('version')} @change=${(e: Event) => set({ version: (e.target as HTMLInputElement).value.trim() })} />
      </label>
      <label>${this.t('table.effectiveFrom')}
        <input type="date" data-field="effectiveFrom" .value=${t.effectiveFrom ?? ''} ?readonly=${this.readonly} aria-invalid=${invalid('effectiveFrom')} @change=${(e: Event) => set({ effectiveFrom: (e.target as HTMLInputElement).value })} />
      </label>
      <label>${this.t('table.effectiveTo')}
        <input type="date" data-field="effectiveTo" .value=${t.effectiveTo ?? ''} ?readonly=${this.readonly} aria-invalid=${invalid('effectiveTo')} @change=${(e: Event) => set({ effectiveTo: (e.target as HTMLInputElement).value })} />
      </label>
      <label title=${this.t('table.hitPolicyHelp')}>${this.t('table.hitPolicy')}
        <select data-field="hitPolicy" ?disabled=${this.readonly} aria-invalid=${invalid('hitPolicy')} @change=${(e: Event) => set({ hitPolicy: (e.target as HTMLSelectElement).value })}>
          ${HIT_POLICIES.map((p) => html`<option value=${p} ?selected=${p === t.hitPolicy}>${this.t(`hitPolicies.${p}`)}</option>`)}
          ${(HIT_POLICIES as readonly string[]).includes(t.hitPolicy) ? nothing : html`<option selected value=${t.hitPolicy}>${t.hitPolicy}</option>`}
        </select>
      </label>
      <label class="description">${this.t('table.description')}
        <input data-field="description" .value=${t.description ?? ''} ?readonly=${this.readonly} @change=${(e: Event) => set({ description: (e.target as HTMLInputElement).value })} />
      </label>
      <div class="status" aria-live="polite">
        ${this._validating
          ? html`<span class="muted">${this.t('table.validating')}</span>`
          : errors === 0
            ? html`<span class="ok">✓ ${this.t('table.valid')}</span>`
            : html`<span class="sev-error">${this.t('table.invalid', { n: errors })}</span>`}
        ${this.readonly ? html` · <span class="muted">${this.t('table.readonly')}</span>` : nothing}
        ${result?.ruleVersion ? html`<div class="version-hash" title=${this.t('table.ruleVersion')}>${result.ruleVersion}</div>` : nothing}
      </div>
    </fieldset>`
  }

  private renderTabs(): TemplateResult {
    const tabs: [Tab, string][] = [
      ['rules', this.t('table.tabRules')],
      ['schema', this.t('table.tabSchema')],
      ['json', this.t('table.tabJson')],
    ]
    return html`<div part="tabs" role="tablist" aria-label=${this.t('table.label')} @keydown=${(e: KeyboardEvent) => {
      const index = tabs.findIndex(([k]) => k === this._tab)
      const next = e.key === 'ArrowRight' ? index + 1 : e.key === 'ArrowLeft' ? index - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : undefined
      if (next === undefined) return
      e.preventDefault()
      this._tab = tabs[(next + tabs.length) % tabs.length]![0]
      void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.focus())
    }}>
      ${tabs.map(
        ([key, label]) => html`<button
          type="button"
          role="tab"
          id=${`tab-${key}`}
          aria-selected=${this._tab === key ? 'true' : 'false'}
          aria-controls=${`panel-${key}`}
          tabindex=${this._tab === key ? 0 : -1}
          @click=${() => (this._tab = key)}
        >${label}</button>`,
      )}
    </div>`
  }

  private renderPanel(): TemplateResult {
    const problems = this._result?.problems ?? []
    return html`<div role="tabpanel" id=${`panel-${this._tab}`} aria-labelledby=${`tab-${this._tab}`}>
      ${this._tab === 'rules'
        ? html`<rulestone-rules-grid
            .table=${this.table}
            .problems=${problems}
            .matchedRules=${this._run?.matchedRules}
            .evaluatedRules=${this._run ? this._run.evaluatedRules : undefined}
            .locale=${this.effectiveLocale}
            .messages=${this.messages}
            .theme=${this.theme}
            ?readonly=${this.readonly}
            @rulestone-change=${(e: CustomEvent<{ table: TableJson }>) => {
              e.stopPropagation()
              this.edit(e.detail.table)
            }}
          ></rulestone-rules-grid>`
        : this._tab === 'schema'
          ? html`<rulestone-schema-designer
              .table=${this.table}
              .locale=${this.effectiveLocale}
              .messages=${this.messages}
              .theme=${this.theme}
              ?readonly=${this.readonly}
              @rulestone-change=${(e: CustomEvent<{ table: TableJson }>) => {
                e.stopPropagation()
                this.edit(e.detail.table)
              }}
            ></rulestone-schema-designer>`
          : html`<p class="muted">${this.t('table.json')}</p>
              <pre class="code" tabindex="0" aria-label=${this.t('table.json')}>${tableText(this.table)}</pre>
              ${this._result?.canonical
                ? html`<p class="muted">${this.t('table.canonical')}</p>
                    <pre class="code" tabindex="0" aria-label=${this.t('table.canonical')}>${this._result.canonical}</pre>`
                : nothing}`}
    </div>`
  }

  private where(target: ProblemTarget): string {
    switch (target.kind) {
      case 'table':
        return target.field ? `${this.t('problems.table')} · ${target.field}` : this.t('problems.table')
      case 'schema':
        return `${this.t(target.side === 'inputs' ? 'problems.inputs' : 'problems.outputs')}${target.name ? ` · ${target.name}` : ''}`
      case 'rule': {
        const id = this.table.rules[target.rule]?.id ?? `#${target.rule + 1}`
        return `${this.t('problems.rule', { id })} · ${this.t(`problems.cells.${target.field}`, { output: target.output ?? '' })}`
      }
    }
  }

  private renderProblems(result: ValidationResult | undefined): TemplateResult {
    const problems = sortProblems(result?.problems ?? [])
    return html`<section part="problems" class="problems" aria-labelledby="problems-heading">
      <h2 id="problems-heading">${this.t('problems.title')} <span class="muted">(${problems.length})</span></h2>
      ${problems.length === 0
        ? html`<p class="ok">${this.t('problems.none')}</p>`
        : html`<ul>
            ${problems.map(
              (p) => html`<li>
                <span class=${p.severity === 'error' ? 'sev-error' : 'sev-warning'}>${p.severity === 'error' ? this.t('problems.error') : this.t('problems.warning')}</span>
                <span class="where">${this.where(p.target)}${p.issue ? ` · ${p.issue.line}:${p.issue.column}` : ''}</span>
                <span class="msg">${p.message.split('\n')[0]}${p.source === 'host' ? html` <span class="muted">(${this.t('problems.host')})</span>` : nothing}</span>
                <button type="button" @click=${() => this.show(p)}>${this.t('problems.show')}</button>
              </li>`,
            )}
          </ul>`}
    </section>`
  }

  /** Moves focus to where a problem is. */
  async show(problem: Problem): Promise<void> {
    const target = problem.target
    if (target.kind === 'rule') {
      this._tab = 'rules'
      await this.updateComplete
      const grid = this.grid
      if (!grid) return
      await grid.updateComplete
      grid.focusCell(target.rule, target.field, target.output, problem.issue?.index)
    } else if (target.kind === 'schema') {
      this._tab = 'schema'
      await this.updateComplete
      this.renderRoot.querySelector<HTMLElement>('rulestone-schema-designer')?.focus()
    } else {
      this.renderRoot.querySelector<HTMLElement>(`[data-field="${target.field ?? 'id'}"]`)?.focus()
    }
  }
}

define('rulestone-table-editor', RulestoneTableEditor)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-table-editor': RulestoneTableEditor
  }
}
