// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  addField, addRule, completionsAt, deleteRule, duplicateRule, emptyForm, expressionProblems, factsJson, formFromJson,
  moveField, moveRule, normalizeTable, renameInput, renameOutput, setFormValue, setOutput, tableText, tableVariables,
  tokenize, updateRule, validateTable, type TableJson,
} from '../src/model/index.js'
import { exampleParamSets, exampleTable, exampleTables } from './helpers.js'
import { readParamSets } from '../src/model/validate.js'

function table(overrides: Partial<TableJson> = {}): TableJson {
  return {
    id: 'loans',
    version: '1',
    hitPolicy: 'FIRST',
    inputs: {
      applicant: { age: 'int', segment: 'string', address: { city: 'string' } },
      customer: { accounts: [{ type: 'string', balance: 'decimal' }] },
      amount: 'decimal',
    },
    outputs: { decision: 'string', limit: 'decimal' },
    rules: [
      { id: 'R1', when: "applicant.age < 18 || has(applicant.segment) && applicant.segment == 'age'", then: { decision: "'REJECT'" } },
      { id: 'R2', when: "customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= amount)", then: { decision: "'APPROVE'", limit: 'amount * 2' } },
      { id: 'R3', when: "applicant.address.city == 'Hanoi' && [1, 2].exists(age, age > applicant.age)", then: { decision: "'REVIEW'" } },
      { id: 'R4', when: 'true', then: { decision: "'REVIEW'" } },
    ],
    ...overrides,
  }
}

describe('renaming a field updates the expressions that use it, through the syntax tree', () => {
  it('renames a top-level input, leaving iteration variables, string literals and other fields alone', () => {
    const result = renameInput(table(), ['amount'], 'loanAmount')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(Object.keys(result.table.inputs)).toEqual(['applicant', 'customer', 'loanAmount'])
    expect(result.table.rules[1]!.when).toBe("customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= loanAmount)")
    expect(result.table.rules[1]!.then!['limit']).toBe('loanAmount * 2')
    expect(result.changes.map((c) => [c.ruleId, c.cell, c.occurrences])).toEqual([['R2', 'when', 1], ['R2', 'then.limit', 1]])
    expect(expressionProblems(result.table)).toEqual([])
  })

  it('renames a struct field only where it is that struct, also inside has()', () => {
    const result = renameInput(table(), ['applicant', 'age'], 'ageYears')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.table.rules[0]!.when).toBe("applicant.ageYears < 18 || has(applicant.segment) && applicant.segment == 'age'")
    // `age` the iteration variable is not the field.
    expect(result.table.rules[2]!.when).toBe("applicant.address.city == 'Hanoi' && [1, 2].exists(age, age > applicant.ageYears)")
    expect(result.changes).toHaveLength(2)
    const segment = renameInput(table(), ['applicant', 'segment'], 'tier')
    expect(segment.ok && segment.table.rules[0]!.when).toBe("applicant.age < 18 || has(applicant.tier) && applicant.tier == 'age'")
  })

  it('renames fields of list elements and of nested structs', () => {
    const balance = renameInput(table(), ['customer', 'accounts', '[]', 'balance'], 'amount')
    expect(balance.ok && balance.table.rules[1]!.when).toBe("customer.accounts.exists(a, a.type == 'SAVINGS' && a.amount >= amount)")
    expect(balance.ok && balance.table.inputs['customer']).toEqual({ accounts: [{ type: 'string', amount: 'decimal' }] })
    const city = renameInput(table(), ['applicant', 'address', 'city'], 'town')
    expect(city.ok && city.table.rules[2]!.when).toBe("applicant.address.town == 'Hanoi' && [1, 2].exists(age, age > applicant.age)")
  })

  it('keeps formatting, comments and other text exactly', () => {
    const t = table({ rules: [{ id: 'X', when: 'amount>0 // amount is "amount"\n  && amount  <  decimal(10)', then: {} }] })
    const result = renameInput(t, ['amount'], 'value')
    expect(result.ok && result.table.rules[0]!.when).toBe('value>0 // amount is "amount"\n  && value  <  decimal(10)')
  })

  it('refuses names that are invalid, taken, reserved or would be captured by an iteration variable', () => {
    expect(renameInput(table(), ['amount'], 'applicant')).toEqual({ ok: false, reason: 'duplicate' })
    expect(renameInput(table(), ['amount'], '9x')).toEqual({ ok: false, reason: 'invalid' })
    expect(renameInput(table(), ['amount'], 'in')).toEqual({ ok: false, reason: 'reserved' })
    expect(renameInput(table(), ['amount'], 'businessDate')).toEqual({ ok: false, reason: 'businessDate' })
    expect(renameInput(table(), ['amount'], 'a')).toEqual({ ok: false, reason: 'capture', ruleId: 'R2' })
    expect(renameInput(table(), ['nope'], 'x')).toEqual({ ok: false, reason: 'notFound' })
  })

  it('reports expressions it cannot read instead of editing their text', () => {
    const t = table({ rules: [{ id: 'BAD', when: 'amount >' }, { id: 'OK', when: 'amount > 1' }] })
    const result = renameInput(t, ['amount'], 'value')
    expect(result.ok && result.skipped).toEqual([{ rule: 0, ruleId: 'BAD', cell: 'when', reason: 'syntax' }])
    expect(result.ok && result.table.rules[0]!.when).toBe('amount >')
    expect(result.ok && result.table.rules[1]!.when).toBe('value > 1')
  })

  it('renames every input of every example table without new problems', () => {
    let renamed = 0
    for (const [, t] of exampleTables()) {
      for (const name of Object.keys(t.inputs)) {
        const result = renameInput(t, [name], `${name}X`)
        expect(result.ok).toBe(true)
        if (!result.ok) continue
        renamed += result.changes.length
        expect(expressionProblems(result.table)).toEqual([])
      }
    }
    expect(renamed).toBeGreaterThan(50)
  })

  it('renames outputs in every rule that sets them', () => {
    const result = renameOutput(table(), 'limit', 'maxAmount')
    expect(result.ok && Object.keys(result.table.outputs)).toEqual(['decision', 'maxAmount'])
    expect(result.ok && result.table.rules[1]!.then).toEqual({ decision: "'APPROVE'", maxAmount: 'amount * 2' })
  })
})

