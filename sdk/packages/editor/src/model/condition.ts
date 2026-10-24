// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The condition builder's model: a CEL condition as nested AND/OR groups of field tests and
// exists/all blocks. CEL that the model cannot show is kept as text, with the reason, and is
// never rewritten. Every node keeps the text it was read from; printing an unedited node
// gives that text back exactly, and printing an edited one gives canonical CEL.

import { analyzeExpression, codePointIndex, type Expr } from '@rulestone/engine'
import type { TypeSpec } from './table.js'

export type ValueNode = (
  | { readonly kind: 'int'; readonly text: string }
  /** form: how the literal is written, decimal('1.5') or decimal(1). */
  | { readonly kind: 'decimal'; readonly text: string; readonly form: 'string' | 'int' }
  | { readonly kind: 'string'; readonly text: string }
  | { readonly kind: 'bool'; readonly value: boolean }
  /** form: how the amount is written: money('12.50', …), money(2000, …) or money(decimal('12.5'), …). */
  | { readonly kind: 'money'; readonly amount: string; readonly currency: string; readonly form: 'string' | 'int' | 'decimal' }
  | { readonly kind: 'date'; readonly text: string }
  | { readonly kind: 'list'; readonly items: readonly ValueNode[] }
  /** Any other expression, such as another field or a calculation. */
  | { readonly kind: 'expr'; readonly text: string }
) & { readonly source?: string }

export type Operator =
  | '==' | '!=' | '<' | '<=' | '>' | '>='
  | 'in' | 'notIn'
  /** value in listField */
  | 'includes' | 'notIncludes'
  | 'startsWith' | 'endsWith' | 'contains' | 'matches'
  | 'has' | 'notHas'
  /** A bool field on its own, or negated. */
  | 'isTrue' | 'isFalse'
  /** The condition `true`: the rule always matches. */
  | 'always'

export const COMPARISONS = ['==', '!=', '<', '<=', '>', '>='] as const
const STRING_FUNCTIONS = ['startsWith', 'endsWith', 'contains', 'matches'] as const

/** Why a part of a condition is shown as CEL text. */
export type RawReason = 'syntax' | 'calculation' | 'valueFirst' | 'listFunction' | 'conditional' | 'other'

interface Base {
  readonly id: string
  /** The text this node was read from; absent once the node, or a node inside it, is edited. */
  readonly source?: string
}

export interface Group extends Base {
  readonly kind: 'group'
  readonly op: 'and' | 'or'
  /** Written as !( … ). */
  readonly negated: boolean
  /** Written in parentheses that precedence does not need; kept so that the CEL stays the same. */
  readonly parens: boolean
  readonly children: readonly CondNode[]
}

export interface Leaf extends Base {
  readonly kind: 'leaf'
  /** The field tested, as CEL: applicant.age, or a.balance inside a block over a list. */
  readonly field: string
  readonly op: Operator
  readonly value?: ValueNode
}

/** list.exists(x, …), list.all(x, …) or list.exists_one(x, …), with a group of conditions on x. */
export interface Quantifier extends Base {
  readonly kind: 'quantifier'
  readonly field: string
  readonly macro: 'exists' | 'all' | 'exists_one'
  readonly variable: string
  readonly body: Group
}

/** CEL the builder does not represent, shown and kept as text. */
export interface Raw extends Base {
  readonly kind: 'raw'
  readonly text: string
  readonly reason: RawReason
}

export type CondNode = Group | Leaf | Quantifier | Raw

let sequence = 0
/** A new node id, unique in this page. */
export function nodeId(): string {
  return `c${++sequence}`
}

// --- Reading CEL -------------------------------------------------------------------------------

/**
 * Reads a condition. The result is always a group; a condition that does not parse is a group
 * holding one raw node with reason 'syntax'.
 */
export function parseCondition(text: string): Group {
  const { ast } = analyzeExpression(text, { variables: {} })
  if (!ast) {
    return { kind: 'group', id: nodeId(), op: 'and', negated: false, parens: false, source: text, children: [{ kind: 'raw', id: nodeId(), text, reason: 'syntax', source: text }] }
  }
  const reader = new Reader(text)
  const node = reader.node(ast)
  if (node.kind === 'group') return { ...node, source: text }
  return { kind: 'group', id: nodeId(), op: 'and', negated: false, parens: false, source: text, children: [node] }
}

