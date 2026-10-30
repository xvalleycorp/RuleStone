// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Evaluates a checked expression with cel-java's semantics: strict function calls, && and ||
// that absorb an error when the other side decides the result, macros evaluated as their CEL
// expansions, and one iteration budget shared by every comprehension of an evaluation.

import { LocalDate } from '../date.js'
import { Decimal } from '../decimal.js'
import { Money } from '../money.js'
import { UInt, type Value } from '../values.js'
import type { Call, Comprehension, Expr } from './ast.js'
import { EvalError, type EvalContext, type Overload } from './functions.js'
import type { CelType } from './types.js'

export interface Activation extends EvalContext {
  readonly variables: ReadonlyMap<string, Value>
  readonly maxIterations: number
}

type Frame = { readonly name: string; readonly value: Value; readonly up: Frame | undefined }

/** Evaluates a checked expression. @throws EvalError */
export function evaluate(root: Expr, activation: Activation): Value {
  return new Interpreter(activation).eval(root, undefined)
}

function nativeAt(message: string, pos: number): EvalError {
  return new EvalError(message, true, pos)
}

class Interpreter {
  private iterations = 0

  constructor(private readonly activation: Activation) {}

  eval(node: Expr, frame: Frame | undefined): Value {
    switch (node.kind) {
      case 'literal':
        if (node.literal === 'uint') return new UInt(node.value as bigint)
        return node.value as Value
      case 'ident': {
        if (!node.global) {
          for (let f = frame; f; f = f.up) if (f.name === node.name) return f.value
        }
        const value = this.activation.variables.get(node.name)
        if (value === undefined) throw nativeAt(`no such attribute: ${node.name}`, node.pos)
        return value
      }
      case 'select': {
        const operand = this.eval(node.operand, frame)
        if (!(operand instanceof Map)) throw nativeAt(`no such key: ${node.field}`, node.pos)
        if (node.test) return operand.has(node.field)
        const value = operand.get(node.field)
        if (value === undefined) throw nativeAt(`key '${node.field}' is not present in map.`, node.pos)
        return value as Value
      }
      case 'list':
        return Object.freeze(node.items.map((item) => this.eval(item, frame)))
      case 'comprehension':
        return this.comprehension(node, frame)
      case 'call':
        return this.call(node, frame)
    }
  }

  /** Evaluates to a value or the error it raised. */
  private attempt(node: Expr, frame: Frame | undefined): Value | EvalError {
    try {
      return this.eval(node, frame)
    } catch (e) {
      if (e instanceof EvalError) return e
      throw e
    }
  }

  private call(node: Call, frame: Frame | undefined): Value {
    switch (node.fn) {
      case '_&&_':
      case '_||_': {
        const decisive = node.fn === '_||_'
        const left = this.attempt(node.args[0]!, frame)
        if (left === decisive) return decisive
        const right = this.attempt(node.args[1]!, frame)
        if (right === decisive) return decisive
        if (left instanceof EvalError) throw left
        if (right instanceof EvalError) throw right
        if (typeof left !== 'boolean' || typeof right !== 'boolean') throw this.noOverload(node)
        return !decisive
      }
      case '_?_:_': {
        const cond = this.eval(node.args[0]!, frame)
        if (typeof cond !== 'boolean') throw this.noOverload(node)
        return this.eval(node.args[cond ? 1 : 2]!, frame)
      }
    }
    const args: Value[] = []
    if (node.aggregate) {
      // As cel-java's expansion: iterate over the target, counting every element, then call.
      const range = this.eval(node.target!, frame)
      if (!Array.isArray(range)) throw nativeAt('expected a list to iterate over', node.pos)
      for (let k = 0; k < range.length; k++) this.tick(node)
      args.push(range)
    } else if (node.target) args.push(this.eval(node.target, frame))
    for (const arg of node.args) args.push(this.eval(arg, frame))
    if (node.aggregate) {
      // cel-java tells overloads apart by the Java class of the arguments, and every list is a List.
      const candidates = node.overloads!.filter((o) => o.params.length === args.length)
      if (candidates.length > 1) {
        const ids = candidates.map((o) => o.id).join(', ')
        throw new EvalError(`evaluation error: Ambiguous overloads for function '${node.fn}'. Matching candidates: ${ids}`, false)
      }
    }
    const overload = node.overloads!.find((o) => o.params.every((p, k) => matches(args[k]!, p)))
    if (!overload) throw this.noOverload(node)
    return this.invoke(overload, args, node.pos)
  }