describe('table operations', () => {
  it('adds, duplicates, moves and deletes rules', () => {
    let t = table()
    const added = addRule(t)
    // Under FIRST a new rule goes before the final catch-all.
    expect(added.index).toBe(3)
    t = added.table
    expect(t.rules.map((r) => r.id)).toEqual(['R1', 'R2', 'R3', 'R5', 'R4'])
    const copy = duplicateRule(t, 0)
    expect(copy.table.rules[1]).toMatchObject({ id: 'R1_2', when: t.rules[0]!.when })
    t = moveRule(copy.table, 5, 0)
    expect(t.rules.map((r) => r.id)).toEqual(['R4', 'R1', 'R1_2', 'R2', 'R3', 'R5'])
    t = deleteRule(t, 0)
    expect(t.rules).toHaveLength(5)
    t = setOutput(t, 0, 'limit', 'amount')
    expect(t.rules[0]!.then).toEqual({ decision: "'REJECT'", limit: 'amount' })
    t = setOutput(setOutput(t, 0, 'limit', undefined), 0, 'decision', undefined)
    expect(t.rules[0]!.then).toBeUndefined()
    t = updateRule(t, 0, { reasonCode: 'X', description: '' })
    expect(t.rules[0]).not.toHaveProperty('description')
  })

  it('reorders schema fields', () => {
    const inputs = moveField(addField(table().inputs, ['applicant'], 'income', 'money'), ['applicant', 'income'], 0)
    expect(Object.keys(inputs['applicant'] as object)).toEqual(['income', 'age', 'segment', 'address'])
  })

  it('writes the file with keys in file order', () => {
    const text = tableText(normalizeTable({ rules: [{ when: 'true', id: 'A' }], hitPolicy: 'FIRST', version: '1', id: 'x', outputs: {}, inputs: {} }))
    expect(text).toBe('{\n  "id": "x",\n  "version": "1",\n  "hitPolicy": "FIRST",\n  "inputs": {},\n  "outputs": {},\n  "rules": [\n    {\n      "id": "A",\n      "when": "true"\n    }\n  ]\n}\n')
  })
})