/** The dotted path of a field read, e.g. applicant.address.city, or undefined. */
function fieldPath(e: Expr): string | undefined {
  if (e.kind === 'ident' && !e.global) return e.name
  if (e.kind === 'select' && !e.test) {
    const operand = fieldPath(e.operand)
    return operand === undefined ? undefined : `${operand}.${e.field}`
  }
  return undefined
}

/** Whether the node is written inside parentheses of its own. */
function parenthesized(e: Expr): boolean {
  if (e.kind !== 'call' || e.target !== undefined) return false
  const first = e.args[0]
  const last = e.args.at(-1)
  if (!first || !last) return false
  if (e.fn === '!_' || e.fn === '-_') return e.start < e.pos || e.end > last.end
  if (e.fn === '_[_]') return false
  return e.start < first.start || e.end > last.end
}

const RELATIONS: Record<string, Operator> = { '_==_': '==', '_!=_': '!=', '_<_': '<', '_<=_': '<=', '_>_': '>', '_>=_': '>=' }

class Reader {
  constructor(private readonly text: string) {}

  src(e: Expr): string {
    return this.text.slice(codePointIndex(this.text, e.start), codePointIndex(this.text, e.end))
  }

  raw(e: Expr, reason: RawReason): Raw {
    const text = this.src(e)
    return { kind: 'raw', id: nodeId(), text, reason, source: text }
  }

  node(e: Expr): CondNode {
    const source = this.src(e)
    if (e.kind === 'call' && e.target === undefined && (e.fn === '_&&_' || e.fn === '_||_')) return this.group(e, false, source)
    if (e.kind === 'literal' && e.literal === 'bool' && e.value === true) {
      return { kind: 'leaf', id: nodeId(), field: '', op: 'always', source }
    }
    if (e.kind === 'select' && e.test) {
      const field = fieldPath({ ...e, test: false })
      if (field !== undefined) return { kind: 'leaf', id: nodeId(), field, op: 'has', source }
    }
    const path = fieldPath(e)
    if (path !== undefined) return { kind: 'leaf', id: nodeId(), field: path, op: 'isTrue', source }
    if (e.kind === 'comprehension') {
      const range = fieldPath(e.range)
      if (range === undefined || e.macro === 'map' || e.macro === 'filter' || !e.predicate) return this.raw(e, 'listFunction')
      const inner = this.node(e.predicate)
      const body: Group =
        inner.kind === 'group' && !inner.negated
          ? inner
          : { kind: 'group', id: nodeId(), op: 'and', negated: false, parens: false, children: [inner], source: inner.source! }
      return { kind: 'quantifier', id: nodeId(), field: range, macro: e.macro, variable: e.variable, body, source }
    }
    if (e.kind !== 'call') return this.raw(e, 'other')
    if (e.target === undefined && e.fn === '!_') return this.negation(e, source)
    if (e.target === undefined && e.fn === '_?_:_') return this.raw(e, 'conditional')
    const leaf = this.test(e)
    if (leaf) return { ...leaf, source }
    if (e.target === undefined && (e.fn in RELATIONS || e.fn === '@in')) {
      const right = e.args[1]
      return this.raw(e, right && fieldPath(right) !== undefined && fieldPath(e.args[0]!) === undefined ? 'valueFirst' : 'calculation')
    }
    return this.raw(e, 'other')
  }

  /** A comparison, membership or string test on a field, or undefined. */
  test(e: Expr & { kind: 'call' }): Omit<Leaf, 'source'> | undefined {
    if (e.target === undefined && e.fn in RELATIONS) {
      const field = fieldPath(e.args[0]!)
      if (field === undefined) return undefined
      return { kind: 'leaf', id: nodeId(), field, op: RELATIONS[e.fn]!, value: this.value(e.args[1]!) }
    }
    if (e.target === undefined && e.fn === '@in') {
      const field = fieldPath(e.args[0]!)
      if (field !== undefined) return { kind: 'leaf', id: nodeId(), field, op: 'in', value: this.value(e.args[1]!) }
      const list = fieldPath(e.args[1]!)
      const value = this.value(e.args[0]!)
      if (list !== undefined && value.kind !== 'expr') return { kind: 'leaf', id: nodeId(), field: list, op: 'includes', value }
      return undefined
    }
    if (e.target !== undefined && e.args.length === 1 && (STRING_FUNCTIONS as readonly string[]).includes(e.fn)) {
      const field = fieldPath(e.target)
      if (field === undefined) return undefined
      return { kind: 'leaf', id: nodeId(), field, op: e.fn as Operator, value: this.value(e.args[0]!) }
    }
    return undefined
  }

