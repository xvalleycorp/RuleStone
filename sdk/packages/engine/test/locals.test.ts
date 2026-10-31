// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Local values (let), as LocalValuesTest checks them in Java. The conformance fixtures check
// their problems and the example credit_limit_increase against the Java engine.

import { describe, expect, it } from 'vitest'
import { createEngine, localValueTypes, parseDecisionTable, writeDecisionTable } from '../src/index.js'

function table(locals: Record<string, string>, when: string): string {
  return JSON.stringify({
    id: 't', version: '1', hitPolicy: 'FIRST', inputs: { x: 'int' }, outputs: { y: 'int' }, let: locals,
    rules: [{ id: 'R', when, then: { y: 'n' } }, { id: 'S', when: 'true', then: { y: '0' } }],
  })
}

describe('local values (let)', () => {
  it('computes a local value only when a rule reads it', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(table({ n: '10 / x' }, 'x > 0 && n > 1'))] })
    expect((await engine.evaluate('t', { x: 2 })).output.get('y')).toBe(5n)
    // x > 0 is false, so n, which would divide by zero, is never computed.
    expect((await engine.evaluate('t', { x: 0 })).output.get('y')).toBe(0n)
  })

  it('reports the failure of a local value where it is not absorbed', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(table({ n: '10 / x' }, 'n > 1 || x == 0'))] })
    // || absorbs the failure in the condition, but the output reads n too.
    await expect(engine.evaluate('t', { x: 0 })).rejects.toThrow(
      "rule R output 'y' failed: local value n failed: evaluation error at <input>:3: / by zero in: 10 / x",
    )
  })

  it('leaves the canonical form of a table without local values unchanged', () => {
    const without = parseDecisionTable(table({}, 'true').replace('"let":{},', ''))
    expect(writeDecisionTable(parseDecisionTable(table({}, 'true')))).toBe(writeDecisionTable(without))
    expect(writeDecisionTable(without)).not.toContain('"let"')
    expect(writeDecisionTable(parseDecisionTable(table({ n: '1' }, 'true')))).toContain('"let":{"n":"1"}')
  })

  it('infers the types of local values for editors, leaving out those with problems', () => {
    const types = localValueTypes(new Map([['x', 'int']]), new Map([['b', 'a > 1'], ['a', 'x * 2'], ['c', 'c'], ['d', 'y']]))
    expect([...types]).toEqual([['a', 'int'], ['b', 'bool']])
  })
})
