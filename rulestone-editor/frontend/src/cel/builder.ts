// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The condition builder: a `when` expression as a list of clauses joined by AND or OR, each
// clause "input path, operator, value". Converts both ways between that model and CEL.
// Expressions with another shape (nested AND/OR, arithmetic on the left side, list macros, ...)
// are not representable: parseCondition returns a reason, and the editor shows them read-only.

import type { TypeSpec } from '../types'
import { type InputPath, listElement } from '../model/schema'
import { codecFor } from './literals'
import { type Node, isParenthesized, parse, pathOf } from './parser'

export type Operator =
  | '=='
  | '!='
  | '<'
  | '<='
  | '>'
  | '>='
  | 'in'
  | 'not in'
  | 'contains'
  | 'not contains'
  | 'is true'
  | 'is false'
  | 'is set'
  | 'not set'

/** The right-hand side of a clause. */
export type ClauseValue =
  | { kind: 'literal'; text: string }
  | { kind: 'list'; items: string[] }
  | { kind: 'path'; path: string }
  | { kind: 'expr'; cel: string }

export interface Clause {
  path: string
  op: Operator
  value?: ClauseValue
}

export interface Condition {
  combinator: 'and' | 'or'
  clauses: Clause[]
}

export type ParseResult = { ok: true; condition: Condition } | { ok: false; reason: string }

const COMPARISONS: Operator[] = ['==', '!=', '<', '<=', '>', '>=']
const ORDERED = new Set(['int', 'decimal', 'money', 'date'])

/** The operators offered for an input path, by its type. */
export function operatorsFor(input: InputPath): Operator[] {
  const ops: Operator[] = []
  const t = input.type
  if (t === 'bool') ops.push('is true', 'is false', '==', '!=')
  else if (typeof t === 'string' && ORDERED.has(t)) ops.push(...COMPARISONS, 'in', 'not in')
  else if (t === 'string') ops.push('==', '!=', 'in', 'not in')
  else if (Array.isArray(t)) ops.push('contains', 'not contains')
  else if (typeof t === 'string') ops.push(...COMPARISONS, 'in', 'not in') // types added later: values as CEL
  if (!input.topLevel) ops.push('is set', 'not set')
  return ops
}

/** Whether the operator takes a value, and of which shape. */
export function valueShape(op: Operator): 'none' | 'single' | 'list' {
  if (op === 'is true' || op === 'is false' || op === 'is set' || op === 'not set') return 'none'
  if (op === 'in' || op === 'not in') return 'list'
  return 'single'
}

/** The type of the clause's value: the path's type, or the element type for contains. */
export function valueType(input: InputPath, op: Operator): TypeSpec | undefined {
  return op === 'contains' || op === 'not contains' ? listElement(input.type) : input.type
}

/** A sensible default clause for a path. */
export function defaultClause(input: InputPath): Clause {
  const op = operatorsFor(input)[0] ?? '=='
  const clause: Clause = { path: input.path, op }
  const shape = valueShape(op)
  if (shape === 'single') clause.value = defaultValue(valueType(input, op))
  if (shape === 'list') clause.value = { kind: 'list', items: [] }
  return clause
}

function defaultValue(type: TypeSpec | undefined): ClauseValue {
  if (!codecFor(type)) return { kind: 'expr', cel: '' }
  return { kind: 'literal', text: type === 'bool' ? 'true' : '' }
}

// ---- model -> CEL -------------------------------------------------------------------------------------------------

export class BuildError extends Error {
  constructor(
    readonly clauseIndex: number,
    readonly key: string,
  ) {
    super(`clause ${clauseIndex + 1}: ${key}`)
  }
}

/** CEL for a condition. Throws BuildError for a clause with a missing or invalid value. */
export function buildCondition(condition: Condition, paths: InputPath[]): string {
  if (condition.clauses.length === 0) return 'true'
  const parts = condition.clauses.map((clause, i) => buildClause(clause, paths, i))
  return parts.join(condition.combinator === 'and' ? ' && ' : ' || ')
}

function buildClause(clause: Clause, paths: InputPath[], index: number): string {
  const input = paths.find((p) => p.path === clause.path)
  if (!input) throw new BuildError(index, 'unknownPath')
  const p = clause.path
  switch (clause.op) {
    case 'is true':
      return p
    case 'is false':
      return `!${p}`
    case 'is set':
      return `has(${p})`
    case 'not set':
      return `!has(${p})`
    case 'in':
    case 'not in': {
      const list = buildValue(clause.value, input.type, index, true)
      return clause.op === 'in' ? `${p} in ${list}` : `!(${p} in ${list})`
    }
    case 'contains':
    case 'not contains': {
      const value = buildValue(clause.value, listElement(input.type), index, false)
      return clause.op === 'contains' ? `${value} in ${p}` : `!(${value} in ${p})`
    }
    default:
      return `${p} ${clause.op} ${buildValue(clause.value, input.type, index, false)}`
  }
}

function buildValue(value: ClauseValue | undefined, type: TypeSpec | undefined, index: number, list: boolean): string {
  if (!value) throw new BuildError(index, 'missingValue')
  switch (value.kind) {
    case 'path':
      if (!value.path) throw new BuildError(index, 'missingValue')
      return value.path
    case 'expr': {
      const cel = value.cel.trim()
      if (!cel) throw new BuildError(index, 'missingValue')
      return needsParentheses(cel) ? `(${cel})` : cel
    }
    case 'literal':
    case 'list': {
      const codec = codecFor(type)
      if (!codec) throw new BuildError(index, 'type')
      const texts = value.kind === 'list' ? value.items : [value.text]
      if (list !== (value.kind === 'list')) throw new BuildError(index, 'missingValue')
      if (list && texts.length === 0) throw new BuildError(index, 'emptyList')
      const cels = texts.map((t) => {
        const r = codec.encode(t)
        if ('error' in r) throw new BuildError(index, `invalid.${r.error}`)
        return r.cel
      })
      return list ? `[${cels.join(', ')}]` : cels[0]
    }
  }
}

