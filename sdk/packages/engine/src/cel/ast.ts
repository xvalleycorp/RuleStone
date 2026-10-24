// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import type { CelType } from './types.js'

/**
 * A parsed CEL expression. `pos` is the offset, in code points, of the token that identifies
 * the node (the operator, the '.' of a selection, the '(' of a call, the '[' of an index or a
 * list), as cel-java reports it in error messages.
 *
 * `start` and `end` delimit the node's source text, in code points, end exclusive. They include
 * the parentheses around the node, if any, so that `source.slice` of the span (after converting
 * to UTF-16 indices) is the node exactly as written.
 */
export type Expr =
  | Literal
  | Ident
  | Select
  | Call
  | ListExpr
  | Comprehension

interface Node {
  readonly pos: number
  /** First code point of the node's source text, including enclosing parentheses. */
  start: number
  /** Code point after the node's source text, including enclosing parentheses. */
  end: number
  /** Set by the type checker. */
  type?: CelType
}

export interface Literal extends Node {
  readonly kind: 'literal'
  readonly literal: 'int' | 'uint' | 'double' | 'string' | 'bool' | 'null' | 'bytes'
  readonly value: bigint | number | string | boolean | null
}

export interface Ident extends Node {
  readonly kind: 'ident'
  readonly name: string
  /** Written with a leading dot: refers to a declared variable, never an iteration variable. */
  readonly global: boolean
}

export interface Select extends Node {
  readonly kind: 'select'
  readonly operand: Expr
  readonly field: string
  /** Offset of the field name, in code points. */
  readonly fieldPos: number
  /** The selection inside has(): tests presence instead of reading the field. */
  readonly test: boolean
}

export interface Call extends Node {
  readonly kind: 'call'
  /** Operators use CEL's internal names: _+_, _&&_, !_, -_, _?_:_, _[_], @in. */
  readonly fn: string
  readonly target: Expr | undefined
  readonly args: readonly Expr[]
  /** Set by the type checker: the overloads that may apply. */
  overloads?: readonly import('./functions.js').Overload[]
}

export interface ListExpr extends Node {
  readonly kind: 'list'
  readonly items: readonly Expr[]
}

/** A list macro: all, exists, exists_one, map (two or three arguments) or filter. */
export interface Comprehension extends Node {
  readonly kind: 'comprehension'
  readonly macro: 'all' | 'exists' | 'exists_one' | 'map' | 'filter'
  readonly range: Expr
  readonly variable: string
  /** Offset of the iteration variable's name, in code points. */
  readonly variablePos: number
  /** The predicate of all/exists/exists_one/filter, or the filter of a three-argument map. */
  readonly predicate: Expr | undefined
  /** The transform of map. */
  readonly transform: Expr | undefined
}
