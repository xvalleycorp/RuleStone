// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import '../src/index.js'
import type {
  RulestoneFlowCanvas, RulestoneRulesGrid, RulestoneSchemaDesigner, RulestoneTableEditor, RulestoneTryIt, RunDetail, TableChangeDetail,
} from '../src/index.js'
import { FIT_ZOOM, LAYOUT_SPACING, NODE_HEIGHT, NODE_WIDTH, autoLayout, parseFlow, type TableJson } from '../src/model/index.js'
import { backRoute } from '../src/elements/flow-canvas.js'
import { $, $$, exampleTable, settle } from './helpers.js'

afterEach(() => document.body.replaceChildren())

async function mount<T extends HTMLElement>(tag: string, props: Record<string, unknown>): Promise<T> {
  const el = document.createElement(tag) as T
  Object.assign(el, props)
  document.body.append(el)
  await settle(el)
  return el
}

function key(target: Element, k: string, init: KeyboardEventInit = {}): void {
  target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, composed: true, ...init }))
}

function events<T>(el: Element, name: string): T[] {
  const out: T[] = []
  el.addEventListener(name, (e) => out.push((e as CustomEvent<T>).detail))
  return out
}

describe('<rulestone-rules-grid>', () => {
  const table = () => exampleTable('loan_eligibility.v3.stone.json')

  it('is an ARIA grid with a row per rule and a column per output', async () => {
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table: table() })
    const grid = $(el, '[role="grid"]')!
    expect(grid.getAttribute('aria-rowcount')).toBe('6')
    const headers = $$(el, '[role="columnheader"]').map((h) => h.textContent!.replace(/\s+/g, ' ').trim())
    expect(headers).toEqual(['Result', 'Rule', 'Condition', 'decision · string', 'limit · decimal', 'Reason code', 'Description', 'Actions'])
    expect($$(el, 'tbody [role="row"]')).toHaveLength(5)
    // One tab stop: the active cell.
    expect($$(el, '[role="gridcell"][tabindex="0"]')).toHaveLength(1)
  })

  it('moves rules with Alt+Up and Alt+Down, announcing the new position', async () => {
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table: table() })
    const changes = events<{ table: TableJson }>(el, 'rulestone-change')
    const cell = $(el, 'td[data-row="2"][data-col="1"]')!
    cell.click()
    await settle(el)
    key($(el, 'td[data-row="2"][data-col="1"]')!, 'ArrowUp', { altKey: true })
    await settle(el)
    const ids = changes.at(-1)!.table.rules.map((r) => r.id)
    expect(ids.indexOf(table().rules[2]!.id)).toBe(1)
    expect($(el, '[role="status"]')!.textContent).toBe(`Rule ${table().rules[2]!.id} moved to position 2 of 5.`)
  })

  it('moves the active cell with the arrow keys and edits on Enter', async () => {
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table: table() })
    $(el, 'td[data-row="0"][data-col="1"]')!.click()
    await settle(el)
    key($(el, 'td[data-row="0"][data-col="1"]')!, 'ArrowRight')
    key($(el, 'td[data-row="0"][data-col="1"]')!, 'ArrowDown')
    await settle(el)
    const active = $(el, 'td[tabindex="0"]')!
    expect([active.dataset['row'], active.dataset['col']]).toEqual(['1', '2'])
    key(active, 'Enter')
    await settle(el)
    expect($(el, 'td.editing rs-cel-input')).not.toBeNull()
  })

  it('checks every expression cell with the engine and shows where the problem is', async () => {
    const t = table()
    t.rules[0]!.when = 'applicant.agee < 18'
    t.rules[3]!.then = { decision: '42' }
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table: t })
    const when = $(el, 'td[data-row="0"][data-col="2"]')!
    expect(when.getAttribute('aria-invalid')).toBe('true')
    expect(when.querySelector('.problem')!.textContent).toBe("1:10 undefined field 'agee'")
    expect(when.querySelector('.tok-err')!.textContent).toBe('.')
    const then = $(el, 'td[data-row="3"][data-col="3"]')!
    expect(then.querySelector('.problem')!.textContent).toContain('expression must produce string but produces int')
  })

  it('adds, duplicates and deletes rules', async () => {
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table: table() })
    const changes = events<{ table: TableJson }>(el, 'rulestone-change')
    ;($(el, '.toolbar button.primary') as HTMLButtonElement).click()
    await settle(el)
    expect(changes.at(-1)!.table.rules).toHaveLength(6)
    // Under FIRST a new rule goes before the final catch-all.
    expect(changes.at(-1)!.table.rules[4]!.when).toBe('true')
    const duplicate = $$(el, 'td[data-row="0"] button').find((b) => b.getAttribute('aria-label')!.startsWith('Duplicate'))!
    duplicate.click()
    await settle(el)
    expect(changes.at(-1)!.table.rules[1]!.id).toBe(`${table().rules[0]!.id}_2`)
    $$(el, 'td[data-row="1"] button').find((b) => b.getAttribute('aria-label')!.startsWith('Delete'))!.click()
    await settle(el)
    expect(changes.at(-1)!.table.rules).toHaveLength(6)
  })

  it('highlights the rows that matched, with text and not only colour', async () => {
    const t = table()
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', {
      table: t,
      matchedRules: [t.rules[3]!.id],
      evaluatedRules: t.rules.slice(0, 4).map((r) => r.id),
    })
    const rows = $$(el, 'tbody tr[role="row"]')
    expect(rows.map((r) => r.className.trim().split(/\s+/)[0])).toEqual(['not-matched', 'not-matched', 'not-matched', 'matched', 'not-evaluated'])
    expect(rows[3]!.querySelector('.sr-only')!.textContent).toBe('matched')
  })
})

