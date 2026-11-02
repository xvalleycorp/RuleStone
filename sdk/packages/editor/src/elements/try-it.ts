// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import {
  InvalidDecisionError, InvalidFactsError, JsonSyntaxError, LocalDate, RuleStoneError, createEngine, currencyTable,
  decisionRecordToJson, isEffectiveOn, parseDecisionFlow, parseDecisionTable, parseReasonCodeCatalog, valueToJson, writeDecisionFlow,
  writeDecisionTable, writeJson,
  writeParameterSet, writeReasonCodeCatalog, type DecisionRecord, type DecisionResult, type Explanation, type ReasonCodeCatalog,
  type RuleStoneEngine, type Writable, type DecisionFlow, type DecisionTable,
} from '@dxa/rulestone-engine'
import { css, html, nothing, type PropertyDeclarations, type PropertyValues, type TemplateResult } from 'lit'
import { emptyForm, factsJson, formFromJson, formValueAt, reconcileForm, setFormValue, type FormValue, type FormValues } from '../model/facts.js'
import { describeType } from '../model/schema.js'
import { readParamSets } from '../model/validate.js'
import type { FlowJson } from '../model/flow.js'
import { normalizeTable, type TableJson, type TypeSpec } from '../model/table.js'
import { RulestoneElement, define, today } from './base.js'

const CURRENCIES = [...currencyTable().keys()].sort()

/** What a run produced, as passed in the rulestone-run event. */
export interface RunDetail {
  readonly ok: boolean
  readonly result?: DecisionResult
  readonly record?: DecisionRecord
  /** The decision record as JSON text, as DecisionRecordJson writes it. */
  readonly recordJson?: string
  /** The explanation of the record, when the panel has a reason-code catalog. */
  readonly explanation?: Explanation
  readonly error?: string
  readonly problems?: readonly string[]
  /** Ids of the matched rules, and of every rule evaluated (the trace), for highlighting. */
  readonly matchedRules: readonly string[]
  readonly evaluatedRules: readonly string[]
}

/** Pretty-printed JSON of a Writable, keeping numbers exactly as the engine writes them. */
export function prettyJson(value: Writable, indent = ''): string {
  const next = indent + '  '
  if (value === null || typeof value !== 'object' || ('rawNumber' in (value as object) && !(value instanceof Map))) return writeJson(value)
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    return `[\n${value.map((v) => next + prettyJson(v as Writable, next)).join(',\n')}\n${indent}]`
  }
  const entries = value instanceof Map ? [...(value as ReadonlyMap<string, Writable>)] : Object.entries(value as Record<string, Writable>)
  if (entries.length === 0) return '{}'
  return `{\n${entries.map(([k, v]) => `${next}${writeJson(k)}: ${prettyJson(v, next)}`).join(',\n')}\n${indent}}`
}

/**
 * `<rulestone-try-it>`: runs a decision table in the browser with `@dxa/rulestone-engine`. The facts
 * come from a form generated from the inputs (typed money, date, struct and list inputs) or
 * from raw JSON; the panel shows the outputs, reason codes, trace and decision record, and, given a
 * reason-code catalog in `reasonCodes`, the explanation of the decision.
 *
 * @fires rulestone-run - detail RunDetail, after every run
 * @csspart form - the facts form
 * @csspart result - the result
 */
