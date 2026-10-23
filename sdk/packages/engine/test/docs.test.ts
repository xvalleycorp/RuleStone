// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The examples in docs/expressions.md and docs/fuzzy-matching.md, checked as the Java tests
// ExpressionsDocTest and FuzzyMatchingDocTest check them.

import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  Decimal, ExpressionEnvironment, ExpressionError, LocalDate, Money, createEngine, listOf, parseDecisionTable, structOf,
  valuesToJson, writeJson, type CompiledExpression, type Value, type ValueType,
} from '../src/index.js'
import { REPO, read } from './helpers.js'
import { javaString } from './java-string.js'

const RESULT_TYPES: ValueType[] = ['bool', 'int', 'string', 'decimal', 'money', 'date', listOf('string')]

const env = new ExpressionEnvironment({
  variables: {
    applicant: structOf({
      age: 'int', blacklisted: 'bool', segment: 'string', monthlyIncome: 'decimal', monthlyDebt: 'decimal',
      idNumber: 'string', birthDate: 'date',
    }),
    amount: 'decimal',
    fee: 'decimal',
    balance: 'decimal',
    income: 'decimal',
    x: 'decimal',
    m: 'money',
    transfer: structOf({ amount: 'decimal' }),
    payment: structOf({ amount: 'money' }),
    loan: structOf({ balance: 'money', maturityDate: 'date' }),
    invoice: structOf({ dueDate: 'date' }),
    customer: structOf({
      segment: 'string', fullName: 'string', accounts: listOf(structOf({ type: 'string', balance: 'decimal' })),
    }),
    watchlist: listOf('string'),
    businessDate: 'date',
  },
})

const inputs = env.bind({
  applicant: {},
  amount: 1,
  fee: 1,
  balance: 1,
  income: 1,
  x: 1,
  m: Money.of(Decimal.parse('1500000')!, 'VND'),
  transfer: { amount: 1 },
  payment: { amount: Money.of(Decimal.parse('1500000')!, 'VND') },
  loan: {},
  invoice: {},
  customer: {},
  watchlist: [],
  businessDate: LocalDate.of(2026, 11, 1),
})

function compile(source: string): CompiledExpression {
  let last: unknown
  for (const type of RESULT_TYPES) {
    try {
      return env.compile(source, type)
    } catch (e) {
      last = e
    }
  }
  throw new Error(`does not compile: ${source}: ${String(last)}`)
}

function evaluate(source: string): Value {
  return compile(source).evaluate(inputs)
}

describe('docs/expressions.md', () => {
  const doc = read(join(REPO, 'docs', 'expressions.md'))

  it('holds every `expression` → result example', () => {
    const checked: string[] = []
    for (const line of doc.split('\n')) {
      for (const m of line.matchAll(/`([^`]+)` → `?([^`|]+?)`?\s*\|/g)) {
        let expected = m[2]!.trim()
        if (expected.startsWith("'") && expected.endsWith("'")) expected = expected.slice(1, -1)
        expect(javaString(evaluate(m[1]!)), m[1]).toBe(expected)
        checked.push(m[1]!)
      }
    }
    expect(checked).toEqual(expect.arrayContaining([
      "money('1234.565', 'USD').round()", "date('2026-11-01').dayOfWeek()",
      "yearsBetween(date('2008-11-02'), date('2026-11-01'))", "string(date('2026-11-01'))",
    ]))
    expect(checked.length).toBeGreaterThanOrEqual(13)
  })

  it('compiles every line of the quick reference and code examples', () => {
    let count = 0
    for (const block of doc.matchAll(/```\n([\s\S]*?)```/g)) {
      for (const line of block[1]!.split('\n')) {
        const source = line.replace(/\/\/.*/, '').trim()
        if (source !== '') {
          compile(source)
          count++
        }
      }
    }
    expect(count).toBeGreaterThan(30)
  })

  it('money', () => {
    expect(javaString(evaluate("money('1500000.50', 'VND')"))).toBe('1500000.50 VND')
    expect(javaString(evaluate("money('100', 'VND') / 3"))).toBe('33.33333333333333333333333333333333 VND')
    expect(javaString(evaluate("m + money(2000, m.currency())"))).toBe('1502000 VND')
    expect(evaluate("money(1, 'VND') == money(1, 'USD')")).toBe(false)
    for (const source of ['m + 2000', 'm > 1000000', 'm * m']) {
      for (const type of RESULT_TYPES) {
        expect(() => env.compile(source, type)).toThrow(ExpressionError)
        expect(() => env.compile(source, type)).not.toThrow(/must produce/)
      }
    }
  })

  it('mixing currencies fails', () => {
    expect(() => evaluate("money(1, 'VND') + money(1, 'USD')")).toThrow('money currencies differ: VND and USD')
    expect(() => evaluate("money(1, 'vnd')")).toThrow('not an ISO 4217 code')
    expect(() => evaluate("money('abc', 'VND')")).toThrow('not a valid number')
  })

  it('minor units come from ISO 4217', () => {
    expect(javaString(evaluate("money('1.23456', 'VND').round()"))).toBe('1 VND')
    expect(javaString(evaluate("money('1.23456', 'USD').round()"))).toBe('1.23 USD')
    expect(javaString(evaluate("money('1.23456', 'KWD').round()"))).toBe('1.235 KWD')
    expect(() => evaluate("money('1.5', 'XAU').round()")).toThrow('currency XAU has no minor unit')
  })

  it('dates', () => {
    expect(evaluate("daysBetween(date('2026-11-01'), date('2026-10-01'))")).toBe(-31n)
    expect(() => evaluate("date('01/11/2026')")).toThrow('not a valid ISO date')
    expect(() => evaluate("date('2026-02-30')")).toThrow('not a valid ISO date')
  })
})

describe('docs/fuzzy-matching.md', () => {
  const doc = read(join(REPO, 'docs', 'fuzzy-matching.md'))

  it('holds every worked example', () => {
    const section = doc.slice(doc.indexOf('## Worked examples'), doc.indexOf('## Performance and limits'))
    let count = 0
    for (const m of section.matchAll(/^\| `(.+)` \| `(.+)` \|$/gm)) {
      let expected = m[2]!
      if (expected.startsWith("'") && expected.endsWith("'")) expected = expected.slice(1, -1)
      expect(javaString(evaluate(m[1]!)), m[1]).toBe(expected)
      count++
    }
    expect(count).toBeGreaterThanOrEqual(34)
  })

  it('runs the screening table', async () => {
    const start = doc.indexOf('```json\n') + '```json\n'.length
    const table = parseDecisionTable(doc.slice(start, doc.indexOf('```', start)))
    const engine = await createEngine({ tables: [table] })
    const watchlist = ['Van An Nguyen', 'Tran Van Binh']
    const hit = await engine.evaluate('name_screening', { customer: { fullName: 'NGUYỄN Văn An' }, watchlist })
    expect(hit.matchedRules).toEqual(['WATCHLIST_HIT'])
    expect(writeJson(valuesToJson(hit.output))).toBe('{"decision":"REVIEW","matchScore":"1.0000"}')
    expect((await engine.evaluate('name_screening', { customer: { fullName: 'Le Thi Hoa' }, watchlist })).matchedRules).toEqual(['NO_ALERT'])
    expect((await engine.evaluate('name_screening', { customer: {}, watchlist })).reasonCodes).toEqual(['NAME_MISSING'])
    expect((await engine.evaluate('name_screening', { customer: { fullName: ' - ' }, watchlist })).reasonCodes).toEqual(['NAME_MISSING'])
  })
})
