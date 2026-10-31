// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  BUSINESS_DATE, InvalidDecisionError, analyzeExpression, codePointIndex, createEngine, formatCelType, functionCatalog,
  inputStructName, localValueTypes, parseDecisionTable, structOf, type DecisionTable, type Expr, type ValueType,
} from '../src/index.js'
import { exampleTables } from './helpers.js'

/** The node without positions and types, for structural comparison. */
function shape(node: Expr): unknown {
  return JSON.parse(
    JSON.stringify(node, (key, value: unknown) =>
      ['pos', 'start', 'end', 'fieldPos', 'variablePos', 'type', 'overloads'].includes(key)
        ? undefined
        : typeof value === 'bigint'
          ? `${value}n`
          : value,
    ),
  )
}

function children(node: Expr): Expr[] {
  switch (node.kind) {
    case 'select':
      return [node.operand]
    case 'call':
      return [...(node.target ? [node.target] : []), ...node.args]
    case 'list':
      return [...node.items]
    case 'comprehension':
      return [node.range, ...(node.predicate ? [node.predicate] : []), ...(node.transform ? [node.transform] : [])]
    default:
      return []
  }
}

function slice(source: string, node: Expr): string {
  return source.slice(codePointIndex(source, node.start), codePointIndex(source, node.end))
}

function variables(table: DecisionTable): Map<string, ValueType> {
  const params: [string, ValueType][] = table.params ? [['params', structOf(table.params.values)]] : []
  return new Map([
    ...table.inputs,
    [BUSINESS_DATE, 'date' as ValueType],
    ...params,
    ...localValueTypes(table.inputs, table.let ?? new Map(), table.params?.values),
  ])
}

describe('source spans', () => {
  const expressions = exampleTables().flatMap((table) =>
    [...(table.let?.values() ?? []), ...table.rules.flatMap((rule) => [rule.when, ...rule.then.values()])].map((source) => ({ table, source })),
  )

  it('cover the whole expression and re-parse to the same node, for every node of every example expression', () => {
    let nodes = 0
    for (const { table, source } of expressions) {
      const { ast, issues } = analyzeExpression(source, { variables: variables(table) })
      expect(issues).toEqual([])
      expect(slice(source, ast!)).toBe(source)
      const visit = (node: Expr): void => {
        nodes++
        const text = slice(source, node)
        // has(x.y) is a select node whose span is the whole macro call.
        const reparsed = analyzeExpression(text, { variables: variables(table) }).ast
        if (reparsed && !(node.kind === 'select' && node.test === false && text.startsWith('has('))) {
          expect(shape(reparsed), text).toEqual(shape(node))
        }
        children(node).forEach(visit)
      }
      visit(ast!)
    }
    expect(nodes).toBeGreaterThan(1000)
  })

  it('include enclosing parentheses and cancelled unary operators', () => {
    const vars = { a: 'int' as ValueType, b: 'bool' as ValueType }
    const ast = analyzeExpression('( a + 1 ) * 2 > 3 && !!b', { variables: vars }).ast!
    expect(ast.kind).toBe('call')
    const [left, right] = (ast as Extract<Expr, { kind: 'call' }>).args
    expect(slice('( a + 1 ) * 2 > 3 && !!b', left!)).toBe('( a + 1 ) * 2 > 3')
    expect(slice('( a + 1 ) * 2 > 3 && !!b', right!)).toBe('!!b')
  })

  it('give the field name position of selections, also inside has()', () => {
    const source = "has(applicant . segment) && applicant.segment == 'VIP'"
    const vars = { applicant: { kind: 'struct', fields: new Map([['segment', 'string']]) } as ValueType }
    const ast = analyzeExpression(source, { variables: vars }).ast as Extract<Expr, { kind: 'call' }>
    const has = ast.args[0] as Extract<Expr, { kind: 'select' }>
    expect(has.test).toBe(true)
    expect(source.slice(has.fieldPos, has.fieldPos + 7)).toBe('segment')
    expect(slice(source, has)).toBe('has(applicant . segment)')
    expect(formatCelType(has.operand.type!)).toBe(inputStructName(['applicant']))
  })

  it('count code points, as cel-java does', () => {
    const source = "'đ🙂' == name && nme"
    const { issues } = analyzeExpression(source, { variables: { name: 'string' } })
    expect(issues).toHaveLength(1)
    expect(issues[0]!.pos).toBe(16)
    expect(source.slice(issues[0]!.index)).toBe('nme')
  })
})