  negation(e: Expr & { kind: 'call' }, source: string): CondNode {
    const inner = e.args[0]!
    if (inner.kind === 'select' && inner.test) {
      const field = fieldPath({ ...inner, test: false })
      if (field !== undefined && !parenthesized(e)) return { kind: 'leaf', id: nodeId(), field, op: 'notHas', source }
    }
    const path = fieldPath(inner)
    if (path !== undefined && !parenthesized(e)) return { kind: 'leaf', id: nodeId(), field: path, op: 'isFalse', source }
    if (inner.kind === 'call' && inner.target === undefined && inner.fn === '@in' && !parenthesized(e)) {
      const leaf = this.test(inner)
      if (leaf) return { ...leaf, op: leaf.op === 'in' ? 'notIn' : 'notIncludes', source }
    }
    if (inner.kind === 'call' && inner.target === undefined && (inner.fn === '_&&_' || inner.fn === '_||_') && !parenthesized(e)) {
      return this.group(inner, true, source)
    }
    const child = this.node(inner)
    if (child.kind === 'raw' || parenthesized(e)) return this.raw(e, child.kind === 'raw' ? child.reason : 'other')
    return { kind: 'group', id: nodeId(), op: 'and', negated: true, parens: false, children: [child], source }
  }

  group(e: Expr & { kind: 'call' }, negated: boolean, source: string): Group {
    const children: CondNode[] = []
    const collect = (node: Expr) => {
      for (const arg of (node as Expr & { kind: 'call' }).args) {
        if (arg.kind === 'call' && arg.target === undefined && arg.fn === e.fn && !parenthesized(arg)) collect(arg)
        else children.push(this.node(arg))
      }
    }
    collect(e)
    return { kind: 'group', id: nodeId(), op: e.fn === '_&&_' ? 'and' : 'or', negated, parens: !negated && parenthesized(e), children, source }
  }

  value(e: Expr): ValueNode {
    const source = this.src(e)
    const expr: ValueNode = { kind: 'expr', text: source, source }
    if (e.kind === 'literal') {
      if (e.literal === 'int') return { kind: 'int', text: String(e.value), source }
      if (e.literal === 'string') return { kind: 'string', text: e.value as string, source }
      if (e.literal === 'bool') return { kind: 'bool', value: e.value as boolean, source }
      return expr
    }
    if (e.kind === 'list') {
      const items = e.items.map((item) => this.value(item))
      return items.every((i) => i.kind !== 'expr' && i.kind !== 'list') ? { kind: 'list', items, source } : expr
    }
    if (e.kind !== 'call' || e.target !== undefined) return expr
    const [a, b] = e.args
    if (e.fn === 'decimal' && e.args.length === 1 && a!.kind === 'literal') {
      if (a.literal === 'string') return { kind: 'decimal', text: a.value as string, form: 'string', source }
      if (a.literal === 'int') return { kind: 'decimal', text: String(a.value), form: 'int', source }
    }
    if (e.fn === 'date' && e.args.length === 1 && a!.kind === 'literal' && a.literal === 'string') {
      return { kind: 'date', text: a.value as string, source }
    }
    if (e.fn === 'money' && e.args.length === 2 && b!.kind === 'literal' && b.literal === 'string') {
      const currency = b.value as string
      if (a!.kind === 'literal' && a.literal === 'string') return { kind: 'money', amount: a.value as string, currency, form: 'string', source }
      if (a!.kind === 'literal' && a.literal === 'int') return { kind: 'money', amount: String(a.value), currency, form: 'int', source }
      if (a!.kind === 'call' && a.fn === 'decimal' && !a.target && a.args.length === 1 && a.args[0]!.kind === 'literal' && a.args[0]!.literal === 'string') {
        return { kind: 'money', amount: a.args[0]!.value as string, currency, form: 'decimal', source }
      }
    }
    return expr
  }
}

// --- Writing CEL -------------------------------------------------------------------------------

export interface PrintOptions {
  /** Use the text each unedited node was read from. Default true. */
  readonly preserve?: boolean
}

/** A CEL string literal in single quotes. */
export function celString(text: string): string {
  let out = "'"
  for (const ch of text) {
    if (ch === '\\') out += '\\\\'
    else if (ch === "'") out += "\\'"
    else if (ch === '\n') out += '\\n'
    else if (ch === '\r') out += '\\r'
    else if (ch === '\t') out += '\\t'
    else if (ch < ' ') out += '\\x' + ch.charCodeAt(0).toString(16).padStart(2, '0')
    else out += ch
  }
  return out + "'"
}