describe('<rulestone-schema-designer>', () => {
  it('renames a field and every expression that uses it, and reports the changes', async () => {
    const el = await mount<RulestoneSchemaDesigner>('rulestone-schema-designer', { table: exampleTable('loan_eligibility.v3.stone.json') })
    const changes = events<{ table: TableJson; rename?: { ok: boolean } }>(el, 'rulestone-change')
    expect($$(el, '[role="tree"]')).toHaveLength(2)
    const item = $(el, 'li[data-key="inputs:applicant.monthlyIncome"]')!
    expect(item.getAttribute('aria-level')).toBe('2')
    const input = item.querySelector('input.name') as HTMLInputElement
    input.value = 'income'
    input.dispatchEvent(new Event('change'))
    await settle(el)
    const table = changes.at(-1)!.table
    expect(table.rules[2]!.when).toBe("applicant.monthlyDebt / applicant.income > decimal('0.5')")
    expect(table.rules[3]!.then!['limit']).toBe("(applicant.income * 12 / 1000000).round(0, 'FLOOR') * 1000000")
    expect($(el, '[part="report"]')!.textContent).toContain('Renamed applicant.monthlyIncome to applicant.income.')
  })

  it('refuses an invalid name and says why', async () => {
    const el = await mount<RulestoneSchemaDesigner>('rulestone-schema-designer', { table: exampleTable('loan_eligibility.v3.stone.json') })
    const input = $(el, 'li[data-key="inputs:amount"] input.name') as HTMLInputElement
    input.value = 'applicant'
    input.dispatchEvent(new Event('input'))
    await settle(el)
    expect($(el, 'li[data-key="inputs:amount"] .name-error')!.textContent).toBe('Another field has this name.')
    input.dispatchEvent(new Event('change'))
    await settle(el)
    expect(input.value).toBe('amount')
  })

  it('reorders fields with Alt+Arrow keys on a tree item', async () => {
    const el = await mount<RulestoneSchemaDesigner>('rulestone-schema-designer', { table: exampleTable('loan_eligibility.v3.stone.json') })
    const changes = events<{ table: TableJson }>(el, 'rulestone-change')
    key($(el, 'li[data-key="inputs:amount"]')!, 'ArrowUp', { altKey: true })
    await settle(el)
    expect(Object.keys(changes.at(-1)!.table.inputs)).toEqual(['amount', 'applicant'])
    expect($(el, '[role="status"]')!.textContent).toBe('amount moved to position 1 of 2.')
  })
})

