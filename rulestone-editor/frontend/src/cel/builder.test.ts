// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { inputPaths } from '../model/schema'
import type { TypeSpec } from '../types'
import { BuildError, type Condition, buildCondition, defaultClause, operatorsFor, parseCondition } from './builder'

const INPUTS: Record<string, TypeSpec> = {
  applicant: {
    age: 'int',
    monthlyIncome: 'decimal',
    monthlyDebt: 'decimal',
    segment: 'string',
    blacklisted: 'bool',
    birthDate: 'date',
    tags: ['string'],
  },
  amount: 'decimal',
  balance: 'money',
  channel: 'string',
  allowedSegments: ['string'],
  riskScore: 'riskband', // a type the editor has no widget for
}
const PATHS = inputPaths(INPUTS, [{ name: 'businessDate', type: 'date' }])

function roundTrip(cel: string): string {
  const parsed = parseCondition(cel, PATHS)
  if (!parsed.ok) throw new Error(`not representable: ${cel} (${parsed.reason})`)
  return buildCondition(parsed.condition, PATHS)
}

describe('buildCondition', () => {
  it('generates typed literals for each input type', () => {
    const condition: Condition = {
      combinator: 'and',
      clauses: [
        { path: 'applicant.age', op: '>=', value: { kind: 'literal', text: '18' } },
        { path: 'applicant.monthlyIncome', op: '>', value: { kind: 'literal', text: '0.5' } },
        { path: 'applicant.segment', op: '==', value: { kind: 'literal', text: "O'Brien" } },
        { path: 'applicant.blacklisted', op: 'is false' },
        { path: 'balance', op: '>=', value: { kind: 'literal', text: '1500000 VND' } },
        { path: 'applicant.birthDate', op: '<', value: { kind: 'literal', text: '2008-11-01' } },
      ],
    }
    expect(buildCondition(condition, PATHS)).toBe(
      "applicant.age >= 18 && applicant.monthlyIncome > decimal('0.5') && applicant.segment == 'O\\'Brien'"
        + " && !applicant.blacklisted && balance >= money('1500000', 'VND') && applicant.birthDate < date('2008-11-01')",
    )
  })

  it('joins clauses with OR', () => {
    expect(buildCondition({
      combinator: 'or',
      clauses: [
        { path: 'applicant.blacklisted', op: 'is true' },
        { path: 'channel', op: '==', value: { kind: 'literal', text: 'COUNTER' } },
      ],
    }, PATHS)).toBe("applicant.blacklisted || channel == 'COUNTER'")
  })

  it('generates membership, has() and their negations', () => {
    expect(buildCondition({
      combinator: 'and',
      clauses: [
        { path: 'applicant.segment', op: 'in', value: { kind: 'list', items: ['RETAIL', 'SME'] } },
        { path: 'amount', op: 'not in', value: { kind: 'list', items: ['1', '2.50'] } },
        { path: 'applicant.tags', op: 'contains', value: { kind: 'literal', text: 'VIP' } },
        { path: 'applicant.tags', op: 'not contains', value: { kind: 'literal', text: 'PEP' } },
        { path: 'applicant.segment', op: 'is set' },
        { path: 'applicant.monthlyDebt', op: 'not set' },
      ],
    }, PATHS)).toBe(
      "applicant.segment in ['RETAIL', 'SME'] && !(amount in [decimal('1'), decimal('2.50')])"
        + " && 'VIP' in applicant.tags && !('PEP' in applicant.tags)"
        + ' && has(applicant.segment) && !has(applicant.monthlyDebt)',
    )
  })

  it('compares with another input or an expression', () => {
    expect(buildCondition({
      combinator: 'and',
      clauses: [
        { path: 'amount', op: '<=', value: { kind: 'expr', cel: 'applicant.monthlyIncome * 12' } },
        { path: 'amount', op: '>', value: { kind: 'path', path: 'applicant.monthlyDebt' } },
        { path: 'applicant.age', op: '==', value: { kind: 'expr', cel: 'a > b ? 1 : 2' } },
        { path: 'applicant.birthDate', op: '<', value: { kind: 'expr', cel: 'businessDate.plusYears(-18)' } },
      ],
    }, PATHS)).toBe(
      'amount <= applicant.monthlyIncome * 12 && amount > applicant.monthlyDebt && applicant.age == (a > b ? 1 : 2)'
        + ' && applicant.birthDate < businessDate.plusYears(-18)',
    )
  })

  it('writes an empty condition as true', () => {
    expect(buildCondition({ combinator: 'and', clauses: [] }, PATHS)).toBe('true')
  })

  it('rejects invalid values with the clause and the reason', () => {
    const invalid = (path: string, text: string) => {
      try {
        buildCondition({ combinator: 'and', clauses: [{ path, op: '==', value: { kind: 'literal', text } }] }, PATHS)
        return 'ok'
      } catch (e) {
        return (e as BuildError).key
      }
    }
    expect(invalid('applicant.age', '18.5')).toBe('invalid.int')
    expect(invalid('applicant.age', '99999999999999999999')).toBe('invalid.int')
    expect(invalid('amount', '1,000')).toBe('invalid.decimal')
    expect(invalid('amount', '1e5')).toBe('invalid.decimal')
    expect(invalid('balance', '1500000')).toBe('invalid.money')
    expect(invalid('balance', '1500000 vnd')).toBe('invalid.money')
    expect(invalid('applicant.birthDate', '2026-02-30')).toBe('invalid.date')
    expect(invalid('nothing', '1')).toBe('unknownPath')
    expect(() => buildCondition({ combinator: 'and', clauses: [{ path: 'applicant.segment', op: 'in', value: { kind: 'list', items: [] } }] }, PATHS))
      .toThrow(BuildError)
  })
})

