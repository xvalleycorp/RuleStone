// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { ExpressionError, InvalidValueError } from '../errors.js'
import { isCurrencyCode } from '../money.js'
import { isPrimitive, type ValueType } from '../types.js'
import { canonicalValue, checkResult, toValue, type Value } from '../values.js'
import { analyze } from './analysis.js'
import type { Expr } from './ast.js'
import { EvalError } from './functions.js'
import { evaluate } from './interpreter.js'
import { celTypeOf, formatType, type CelType } from './types.js'

/** Default upper bound on the iterations of the list macros in one evaluation. */
export const DEFAULT_MAX_ITERATIONS = 10_000

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/
const RESERVED = new Set([
  'true', 'false', 'null', 'in', 'as', 'break', 'const', 'continue', 'else', 'for', 'function', 'if', 'import', 'let',
  'loop', 'package', 'namespace', 'return', 'var', 'void', 'while',
])

export interface EnvironmentOptions {
  /** Typed variables, in declaration order. */
  readonly variables: ReadonlyMap<string, ValueType> | { readonly [name: string]: ValueType }
  /** Upper bound on the iterations of all list macros in one evaluation. Default 10,000. */
  readonly maxIterations?: number
  /** Minor units for money.round(), overriding ISO 4217, by currency code (0 to 18 digits). */
  readonly currencyScales?: ReadonlyMap<string, number> | { readonly [currency: string]: number }
}

function requireIdentifier(name: string, kind: string): void {
  if (!IDENTIFIER.test(name) || RESERVED.has(name)) throw new RangeError(`invalid ${kind} name: '${name}'`)
}

function checkFieldNames(type: ValueType): void {
  if (isPrimitive(type)) return
  if (type.kind === 'list') return checkFieldNames(type.element)
  for (const [field, fieldType] of type.fields) {
    requireIdentifier(field, 'field')
    checkFieldNames(fieldType)
  }
}

/** Validates a currency scale override as ExpressionEnvironment.Builder.currencyScale. @throws RangeError */
export function validateCurrencyScale(currency: string, digits: number): void {
  if (!isCurrencyCode(currency)) throw new RangeError(`not an upper-case ISO 4217 currency code: '${currency}'`)
  if (!Number.isInteger(digits) || digits < 0 || digits > 18) throw new RangeError('currency scale must be between 0 and 18')
}

/**
 * Typed variables plus the RuleStone CEL dialect, as vn.io.dxa.rulestone.cel.ExpressionEnvironment.
 * Compile expressions once, then evaluate them many times. Immutable.
 */
export class ExpressionEnvironment {
  readonly variables: ReadonlyMap<string, ValueType>
  readonly maxIterations: number
  readonly currencyScales: ReadonlyMap<string, number>
  private readonly celVariables: ReadonlyMap<string, CelType>

  /** @throws RangeError for an invalid variable or field name, iteration limit or currency scale */
  constructor(options: EnvironmentOptions) {
    const variables = options.variables instanceof Map ? options.variables : new Map(Object.entries(options.variables))
    for (const name of variables.keys()) requireIdentifier(name, 'variable')
    const maxIterations = options.maxIterations ?? DEFAULT_MAX_ITERATIONS
    if (!Number.isInteger(maxIterations) || maxIterations < 1) throw new RangeError('maxIterations must be positive')
    const scales = options.currencyScales instanceof Map ? options.currencyScales : new Map(Object.entries(options.currencyScales ?? {}))
    for (const [currency, digits] of scales) validateCurrencyScale(currency, digits)
    for (const type of variables.values()) checkFieldNames(type)
    this.variables = new Map(variables)
    this.maxIterations = maxIterations
    this.currencyScales = new Map(scales)
    const cel = new Map<string, CelType>()
    for (const [name, type] of variables) cel.set(name, celTypeOf(type, 'rulestone.input.' + name))
    this.celVariables = cel
  }

  /**
   * Parses and type-checks source, requiring its result to be `expected`.
   * @throws ExpressionError if the expression is malformed, ill-typed, or of another type
   */
  compile(source: string, expected: ValueType): CompiledExpression {
    if (!isPrimitive(expected) && expected.kind === 'struct') throw new RangeError('expressions cannot be required to produce a struct')
    const { ast, type, message } = analyze(source, this.celVariables, expected)
    if (message !== undefined) throw new ExpressionError(message)
    return new CompiledExpression(this, source, expected, ast!, formatType(type!))
  }

  /**
   * Converts values for every declared variable into their runtime form. Undeclared entries are ignored.
   * @throws InvalidValueError if a declared variable is missing or does not match its type
   */
  bind(values: ReadonlyMap<string, unknown> | { readonly [name: string]: unknown }): Bindings {
    const get = (name: string): unknown =>
      values instanceof Map ? values.get(name) : Object.prototype.hasOwnProperty.call(values, name) ? (values as Record<string, unknown>)[name] : undefined
    const converted = new Map<string, Value>()
    for (const [name, type] of this.variables) {
      const value = get(name)
      if (value === undefined || value === null) throw new InvalidValueError(name, 'missing input')
      converted.set(name, toValue(value, type, name))
    }
    return new Bindings(this, converted)
  }
}

/** Inputs converted and checked for one environment. Create once per evaluation. */
export class Bindings {
  constructor(
    readonly environment: ExpressionEnvironment,
    readonly values: ReadonlyMap<string, Value>,
  ) {}

  /** The values in canonical form for hashing, sorted by name. They may be personal data: hash them, never log them. */
  canonicalValues(): ReadonlyMap<string, Value> {
    const out = new Map<string, Value>()
    for (const name of [...this.values.keys()].sort()) out.set(name, canonicalValue(this.values.get(name)!))
    return out
  }
}

/** A type-checked expression, safe to evaluate many times. */
export class CompiledExpression {
  constructor(
    readonly environment: ExpressionEnvironment,
    readonly source: string,
    readonly resultType: ValueType,
    private readonly ast: Expr,
    /** The checked type, as cel-java prints it. */
    readonly checkedType: string,
  ) {}

  /**
   * Evaluates the expression.
   * @throws ExpressionError if evaluation fails or produces a value of the wrong type
   */
  evaluate(bindings: Bindings): Value {
    if (bindings.environment !== this.environment) throw new RangeError('bindings were created by a different environment')
    let result: Value
    try {
      result = evaluate(this.ast, {
        variables: bindings.values,
        maxIterations: this.environment.maxIterations,
        currencyScales: this.environment.currencyScales,
      })
    } catch (e) {
      if (e instanceof EvalError) throw new ExpressionError(`${e.display()} in: ${this.source}`)
      throw e
    }
    return checkResult(result, this.resultType)
  }
}