describe('<rulestone-try-it>', () => {
  it('runs a table with typed facts and shows the outputs, trace and decision record', async () => {
    const el = await mount<RulestoneTryIt>('rulestone-try-it', {
      table: exampleTable('loan_eligibility.v3.stone.json'),
      asOf: '2026-11-15',
      facts: JSON.stringify({ applicant: { age: 30, monthlyIncome: '20000000', monthlyDebt: '2000000', segment: 'RETAIL', blacklisted: false }, amount: '100000000' }),
    })
    const runs = events<RunDetail>(el, 'rulestone-run')
    const detail = await el.run()
    await settle(el)
    expect(detail.ok).toBe(true)
    expect(detail.matchedRules).toEqual([exampleTable('loan_eligibility.v3.stone.json').rules[3]!.id])
    expect(runs).toHaveLength(1)
    expect($(el, '[part="result"] table')!.textContent).toContain('"240000000"')
    expect(detail.recordJson).toContain('"status": "OK"')
    expect(detail.recordJson).toContain('"businessDate": "2026-11-15"')
  })

  it('generates a typed form from the inputs and reports facts that do not fit', async () => {
    const el = await mount<RulestoneTryIt>('rulestone-try-it', { table: exampleTable('aml_transaction_monitoring.stone.json'), asOf: '2026-11-15' })
    expect($$(el, 'input[type="date"]').length).toBeGreaterThanOrEqual(4)
    expect($$(el, 'input.currency').length).toBeGreaterThanOrEqual(3)
    const detail = await el.run()
    expect(detail.ok).toBe(false)
    // Empty structs leave every field unset, and reading an unset field fails the evaluation.
    expect(detail.error).toContain('condition failed')
    expect(detail.recordJson).toContain('"status": "ERROR"')
    const json = $$(el, '[role="group"] button').find((b) => b.textContent!.trim() === 'JSON')!
    json.click()
    await settle(el)
    const textarea = $(el, 'textarea.json') as HTMLTextAreaElement
    textarea.value = '{ "account": 1 }'
    textarea.dispatchEvent(new Event('input'))
    const invalid = await el.run()
    expect(invalid.error).toBe('The facts do not match the inputs')
    expect(invalid.problems![0]).toContain('account')
  })
})

describe('<rulestone-table-editor>', () => {
  it('reports each edit with the table, its canonical form, the problems and the rule version', async () => {
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', { table: exampleTable('deposit_rate.stone.json') })
    const validated = await el.validate()
    expect(validated.valid).toBe(true)
    const changes = events<TableChangeDetail>(el, 'rulestone-change')
    const id = $(el, 'input[data-field="version"]') as HTMLInputElement
    id.value = '2'
    id.dispatchEvent(new Event('change'))
    await new Promise((r) => setTimeout(r, 300))
    await settle(el)
    expect(changes).toHaveLength(1)
    expect(changes[0]!.table.version).toBe('2')
    expect(changes[0]!.ruleVersion).toMatch(/^2@sha256:[0-9a-f]{64}$/)
    expect(changes[0]!.canonical).toContain('"version":"2"')
    expect(el.getTable().version).toBe('2')
  })

  it('shows the host validator’s problems with its own', async () => {
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', {
      table: exampleTable('deposit_rate.stone.json'),
      validator: async () => [{ message: 'version 1 is already published', target: { kind: 'table', field: 'version' } }],
    })
    const result = await el.validate()
    expect(result.valid).toBe(false)
    await settle(el)
    expect($(el, '[part="problems"]')!.textContent).toContain('version 1 is already published')
  })

  it('takes a copy of the host’s table and never changes it', async () => {
    const original = exampleTable('deposit_rate.stone.json')
    const snapshot = JSON.stringify(original)
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', { table: original })
    el.setTable({ ...original, version: '9' })
    await settle(el)
    expect(el.getTable().version).toBe('9')
    expect(JSON.stringify(original)).toBe(snapshot)
  })

  it('is read-only on request and speaks Vietnamese', async () => {
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', { table: exampleTable('deposit_rate.stone.json'), readonly: true, locale: 'vi' })
    expect(($(el, 'input[data-field="id"]') as HTMLInputElement).readOnly).toBe(true)
    expect($$(el, '[role="tab"]').map((t) => t.textContent!.trim())).toEqual(['Luật', 'Đầu vào và đầu ra', 'JSON'])
    expect($(el, '.toolbar button.primary')).toBeNull()
  })

  it('highlights matched rows after Try it', async () => {
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', { table: exampleTable('deposit_rate.stone.json'), asOf: '2026-06-01' })
    const tryIt = $(el, 'rulestone-try-it') as RulestoneTryIt
    tryIt.facts = JSON.stringify({ termMonths: 9, balance: '1000000' })
    await settle(el)
    await tryIt.run()
    await settle(el)
    const matched = $$(el, 'tbody tr.matched')
    expect(matched).toHaveLength(1)
  })
})

