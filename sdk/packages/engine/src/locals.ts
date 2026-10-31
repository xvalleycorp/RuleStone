// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The local values (let) of a decision table, compiled as the Java engine's LocalValues does:
// names checked, the local values each one reads found, cycles rejected, and each type-checked
// after those it reads so that its type is inferred first. Names are processed in sorted order,
// as the canonical form sorts them, so the order in the file does not matter.

import { CompiledExpression, ExpressionEnvironment, isValidName, type EnvironmentOptions } from './cel/environment.js'
import { ExpressionError } from './errors.js'
import { compareJavaStrings } from './json.js'
import type { DecisionTable } from './table.js'
import type { ValueType } from './types.js'
import { paramsType } from './params.js'

/** Reserved for parameter sets, which tables read as params.x. */
export const PARAMS = 'params'

const BUSINESS_DATE = 'businessDate'

/**
 * Compiles the table's local values.
 * @param inputs an environment with the inputs and businessDate, used to parse
 * @param options the options of `inputs`, to which the local values are added
 * @param failed receives the local values that could not be compiled, also because one they read could not
 * @param undeclaredParams why an expression cannot read what it reads from params, or undefined
 * @returns an environment with the inputs, businessDate, parameters and every local value compiled
 */
export function compileLocals(
  table: DecisionTable,
  inputs: ExpressionEnvironment,
  options: EnvironmentOptions,
  at: string,
  problems: string[],
  failed: Set<string>,
  undeclaredParams: (source: string) => string | undefined = () => undefined,
): ExpressionEnvironment {
  const sources = new Map([...(table.let ?? new Map<string, string>())].sort((a, b) => compareJavaStrings(a[0], b[0])))
  const reads = new Map<string, string[]>()
  const compiled = new Map<string, CompiledExpression>()
  const problem = (name: string, message: string): void => {
    problems.push(`${at}let.${name}: ${message}`)
  }
  const environment = (): ExpressionEnvironment => new ExpressionEnvironment({ ...options, locals: compiled })

  for (const [name, source] of sources) {
    let valid = true
    if (!isValidName(name)) {
      problem(name, "must be an identifier (a letter or '_', then letters, digits or '_') and not a reserved word")
      valid = false
    } else if (name === BUSINESS_DATE) {
      problem(name, `reserved name; every decision receives ${BUSINESS_DATE} implicitly`)
      valid = false
    } else if (name === PARAMS) {
      problem(name, 'reserved name for parameter sets')
      valid = false
    } else if (table.inputs.has(name)) {
      problem(name, 'an input has the same name')
      valid = false
    }
    try {
      reads.set(name, inputs.references(source).filter((n) => sources.has(n)).sort(compareJavaStrings))
    } catch (e) {
      if (!(e instanceof ExpressionError)) throw e
      problem(name, e.message)
      valid = false
    }
    if (!valid) failed.add(name)
  }

  // Cycles: each reported once, from its smallest name.
  const cycles = new Set<string>()
  const done = new Set<string>()
  const visit = (name: string, path: string[]): void => {
    const index = path.indexOf(name)
    if (index >= 0) {
      const cycle = path.slice(index)
      cycle.forEach((n) => failed.add(n))
      const smallest = [...cycle].sort(compareJavaStrings)[0]!
      const start = cycle.indexOf(smallest)
      cycles.add([...cycle.slice(start), ...cycle.slice(0, start), smallest].join(' -> '))
      return
    }
    if (done.has(name) || !reads.has(name)) return
    path.push(name)
    for (const next of reads.get(name)!) visit(next, path)
    path.pop()
    done.add(name)
  }
  for (const name of reads.keys()) visit(name, [])
  for (const cycle of [...cycles].sort(compareJavaStrings)) problem(cycle.slice(0, cycle.indexOf(' ')), `cycle: ${cycle}`)

  const compile = (name: string): boolean => {
    if (compiled.has(name)) return true
    if (failed.has(name)) return false
    for (const dependency of reads.get(name)!) {
      if (!compile(dependency)) {
        failed.add(name) // the dependency's problem is reported
        return false
      }
    }
    const paramsProblem = undeclaredParams(sources.get(name)!)
    if (paramsProblem !== undefined) {
      problem(name, paramsProblem)
      failed.add(name)
      return false
    }
    try {
      compiled.set(name, environment().compileLocal(sources.get(name)!))
      return true
    } catch (e) {
      if (!(e instanceof ExpressionError)) throw e
      problem(name, e.message)
      failed.add(name)
      return false
    }
  }
  for (const name of sources.keys()) compile(name)
  return environment()
}

/**
 * The inferred types of a table's local values, for editors: the local values that compile, by
 * name, in sorted order. Those with a problem (a bad name, a cycle, an error, or reading one
 * that has a problem) are left out; createEngine reports them. `params` are the parameters the
 * table declares, by name, which local values read as params.<name>.
 */
export function localValueTypes(
  inputs: ReadonlyMap<string, ValueType>,
  locals: ReadonlyMap<string, string>,
  params?: ReadonlyMap<string, ValueType>,
): ReadonlyMap<string, ValueType> {
  const variables = new Map(inputs)
  variables.delete(BUSINESS_DATE)
  variables.set(BUSINESS_DATE, 'date')
  if (params) {
    variables.delete(PARAMS)
    const type = paramsType({ set: '', values: params })
    if (type) variables.set(PARAMS, type)
  }
  let base: ExpressionEnvironment
  try {
    base = new ExpressionEnvironment({ variables })
  } catch (e) {
    if (e instanceof RangeError) return new Map()
    throw e
  }
  const table = { id: 't', version: '1', hitPolicy: 'FIRST', inputs, outputs: new Map(), let: locals, rules: [] } as const
  const environment = compileLocals(table, base, { variables }, '', [], new Set())
  return new Map(
    [...environment.locals]
      .sort((a, b) => compareJavaStrings(a[0], b[0]))
      .map(([name, expression]) => [name, expression.resultType] as const),
  )
}