export class RulestoneTryIt extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    table: { attribute: false },
    flow: { attribute: false },
    tables: { attribute: false },
    models: { type: String },
    paramSets: { attribute: false },
    reasonCodes: { attribute: false },
    asOf: { type: String, attribute: 'as-of' },
    facts: { type: String },
    _form: { state: true },
    _mode: { state: true },
    _json: { state: true },
    _jsonError: { state: true },
    _run: { state: true },
    _running: { state: true },
    _tableProblems: { state: true },
  }

  declare table: TableJson
  /**
   * A decision flow to run instead of the table. The facts are the flow's inputs; the tables the flow
   * uses go in `tables`, and the results of its model nodes in `models`.
   */
  declare flow: FlowJson | undefined
  /** With a flow: every version of every table it uses, as .stone.json text or parsed JSON. */
  declare tables: readonly (string | object)[] | undefined
  /**
   * With a flow that has model nodes: their results as JSON text, by node id, as in a .stone-test.json
   * case. The panel runs model nodes as tests do (suppliedModelsOnly): it calls no model, also for
   * nodes in invoke mode.
   */
  declare models: string | undefined
  /**
   * The parameter sets the table reads, as .params.json text or parsed JSON: every version, so
   * that the business date selects one as the engine does.
   */
  declare paramSets: readonly (string | object)[] | undefined
  /**
   * A reason-code catalog, as reason-codes.json text, parsed JSON or a parsed catalog. With one, every
   * reason code of the table must have a message, and the panel explains each run in the panel's
   * locale, or the catalog's first locale if it has no messages in it.
   */
  declare reasonCodes: string | object | undefined
  /** The business date, yyyy-MM-dd; default today. */
  declare asOf: string
  /** Initial facts as JSON text, e.g. from a .stone-test.json case. */
  declare facts: string | undefined
  declare _form: FormValues
  declare _mode: 'form' | 'json'
  declare _json: string
  declare _jsonError: string | undefined
  declare _run: RunDetail | undefined
  declare _running: boolean
  declare _tableProblems: readonly string[]

  private engine: { key: string; engine: RuleStoneEngine; catalog: ReasonCodeCatalog | undefined } | undefined

  static override styles = [
    ...RulestoneElement.styles,
    css`
      .top {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: end;
        margin-bottom: 8px;
      }
      fieldset {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        margin: 4px 0;
        padding: 4px 8px 8px;
        min-width: 0;
      }
      legend {
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
        padding: 0 4px;
      }
      .field {
        display: grid;
        grid-template-columns: minmax(6em, 11em) minmax(0, 1fr);
        column-gap: 8px;
        align-items: center;
        margin: 4px 0;
      }
      .field > .name {
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
        color: var(--_color-text);
        overflow-wrap: anywhere;
      }
      .field .control {
        display: flex;
        gap: 4px;
        min-width: 0;
      }
      .field .control input,
      .field .control select {
        width: 100%;
        min-width: 0;
      }
      .field .control input.currency {
        width: 5.5em;
        flex: none;
        text-transform: uppercase;
      }
      .field .hint {
        grid-column: 2;
        font-size: 0.82em;
      }
      textarea.json {
        width: 100%;
        min-height: 180px;
        font-family: var(--_font-family-mono);
        font-size: 0.9em;
      }
      .result {
        margin-top: 12px;
        border-top: 1px solid var(--_color-border);
        padding-top: 8px;
      }
      .result h4 {
        margin: 10px 0 4px;
        font-size: 0.98em;
      }
      .result table {
        border-collapse: collapse;
      }
      .result td,
      .result th {
        border: 1px solid var(--_color-border);
        padding: 2px 8px;
        text-align: left;
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
      }
      .trace {
        list-style: none;
        padding: 0;
        margin: 0;
      }
      .trace li {
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
      }
      .trace .yes {
        color: var(--_color-success);
        font-weight: 700;
      }
      .failure {
        border: 1px solid var(--_color-error);
        background: var(--_color-error-subtle);
        border-radius: var(--_radius);
        padding: 6px 10px;
        white-space: pre-wrap;
        font-family: var(--_font-family-mono);
        font-size: 0.9em;
        margin: 0;
      }
      .note {
        font-size: 0.88em;
        overflow-wrap: anywhere;
      }
      .result p.mono {
        overflow-wrap: anywhere;
        margin: 0;
      }
      ol.items {
        margin: 0;
        padding-left: 20px;
      }
    `,
  ]

  constructor() {
    super()
    this.table = normalizeTable({})
    this.flow = undefined
    this.tables = undefined
    this.models = undefined
    this.paramSets = undefined
    this.reasonCodes = undefined
    this.asOf = today()
    this.facts = undefined
    this._form = {}
    this._mode = 'form'
    this._json = '{}'
    this._jsonError = undefined
    this._run = undefined
    this._running = false
    this._tableProblems = []
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('table') || changed.has('flow')) {
      this._form = Object.keys(this._form).length === 0 ? emptyForm(this.inputs) : reconcileForm(this._form, this.inputs)
      if (this._mode === 'form') this._json = factsJson(this._form, this.inputs)
    }
    if (changed.has('facts') && this.facts !== undefined) {
      this._json = this.facts
      try {
        this._form = formFromJson(this.facts, this.inputs)
        this._jsonError = undefined
      } catch (e) {
        this._mode = 'json'
        this._jsonError = e instanceof Error ? e.message : String(e)
      }
    }
  }

  /** The inputs of what the panel runs: the flow's, if it has one, else the table's. */
  get inputs(): Record<string, TypeSpec> {
    return this.flow ? this.flow.inputs : this.table.inputs
  }

  /** Whether the flow has model nodes, whose results the panel takes in `models`. */
  private get hasModels(): boolean {
    return this.flow !== undefined && Object.values(this.flow.nodes).some((n) => n.type === 'model')
  }

  /** The facts as JSON text, as they would be run. */
  get factsText(): string {
    return this._mode === 'json' ? this._json : factsJson(this._form, this.inputs)
  }

  private setMode(mode: 'form' | 'json'): void {
    if (mode === this._mode) return
    if (mode === 'json') {
      this._json = factsJson(this._form, this.inputs)
      this._jsonError = undefined
    } else {
      try {
        this._form = formFromJson(this._json, this.inputs)
        this._jsonError = undefined
      } catch (e) {
        this._jsonError = this.t('tryIt.invalidJson', { message: e instanceof JsonSyntaxError ? `${e.line}:${e.column}` : String(e) })
        return
      }
    }
    this._mode = mode
  }

  private async compile(): Promise<RuleStoneEngine | undefined> {
    if (this.flow) return this.compileFlow(this.flow)
    let parsed
    try {
      parsed = parseDecisionTable(JSON.stringify(this.table))
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems
        return undefined
      }
      throw e
    }
    let paramSets
    try {
      paramSets = readParamSets(this.paramSets)
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems
        return undefined
      }
      throw e
    }
    let reasonCodeCatalog: ReasonCodeCatalog | undefined
    try {
      reasonCodeCatalog = readCatalog(this.reasonCodes)
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems.map((p) => `reasonCodes ${p}`)
        return undefined
      }
      throw e
    }
    const key = [writeDecisionTable(parsed), ...paramSets.map(writeParameterSet), reasonCodeCatalog ? writeReasonCodeCatalog(reasonCodeCatalog) : ''].join('\n')
    if (this.engine?.key === key) return this.engine.engine
    try {
      const engine = await createEngine({ tables: [parsed], paramSets, ...(reasonCodeCatalog ? { reasonCodeCatalog } : {}) })
      this.engine = { key, engine, catalog: reasonCodeCatalog }
      this._tableProblems = []
      return engine
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems
        return undefined
      }
      throw e
    }
  }

  /** An engine with the flow and its tables, model nodes reading their results from `models`. */
  private async compileFlow(flow: FlowJson): Promise<RuleStoneEngine | undefined> {
    let parsed: DecisionFlow
    let tables: DecisionTable[]
    let paramSets
    let reasonCodeCatalog: ReasonCodeCatalog | undefined
    try {
      parsed = parseDecisionFlow(JSON.stringify(flow))
      tables = (this.tables ?? []).map((t) => parseDecisionTable(typeof t === 'string' ? t : JSON.stringify(t)))
      paramSets = readParamSets(this.paramSets)
      reasonCodeCatalog = readCatalog(this.reasonCodes)
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems
        return undefined
      }
      throw e
    }
    const key = [
      writeDecisionFlow(parsed), ...tables.map(writeDecisionTable), ...paramSets.map(writeParameterSet),
      reasonCodeCatalog ? writeReasonCodeCatalog(reasonCodeCatalog) : '',
    ].join('\n')
    if (this.engine?.key === key) return this.engine.engine
    try {
      const engine = await createEngine({
        tables, flows: [parsed], paramSets, suppliedModelsOnly: true, ...(reasonCodeCatalog ? { reasonCodeCatalog } : {}),
      })
      this.engine = { key, engine, catalog: reasonCodeCatalog }
      this._tableProblems = []
      return engine
    } catch (e) {
      if (e instanceof InvalidDecisionError) {
        this._tableProblems = e.problems
        return undefined
      }
      throw e
    }
  }

  /** Runs the table, or the flow, with the current facts. Resolves with what the rulestone-run event carries. */
  async run(): Promise<RunDetail> {
    this._running = true
    let detail: RunDetail
    try {
      const engine = await this.compile()
      if (!engine) {
        detail = { ok: false, error: this.t('tryIt.invalidTable', { n: this._tableProblems.length }), problems: this._tableProblems, matchedRules: [], evaluatedRules: [] }
      } else {
        const what = this.flow ?? this.table
        const ref = { id: what.id, version: what.version, asOf: this.asOf || today() }
        try {
          const models = this.hasModels && this.models !== undefined && this.models.trim() !== '' ? this.models : undefined
          const result = await engine.evaluateJson(ref, this.factsText, models)
          detail = {
            ok: true,
            result,
            record: result.record,
            recordJson: prettyJson(decisionRecordToJson(result.record)),
            ...this.explain(engine, result.record),
            matchedRules: result.matchedRules,
            evaluatedRules: result.trace.map((t) => t.ruleId),
          }
        } catch (e) {
          if (!(e instanceof RuleStoneError) && !(e instanceof RangeError)) throw e
          const record = e instanceof RuleStoneError ? e.record : undefined
          detail = {
            ok: false,
            error: e instanceof InvalidFactsError ? this.t('tryIt.invalidFacts') : e.message,
            ...(e instanceof InvalidFactsError ? { problems: e.problems } : {}),
            ...(record ? { record, recordJson: prettyJson(decisionRecordToJson(record)) } : {}),
            matchedRules: [],
            evaluatedRules: [],
          }
        }
      }
    } finally {
      this._running = false
    }
    this._run = detail
    this.emit('rulestone-run', detail)
    this.announce(detail.ok ? this.t('tryIt.matchedAnnounce', { n: detail.matchedRules.length }) : this.t('tryIt.error'))
    return detail
  }

  /** The explanation of a record in the panel's locale, if the engine has a catalog. */
  private explain(engine: RuleStoneEngine, record: DecisionRecord): { explanation?: Explanation } {
    const catalog = this.engine?.engine === engine ? this.engine.catalog : undefined
    if (!catalog) return {}
    const locales = catalog.locales
    const wanted = this.effectiveLocale
    const locale = locales.find((l) => l === wanted) ?? locales.find((l) => l.split('-')[0] === wanted.split('-')[0]) ?? locales[0]!
    return { explanation: engine.explain(record, locale) }
  }

  protected override render() {
    const inputs = Object.entries(this.inputs)
    const date = LocalDate.parse(this.asOf)
    let notEffective = false
    try {
      const parsed = this.flow ? parseDecisionFlow(JSON.stringify(this.flow)) : parseDecisionTable(JSON.stringify(this.table))
      notEffective = date !== undefined && !isEffectiveOn(parsed, date)
    } catch {
      // Problems are shown when the user runs the table.
    }
    return html`
      <p class="muted note">${this.t(this.flow ? 'tryIt.introFlow' : 'tryIt.intro')}</p>
      <div class="top">
        <label>${this.t('tryIt.asOf')}
          <input type="date" .value=${this.asOf} title=${this.t('tryIt.asOfHelp')} @change=${(e: Event) => (this.asOf = (e.target as HTMLInputElement).value)} />
        </label>
        <div class="row" role="group" aria-label=${this.t('tryIt.factsMode')}>
          <button type="button" aria-pressed=${this._mode === 'form' ? 'true' : 'false'} @click=${() => this.setMode('form')}>${this.t('tryIt.form')}</button>
          <button type="button" aria-pressed=${this._mode === 'json' ? 'true' : 'false'} @click=${() => this.setMode('json')}>${this.t('tryIt.json')}</button>
        </div>
        <button type="button" class="primary" ?disabled=${this._running} @click=${() => void this.run()}>${this._running ? this.t('tryIt.running') : this.t('tryIt.run')}</button>
      </div>
      ${notEffective ? html`<p class="warning-text note">${this.t('tryIt.notEffective', { date: this.asOf })}</p>` : nothing}
      ${this._jsonError ? html`<p class="error-text" role="alert">${this._jsonError}</p>` : nothing}
      <div part="form">
        ${this._mode === 'json'
          ? html`<textarea
              class="json"
              spellcheck="false"
              aria-label=${this.t('tryIt.json')}
              .value=${this._json}
              @input=${(e: Event) => (this._json = (e.target as HTMLTextAreaElement).value)}
            ></textarea>`
          : inputs.length === 0
            ? html`<p class="muted">${this.t('tryIt.noInputs')}</p>`
            : inputs.map(([name, spec]) => this.renderValue([name], name, spec, false))}
      </div>
      ${this.hasModels
        ? html`<label class="models">${this.t('tryIt.models')}
            <textarea
              class="json"
              spellcheck="false"
              .value=${this.models ?? ''}
              placeholder='{ "fraud": { "modelId": "…", "modelVersion": "…", "outputs": { … } } }'
              @input=${(e: Event) => (this.models = (e.target as HTMLTextAreaElement).value)}
            ></textarea>
          </label>
          <p class="muted note">${this.t('tryIt.modelsHelp')}</p>`
        : nothing}
      ${this.renderResult()}
      ${this.liveRegion()}
    `
  }

  private update_(path: readonly (string | number)[], value: FormValue): void {
    this._form = setFormValue(this._form, path, value)
  }

  private renderValue(path: readonly (string | number)[], label: string, spec: TypeSpec, optional: boolean): TemplateResult {
    const value = formValueAt(this._form, path)
    const id = `f-${path.join('-')}`
    if (Array.isArray(spec)) {
      const items = value?.kind === 'list' ? value.items : []
      const element = spec[0]!
      return html`<fieldset>
        <legend>${label} <span class="muted">${describeType(spec)}</span></legend>
        <ol class="items">
          ${items.map(
            (_, i) => html`<li>
              ${this.renderValue([...path, i], this.t('tryIt.item', { n: i + 1 }), element, false)}
              <button type="button" @click=${() => this.update_(path, { kind: 'list', items: items.filter((__, k) => k !== i) })}>${this.t('tryIt.removeItem', { n: i + 1 })}</button>
            </li>`,
          )}
        </ol>
        <button type="button" @click=${() => this.update_(path, { kind: 'list', items: [...items, emptyForm({ x: element })['x']!] })}>+ ${this.t('tryIt.addItem')}</button>
      </fieldset>`
    }
    if (typeof spec === 'object') {
      return html`<fieldset>
        <legend>${label} <span class="muted">struct</span></legend>
        ${Object.entries(spec).map(([field, type]) => this.renderValue([...path, field], field, type, true))}
        <p class="muted note">${this.t('tryIt.leaveBlank')}</p>
      </fieldset>`
    }
    const typeHint = html`<span class="type muted">${spec}${optional ? ` · ${this.t('common.optional')}` : ''}</span>`
    if (spec === 'money') {
      const money = value?.kind === 'money' ? value : { kind: 'money' as const, amount: '', currency: 'VND' }
      return html`<div class="field">
        <label class="name" for=${id}>${label}</label>
        <span class="control">
          <input id=${id} inputmode="decimal" aria-describedby=${`${id}-t`} placeholder="1500000" .value=${money.amount}
            @input=${(e: Event) => this.update_(path, { ...money, amount: (e.target as HTMLInputElement).value })} />
          <input class="currency" list="currencies" aria-label=${`${label}: ${this.t('builder.currency')}`} .value=${money.currency}
            @input=${(e: Event) => this.update_(path, { ...money, currency: (e.target as HTMLInputElement).value.toUpperCase() })} />
          <datalist id="currencies">${CURRENCIES.map((c) => html`<option value=${c}></option>`)}</datalist>
        </span>
        <span class="hint" id=${`${id}-t`}>${typeHint}</span>
      </div>`
    }
    const text = value?.kind === 'scalar' ? value.text : ''
    const set = (t: string) => this.update_(path, { kind: 'scalar', text: t })
    let control: TemplateResult
    if (spec === 'bool') {
      control = html`<select id=${id} aria-describedby=${`${id}-t`} @change=${(e: Event) => set((e.target as HTMLSelectElement).value)}>
        ${optional || text === '' ? html`<option value="" ?selected=${text === ''}>${optional ? this.t('common.unset') : '—'}</option>` : nothing}
        <option value="true" ?selected=${text === 'true'}>true</option>
        <option value="false" ?selected=${text === 'false'}>false</option>
      </select>`
    } else if (spec === 'date') {
      control = html`<input id=${id} type="date" aria-describedby=${`${id}-t`} .value=${text} @input=${(e: Event) => set((e.target as HTMLInputElement).value)} />`
    } else {
      const numeric = spec === 'int' || spec === 'decimal'
      control = html`<input
        id=${id}
        inputmode=${spec === 'int' ? 'numeric' : spec === 'decimal' ? 'decimal' : nothing}
        aria-describedby=${`${id}-t`}
        aria-invalid=${numeric && text !== '' && !(spec === 'int' ? /^-?[0-9]+$/ : /^-?[0-9]+(\.[0-9]+)?$/).test(text.trim()) ? 'true' : 'false'}
        .value=${text}
        @input=${(e: Event) => set((e.target as HTMLInputElement).value)}
      />`
    }
    return html`<div class="field"><label class="name" for=${id}>${label}</label><span class="control">${control}</span><span class="hint" id=${`${id}-t`}>${typeHint}</span></div>`
  }

  private renderResult(): TemplateResult {
    const run = this._run
    if (!run) return html``
    const result = run.result
    return html`<section part="result" class="result" aria-label=${this.t('tryIt.result')}>
      ${run.ok && result
        ? html`
            <h4>${this.t('tryIt.output')}</h4>
            ${result.output.size === 0
              ? html`<p class="muted">${result.matched ? this.t('common.none') : this.t('tryIt.noMatch')}</p>`
              : html`<table>
                  <tbody>
                    ${[...result.output].map(([name, value]) => html`<tr><th scope="row">${name}</th><td>${writeJson(valueToJson(value, name))}</td></tr>`)}
                  </tbody>
                </table>`}
            <h4>${this.t('tryIt.reasonCodes')}</h4>
            <p class="mono">${result.reasonCodes.length === 0 ? this.t('common.none') : result.reasonCodes.join(', ')}</p>
            ${run.explanation ? this.renderExplanation(run.explanation) : nothing}
            ${this.flow ? this.renderPath(result.record) : nothing}
            <h4>${this.t('tryIt.trace')}</h4>
            <ol class="trace">
              ${result.trace.map((step) => html`<li>
                <span class=${step.matched ? 'yes' : ''} aria-hidden="true">${step.matched ? '✓' : '✗'}</span>
                ${step.ruleId} <span class="sr-only">${step.matched ? this.t('grid.matched') : this.t('grid.notMatched')}</span>
              </li>`)}
            </ol>
            <h4>${this.t('tryIt.ruleVersion')}</h4>
            <p class="mono">${result.ruleVersion}</p>
          `
        : html`<h4 class="error-text">${this.t('tryIt.error')}</h4>
            <pre class="failure" role="alert">${run.error}${run.problems ? '\n' + run.problems.map((p) => `- ${p}`).join('\n') : ''}</pre>`}
      ${run.recordJson
        ? html`<h4>${this.t('tryIt.record')}</h4>
            <pre class="code" tabindex="0" aria-label=${this.t('tryIt.record')}>${run.recordJson}</pre>`
        : nothing}
      <p class="muted note">${this.t('tryIt.engineNote')}</p>
    </section>`
  }

  /** The nodes a flow visited: the table and rules of a table node, the branch taken, a model's status. */
  private renderPath(record: DecisionRecord): TemplateResult {
    return html`<h4>${this.t('tryIt.path')}</h4>
      <ol class="items path" part="path">
        ${record.path.map((step) => {
          let detail = ''
          if (step.type === 'table') detail = `${step.decision ?? ''}: ${step.matchedRules.length === 0 ? '—' : step.matchedRules.join(', ')}`
          else if (step.taken !== undefined) detail = `→ ${step.taken}`
          else if (step.model) detail = `${step.model.id}@${step.model.version} ${step.model.status}`
          return html`<li><span class="mono">${step.node}</span> <span class="muted">${step.type}</span> ${detail}</li>`
        })}
      </ol>`
  }

  private renderExplanation(explanation: Explanation): TemplateResult {
    return html`<h4>${this.t('tryIt.explanation')}</h4>
      ${explanation.reasons.length === 0
        ? html`<p class="muted">${this.t('common.none')}</p>`
        : html`<ol class="items" part="explanation" lang=${explanation.locale}>
            ${explanation.reasons.map((r) => html`<li>${r.message ?? r.code} <span class="muted mono">${r.code}</span></li>`)}
          </ol>`}
      <p class="muted note">${this.t('tryIt.explanationLocale', { locale: explanation.locale, catalog: explanation.catalog })}</p>`
  }
}

/** A catalog given as text, parsed JSON or an already parsed catalog. @throws InvalidDecisionError */
function readCatalog(catalog: string | object | undefined): ReasonCodeCatalog | undefined {
  if (catalog === undefined) return undefined
  if (typeof catalog === 'object' && (catalog as { codes?: unknown }).codes instanceof Map) return catalog as ReasonCodeCatalog
  return parseReasonCodeCatalog(typeof catalog === 'string' ? catalog : JSON.stringify(catalog))
}

define('rulestone-try-it', RulestoneTryIt)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-try-it': RulestoneTryIt
  }
}
