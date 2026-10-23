// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Every expression in fixtures/probes.json gives the result, or the error, that the Java engine
// gives (fixtures/java-probes.json, written by rulestone-test's SdkFixturesTest). The probes
// cover operators, macros and the shared iteration budget, && and || absorbing errors, error
// positions, list result types, decimal arithmetic against BigDecimal, money, dates, fuzzy
// matching and value-free error messages.

import { describe, expect, it } from 'vitest'
import {
  ExpressionEnvironment, ExpressionError, listOf, readValues, typeFromSpec, typeToString, valueToJson, writeJson,
  type CompiledExpression, type TypeSpec, type ValueType,
} from '../src/index.js'
import { fixture } from './helpers.js'
import { javaString } from './java-string.js'

interface Probes {
  variables: Record<string, TypeSpec>
  inputs: Record<string, unknown>
  maxIterations: number
  currencyScales: Record<string, number>
  probes: { expr: string; type?: string }[]
}

interface JavaProbe {
  expr: string
  type?: string
  json?: unknown
  text?: string
  compileError?: string
  evalError?: string
}

const PROBE_TYPES: ValueType[] = [
  'bool', 'int', 'string', 'decimal', 'money', 'date', listOf('string'), listOf('int'), listOf('decimal'), listOf('bool'),
  listOf('money'), listOf('date'), listOf(listOf('int')),
]

function parseType(name: string): ValueType {
  if (name.startsWith('list<') && name.endsWith('>')) return listOf(parseType(name.slice(5, -1)))
  return name as ValueType
}

/**
 * Where the browser engine knowingly differs: standard CEL features it does not provide
 * (it rejects them when compiling, where Java accepts them), and the wording of syntax errors.
 */
const UNSUPPORTED = /not supported by the browser engine/
const SYNTAX = /mismatched input|extraneous input|no viable alternative|token recognition error|missing '.' at/

const input = fixture<Probes>('probes.json')
const java = fixture<JavaProbe[]>('java-probes.json')

const variables = new Map(Object.entries(input.variables).map(([n, t]) => [n, typeFromSpec(t)]))
const env = new ExpressionEnvironment({ variables, maxIterations: input.maxIterations, currencyScales: input.currencyScales })
// The inputs are read from JSON text so that 9223372036854775807 stays exact.
const inputsText = JSON.stringify(input.inputs).replace('"big":9223372036854776000', '"big":9223372036854775807')
const bindings = env.bind(readValues(inputsText, variables))

/** Java's message without the list of candidate overloads, which names protobuf types. */
function withoutCandidates(message: string): string {
  return message.replace(/ \(candidates: [^\n]*\)/g, '')
}

/** Runtime overload candidates in a fixed order: cel-java lists them in hash order. */
function sortedCandidates(message: string): string {
  return message.replace(/Overload candidates: ([^ ]*)/, (_, ids: string) => `Overload candidates: ${ids.split(',').sort().join(',')}`)
}

describe('expression probes against the Java engine', () => {
  it('has a Java result for every probe', () => {
    expect(java.map((p) => p.expr)).toEqual(input.probes.map((p) => p.expr))
  })

  const divergences: string[] = []
  input.probes.forEach((probe, index) => {
    const expected = java[index]!
    it(probe.expr, () => {
      const types = probe.type ? [parseType(probe.type)] : PROBE_TYPES
      let compiled: CompiledExpression | undefined
      let error: string | undefined
      for (const type of types) {
        try {
          compiled = env.compile(probe.expr, type)
          break
        } catch (e) {
          if (!(e instanceof ExpressionError)) throw e
          error ??= e.message
        }
      }
      if (expected.compileError !== undefined) {
        expect(compiled, `Java rejects it: ${expected.compileError}`).toBeUndefined()
        if (SYNTAX.test(expected.compileError) || UNSUPPORTED.test(error!)) {
          divergences.push(probe.expr)
          return
        }
        expect(error).toBe(withoutCandidates(expected.compileError))
        return
      }
      if (!compiled) {
        expect(error, `Java compiles it as ${expected.type}`).toMatch(UNSUPPORTED)
        divergences.push(probe.expr)
        return
      }
      expect(typeToString(compiled.resultType)).toBe(expected.type)
      let value
      try {
        value = compiled.evaluate(bindings)
      } catch (e) {
        if (!(e instanceof ExpressionError)) throw e
        expect(expected.evalError, `Java gives ${JSON.stringify(expected.json)}`).toBeDefined()
        expect(sortedCandidates(e.message)).toBe(sortedCandidates(expected.evalError!))
        return
      }
      expect(expected.evalError, `JS gives ${javaString(value)}`).toBeUndefined()
      expect(JSON.parse(writeJson(valueToJson(value)))).toEqual(expected.json)
      expect(javaString(value)).toBe(expected.text)
    })
  })

  it('differs from Java only in unsupported features and syntax error wording', () => {
    expect(divergences).toEqual([
      // Map literals, type() and bytes are standard CEL that RuleStone rules do not use;
      // Java's formatting of doubles depends on the JDK version.
      "{'a': 1}",
      "{'a': 1}['a']",
      'type(1)',
      "string(1.5) == '1.5'",
      "size({'a': 1}) == 1",
      "'a' in {'a': 1}",
      "{'a': 1}.a == 1",
      "b'abc' == b'abc'",
      "size(b'abc') == 3",
      // Syntax errors are reported in other words than ANTLR's.
      '1 +',
      '(1 + 2',
      "'unterminated",
      '1 2',
      'p.in > 1',
      'type(1) == int',
      'in',
    ])
  })
})
