// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Parameter sets: params.<name> in rule expressions and the field picker, checks against the
// sets given to the editor, the Parameters tab, and Try it with the version in force.

import { afterEach, describe, expect, it } from 'vitest'
import '../src/index.js'
import type { RulestoneConditionBuilder, RulestoneTableEditor, RulestoneTryIt } from '../src/index.js'
import { expressionProblems, readParamSets, setParams, tableText, tableVariables, validateTable } from '../src/model/index.js'
import { $, $$, exampleParamSets, exampleTable, settle } from './helpers.js'

const FILE = 'loan_eligibility.v4.stone.json'

afterEach(() => document.body.replaceChildren())

describe('parameters in the model', () => {
  it('gives expressions the declared parameters as params', () => {
    const t = exampleTable(FILE)
    const variables = tableVariables(t.inputs, t.let, t.params?.values)
    const params = variables.get('params')
    expect(params).toMatchObject({ kind: 'struct' })
    expect(variables.get('maxAmount')).toBe('decimal')
    expect(expressionProblems(t)).toEqual([])
    t.rules[0]!.when = 'applicant.age < params.maxAge'
    expect(expressionProblems(t).map((p) => p.message.split('\n')[0])).toEqual([expect.stringContaining("undefined field 'maxAge'")])
  })

  it('checks the declaration against the parameter sets it is given', async () => {
    const t = exampleTable(FILE)
    const paramSets = readParamSets(exampleParamSets())
    expect((await validateTable(t, { paramSets })).problems).toEqual([])
    const without = await validateTable(t)
    expect(without.problems.map((p) => [p.target, p.message])).toEqual([
      [{ kind: 'params' }, "no parameter set 'retail_credit' is registered"],
    ])
    const wrong = setParams(t, { ...t.params!, values: { ...t.params!.values, maxDti: 'int' } })
    expect((await validateTable(wrong, { paramSets })).problems.map((p) => [p.target, p.message])).toContainEqual([
      { kind: 'params', name: 'maxDti' }, 'declared int but parameter set retail_credit@1 has it as decimal',
    ])
  })

  it('keeps params in the file order and removes it', () => {
    let t = exampleTable('deposit_rate.stone.json')
    t = setParams(t, { set: 's', version: '', values: { a: 'int' } })
    expect(Object.keys(JSON.parse(tableText(t)) as object)).toEqual([
      'id', 'version', 'description', 'hitPolicy', 'inputs', 'outputs', 'params', 'rules',
    ])
    expect(t.params).toEqual({ set: 's', values: { a: 'int' } })
    expect(tableText(setParams(t, undefined))).not.toContain('"params"')
  })
})

describe('parameters in the elements', () => {
  it('offers params.<name> in the condition builder field picker', async () => {
    const t = exampleTable(FILE)
    const el = document.createElement('rulestone-condition-builder') as RulestoneConditionBuilder
    el.inputs = t.inputs
    el.locals = t.let
    el.params = t.params!.values
    el.value = 'applicant.age < params.minAge'
    document.body.append(el)
    await settle(el)
    expect($(el, '.block.raw')).toBeNull()
    const options = $$(el, 'select.field').flatMap((s) => [...(s as HTMLSelectElement).options].map((o) => o.value))
    expect(options).toEqual(expect.arrayContaining(['params']))
  })

  it('shows the declaration and the values of each version in the Parameters tab', async () => {
    const el = document.createElement('rulestone-table-editor') as RulestoneTableEditor
    el.table = exampleTable(FILE)
    el.paramSets = exampleParamSets()
    document.body.append(el)
    await settle(el)
    $$(el, '[role="tab"]').find((t) => t.textContent!.trim() === 'Parameters')!.click()
    await settle(el)
    expect(($(el, 'input.params-set') as HTMLInputElement).value).toBe('retail_credit')
    expect($$(el, 'li[data-param] input.local-name').map((i) => (i as HTMLInputElement).value)).toEqual([
      'minAge', 'maxDti', 'incomeMultiple', 'blockedSegments',
    ])
    expect($(el, 'li[data-param="maxDti"] .param-values')!.textContent).toContain('version 2: "0.45"')
    await new Promise((r) => setTimeout(r, 300))
    await settle(el)
    expect($(el, '[part="problems"]')!.textContent).toContain('No problems.')
  })

  it('runs Try it with the parameter set version in force on its date', async () => {
    const el = document.createElement('rulestone-try-it') as RulestoneTryIt
    el.table = exampleTable(FILE)
    el.paramSets = exampleParamSets()
    el.facts = JSON.stringify({
      applicant: { age: 30, monthlyIncome: 20000000, monthlyDebt: 9600000, segment: 'RETAIL', blacklisted: false },
      amount: 200000000,
    })
    document.body.append(el)
    await settle(el)
    el.asOf = '2027-06-30'
    expect((await el.run()).matchedRules).toEqual(['R5'])
    el.asOf = '2027-07-01'
    const after = await el.run()
    expect(after.matchedRules).toEqual(['R4'])
    expect(after.record?.params?.[0]?.version).toMatch(/^2@sha256:/)
  })
})