describe('<rulestone-flow-canvas>', () => {
  const flow = () => parseFlow(readFileSync(join(process.cwd(), 'demo', 'card_origination.flow.json'), 'utf8'))

  it('draws every node and edge and says that flows are design-only', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: flow(), tables: ['card_policy', 'pd_to_grade', 'card_limit_matrix', 'card_limit_matrix_b'] })
    expect($$(el, 'g.node')).toHaveLength(11)
    expect($$(el, 'path.edge').length).toBeGreaterThanOrEqual(13)
    expect($(el, '[part="banner"]')!.textContent).toContain('Design only')
    expect($(el, '[part="problems"]')!.textContent).toContain('No problems')
    expect($(el, 'g.node[data-id="policy"]')!.getAttribute('aria-label')).toBe('Table node policy, start node')
  })

  it('adds nodes from the palette and connects them with the keyboard', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: flow() })
    const changes = events<{ flow: { nodes: Record<string, { next?: unknown }> } }>(el, 'rulestone-change')
    $$(el, '[part="palette"] button').find((b) => b.textContent!.trim() === 'Derive')!.click()
    await settle(el)
    expect(changes.at(-1)!.flow.nodes['derive1']).toBeDefined()
    key($(el, 'g.node[data-id="derive1"]')!, 'c')
    await settle(el)
    expect($(el, '[role="status"]')!.textContent).toContain('Connecting from derive1')
    key($(el, 'g.node[data-id="approved"]')!, 'Enter')
    await settle(el)
    expect(changes.at(-1)!.flow.nodes['derive1']!.next).toBe('approved')
    expect($(el, '[part="problems"]')!.textContent).toContain('Cannot be reached from the start node.')
  })

  it('reports model nodes without onFailure and splits that do not add up to 100', async () => {
    const f = flow()
    delete f.nodes['pd_behaviour']!.onFailure
    f.nodes['limit_strategy']!.buckets![0]!.percent = 80
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: f })
    const text = $(el, '[part="problems"]')!.textContent!
    expect(text).toContain('A model node needs onFailure')
    expect(text).toContain('Bucket percentages add up to 90, not 100.')
  })

  it('imports and exports JSON', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', {})
    expect(el.importText(JSON.stringify(flow()))).toBe(true)
    await settle(el)
    expect($$(el, 'g.node')).toHaveLength(11)
    expect(JSON.parse(el.toJson())).toEqual(flow())
    const exported = events<{ json: string }>(el, 'rulestone-export')
    el.addEventListener('rulestone-export', (e) => e.preventDefault())
    $$(el, '.toolbar button')[0]!.click()
    expect(JSON.parse(exported[0]!.json).id).toBe('card_origination')
    expect(el.importText('{ "nodes": 3 }')).toBe(false)
  })

  it('fits at a readable zoom, and arranges itself for its size until a node is moved', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: flow() })
    el.fit()
    await settle(el)
    const view = (el as unknown as { _view: { zoom: number } })._view
    expect(view.zoom).toBeGreaterThanOrEqual(FIT_ZOOM.min)
    expect(view.zoom).toBeLessThanOrEqual(FIT_ZOOM.max)
    // The default size (900 x 540) wraps the seven columns into rows.
    expect(new Set(Object.values(el.layout).map((p) => p.x)).size).toBeLessThan(7)
    const layout = { ...el.layout, policy: { x: 500, y: 500 } }
    el.layout = layout
    await settle(el)
    el.fit()
    expect(el.layout).toBe(layout)
  })

  it('arranges the nodes again on request and reports the new layout', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: flow(), layout: autoLayout(flow()) })
    const changes = events<{ layout: Record<string, { x: number; y: number }> }>(el, 'rulestone-change')
    $$(el, '.toolbar button').find((b) => b.textContent!.trim() === 'Arrange')!.click()
    await settle(el)
    expect(changes).toHaveLength(1)
    expect(new Set(Object.values(changes[0]!.layout).map((p) => p.x)).size).toBeLessThan(7)
    expect($(el, '[role="status"]')!.textContent).toContain('Nodes arranged.')
  })

  it('leaves the mouse wheel to the page unless Ctrl or Cmd is held', async () => {
    const el = await mount<RulestoneFlowCanvas>('rulestone-flow-canvas', { flow: flow() })
    const svg = $(el, 'svg')!
    const plain = new WheelEvent('wheel', { deltaY: 100, cancelable: true, bubbles: true })
    svg.dispatchEvent(plain)
    expect(plain.defaultPrevented).toBe(false)
    const zoom = new WheelEvent('wheel', { deltaY: -100, ctrlKey: true, cancelable: true, bubbles: true })
    Object.defineProperty(zoom, 'ctrlKey', { value: true }) // happy-dom drops modifier keys of wheel events
    svg.dispatchEvent(zoom)
    expect(zoom.defaultPrevented).toBe(true)
  })

  it('routes an edge to a node on the left around the nodes in between', () => {
    const layout = autoLayout(flow(), {}, { maxColumns: 3 })
    for (const [from, to, kind] of [['pd_application', 'grade', 'next'], ['pd_behaviour', 'referred', 'failure']] as const) {
      const d = backRoute({ from, to, kind }, layout)
      // The corners are the control points of the rounded path; the longest horizontal run must not cross a node.
      const corners = [...d.matchAll(/Q (-?[\d.]+) (-?[\d.]+)/g)].map((m) => [Number(m[1]), Number(m[2])] as const)
      const runs = corners.slice(1).map((c, i) => ({ y: c[1], length: c[1] === corners[i]![1] ? Math.abs(c[0] - corners[i]![0]) : 0 }))
      const run = runs.reduce((x, y) => (y.length > x.length ? y : x)).y
      const a = layout[from]!
      const b = layout[to]!
      const left = b.x - 20
      const right = kind === 'failure' ? a.x - 20 : a.x + NODE_WIDTH + 20
      for (const [id, p] of Object.entries(layout)) {
        if (id === from || id === to) continue
        const crosses = p.x < Math.max(left, right) && p.x + NODE_WIDTH > Math.min(left, right) && run > p.y && run < p.y + NODE_HEIGHT
        expect(crosses, `${from} -> ${to} crosses ${id} at y ${run}`).toBe(false)
      }
      expect(run).toBeGreaterThan(a.y)
      expect(run).toBeLessThan(b.y + LAYOUT_SPACING.margin)
    }
  })
})