/** Binding strength of an expression's top-level operator: 0 for ?:, 7 for a field or call. -1 if it does not parse. */
export function precedence(text: string): number {
  const { ast } = analyzeExpression(text, { variables: {} })
  if (!ast) return -1
  if (ast.kind !== 'call' || ast.target !== undefined || parenthesized(ast)) return 7
  switch (ast.fn) {
    case '_?_:_':
      return 0
    case '_||_':
      return 1
    case '_&&_':
      return 2
    case '_==_': case '_!=_': case '_<_': case '_<=_': case '_>_': case '_>=_': case '@in':
      return 3
    case '_+_': case '_-_':
      return 4
    case '_*_': case '_/_': case '_%_':
      return 5
    case '!_': case '-_':
      return 6
    default:
      return 7
  }
}

function wrap(text: string, minimum: number): string {
  return precedence(text) < minimum ? `(${text})` : text
}

export function printValue(value: ValueNode, options: PrintOptions = {}): string {
  if ((options.preserve ?? true) && value.source !== undefined) return value.source
  switch (value.kind) {
    case 'int':
      return value.text
    case 'decimal':
      return value.form === 'int' ? `decimal(${value.text})` : `decimal(${celString(value.text)})`
    case 'string':
      return celString(value.text)
    case 'bool':
      return value.value ? 'true' : 'false'
    case 'money': {
      const amount = value.form === 'int' ? value.amount : value.form === 'decimal' ? `decimal(${celString(value.amount)})` : celString(value.amount)
      return `money(${amount}, ${celString(value.currency)})`
    }
    case 'date':
      return `date(${celString(value.text)})`
    case 'list':
      return `[${value.items.map((i) => printValue(i, options)).join(', ')}]`
    case 'expr':
      return value.text
  }
}

/** The condition as CEL. */
export function printCondition(node: CondNode, options: PrintOptions = {}): string {
  const preserve = options.preserve ?? true
  if (preserve && node.source !== undefined) return node.source
  switch (node.kind) {
    case 'raw':
      return node.text
    case 'quantifier':
      return `${node.field}.${node.macro}(${node.variable}, ${printCondition(node.body, options)})`
    case 'leaf':
      return printLeaf(node, options)
    case 'group': {
      if (node.children.length === 0) return node.negated ? (node.op === 'and' ? 'false' : 'true') : node.op === 'and' ? 'true' : 'false'
      const minimum = node.op === 'and' ? 2 : 1
      const inner = node.children.map((c) => wrap(printCondition(c, options), minimum)).join(node.op === 'and' ? ' && ' : ' || ')
      if (node.negated) return node.children.length === 1 && precedence(inner) >= 6 ? `!${inner}` : `!(${inner})`
      return node.parens ? `(${inner})` : inner
    }
  }
}

function printLeaf(leaf: Leaf, options: PrintOptions): string {
  const value = leaf.value ? printValue(leaf.value, options) : "''"
  switch (leaf.op) {
    case 'always':
      return 'true'
    case 'isTrue':
      return leaf.field
    case 'isFalse':
      return `!${leaf.field}`
    case 'has':
      return `has(${leaf.field})`
    case 'notHas':
      return `!has(${leaf.field})`
    case 'in':
      return `${leaf.field} in ${wrap(value, 4)}`
    case 'notIn':
      return `!(${leaf.field} in ${wrap(value, 4)})`
    case 'includes':
      return `${wrap(value, 4)} in ${leaf.field}`
    case 'notIncludes':
      return `!(${wrap(value, 4)} in ${leaf.field})`
    case 'startsWith':
    case 'endsWith':
    case 'contains':
    case 'matches':
      return `${leaf.field}.${leaf.op}(${value})`
    default:
      return `${leaf.field} ${leaf.op} ${wrap(value, 4)}`
  }
}

// --- Editing -----------------------------------------------------------------------------------

/** Removes the read-from text of a node, so that it is printed from its parts. */
function edited<T extends CondNode>(node: T): T {
  if (node.source === undefined) return node
  const { source: _source, ...rest } = node
  return rest as unknown as T
}

function childrenOf(node: CondNode): readonly CondNode[] {
  if (node.kind === 'group') return node.children
  if (node.kind === 'quantifier') return [node.body]
  return []
}

