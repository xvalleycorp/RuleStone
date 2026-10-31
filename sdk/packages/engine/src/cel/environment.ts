// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { ExpressionError, InvalidValueError } from '../errors.js'
import { isCurrencyCode } from '../money.js'
import { isPrimitive, type ValueType } from '../types.js'
import { canonicalValue, checkResult, toValue, type Value } from '../values.js'
import { analyze, formatIssues } from './analysis.js'
import type { Expr } from './ast.js'
import { EvalError } from './functions.js'
import { evaluate } from './interpreter.js'
import { CelParseError, parseCel } from './parser.js'
import { celTypeOf, formatType, typeName, valueTypeOf, type CelType } from './types.js'

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
  /**
   * Local values, as ExpressionEnvironment.Builder.local: variables computed from an expression
   * (compiled with compileLocal) when first read, at most once per Bindings.
   */
  readonly locals?: ReadonlyMap<string, CompiledExpression>
}

/** Whether a name can name a variable or a local value: an identifier that is not a reserved word. */
export function isValidName(name: string): boolean {
  return IDENTIFIER.test(name) && !RESERVED.has(name)
}

function requireIdentifier(name: string, kind: string): void {
  if (!isValidName(name)) throw new RangeError(`invalid ${kind} name: '${name}'`)
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
 * The failure of a local value, raised where an expression reads it. It is an evaluation error
 * like any other, so || and && absorb it as they would absorb the error of the expression
 * written in place; when it is not absorbed, the local value's own failure is reported.
 */
export class LocalValueError extends EvalError {
  constructor(name: string, message: string) {
    super(`local value ${name} failed: ${message}`, false)
    this.name = 'LocalValueError'
  }
}

/** The names an expression reads from its environment: not the variables of list macros. */
function collectReferences(node: Expr, bound: readonly string[], names: Set<string>): void {
  switch (node.kind) {
    case 'ident':
      if (node.global || !bound.includes(node.name)) names.add(node.name)
      break
    case 'select':
      collectReferences(node.operand, bound, names)
      break
    case 'call':
      if (node.target) collectReferences(node.target, bound, names)
      for (const arg of node.args) collectReferences(arg, bound, names)
      break
    case 'list':
      for (const item of node.items) collectReferences(item, bound, names)
      break
    case 'comprehension': {
      collectReferences(node.range, bound, names)
      const inner = [...bound, node.variable]
      if (node.predicate) collectReferences(node.predicate, inner, names)
      if (node.transform) collectReferences(node.transform, inner, names)
      break
    }
    case 'literal':
      break
  }
}

function collectSelections(node: Expr, variable: string, shadowed: boolean, fields: Set<string>): void {
  switch (node.kind) {
    case 'select':
      if (!shadowed && node.operand.kind === 'ident' && node.operand.name === variable) fields.add(node.field)
      else collectSelections(node.operand, variable, shadowed, fields)
      break
    case 'call':
      if (node.target) collectSelections(node.target, variable, shadowed, fields)
      for (const arg of node.args) collectSelections(arg, variable, shadowed, fields)
      break
    case 'list':
      for (const item of node.items) collectSelections(item, variable, shadowed, fields)
      break
    case 'comprehension': {
      collectSelections(node.range, variable, shadowed, fields)
      const inner = shadowed || node.variable === variable
      if (node.predicate) collectSelections(node.predicate, variable, inner, fields)
      if (node.transform) collectSelections(node.transform, variable, inner, fields)
      break
    }
    case 'ident':
    case 'literal':
      break
  }
}

/**
 * Typed variables plus the RuleStone CEL dialect, as vn.io.dxa.rulestone.cel.ExpressionEnvironment.
 * Compile expressions once, then evaluate them many times. Immutable.
 */
export class ExpressionEnvironment {
  readonly variables: ReadonlyMap<string, ValueType>
  readonly maxIterations: number
  readonly currencyScales: ReadonlyMap<string, number>
  /** The local values, by name. */
  readonly locals: ReadonlyMap<string, CompiledExpression>
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
    const locals = new Map(options.locals ?? [])
    for (const [name, expression] of locals) {
      requireIdentifier(name, 'local value')
      if (variables.has(name)) throw new RangeError(`duplicate variable: ${name}`)
      cel.set(name, expression.celType)
    }
    this.locals = locals
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
    return new CompiledExpression(this, source, expected, ast!, type!)
  }

  /**
   * Parses and type-checks the expression of a local value, inferring its type, which must be a
   * bool, int, string, decimal, money, date, a list or a struct.
   * @throws ExpressionError if the expression is malformed, ill-typed, or of another type
   */
  compileLocal(source: string): CompiledExpression {
    const { ast, type, message } = analyze(source, this.celVariables, undefined)
    if (message !== undefined) throw new ExpressionError(message)
    const valueType = valueTypeOf(type!)
    if (valueType === undefined) {
      throw new ExpressionError(
        `a local value must produce bool, int, string, decimal, money, date, a list or a struct but produces ${typeName(type!)}: ${source}`,
      )
    }
    return new CompiledExpression(this, source, valueType, ast!, type!)
  }

  /**
   * The names an expression reads from its environment (inputs and local values, but not the
   * variables of list macros), sorted. Parsed but not type-checked.
   * @throws ExpressionError if the expression is malformed
   */
  references(source: string): readonly string[] {
    let ast: Expr
    try {
      ast = parseCel(source)
    } catch (e) {
      if (e instanceof CelParseError) throw new ExpressionError(formatIssues(source, e.issues))
      throw e
    }
    const names = new Set<string>()
    collectReferences(ast, [], names)
    return [...names].sort()
  }

  /**
   * The fields an expression selects directly on variable `variable`, such as maxDti in
   * `dti > params.maxDti`, also inside has(), but not on a list macro's variable of the same
   * name; sorted. Parsed but not type-checked.
   * @throws ExpressionError if the expression is malformed
   */
  selectedFields(source: string, variable: string): readonly string[] {
    let ast: Expr
    try {
      ast = parseCel(source)
    } catch (e) {
      if (e instanceof CelParseError) throw new ExpressionError(formatIssues(source, e.issues))
      throw e
    }
    const fields = new Set<string>()
    collectSelections(ast, variable, false, fields)
    return [...fields].sort()
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

/**
 * Inputs converted and checked for one environment. Create once per evaluation. Local values are
 * computed when an expression first reads them, at most once per Bindings.
 */
export class Bindings {
  private readonly computed = new Map<string, Value | LocalValueError>()

  constructor(
    readonly environment: ExpressionEnvironment,
    readonly values: ReadonlyMap<string, Value>,
  ) {}

  /** Resolves a variable for the interpreter: an input, or a local value computed on first use. @throws LocalValueError */
  resolve(name: string): Value | undefined {
    const value = this.values.get(name)
    if (value !== undefined) return value
    const local = this.environment.locals.get(name)
    if (!local) return undefined
    let result = this.computed.get(name)
    if (result === undefined) {
      try {
        result = local.evaluateRaw(this)
      } catch (e) {
        if (!(e instanceof EvalError)) throw e
        // A local value that failed because another one did reports the other one.
        result = e instanceof LocalValueError ? e : new LocalValueError(name, `${e.display()} in: ${local.source}`)
      }
      this.computed.set(name, result)
    }
    if (result instanceof LocalValueError) throw result
    return result
  }

  /** The values in canonical form for hashing, sorted by name; local values are not included. They may be personal data: hash them, never log them. */
  canonicalValues(): ReadonlyMap<string, Value> {
    const out = new Map<string, Value>()
    for (const name of [...this.values.keys()].sort()) out.set(name, canonicalValue(this.values.get(name)!))
    return out
  }
}

/** A type-checked expression, safe to evaluate many times. */
export class CompiledExpression {
  /** The checked type, as cel-java prints it. */
  readonly checkedType: string

  constructor(
    readonly environment: ExpressionEnvironment,
    readonly source: string,
    /** The type required when compiling, or the inferred type of a local value. */
    readonly resultType: ValueType,
    private readonly ast: Expr,
    /** The checked type. */
    readonly celType: CelType,
  ) {
    this.checkedType = formatType(celType)
  }

  /**
   * Evaluates the expression.
   * @throws ExpressionError if evaluation fails or produces a value of the wrong type
   */
  evaluate(bindings: Bindings): Value {
    if (bindings.environment !== this.environment) throw new RangeError('bindings were created by a different environment')
    let result: Value
    try {
      result = this.evaluateRaw(bindings)
    } catch (e) {
      if (e instanceof LocalValueError) throw new ExpressionError(e.message)
      if (e instanceof EvalError) throw new ExpressionError(`${e.display()} in: ${this.source}`)
      throw e
    }
    return checkResult(result, this.resultType)
  }

  /** @internal the runtime value, reading inputs and local values from `bindings`. @throws EvalError */
  evaluateRaw(bindings: Bindings): Value {
    return evaluate(this.ast, {
      variables: { get: (name) => bindings.resolve(name) },
      maxIterations: this.environment.maxIterations,
      currencyScales: this.environment.currencyScales,
    })
  }
}
