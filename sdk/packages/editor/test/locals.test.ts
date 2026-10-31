// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Local values (let): their types in rule expressions, their problems, renaming inputs they
// read, the table editor's list of them and the condition builder's field picker.

import { afterEach, describe, expect, it } from 'vitest'
import '../src/index.js'
import type { RulestoneConditionBuilder, RulestoneTableEditor, TableChangeDetail } from '../src/index.js'
import {
  deleteLocal, expressionProblems, localSpecs, newLocalName, renameInput, setLocal, tableText, tableVariables, validateTable,
} from '../src/model/index.js'
import { $, $$, exampleTable, settle } from './helpers.js'

const FILE = 'credit_limit_increase.stone.json'

afterEach(() => document.body.replaceChildren())

describe('local values in the model', () => {
  it('gives rule expressions the inferred types of the local values', () => {
    const t = exampleTable(FILE)
    const variables = tableVariables(t.inputs, t.let)
    expect(variables.get('dti')).toBe('decimal')
    expect(variables.get('monthlyDebt')).toBe('money')
    expect([...localSpecs(t.inputs, t.let)]).toContainEqual(['affordableLimit', 'money'])
    expect(expressionProblems(t)).toEqual([])
  })

  it('places problems of local values on them, with the position in the expression', async () => {
    const t = exampleTable(FILE)
    t.let = { ...t.let, dti: 'monthlyDebt / applicant.monthlyIncome.amount() + 1', again: 'again + 1' }
    const result = await validateTable(t)
    const summary = result.problems.map((p) => [p.target, p.message.split('\n')[0], p.issue?.column])
    expect(summary).toContainEqual([{ kind: 'let', name: 'dti' }, "found no matching overload for '_+_' applied to '(money, int)'", 48])
    expect(summary).toContainEqual([{ kind: 'let', name: 'again' }, 'cycle: again -> again', undefined])
    expect(result.valid).toBe(false)
  })

  it('adds, renames and deletes local values, keeping their order and the file order of keys', () => {
    let t = exampleTable('deposit_rate.stone.json')
    t = setLocal(t, undefined, newLocalName(t), 'termMonths * 30')
    expect(t.let).toEqual({ value1: 'termMonths * 30' })
    t = setLocal(t, undefined, 'big', 'balance >= 5000000000')
    t = setLocal(t, 'value1', 'termDays', 'termMonths * 30')
    expect(Object.keys(t.let!)).toEqual(['termDays', 'big'])
    expect(Object.keys(JSON.parse(tableText(t)) as object)).toEqual([
      'id', 'version', 'description', 'hitPolicy', 'inputs', 'outputs', 'let', 'rules',
    ])
    t = deleteLocal(deleteLocal(t, 'termDays'), 'big')
    expect(tableText(t)).not.toContain('"let"')
  })

  it('renames an input also in the local values that read it, and refuses a name a local value has', () => {
    const t = exampleTable(FILE)
    const result = renameInput(t, ['loans'], 'credits')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.table.let!['monthlyDebt']).toBe("credits.map(l, l.monthlyPayment).sum(money(0, 'VND'))")
    expect(result.changes.map((c) => c.cell)).toContain('let.unsecured')
    expect(expressionProblems(result.table)).toEqual([])
    expect(renameInput(t, ['loans'], 'dti')).toEqual({ ok: false, reason: 'duplicate' })
  })
})

describe('local values in the elements', () => {
  it('lists the local values in the table editor and edits them', async () => {
    const el = document.createElement('rulestone-table-editor') as RulestoneTableEditor
    el.table = exampleTable(FILE)
    document.body.append(el)
    await settle(el)
    const tab = $$(el, '[role="tab"]').find((t) => t.textContent!.trim() === 'Local values')!
    tab.click()
    await settle(el)
    const names = $$(el, 'input.local-name').map((i) => (i as HTMLInputElement).value)
    expect(names).toEqual(['monthlyDebt', 'dti', 'affordableLimit', 'unsecured'])
    const changes: TableChangeDetail[] = []
    el.addEventListener('rulestone-change', (e) => changes.push((e as CustomEvent<TableChangeDetail>).detail))
    const name = $(el, 'li[data-local="dti"] input.local-name') as HTMLInputElement
    name.value = 'ratio'
    name.dispatchEvent(new Event('change'))
    await new Promise((r) => setTimeout(r, 300))
    await settle(el)
    expect(Object.keys(el.getTable().let!)).toEqual(['monthlyDebt', 'ratio', 'affordableLimit', 'unsecured'])
    // The rule that reads dti now has a problem, shown in the problems panel.
    expect(changes.at(-1)!.valid).toBe(false)
    expect($(el, '[part="problems"]')!.textContent).toContain("undeclared reference to 'dti'")
  })

  it('offers local values in the condition builder field picker', async () => {
    const t = exampleTable(FILE)
    const el = document.createElement('rulestone-condition-builder') as RulestoneConditionBuilder
    el.inputs = t.inputs
    el.locals = t.let
    el.value = "dti > decimal('0.5')"
    document.body.append(el)
    await settle(el)
    const select = $(el, 'select.field') as HTMLSelectElement
    expect(select.querySelector<HTMLOptionElement>('option[selected]')?.value).toBe('dti')
    const options = [...select.options].map((o) => o.value)
    expect(options).toEqual(expect.arrayContaining(['dti', 'affordableLimit', 'monthlyDebt', 'requestedLimit']))
    expect($(el, '.block.raw')).toBeNull()
  })
})