describe('parseCondition', () => {
  it('reads the examples of the loan eligibility table', () => {
    expect(parseCondition('applicant.age < 18', PATHS)).toEqual({
      ok: true,
      condition: { combinator: 'and', clauses: [{ path: 'applicant.age', op: '<', value: { kind: 'literal', text: '18' } }] },
    })
    expect(parseCondition('applicant.blacklisted', PATHS)).toEqual({
      ok: true,
      condition: { combinator: 'and', clauses: [{ path: 'applicant.blacklisted', op: 'is true' }] },
    })
    expect(parseCondition('true', PATHS)).toEqual({ ok: true, condition: { combinator: 'and', clauses: [] } })
    expect(parseCondition('amount <= applicant.monthlyIncome * 12', PATHS)).toEqual({
      ok: true,
      condition: {
        combinator: 'and',
        clauses: [{ path: 'amount', op: '<=', value: { kind: 'expr', cel: 'applicant.monthlyIncome * 12' } }],
      },
    })
  })

  it('reads typed literals back into the text users type', () => {
    const clauses = (cel: string) => {
      const r = parseCondition(cel, PATHS)
      return r.ok ? r.condition.clauses.map((c) => c.value) : r.reason
    }
    expect(clauses("amount > decimal('0.50')")).toEqual([{ kind: 'literal', text: '0.50' }])
    expect(clauses('amount > decimal(5)')).toEqual([{ kind: 'literal', text: '5' }])
    expect(clauses("balance >= money('1500000.50', 'VND')")).toEqual([{ kind: 'literal', text: '1500000.50 VND' }])
    expect(clauses("balance >= money(2000, 'VND')")).toEqual([{ kind: 'literal', text: '2000 VND' }])
    expect(clauses("applicant.birthDate < date('2008-11-01')")).toEqual([{ kind: 'literal', text: '2008-11-01' }])
    expect(clauses('applicant.age >= -1')).toEqual([{ kind: 'literal', text: '-1' }])
    expect(clauses('applicant.segment == "SME"')).toEqual([{ kind: 'literal', text: 'SME' }])
    expect(clauses("applicant.segment == 'it\\'s'")).toEqual([{ kind: 'literal', text: "it's" }])
    expect(clauses('applicant.blacklisted == false')).toEqual([{ kind: 'literal', text: 'false' }])
    expect(clauses('amount > applicant.monthlyDebt')).toEqual([{ kind: 'path', path: 'applicant.monthlyDebt' }])
    // a literal of the wrong type stays visible as an expression; the server reports the type error
    expect(clauses("applicant.age > 'x'")).toEqual([{ kind: 'expr', cel: "'x'" }])
    expect(clauses("riskScore == riskband('HIGH')")).toEqual([{ kind: 'expr', cel: "riskband('HIGH')" }])
  })

  it('reads membership, has() and negations', () => {
    const r = parseCondition(
      "applicant.segment in ['RETAIL', 'SME'] || !(amount in [decimal('1')]) || 'VIP' in applicant.tags"
        + " || !('PEP' in applicant.tags) || has(applicant.segment) || !has(applicant.age) || channel in allowedSegments",
      PATHS,
    )
    expect(r).toEqual({
      ok: true,
      condition: {
        combinator: 'or',
        clauses: [
          { path: 'applicant.segment', op: 'in', value: { kind: 'list', items: ['RETAIL', 'SME'] } },
          { path: 'amount', op: 'not in', value: { kind: 'list', items: ['1'] } },
          { path: 'applicant.tags', op: 'contains', value: { kind: 'literal', text: 'VIP' } },
          { path: 'applicant.tags', op: 'not contains', value: { kind: 'literal', text: 'PEP' } },
          { path: 'applicant.segment', op: 'is set' },
          { path: 'applicant.age', op: 'not set' },
          { path: 'channel', op: 'in', value: { kind: 'path', path: 'allowedSegments' } },
        ],
      },
    })
  })

  it('flattens parenthesised groups of the same operator', () => {
    const r = parseCondition('(applicant.age >= 18 && amount > 0) && applicant.blacklisted', PATHS)
    expect(r.ok && r.condition.clauses.length).toBe(3)
  })

  it('explains what it cannot represent', () => {
    const reason = (cel: string) => {
      const r = parseCondition(cel, PATHS)
      return r.ok ? 'ok' : r.reason
    }
    expect(reason("applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')")).toBe('shape')
    expect(reason('applicant.age >= 18 && (amount > 1 || applicant.blacklisted)')).toBe('shape')
    expect(reason("customer.accounts.exists(a, a.balance < 0)")).toBe('shape')
    expect(reason('applicant.agee < 18')).toBe('shape')
    expect(reason('has(amount)')).toBe('shape') // top-level inputs are always set
    expect(reason('applicant.age < ')).toBe('syntax')
    expect(reason("'unterminated")).toBe('syntax')
    expect(reason('applicant.blacklisted < true')).toBe('shape')
  })

  it('round-trips every clause shape', () => {
    for (const cel of [
      'applicant.age >= 18',
      "applicant.age >= 18 && applicant.segment == 'SME' && !applicant.blacklisted",
      "applicant.segment in ['RETAIL', 'SME'] || !(applicant.age in [1, 2])",
      "'VIP' in applicant.tags && !('PEP' in applicant.tags)",
      'has(applicant.segment) && !has(applicant.age)',
      "balance >= money('1500000', 'VND') && applicant.birthDate < date('2008-11-01')",
      'amount <= applicant.monthlyIncome * 12',
      'applicant.birthDate <= businessDate.plusYears(-18)',
      'businessDate >= applicant.birthDate',
      'true',
    ]) {
      expect(roundTrip(cel)).toBe(cel)
    }
  })

  it('normalises equivalent spellings when regenerating', () => {
    expect(roundTrip('applicant.segment == "SME"')).toBe("applicant.segment == 'SME'")
    expect(roundTrip('amount > decimal(5)')).toBe("amount > decimal('5')")
    expect(roundTrip('amount <= (applicant.monthlyIncome * 12)')).toBe('amount <= applicant.monthlyIncome * 12')
    expect(roundTrip('applicant.age == (1 > 2 ? 1 : 2)')).toBe('applicant.age == (1 > 2 ? 1 : 2)')
  })
})

