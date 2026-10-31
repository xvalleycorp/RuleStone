// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Type checker: resolves identifiers, fields and overloads, and infers the type of every node,
// reporting problems with cel-java's messages.

import type { Call, Comprehension, Expr } from './ast.js'
import { UNSUPPORTED_FUNCTIONS, overloadsOf, type Overload } from './functions.js'
import type { Issue } from './parser.js'
import {
  BOOL, BYTES, DOUBLE, DYN, ERROR, INT, NULL, STRING, UINT, Substitution, formatType, list, type CelType,
} from './types.js'

export interface CheckResult {
  readonly type: CelType
  readonly issues: readonly Issue[]
}

/** Type-checks a parsed expression against the declared variables. Annotates the nodes. */
export function check(root: Expr, variables: ReadonlyMap<string, CelType>): CheckResult {
  const checker = new Checker(variables)
  checker.visit(root, [])
  const type = checker.subst.apply(root.type!, true)
  finalize(root, checker.subst)
  const issues = [...checker.issues].sort((a, b) => a.pos - b.pos)
  return { type, issues }
}

function finalize(node: Expr, subst: Substitution): void {
  node.type = subst.apply(node.type!, true)
  switch (node.kind) {
    case 'select':
      finalize(node.operand, subst)
      break
    case 'call':
      if (node.target) finalize(node.target, subst)
      node.args.forEach((a) => finalize(a, subst))
      break
    case 'list':
      node.items.forEach((a) => finalize(a, subst))
      break
    case 'comprehension':
      finalize(node.range, subst)
      if (node.predicate) finalize(node.predicate, subst)
      if (node.transform) finalize(node.transform, subst)
      break
  }
}

type Scope = readonly { readonly name: string; readonly type: CelType }[]

class Checker {
  readonly subst = new Substitution()
  readonly issues: Issue[] = []
  private nextVar = 0

  constructor(private readonly variables: ReadonlyMap<string, CelType>) {}

  private fresh(): CelType {
    return { k: 'var', id: this.nextVar++ }
  }

  private report(pos: number, message: string): CelType {
    this.issues.push({ pos, message })
    return ERROR
  }

  visit(node: Expr, scope: Scope): CelType {
    const type = this.typeOf(node, scope)
    node.type = type
    return type
  }

  private resolved(t: CelType): CelType {
    return this.subst.resolve(t)
  }

  private typeOf(node: Expr, scope: Scope): CelType {
    switch (node.kind) {
      case 'literal':
        switch (node.literal) {
          case 'int':
            return INT
          case 'uint':
            return UINT
          case 'double':
            return DOUBLE
          case 'string':
            return STRING
          case 'bool':
            return BOOL
          case 'null':
            return NULL
          case 'bytes':
            this.report(node.pos, 'bytes literals are not supported by the browser engine')
            return BYTES
        }
        break
      case 'ident': {
        if (!node.global) {
          for (let k = scope.length - 1; k >= 0; k--) if (scope[k]!.name === node.name) return scope[k]!.type
        }
        const type = this.variables.get(node.name)
        if (type) return type
        return this.report(node.pos, `undeclared reference to '${node.name}' (in container '')`)
      }
      case 'select': {
        const operand = this.resolved(this.visit(node.operand, scope))
        switch (operand.k) {
          case 'error':
            return ERROR
          case 'dyn':
          case 'var':
            return node.test ? BOOL : DYN
          case 'struct': {
            const field = operand.fields.get(node.field)
            if (!field) return this.report(node.pos, `undefined field '${node.field}'`)
            return node.test ? BOOL : field
          }
          default:
            return this.report(node.pos, `type '${formatType(this.subst.apply(operand))}' does not support field selection`)
        }
      }
      case 'list': {
        let elem = this.fresh()
        let mixed = false
        for (const item of node.items) {
          const t = this.visit(item, scope)
          if (mixed) continue
          const trial = this.subst.copy()
          if (trial.unify(elem, t)) this.commit(trial)
          else {
            mixed = true
            elem = DYN
          }
        }
        return list(elem)
      }
      case 'comprehension':
        return this.comprehension(node, scope)
      case 'call':
        return this.call(node, scope)
    }
    return ERROR
  }

  private commit(trial: Substitution): void {
    this.subst.bindings.clear()
    trial.bindings.forEach((v, k) => this.subst.bindings.set(k, v))
  }

  /** The element type of a comprehension's range; reports a range that is not a list. */
  private rangeElement(rangeNode: Expr, scope: Scope): CelType {
    const range = this.resolved(this.visit(rangeNode, scope))
    switch (range.k) {
      case 'list':
        return range.elem
      case 'dyn':
      case 'error':
      case 'var':
        return DYN
      default:
        this.report(
          rangeNode.pos,
          `expression of type '${formatType(this.subst.apply(range))}' cannot be range of a comprehension (must be list, map, or dynamic)`,
        )
        return ERROR
    }
  }

  /**
   * An aggregate function, checked as cel-java checks its expansion: the target as the range of
   * a comprehension that collects the elements, then the function called on the collected list.
   */
  private aggregate(node: Call, scope: Scope): CelType {
    const elem = this.rangeElement(node.target!, scope)
    const argTypes = node.args.map((a) => this.visit(a, scope))
    if (elem.k === 'error') return ERROR
    const candidates = overloadsOf(node.fn)!.filter((o) => o.member)
    return this.resolveOverload(node, node.fn, true, [list(elem), ...argTypes], candidates)
  }