/** The node with an id, or undefined. */
export function findNode(root: CondNode, id: string): CondNode | undefined {
  if (root.id === id) return root
  for (const child of childrenOf(root)) {
    const found = findNode(child, id)
    if (found) return found
  }
  return undefined
}

/** The group that holds the node with an id, and its index there. */
export function findParent(root: CondNode, id: string): { parent: Group; index: number } | undefined {
  if (root.kind === 'group') {
    const index = root.children.findIndex((c) => c.id === id)
    if (index >= 0) return { parent: root, index }
  }
  for (const child of childrenOf(root)) {
    const found = findParent(child, id)
    if (found) return found
  }
  return undefined
}

/** The ids from the root down to the node, inclusive. */
export function pathTo(root: CondNode, id: string): string[] | undefined {
  if (root.id === id) return [root.id]
  for (const child of childrenOf(root)) {
    const path = pathTo(child, id)
    if (path) return [root.id, ...path]
  }
  return undefined
}

/** Rebuilds the tree with the node replaced; the node and its ancestors lose their read-from text. */
export function updateNode(root: Group, id: string, update: (node: CondNode) => CondNode): Group {
  const go = (node: CondNode): CondNode => {
    if (node.id === id) return edited(update(node))
    if (node.kind === 'group') {
      if (!node.children.some((c) => contains(c, id))) return node
      return edited({ ...node, children: node.children.map(go) })
    }
    if (node.kind === 'quantifier' && contains(node.body, id)) return edited({ ...node, body: go(node.body) as Group })
    return node
  }
  return go(root) as Group
}

function contains(node: CondNode, id: string): boolean {
  return findNode(node, id) !== undefined
}

/** Inserts a node into a group at an index (default: the end). */
export function insertNode(root: Group, groupId: string, node: CondNode, index?: number): Group {
  return updateNode(root, groupId, (group) => {
    if (group.kind !== 'group') return group
    const children = [...group.children]
    children.splice(index ?? children.length, 0, node)
    return { ...group, children }
  })
}

/** Removes a node. The root cannot be removed. */
export function removeNode(root: Group, id: string): Group {
  const found = findParent(root, id)
  if (!found) return root
  return updateNode(root, found.parent.id, (group) => ({ ...(group as Group), children: (group as Group).children.filter((c) => c.id !== id) }))
}

/**
 * Moves a node into a group at an index counted after the node is taken out. Moving a node
 * into itself or into a node inside it is refused (the tree is returned unchanged).
 */
export function moveNode(root: Group, id: string, groupId: string, index: number): Group {
  const node = findNode(root, id)
  const target = findNode(root, groupId)
  if (!node || !target || target.kind !== 'group' || node.id === root.id || contains(node, groupId)) return root
  const without = removeNode(root, id)
  const group = findNode(without, groupId) as Group
  return insertNode(without, groupId, node, Math.max(0, Math.min(group.children.length, index)))
}

/** Moves a node one place up (-1) or down (+1) in its group; unchanged at the edge. */
export function moveBy(root: Group, id: string, delta: -1 | 1): Group {
  const found = findParent(root, id)
  if (!found) return root
  const to = found.index + delta
  if (to < 0 || to >= found.parent.children.length) return root
  return moveNode(root, id, found.parent.id, to)
}

/** Moves a node into the group (or block) just above it, at its end. */
export function indent(root: Group, id: string): Group {
  const found = findParent(root, id)
  if (!found || found.index === 0) return root
  const previous = found.parent.children[found.index - 1]!
  const target = previous.kind === 'group' ? previous : previous.kind === 'quantifier' ? previous.body : undefined
  if (!target) return root
  return moveNode(root, id, target.id, target.children.length)
}

/** Moves a node out of its group, to just after that group in the enclosing one. */
export function outdent(root: Group, id: string): Group {
  const found = findParent(root, id)
  if (!found || found.parent.id === root.id) return root
  // The group may be a block's body: then the block is what sits in the enclosing group.
  let holder: CondNode = found.parent
  let outer = findParent(root, holder.id)
  if (!outer) {
    const block = findBlockOf(root, holder.id)
    if (!block) return root
    holder = block
    outer = findParent(root, block.id)
    if (!outer) return root
  }
  return moveNode(root, id, outer.parent.id, outer.index + 1)
}

function findBlockOf(root: CondNode, bodyId: string): Quantifier | undefined {
  if (root.kind === 'quantifier' && root.body.id === bodyId) return root
  for (const child of childrenOf(root)) {
    const found = findBlockOf(child, bodyId)
    if (found) return found
  }
  return undefined
}