describe('operators and defaults', () => {
  const at = (path: string) => PATHS.find((p) => p.path === path)!

  it('offers operators that fit the type', () => {
    expect(operatorsFor(at('applicant.blacklisted'))).toEqual(['is true', 'is false', '==', '!=', 'is set', 'not set'])
    expect(operatorsFor(at('amount'))).toEqual(['==', '!=', '<', '<=', '>', '>=', 'in', 'not in'])
    expect(operatorsFor(at('channel'))).toEqual(['==', '!=', 'in', 'not in'])
    expect(operatorsFor(at('applicant.tags'))).toEqual(['contains', 'not contains', 'is set', 'not set'])
    expect(operatorsFor(at('balance'))).toContain('<')
    expect(operatorsFor(at('businessDate'))).toContain('>=')
    expect(operatorsFor(at('riskScore'))).toContain('==')
  })

  it('starts a new clause with an empty typed value', () => {
    expect(defaultClause(at('applicant.age'))).toEqual({ path: 'applicant.age', op: '==', value: { kind: 'literal', text: '' } })
    expect(defaultClause(at('applicant.blacklisted'))).toEqual({ path: 'applicant.blacklisted', op: 'is true' })
    expect(defaultClause(at('riskScore'))).toEqual({ path: 'riskScore', op: '==', value: { kind: 'expr', cel: '' } })
  })
})