/** Whether a value expression binds more loosely than a comparison and so needs parentheses. */
function needsParentheses(cel: string): boolean {
  try {
    const node = parse(cel)
    return (node.kind === 'binary' && ['&&', '||', '==', '!=', '<', '<=', '>', '>=', 'in'].includes(node.op))
      || node.kind === 'ternary'
  } catch {
    return true
  }
}

// ---- CEL -> model -------------------------------------------------------------------------------------------------

/** Reads a `when` expression into the builder model, or explains why it cannot. */
export function parseCondition(cel: string, paths: InputPath[]): ParseResult {
  let root: Node
  try {
    root = parse(cel)
  } catch {
    return { ok: false, reason: 'syntax' }
  }
  if (root.kind === 'literal' && root.type === 'bool' && root.value === true) {
    return { ok: true, condition: { combinator: 'and', clauses: [] } }
  }
  let combinator: 'and' | 'or' = 'and'
  let operands: Node[] = [root]
  if (root.kind === 'binary' && (root.op === '&&' || root.op === '||')) {
    combinator = root.op === '&&' ? 'and' : 'or'
    operands = flatten(root, root.op)
  }
  const clauses: Clause[] = []
  for (const operand of operands) {
    const clause = parseClause(operand, cel, paths)
    if (!clause) return { ok: false, reason: 'shape' }
    clauses.push(clause)
  }
  return { ok: true, condition: { combinator, clauses } }
}

function flatten(node: Node, op: string): Node[] {
  // (a && b) && c is the same as a && b && c
  if (node.kind === 'binary' && node.op === op) {
    return [...flatten(node.left, op), ...flatten(node.right, op)]
  }
  return [node]
}

function parseClause(node: Node, source: string, paths: InputPath[]): Clause | null {
  const known = (n: Node) => {
    const p = pathOf(n)
    return p === null ? undefined : paths.find((i) => i.path === p)
  }
  const direct = known(node)
  if (direct) return direct.type === 'bool' ? { path: direct.path, op: 'is true' } : null

  if (node.kind === 'unary' && node.op === '!') {
    const inner = node.operand
    const negated = known(inner)
    if (negated) return negated.type === 'bool' ? { path: negated.path, op: 'is false' } : null
    const has = hasPath(inner, paths)
    if (has) return { path: has.path, op: 'not set' }
    if (inner.kind === 'binary' && inner.op === 'in') {
      const clause = membership(inner, source, paths)
      if (!clause) return null
      return { ...clause, op: clause.op === 'in' ? 'not in' : 'not contains' }
    }
    return null
  }

  const has = hasPath(node, paths)
  if (has) return { path: has.path, op: 'is set' }

  if (node.kind === 'binary' && node.op === 'in') return membership(node, source, paths)

  if (node.kind === 'binary' && (COMPARISONS as string[]).includes(node.op)) {
    const left = known(node.left)
    if (!left || Array.isArray(left.type) || (typeof left.type === 'object')) return null
    if (left.type === 'bool' && node.op !== '==' && node.op !== '!=') return null
    return { path: left.path, op: node.op as Operator, value: single(node.right, left.type, source, paths) }
  }
  return null
}

function hasPath(node: Node, paths: InputPath[]): InputPath | undefined {
  if (node.kind !== 'call' || node.target !== null || node.fn !== 'has' || node.args.length !== 1) return undefined
  const arg = node.args[0]
  if (arg.kind !== 'select') return undefined
  const p = pathOf(arg)
  return paths.find((i) => i.path === p && !i.topLevel)
}

/** `path in [..]` / `path in otherList` (in) or `value in listPath` (contains). */
function membership(node: Node & { kind: 'binary' }, source: string, paths: InputPath[]): Clause | null {
  const leftPath = pathOf(node.left)
  const left = leftPath === null ? undefined : paths.find((i) => i.path === leftPath)
  const rightPath = pathOf(node.right)
  const right = rightPath === null ? undefined : paths.find((i) => i.path === rightPath)
  if (!left && right && Array.isArray(right.type)) {
    return { path: right.path, op: 'contains', value: single(node.left, listElement(right.type), source, paths) }
  }
  if (!left || typeof left.type !== 'string') return null
  if (node.right.kind === 'list') {
    const codec = codecFor(left.type)
    if (codec) {
      const items = node.right.items.map((item) => codec.decode(item))
      if (items.every((i) => i !== undefined)) return { path: left.path, op: 'in', value: { kind: 'list', items: items as string[] } }
    }
    return { path: left.path, op: 'in', value: { kind: 'expr', cel: slice(source, node.right) } }
  }
  if (right) return { path: left.path, op: 'in', value: { kind: 'path', path: right.path } }
  return { path: left.path, op: 'in', value: { kind: 'expr', cel: slice(source, node.right) } }
}

function single(node: Node, type: TypeSpec | undefined, source: string, paths: InputPath[]): ClauseValue {
  const p = pathOf(node)
  if (p !== null && paths.some((i) => i.path === p)) return { kind: 'path', path: p }
  const codec = codecFor(type)
  const text = codec?.decode(node)
  if (text !== undefined) return { kind: 'literal', text }
  return { kind: 'expr', cel: slice(source, node) }
}

function slice(source: string, node: Node): string {
  let text = source.slice(node.start, node.end).trim()
  if (isParenthesized(node) && text.startsWith('(') && text.endsWith(')')) text = text.slice(1, -1).trim()
  return text
}