describe('wrapping in the rules grid', () => {
  /** The pieces of a cell's text between the line-break opportunities the grid adds. */
  function pieces(cell: HTMLElement): string[] {
    const out = ['']
    const walk = (node: Node) => {
      for (const child of Array.from(node.childNodes)) {
        if (child.nodeName === 'WBR') out.push('')
        else if (child.nodeType === 3) out[out.length - 1] += child.textContent
        else walk(child)
      }
    }
    walk(cell)
    return out
  }

  it('breaks conditions between tokens, never inside a name', async () => {
    const table = exampleTable('loan_eligibility.v3.stone.json')
    table.rules[0]!.when = "applicant.age<18&&applicant.segment in ['RETAIL','SME']"
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table })
    const cel = $(el, 'td.when .cel')!
    expect(cel.textContent).toBe(table.rules[0]!.when)
    expect(pieces(cel)).toEqual(['applicant.', 'age', '<', '18', '&&', 'applicant.', 'segment in [', "'RETAIL',", "'SME']"])
  })

  it('lets reason codes and rule ids wrap after underscores', async () => {
    const table = exampleTable('aml_name_screening.stone.json')
    const el = await mount<RulestoneRulesGrid>('rulestone-rules-grid', { table })
    const row = table.rules.findIndex((r) => r.reasonCode === 'SANCTIONS_POSSIBLE_MATCH')
    expect(row).toBeGreaterThanOrEqual(0)
    expect(pieces($(el, `td.reasonCode[data-row="${row}"] .mono`)!)).toEqual(['SANCTIONS_', 'POSSIBLE_', 'MATCH'])
  })
})

describe('<rulestone-table-editor> Try it', () => {
  it('takes sample facts and runs them', async () => {
    const table = exampleTable('deposit_rate.stone.json')
    const el = await mount<RulestoneTableEditor>('rulestone-table-editor', { table, facts: '{ "termMonths": 12, "balance": 6000000000 }' })
    const tryIt = $(el, 'rulestone-try-it') as RulestoneTryIt
    const facts = JSON.parse(tryIt.factsText) as { termMonths: unknown; balance: unknown }
    expect([String(facts.termMonths), String(facts.balance)]).toEqual(['12', '6000000000'])
    const runs = events<RunDetail>(el, 'rulestone-run')
    const detail = await el.run()
    expect(detail.ok).toBe(true)
    expect(detail.matchedRules).toEqual(['VIP_LONG'])
    expect(runs).toHaveLength(1)
  })
})
