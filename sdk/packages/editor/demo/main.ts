// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/// <reference types="vite/client" />

// The development demo: the editor components on example tables from examples/ and on the
// example flow of docs/hybrid-ai.md. Query parameters (view, example, locale, theme, readonly)
// select what is shown, so that screenshots can be taken of each state.

import '../src/index.js'
import type { RulestoneConditionBuilder, RulestoneElement, RulestoneFlowCanvas, RulestoneTableEditor, TableChangeDetail } from '../src/index.js'
import flowText from './card_origination.flow.json?raw'

const tables = import.meta.glob('../../../../examples/*.stone.json', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const paramSets = Object.values(
  import.meta.glob('../../../../examples/*.params.json', { query: '?raw', import: 'default', eager: true }) as Record<string, string>,
)
const tests = import.meta.glob('../../../../examples/*.stone-test.json', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

const examples = new Map(
  Object.entries(tables)
    .map(([path, text]) => [path.split('/').pop()!.replace('.stone.json', ''), text] as const)
    .sort(([a], [b]) => a.localeCompare(b)),
)

const params = new URLSearchParams(location.search)
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T
const view = $<HTMLSelectElement>('view')
const example = $<HTMLSelectElement>('example')
const locale = $<HTMLSelectElement>('locale')
const theme = $<HTMLSelectElement>('theme')
const readonly = $<HTMLInputElement>('readonly')
const status = $<HTMLParagraphElement>('status')
const main = $<HTMLElement>('main')

for (const name of examples.keys()) example.add(new Option(name, name))
view.value = params.get('view') ?? 'table'
example.value = params.get('example') ?? 'loan_eligibility.v3'
locale.value = params.get('locale') ?? 'en'
theme.value = params.get('theme') ?? ''
readonly.checked = params.get('readonly') === '1'

let violations = 0
document.addEventListener('securitypolicyviolation', (e) => {
  violations++
  setStatus(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`, true)
})

function setStatus(text: string, violation = false): void {
  status.replaceChildren()
  if (violations > 0) {
    const v = document.createElement('span')
    v.className = 'violation'
    v.textContent = `${violations} CSP violations. `
    status.append(v)
  }
  status.append(violation ? '' : text)
}

/** The first test case of the example, for the Try it panel. */
function firstCase(name: string): { facts?: string; asOf?: string } {
  const text = tests[`../../../../examples/${name}.stone-test.json`]
  if (!text) return {}
  const suite = JSON.parse(text) as { cases: { facts: unknown; asOf?: string }[] }
  const c = suite.cases.find((x) => x.asOf !== undefined) ?? suite.cases[0]
  return c ? { facts: JSON.stringify(c.facts, null, 2), ...(c.asOf ? { asOf: c.asOf } : {}) } : {}
}

function shared(el: RulestoneElement): void {
  el.locale = locale.value
  if (theme.value) el.setAttribute('theme', theme.value)
  else el.removeAttribute('theme')
  el.readonly = readonly.checked
}

function render(): void {
  document.documentElement.lang = locale.value
  if (theme.value) document.documentElement.dataset['theme'] = theme.value
  else delete document.documentElement.dataset['theme']
  const url = new URLSearchParams({ view: view.value, example: example.value, locale: locale.value, ...(theme.value ? { theme: theme.value } : {}), ...(readonly.checked ? { readonly: '1' } : {}) })
  history.replaceState(null, '', `?${url}`)
  example.disabled = view.value === 'flow'
  main.replaceChildren()
  const table = JSON.parse(examples.get(example.value) ?? '{}') as Record<string, unknown>
  if (view.value === 'table') {
    const editor = document.createElement('rulestone-table-editor') as RulestoneTableEditor
    shared(editor)
    editor.paramSets = paramSets
    editor.table = table as never
    const { facts, asOf } = firstCase(example.value)
    if (asOf) editor.asOf = asOf
    editor.addEventListener('rulestone-validate', (e) => {
      const d = (e as CustomEvent<{ valid: boolean; ruleVersion?: string; problems: unknown[] }>).detail
      setStatus(`${d.valid ? 'valid' : `${d.problems.length} problems`} · ${d.ruleVersion ?? 'not readable'}`)
    })
    editor.addEventListener('rulestone-change', (e) => {
      const d = (e as CustomEvent<TableChangeDetail>).detail
      setStatus(`changed · ${d.valid ? 'valid' : `${d.problems.length} problems`} · ${d.ruleVersion ?? 'not readable'}`)
    })
    main.append(editor)
    if (facts) {
      void editor.updateComplete.then(() => {
        const tryIt = editor.shadowRoot?.querySelector('rulestone-try-it') as (HTMLElement & { facts?: string; run(): Promise<unknown> }) | null
        if (!tryIt) return
        tryIt.facts = facts
        if (params.get('run') === '1') void (tryIt as unknown as { updateComplete: Promise<unknown> }).updateComplete.then(() => tryIt.run())
      })
    }
  } else if (view.value === 'flow') {
    const canvas = document.createElement('rulestone-flow-canvas') as RulestoneFlowCanvas
    shared(canvas)
    canvas.flow = JSON.parse(flowText) as never
    canvas.tables = ['card_policy', 'pd_to_grade', 'card_limit_matrix', 'card_limit_matrix_b', ...examples.keys()]
    canvas.addEventListener('rulestone-change', (e) => {
      const d = (e as CustomEvent<{ problems: unknown[] }>).detail
      setStatus(`flow changed · ${d.problems.length} problems`)
    })
    main.append(canvas)
    void canvas.updateComplete.then(() => canvas.fit())
  } else {
    const builder = document.createElement('rulestone-condition-builder') as RulestoneConditionBuilder
    shared(builder)
    builder.inputs = table['inputs'] as never
    const rules = (table['rules'] as { when: string }[] | undefined) ?? []
    builder.value = rules.map((r) => r.when).sort((a, b) => b.length - a.length)[Number(params.get('rule') ?? 0)] ?? 'true'
    builder.addEventListener('rulestone-change', (e) => setStatus((e as CustomEvent<{ value: string }>).detail.value))
    main.append(builder)
    setStatus(builder.value)
  }
}

for (const control of [view, example, locale, theme, readonly]) control.addEventListener('change', render)
render()