/** Puts a node into a new group of its own, of the opposite kind to its parent. */
export function wrapInGroup(root: Group, id: string): Group {
  const found = findParent(root, id)
  if (!found) return root
  const node = found.parent.children[found.index]!
  const group: Group = { kind: 'group', id: nodeId(), op: found.parent.op === 'and' ? 'or' : 'and', negated: false, parens: false, children: [node] }
  return updateNode(root, found.parent.id, (p) => {
    const children = [...(p as Group).children]
    children[found.index] = group
    return { ...(p as Group), children }
  })
}

export function newGroup(op: 'and' | 'or', children: CondNode[] = []): Group {
  return { kind: 'group', id: nodeId(), op, negated: false, parens: false, children }
}

export function newLeaf(field: string, op: Operator, value?: ValueNode): Leaf {
  return value === undefined ? { kind: 'leaf', id: nodeId(), field, op } : { kind: 'leaf', id: nodeId(), field, op, value }
}

export function newQuantifier(field: string, macro: Quantifier['macro'], variable: string): Quantifier {
  return { kind: 'quantifier', id: nodeId(), field, macro, variable, body: newGroup('and') }
}

export function newRaw(text: string): Raw {
  return { kind: 'raw', id: nodeId(), text, reason: 'other' }
}

// --- Fields and operators ----------------------------------------------------------------------

/** The spec of a field expression such as applicant.address.city, given the variables in scope. */
export function fieldSpec(scope: ReadonlyMap<string, TypeSpec>, field: string): TypeSpec | undefined {
  const [first, ...rest] = field.split('.')
  let spec = scope.get(first ?? '')
  for (const name of rest) {
    if (spec === undefined || typeof spec === 'string' || Array.isArray(spec)) return undefined
    spec = spec[name]
  }
  return spec
}

/** The operators that apply to a field of a type. Struct fields (optional ones) also get has() tests. */
export function operatorsFor(spec: TypeSpec | undefined, optional: boolean): Operator[] {
  const presence: Operator[] = optional ? ['has', 'notHas'] : []
  if (spec === undefined) return [...COMPARISONS, ...presence]
  if (Array.isArray(spec)) return [...(typeof spec[0] === 'string' ? (['includes', 'notIncludes'] as Operator[]) : []), ...presence]
  if (typeof spec === 'object') return presence
  switch (spec) {
    case 'bool':
      return ['isTrue', 'isFalse', '==', '!=', ...presence]
    case 'string':
      return ['==', '!=', 'in', 'notIn', 'startsWith', 'endsWith', 'contains', 'matches', ...presence]
    case 'int':
    case 'decimal':
      return [...COMPARISONS, 'in', 'notIn', ...presence]
    default:
      return [...COMPARISONS, ...presence]
  }
}

/** Whether an operator takes a value. */
export function takesValue(op: Operator): boolean {
  return !['has', 'notHas', 'isTrue', 'isFalse', 'always'].includes(op)
}

/** A default value for a field type and operator, as the builder offers it. */
export function defaultValue(spec: TypeSpec | undefined, op: Operator): ValueNode | undefined {
  if (!takesValue(op)) return undefined
  const element = Array.isArray(spec) ? spec[0] : spec
  const scalar = (): ValueNode => {
    switch (element) {
      case 'int':
        return { kind: 'int', text: '0' }
      case 'decimal':
        return op === '==' || op === '!=' || op === 'in' || op === 'notIn' ? { kind: 'decimal', text: '0', form: 'string' } : { kind: 'int', text: '0' }
      case 'money':
        return { kind: 'money', amount: '0', currency: 'VND', form: 'int' }
      case 'date':
        return { kind: 'date', text: '2026-01-01' }
      case 'bool':
        return { kind: 'bool', value: true }
      default:
        return { kind: 'string', text: '' }
    }
  }
  if (op === 'in' || op === 'notIn') return { kind: 'list', items: [scalar()] }
  return scalar()
}

/**
 * A number typed into the builder for a field of a type: an int literal for int fields, and for
 * decimal fields compared by order with a whole number (decimals compare with ints); otherwise
 * decimal('…'), which equality needs.
 */
export function numberValue(spec: TypeSpec | undefined, op: Operator, text: string): ValueNode {
  const whole = /^-?[0-9]+$/.test(text)
  if (spec === 'int') return { kind: 'int', text }
  if (whole && ['<', '<=', '>', '>='].includes(op)) return { kind: 'int', text }
  return { kind: 'decimal', text, form: 'string' }
}