  private invoke(overload: Overload, args: readonly Value[], pos: number): Value {
    try {
      return overload.impl(args, this.activation)
    } catch (e) {
      if (e instanceof EvalError && e.native && e.pos < 0) e.pos = pos
      throw e
    }
  }

  private noOverload(node: Call): EvalError {
    const candidates = (node.overloads ?? []).map((o) => o.id).join(',')
    return nativeAt(`No matching overload for function '${node.fn}'. Overload candidates: ${candidates}`, node.pos)
  }

  private tick(node: Comprehension | Call): void {
    if (++this.iterations > this.activation.maxIterations) {
      throw nativeAt(`Iteration budget exceeded: ${this.activation.maxIterations}`, node.pos)
    }
  }

  /**
   * A list macro, evaluated as its CEL expansion: each iteration first counts against the
   * budget (failing strictly), then checks the loop condition, then evaluates the step
   * non-strictly, so that an error becomes the accumulator's value and iteration goes on.
   */
  private comprehension(node: Comprehension, frame: Frame | undefined): Value {
    const range = this.eval(node.range, frame)
    if (!Array.isArray(range)) throw nativeAt('expected a list to iterate over', node.pos)
    const items = range as readonly Value[]
    const at = (item: Value): Frame => ({ name: node.variable, value: item, up: frame })
    /** The predicate's value: a boolean or the error it raised. */
    const test = (item: Value): boolean | EvalError => {
      const v = this.attempt(node.predicate!, at(item))
      if (v instanceof EvalError || typeof v === 'boolean') return v
      return nativeAt(`No matching overload for function '_?_:_'`, node.pos)
    }
    switch (node.macro) {
      case 'exists':
      case 'all': {
        // exists: accu || pred while accu is not true; all: accu && pred while accu is not false.
        const decisive = node.macro === 'exists'
        let accu: boolean | EvalError = !decisive
        for (const item of items) {
          this.tick(node)
          if (accu === decisive) break
          const v = this.attempt(node.predicate!, at(item))
          if (v === decisive) accu = decisive
          else if (accu instanceof EvalError) continue
          else if (v instanceof EvalError) accu = v
          else if (typeof v !== 'boolean') accu = nativeAt(`No matching overload for function '${decisive ? '_||_' : '_&&_'}'`, node.pos)
        }
        if (accu instanceof EvalError) throw accu
        return accu
      }
      case 'exists_one':
      case 'filter': {
        // pred ? accu + [x] : accu (exists_one counts instead)
        let accu: Value[] | EvalError = []
        for (const item of items) {
          this.tick(node)
          const p = test(item)
          if (p instanceof EvalError) accu = p
          else if (p && !(accu instanceof EvalError)) accu.push(item)
        }
        if (accu instanceof EvalError) throw accu
        return node.macro === 'filter' ? Object.freeze(accu) : accu.length === 1
      }
      case 'map': {
        // accu + [transform], or pred ? accu + [transform] : accu
        let accu: Value[] | EvalError = []
        for (const item of items) {
          this.tick(node)
          if (node.predicate) {
            const p = test(item)
            if (p instanceof EvalError) {
              accu = p
              continue
            }
            if (!p) continue
          }
          if (accu instanceof EvalError) continue
          const v = this.attempt(node.transform!, at(item))
          if (v instanceof EvalError) accu = v
          else accu.push(v)
        }
        if (accu instanceof EvalError) throw accu
        return Object.freeze(accu)
      }
    }
  }
}

/** Whether a runtime value can be passed as a parameter of the given type (for dyn dispatch). */
function matches(value: Value, type: CelType): boolean {
  switch (type.k) {
    case 'dyn':
    case 'var':
    case 'error':
      return true
    case 'prim':
      switch (type.name) {
        case 'bool':
          return typeof value === 'boolean'
        case 'int':
          return typeof value === 'bigint'
        case 'uint':
          return value instanceof UInt
        case 'double':
          return typeof value === 'number'
        case 'string':
          return typeof value === 'string'
        case 'null_type':
          return value === null
        default:
          return false
      }
    case 'opaque':
      return type.name === 'decimal' ? value instanceof Decimal : type.name === 'money' ? value instanceof Money : value instanceof LocalDate
    case 'list':
      return Array.isArray(value)
    case 'struct':
      return value instanceof Map
  }
}