  private comprehension(node: Comprehension, scope: Scope): CelType {
    const elem = this.rangeElement(node.range, scope)
    const inner: Scope = [...scope, { name: node.variable, type: elem }]
    const requireBool = (e: Expr | undefined, op: string, describe: (t: CelType) => string): void => {
      if (!e) return
      const t = this.visit(e, inner)
      if (this.resolved(t).k === 'error') return
      const trial = this.subst.copy()
      if (trial.unify(BOOL, t)) this.commit(trial)
      else this.report(node.pos, `found no matching overload for '${op}' applied to '${describe(t)}'`)
    }
    switch (node.macro) {
      case 'all':
        requireBool(node.predicate, '_&&_', (t) => `(bool, ${formatType(this.subst.apply(t))})`)
        return BOOL
      case 'exists':
        requireBool(node.predicate, '_||_', (t) => `(bool, ${formatType(this.subst.apply(t))})`)
        return BOOL
      case 'exists_one':
        requireBool(node.predicate, '_?_:_', (t) => `(${formatType(this.subst.apply(t))}, int, int)`)
        return BOOL
      case 'filter':
        requireBool(node.predicate, '_?_:_', (t) => `(${formatType(this.subst.apply(t))}, list(${formatType(this.subst.apply(elem))}), list(${formatType(this.subst.apply(elem))}))`)
        return list(elem)
      case 'map': {
        const t = this.visit(node.transform!, inner)
        requireBool(node.predicate, '_?_:_', (c) => `(${formatType(this.subst.apply(c))}, list(${formatType(this.subst.apply(t))}), list(${formatType(this.subst.apply(t))}))`)
        return list(t)
      }
    }
  }

  private call(node: Call, scope: Scope): CelType {
    const fn = node.fn
    if (fn === '_&&_' || fn === '_||_') {
      const types = node.args.map((a) => this.visit(a, scope))
      return this.resolveOverload(node, fn, false, types, [{ params: [BOOL, BOOL], result: BOOL }])
    }
    if (fn === '_?_:_') {
      const types = node.args.map((a) => this.visit(a, scope))
      const A: CelType = { k: 'var', id: -1 }
      return this.resolveOverload(node, fn, false, types, [{ params: [BOOL, A, A], result: A }])
    }
    if (node.aggregate) return this.aggregate(node, scope)
    const target = node.target ? this.visit(node.target, scope) : undefined
    const argTypes = node.args.map((a) => this.visit(a, scope))
    if (UNSUPPORTED_FUNCTIONS.has(fn)) return this.report(node.pos, `function '${fn}' is not supported by the browser engine`)
    const overloads = overloadsOf(fn)
    if (!overloads) return this.report(node.pos, `undeclared reference to '${fn}' (in container '')`)
    const isMember = node.target !== undefined
    const candidates = overloads.filter((o) => o.member === isMember)
    const types = isMember ? [target!, ...argTypes] : argTypes
    const result = this.resolveOverload(node, fn, isMember, types, candidates)
    return result
  }

  private resolveOverload(
    node: Call,
    fn: string,
    isMember: boolean,
    argTypes: CelType[],
    candidates: readonly Pick<Overload, 'params' | 'result'>[],
  ): CelType {
    if (argTypes.some((t) => this.resolved(t).k === 'error')) return ERROR
    const matches: { overload: Pick<Overload, 'params' | 'result'>; subst: Substitution; result: CelType }[] = []
    for (const overload of candidates) {
      if (overload.params.length !== argTypes.length) continue
      // As in cel-java, a match binds type variables for the overloads tried after it: [].sum() is an int.
      const trial = (matches.length > 0 ? matches[matches.length - 1]!.subst : this.subst).copy()
      const instance = new Map<number, CelType>()
      const inst = (t: CelType): CelType => {
        if (t.k === 'var' && t.id < 0) {
          let v = instance.get(t.id)
          if (!v) instance.set(t.id, (v = this.fresh()))
          return v
        }
        if (t.k === 'list') return list(inst(t.elem))
        return t
      }
      let ok = true
      for (let k = 0; k < argTypes.length && ok; k++) ok = trial.unify(inst(overload.params[k]!), argTypes[k]!)
      if (ok) matches.push({ overload, subst: trial, result: inst(overload.result) })
    }
    if (matches.length === 0) {
      const shown = argTypes.map((t) => formatType(this.subst.apply(t)))
      const applied = isMember ? `${shown[0]}.(${shown.slice(1).join(', ')})` : `(${shown.join(', ')})`
      return this.report(node.pos, `found no matching overload for '${fn}' applied to '${applied}'`)
    }
    if (matches.length === 1 && (matches[0]!.overload as Overload).unsupported) {
      const shown = argTypes.map((t) => formatType(this.subst.apply(t))).join(', ')
      return this.report(node.pos, `${fn}(${shown}) is not supported by the browser engine`)
    }
    node.overloads = matches.map((m) => m.overload as Overload).filter((o) => !o.unsupported)
    if (matches.length === 1) {
      this.commit(matches[0]!.subst)
      return matches[0]!.result
    }
    const first = matches[0]!.subst.apply(matches[0]!.result, true)
    const same = matches.every((m) => formatType(m.subst.apply(m.result, true)) === formatType(first))
    return same ? first : DYN
  }
}
