// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Expression analysis for editors: the parsed and type-checked syntax tree, every problem with
// its exact position, and the functions available for autocomplete. It runs the same parser
// and type checker as ExpressionEnvironment.compile, so its messages are the engine's.

import { isPrimitive, type ValueType } from '../types.js'
import type { Expr } from './ast.js'
import { check } from './checker.js'
import { allOverloads } from './functions.js'
import { CelParseError, parseCel, type Issue } from './parser.js'
import { celTypeOf, formatType, sameType, typeName, type CelType } from './types.js'
import { typeToString } from '../types.js'

/** One problem in an expression. */
export interface ExpressionIssue {
  /** What found it: the parser, the type checker, or the check of the result type. */
  readonly kind: 'syntax' | 'type' | 'result'
  /** Offset of the problem in code points, as cel-java reports it. */
  readonly pos: number
  /** The same offset in UTF-16 code units, for `String.prototype.slice` and text inputs. */
  readonly index: number
  /** 1-based line and column, in code points, as in the engine's message. */
  readonly line: number
  readonly column: number
  readonly message: string
}

/** The result of analyzing one expression. */
export interface ExpressionAnalysis {
  readonly source: string
  /** The syntax tree, annotated with types by the checker; undefined when the expression does not parse. */
  readonly ast: Expr | undefined
  /** The checked type of the whole expression; undefined when it does not parse. */
  readonly type: CelType | undefined
  /** Every problem, sorted by position. Empty when the expression would compile. */
  readonly issues: readonly ExpressionIssue[]
  /**
   * The problems as the engine reports them (the text of ExpressionError, and of the problems of
   * InvalidDecisionError after the location prefix), or undefined when there are none.
   */
  readonly message: string | undefined
}

export interface AnalysisOptions {
  /** Typed variables in scope, e.g. a table's inputs plus businessDate. */
  readonly variables: ReadonlyMap<string, ValueType> | { readonly [name: string]: ValueType }
  /** The type the expression must produce: bool for a condition, the output's type for an output. */
  readonly expected?: ValueType
}

/**
 * The CEL type name of a struct among the variables: the variable name and the field names on
 * the way down, with `item` for the elements of a list, e.g. ['customer', 'accounts', 'item'].
 * The type checker gives every Select node's operand this type, so editors can tell which
 * declared field a selection refers to.
 */
export function inputStructName(path: readonly string[]): string {
  return ['rulestone.input', ...path].join('.')
}

/** The CEL type of a declared variable, as the type checker sees it. */
export function variableCelType(name: string, type: ValueType): CelType {
  return celTypeOf(type, inputStructName([name]))
}

/**
 * Parses and type-checks an expression without throwing. Used by editors to show every problem
 * at its position, to drive autocomplete and to refactor expressions through their syntax tree.
 */
export function analyzeExpression(source: string, options: AnalysisOptions): ExpressionAnalysis {
  const variables = options.variables instanceof Map ? options.variables : new Map(Object.entries(options.variables))
  const cel = new Map<string, CelType>()
  for (const [name, type] of variables) cel.set(name, variableCelType(name, type))
  return analyze(source, cel, options.expected)
}

/** @internal shared with ExpressionEnvironment.compile */
export function analyze(source: string, variables: ReadonlyMap<string, CelType>, expected: ValueType | undefined): ExpressionAnalysis {
  let ast: Expr
  try {
    ast = parseCel(source)
  } catch (e) {
    if (e instanceof CelParseError) return result(source, undefined, undefined, e.issues.map((i) => issue(source, i, 'syntax')))
    throw e
  }
  const { type, issues } = check(ast, variables)
  const found = issues.map((i) => issue(source, i, 'type'))
  if (found.length === 0 && expected !== undefined && (isPrimitive(expected) || expected.kind !== 'struct')) {
    const wanted = celTypeOf(expected, '')
    if (type.k !== 'dyn' && !sameType(wanted, type)) {
      const message = `expression must produce ${typeToString(expected)} but produces ${typeName(type)}: ${source}`
      return { source, ast, type, issues: [{ kind: 'result', pos: 0, index: 0, line: 1, column: 1, message }], message }
    }
  }
  return result(source, ast, type, found)
}

function result(source: string, ast: Expr | undefined, type: CelType | undefined, issues: ExpressionIssue[]): ExpressionAnalysis {
  const message = issues.length === 0 ? undefined : formatIssues(source, issues)
  return { source, ast, type, issues, message }
}

function issue(source: string, { pos, message }: Issue, kind: ExpressionIssue['kind']): ExpressionIssue {
  const cps = Array.from(source)
  let line = 1
  let lineStart = 0
  for (let k = 0; k < pos && k < cps.length; k++) {
    if (cps[k] === '\n') {
      line++
      lineStart = k + 1
    }
  }
  return { kind, pos, index: codePointIndex(source, pos), line, column: pos - lineStart + 1, message }
}

/** Converts an offset in code points (as in the syntax tree and messages) to a UTF-16 index. */
export function codePointIndex(source: string, pos: number): number {
  let index = 0
  let count = 0
  while (count < pos && index < source.length) {
    const cp = source.codePointAt(index)!
    index += cp > 0xffff ? 2 : 1
    count++
  }
  return index
}

/** Issues formatted as cel-java formats them: ERROR: <input>:line:column: message, the line and a caret. */
export function formatIssues(source: string, issues: readonly Issue[]): string {
  const cps = Array.from(source)
  return issues
    .map(({ pos, message }) => {
      let line = 1
      let lineStart = 0
      for (let k = 0; k < pos && k < cps.length; k++) {
        if (cps[k] === '\n') {
          line++
          lineStart = k + 1
        }
      }
      let lineEnd = lineStart
      while (lineEnd < cps.length && cps[lineEnd] !== '\n') lineEnd++
      const column = pos - lineStart + 1
      const text = cps.slice(lineStart, lineEnd).join('')
      return `ERROR: <input>:${line}:${column}: ${message}\n | ${text}\n | ${'.'.repeat(column - 1)}^`
    })
    .join('\n')
}

/** A function or method rules can call, for autocomplete and help. */
export interface FunctionSignature {
  readonly name: string
  /** Called on a value: target.name(args). The target's type is params[0]. */
  readonly member: boolean
  /** Parameter types as the checker prints them; a type parameter is shown as T. */
  readonly params: readonly string[]
  readonly result: string
}

let catalog: readonly FunctionSignature[] | undefined

/**
 * Every function and method the engine provides, one entry per overload, without operators and
 * without the CEL functions the browser engine does not support. The list macros (all, exists,
 * exists_one, filter, map) and has() are not functions and are not listed.
 */
export function functionCatalog(): readonly FunctionSignature[] {
  if (catalog) return catalog
  const show = (t: CelType): string => formatType(t).replace(/_var-\d+/g, 'T')
  const out: FunctionSignature[] = []
  for (const [name, overloads] of allOverloads()) {
    if (!/^[A-Za-z]/.test(name)) continue
    for (const o of overloads) {
      if (o.unsupported) continue
      out.push({ name, member: o.member, params: o.params.map(show), result: show(o.result) })
    }
  }
  catalog = Object.freeze(out)
  return catalog
}