describe('analyzeExpression', () => {
  it('reports every type error with its position, as the engine does', async () => {
    const text = JSON.stringify({
      id: 't', version: '1', hitPolicy: 'FIRST',
      inputs: { applicant: { age: 'int' }, amount: 'decimal' },
      outputs: { limit: 'decimal' },
      rules: [{ id: 'R1', when: 'applicant.agee > 1 && amount > 0.5', then: { limit: "'x'" } }],
    })
    const table = parseDecisionTable(text)
    const vars = variables(table)
    const when = analyzeExpression(table.rules[0]!.when, { variables: vars, expected: 'bool' })
    expect(when.issues.map((i) => [i.kind, i.pos, i.line, i.column, i.message])).toEqual([
      ['type', 9, 1, 10, "undefined field 'agee'"],
      ['type', 29, 1, 30, "found no matching overload for '_>_' applied to '(decimal, double)'"],
    ])
    const then = analyzeExpression("'x'", { variables: vars, expected: 'decimal' })
    expect(then.issues.map((i) => i.kind)).toEqual(['result'])
    const problems = await createEngine({ tables: [table] }).then(
      () => [],
      (e: unknown) => (e as InvalidDecisionError).problems,
    )
    expect(problems).toEqual([`t@1 rules[R1].when: ${when.message}`, `t@1 rules[R1].then.limit: ${then.message}`])
  })

  it('reports syntax errors without a tree', () => {
    const result = analyzeExpression('a >= ', { variables: { a: 'int' } })
    expect(result.ast).toBeUndefined()
    expect(result.issues).toMatchObject([{ kind: 'syntax', pos: 5 }])
    expect(result.message).toContain("ERROR: <input>:1:6: mismatched input '<EOF>'")
  })

  it('types every node, naming structs by their path', () => {
    const vars = { customer: { kind: 'struct', fields: new Map([['accounts', { kind: 'list', element: { kind: 'struct', fields: new Map([['balance', 'decimal']]) } }]]) } } as const
    const { ast, type } = analyzeExpression('customer.accounts.exists(a, a.balance > 0)', { variables: vars as unknown as Map<string, ValueType> })
    expect(formatCelType(type!)).toBe('bool')
    const comprehension = ast as Extract<Expr, { kind: 'comprehension' }>
    const select = (comprehension.predicate as Extract<Expr, { kind: 'call' }>).args[0] as Extract<Expr, { kind: 'select' }>
    expect(formatCelType(select.operand.type!)).toBe(inputStructName(['customer', 'accounts', 'item']))
    expect(formatCelType(select.type!)).toBe('decimal')
    expect(comprehension.variablePos).toBe(25)
  })
})

describe('functionCatalog', () => {
  it('lists functions and methods without operators or unsupported functions', () => {
    const catalog = functionCatalog()
    expect(catalog).toContainEqual({ name: 'plusDays', member: true, params: ['date', 'int'], result: 'date' })
    expect(catalog).toContainEqual({ name: 'money', member: false, params: ['string', 'string'], result: 'money' })
    expect(catalog).toContainEqual({ name: 'size', member: true, params: ['list(T)'], result: 'int' })
    expect(catalog.some((f) => f.name.startsWith('_') || f.name === 'timestamp')).toBe(false)
  })
})