describe('validation', () => {
  it('accepts every example table and gives the rule version Java gives', async () => {
    const paramSets = readParamSets(exampleParamSets())
    for (const [, t] of exampleTables()) {
      const result = await validateTable(t, { paramSets })
      expect(result.problems).toEqual([])
      expect(result.ruleVersion).toMatch(new RegExp(`^${t.version}@sha256:[0-9a-f]{64}$`))
    }
  })

  it('places every problem on its cell, with the position in expressions', async () => {
    const t = exampleTable('loan_eligibility.v3.stone.json')
    t.rules[0]!.when = 'applicant.agee < 18'
    t.rules[1]!.reasonCode = 'bad code'
    t.rules[2]!.id = t.rules[3]!.id
    t.rules[3]!.then = { decision: '1', bogus: "'x'" }
    t['efectiveFrom'] = '2026-01-01'
    let result = await validateTable(t)
    expect(result.valid).toBe(false)
    expect(result.problems.map((p) => [p.source, p.target, p.message])).toContainEqual([
      'reader', { kind: 'table', field: 'efectiveFrom' }, expect.stringContaining('unknown field'),
    ])
    delete t['efectiveFrom']
    result = await validateTable(t)
    const summary = result.problems.map((p) => ({ target: p.target, message: p.message.split('\n')[0], line: p.issue?.line, column: p.issue?.column }))
    expect(summary).toEqual(expect.arrayContaining([
      { target: { kind: 'rule', rule: 0, field: 'when' }, message: "undefined field 'agee'", line: 1, column: 10 },
      { target: { kind: 'rule', rule: 1, field: 'reasonCode' }, message: 'must be UPPER_SNAKE_CASE, e.g. DTI_HIGH', line: undefined, column: undefined },
      { target: { kind: 'rule', rule: 3, field: 'rule' }, message: 'duplicate rule id', line: undefined, column: undefined },
      { target: { kind: 'rule', rule: 3, field: 'then', output: 'decision' }, message: 'expression must produce string but produces int: 1', line: 1, column: 1 },
      { target: { kind: 'rule', rule: 3, field: 'then', output: 'bogus' }, message: 'undeclared output (declared: [decision, limit])', line: undefined, column: undefined },
    ]))
    // An expression problem is reported once, with its position, not again by the engine.
    expect(result.problems.filter((p) => p.target.kind === 'rule' && p.target.rule === 0)).toHaveLength(1)
  })

  it('warns about rules after a catch-all under FIRST', async () => {
    const t = table()
    t.rules.push({ id: 'LATE', when: 'amount > 1' })
    const result = await validateTable(t)
    expect(result.problems).toContainEqual(expect.objectContaining({ severity: 'warning', target: { kind: 'rule', rule: 4, field: 'rule' } }))
    expect(result.valid).toBe(true)
  })
})

describe('facts form', () => {
  const inputs = exampleTable('aml_transaction_monitoring.stone.json').inputs

  it('writes typed JSON without floating point and reads it back', () => {
    let form = emptyForm(inputs)
    form = setFormValue(form, ['transaction', 'amount'], { kind: 'money', amount: '300000000.50', currency: 'VND' })
    form = setFormValue(form, ['transaction', 'valueDate'], { kind: 'scalar', text: '2026-11-01' })
    form = setFormValue(form, ['history', 'cashDepositsLast7d'], { kind: 'list', items: [{ kind: 'money', amount: '1', currency: 'USD' }] })
    const json = factsJson(form, inputs)
    expect(JSON.parse(json)).toEqual({
      account: {},
      transaction: { amount: '300000000.50 VND', valueDate: '2026-11-01' },
      history: { cashDepositsLast7d: ['1 USD'] },
    })
    expect(factsJson(formFromJson(json, inputs), inputs)).toBe(json)
    const big = formFromJson('{"riskScore": 123456789012345678901}', { riskScore: 'int' })
    expect(factsJson(big, { riskScore: 'int' })).toBe('{\n  "riskScore": 123456789012345678901\n}')
  })
})

describe('CEL text support', () => {
  it('tokenizes any text, so that the tokens put it back together', () => {
    for (const text of ["a.b >= decimal('1.5') && x.exists(y, y in [1, 2]) // c", "'unterminated", '#$@ "x']) {
      expect(tokenize(text).map((t) => t.text).join('')).toBe(text)
    }
    expect(tokenize("a.startsWith('x')").map((t) => t.kind)).toEqual(['variable', 'punct', 'function', 'punct', 'string', 'punct'])
  })

  it('completes fields from the type checker and methods for the type', () => {
    const variables = tableVariables(table().inputs)
    const fields = completionsAt('applicant.', 10, variables)
    expect(fields.items.filter((i) => i.kind === 'field').map((i) => i.label)).toEqual(['age', 'segment', 'address'])
    const methods = completionsAt('applicant.segment.st', 20, variables)
    expect(methods.from).toBe(18)
    expect(methods.items.map((i) => i.label)).toEqual(['startsWith'])
    const inLoop = completionsAt('customer.accounts.exists(a, a.', 30, variables)
    expect(inLoop.items.filter((i) => i.kind === 'field').map((i) => i.label)).toEqual(['type', 'balance'])
    const top = completionsAt('bus', 3, variables)
    expect(top.items.map((i) => i.label)).toEqual(['businessDate'])
    expect(completionsAt('max', 3, variables).items.map((i) => i.label)).toContain('maxNameSimilarity')
  })
})
