// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  Decimal, InvalidFactsError, JsonSyntaxError, LocalDate, parseJson, readValue, readValues, structOf, writeJson,
  writeValues, listOf, type ValueType, type Writable,
} from '../src/index.js'

describe('strict JSON', () => {
  it('keeps numbers exact and key order', () => {
    const node = parseJson('{"b": 25750000.50, "a": 9223372036854775807, "c": 1e-3}')!
    expect(node.type === 'object' && [...node.entries.keys()]).toEqual(['b', 'a', 'c'])
    expect(node.type === 'object' && node.entries.get('a')).toEqual({ type: 'number', text: '9223372036854775807', integral: true })
  })

  it('rejects duplicate keys, comments, NaN, leading zeros and trailing text', () => {
    for (const text of ['{"a":1,"a":2}', '{"a":1 /* x */}', '[NaN]', '[01]', '{} x', '[1,]', '"\t"']) {
      expect(() => parseJson(text), text).toThrow(JsonSyntaxError)
    }
    expect(parseJson('  ')).toBeUndefined()
  })

  it('writes strings exactly as Jackson does', () => {
    expect(writeJson(['"\\/\b\f\n\r\t\u0001\u001f\u007f é😀'])).toBe('["\\"\\\\/\\b\\f\\n\\r\\t\\u0001\\u001F\u007f é😀"]')
    expect(writeJson(new Map<string, Writable>([['z', 1n], ['a', [true, null]]]))).toBe('{"z":1,"a":[true,null]}')
  })
})

describe('decimals', () => {
  it('keep their scale as BigDecimal does', () => {
    expect(Decimal.parse('1.50')!.add(Decimal.parse('1')!).toPlainString()).toBe('2.50')
    expect(Decimal.parse('1.5')!.multiply(Decimal.parse('0.20')!).toPlainString()).toBe('0.300')
    expect(Decimal.parse('1535123')!.setScale(-3, 'HALF_EVEN').toString()).toBe('1.535E+6')
    expect(Decimal.parse('1535123')!.setScale(-3, 'HALF_EVEN').toPlainString()).toBe('1535000')
    expect(Decimal.parse('0.000001')!.toString()).toBe('0.000001')
    expect(Decimal.parse('0.0000001')!.toString()).toBe('1E-7')
    expect(Decimal.parse('-0.00')!.toPlainString()).toBe('0.00')
    expect(Decimal.parse('1.0')!.equals(Decimal.parse('1.00')!)).toBe(true)
  })

  it('divide to 34 significant digits, half-even, with Java scales', () => {
    const div = (a: string, b: string) => Decimal.parse(a)!.divide(Decimal.parse(b)!).toString()
    expect(div('1', '3')).toBe('0.3333333333333333333333333333333333')
    expect(div('2', '3')).toBe('0.6666666666666666666666666666666667')
    expect(div('10', '4')).toBe('2.5')
    expect(div('100', '0.5')).toBe('2.0E+2')
    expect(div('0.00', '0.5')).toBe('0.0')
    expect(div('9999999999999999999999999999999999.5', '1')).toBe('1.000000000000000000000000000000000E+34')
  })

  it('parse as new BigDecimal(String) does', () => {
    for (const text of ['1E+3', '.5', '1.', '+1', '١٢٣']) expect(Decimal.parse(text), text).toBeDefined()
    for (const text of ['', '-', '.', '1e', 'abc', '1_000', ' 1', 'Infinity', '0x10']) expect(Decimal.parse(text), text).toBeUndefined()
    expect(Decimal.parse('١٢٣')!.toString()).toBe('123')
  })
})

describe('dates', () => {
  it('parse and print as LocalDate does', () => {
    for (const [text, printed] of [
      ['2026-11-01', '2026-11-01'], ['+12345-01-01', '+12345-01-01'], ['-0001-01-01', '-0001-01-01'], ['0000-01-01', '0000-01-01'],
    ] as const) {
      expect(LocalDate.parse(text)?.toString()).toBe(printed)
    }
    for (const text of ['12345-01-01', '+2026-01-01', '-0000-01-01', '2026-02-30', '2026-1-01', ' 2026-01-01', '+1000000000-01-01']) {
      expect(LocalDate.parse(text), text).toBeUndefined()
    }
  })

  it('do calendar arithmetic as java.time does', () => {
    const d = (text: string) => LocalDate.parse(text)!
    expect(d('2026-01-31').plusMonths(1n).toString()).toBe('2026-02-28')
    expect(d('2028-02-29').plusYears(1n).toString()).toBe('2029-02-28')
    expect(d('2026-11-01').dayOfWeek()).toBe(7)
    expect(d('2008-11-02').yearsUntil(d('2026-11-01'))).toBe(17)
    expect(d('2026-01-31').monthsUntil(d('2026-02-28'))).toBe(0)
    expect(d('-999999999-01-01').daysUntil(d('+999999999-12-31'))).toBe(730484999633)
    expect(() => d('+999999999-12-31').plusDays(1n)).toThrow(RangeError)
  })
})

describe('JSON facts', () => {
  const types = new Map<string, ValueType>([
    ['applicant', structOf({ age: 'int', income: 'decimal', since: 'date' })],
    ['amounts', listOf('money')],
  ])

  it('read the FactsJson forms exactly', () => {
    const values = readValues('{"applicant":{"age":30,"income":25750000.50,"since":"2019-04-10"},"amounts":["1500000.50 VND"]}', types)
    expect(writeValues(values)).toBe('{"applicant":{"age":30,"income":"25750000.50","since":"2019-04-10"},"amounts":["1500000.50 VND"]}')
    expect(String(readValue('"0.0980"', 'decimal'))).toBe('0.0980')
    expect(String(readValue('1e3', 'decimal'))).toBe('1E+3')
  })

  it('report every problem without quoting values', () => {
    let error: InvalidFactsError | undefined
    try {
      readValues('{"applicant":{"age":1.5,"income":"1,5","since":"2019-02-30","x":1},"amounts":["1 vnd", null],"other":1}', types)
    } catch (e) {
      error = e as InvalidFactsError
    }
    expect(error?.problems).toEqual([
      'applicant.age: expected an integer',
      'applicant.income: expected a decimal as a number or a string in plain notation, e.g. "0.0980"',
      'applicant.since: expected an existing date as "yyyy-MM-dd"',
      'applicant.x: unknown field (declared: age, income, since)',
      'amounts[0]: expected money as "<amount> <CCY>", e.g. "1500000 VND"',
      'amounts[1]: null is not a value',
      'other: not declared (declared: applicant, amounts)',
    ])
  })

  it('reject floating-point JavaScript numbers for decimals', () => {
    expect(() => readValue(0.1, 'decimal')).toThrow(/floating-point number/)
    expect(String(readValue(12, 'decimal'))).toBe('12')
  })
})
