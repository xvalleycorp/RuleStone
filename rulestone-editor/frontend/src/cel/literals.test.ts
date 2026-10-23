// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { codecFor, encodeOutput, parseOutput, quote } from './literals'
import { parse, tokenize } from './parser'

describe('output cells', () => {
  it('recognise literals of the output type', () => {
    expect(parseOutput("'REJECT'", 'string')).toEqual({ mode: 'literal', text: 'REJECT' })
    expect(parseOutput("decimal('0.035')", 'decimal')).toEqual({ mode: 'literal', text: '0.035' })
    expect(parseOutput('decimal(5000)', 'decimal')).toEqual({ mode: 'literal', text: '5000' })
    expect(parseOutput("money('2000', 'VND')", 'money')).toEqual({ mode: 'literal', text: '2000 VND' })
    expect(parseOutput("date('2026-11-01')", 'date')).toEqual({ mode: 'literal', text: '2026-11-01' })
    expect(parseOutput('false', 'bool')).toEqual({ mode: 'literal', text: 'false' })
    expect(parseOutput('42', 'int')).toEqual({ mode: 'literal', text: '42' })
    expect(parseOutput("['A', 'B']", ['string'])).toEqual({ mode: 'list', items: ['A', 'B'] })
    expect(parseOutput('[]', ['decimal'])).toEqual({ mode: 'list', items: [] })
    expect(parseOutput(undefined, 'string')).toEqual({ mode: 'empty' })
  })

  it('keep anything else as an expression', () => {
    const cel = "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000"
    expect(parseOutput(cel, 'decimal')).toEqual({ mode: 'expr', cel })
    expect(parseOutput("'REJECT'", 'decimal')).toEqual({ mode: 'expr', cel: "'REJECT'" })
    expect(parseOutput('0.5', 'decimal')).toEqual({ mode: 'expr', cel: '0.5' })
    expect(parseOutput("decimal('abc')", 'decimal')).toEqual({ mode: 'expr', cel: "decimal('abc')" })
    expect(parseOutput("'x' +", 'string')).toEqual({ mode: 'expr', cel: "'x' +" })
  })

  it('encode typed values', () => {
    expect(encodeOutput({ mode: 'literal', text: 'APPROVE' }, 'string')).toEqual({ cel: "'APPROVE'" })
    expect(encodeOutput({ mode: 'literal', text: '0.035' }, 'decimal')).toEqual({ cel: "decimal('0.035')" })
    expect(encodeOutput({ mode: 'literal', text: '2000 VND' }, 'money')).toEqual({ cel: "money('2000', 'VND')" })
    expect(encodeOutput({ mode: 'list', items: ['1', '2'] }, ['int'])).toEqual({ cel: '[1, 2]' })
    expect(encodeOutput({ mode: 'literal', text: 'abc' }, 'decimal')).toEqual({ error: 'decimal' })
    expect(encodeOutput({ mode: 'empty' }, 'decimal')).toEqual({ cel: undefined })
    expect(encodeOutput({ mode: 'expr', cel: '  ' }, 'decimal')).toEqual({ cel: undefined })
  })
})

describe('quote', () => {
  it('escapes what CEL requires and round-trips through the parser', () => {
    for (const text of ["O'Brien", 'back\\slash', 'line\nbreak', 'Nguyễn Văn Đức', '"double"', '']) {
      const node = parse(quote(text))
      expect(node.kind === 'literal' && node.value).toBe(text)
    }
  })

  it('codecs decode what they encode', () => {
    const cases: [string, string][] = [['int', '-42'], ['decimal', '-0.001'], ['string', "a'b"], ['bool', 'true'],
      ['money', '-12.5 USD'], ['date', '2028-02-29']]
    for (const [type, text] of cases) {
      const codec = codecFor(type)!
      const encoded = codec.encode(text)
      expect('cel' in encoded).toBe(true)
      expect(codec.decode(parse((encoded as { cel: string }).cel))).toBe(text)
    }
    expect(codecFor('struct')).toBeUndefined()
    expect(codecFor(['string'])).toBeUndefined()
  })
})

describe('parser', () => {
  it('tokenizes CEL', () => {
    expect(tokenize("a.b >= decimal('1') && x in [1, 2u, 3.5e2] // comment").map((t) => t.text)).toEqual([
      'a', '.', 'b', '>=', 'decimal', '(', "'1'", ')', '&&', 'x', 'in', '[', '1', ',', '2u', ',', '3.5e2', ']', '',
    ])
  })

  it('respects precedence', () => {
    const node = parse('a || b && c == d + e * f')
    expect(node.kind === 'binary' && node.op).toBe('||')
    const ternary = parse('a ? b : c ? d : e')
    expect(ternary.kind).toBe('ternary')
  })

  it('parses method calls, indexes, maps and raw strings', () => {
    expect(() => parse("customer.accounts[0].balance.round(2, 'HALF_UP')")).not.toThrow()
    expect(() => parse("{'a': 1, 'b': [r'\\d+', b'x']}")).not.toThrow()
    expect(() => parse("customer.accounts.exists(a, a.type == 'SAVINGS')")).not.toThrow()
    expect(() => parse("'''multi\nline'''")).not.toThrow()
  })

  it('reports syntax errors with a position', () => {
    expect(() => parse('a +')).toThrow(/unexpected end/)
    expect(() => parse('a # b')).toThrow(/unexpected character/)
    expect(() => parse('(a')).toThrow(/expected '\)'/)
  })
})
