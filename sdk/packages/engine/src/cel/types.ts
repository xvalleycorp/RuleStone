// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The types of the CEL type checker, with type parameters and unification.

import { isPrimitive, type ValueType } from '../types.js'

export type CelType =
  | { readonly k: 'prim'; readonly name: 'bool' | 'int' | 'uint' | 'double' | 'string' | 'bytes' | 'null_type' }
  | { readonly k: 'opaque'; readonly name: 'decimal' | 'money' | 'date' }
  | { readonly k: 'list'; readonly elem: CelType }
  | { readonly k: 'struct'; readonly name: string; readonly fields: ReadonlyMap<string, CelType> }
  | { readonly k: 'dyn' }
  | { readonly k: 'var'; readonly id: number }
  | { readonly k: 'error' }

export const BOOL: CelType = { k: 'prim', name: 'bool' }
export const INT: CelType = { k: 'prim', name: 'int' }
export const UINT: CelType = { k: 'prim', name: 'uint' }
export const DOUBLE: CelType = { k: 'prim', name: 'double' }
export const STRING: CelType = { k: 'prim', name: 'string' }
export const BYTES: CelType = { k: 'prim', name: 'bytes' }
export const NULL: CelType = { k: 'prim', name: 'null_type' }
export const DECIMAL: CelType = { k: 'opaque', name: 'decimal' }
export const MONEY: CelType = { k: 'opaque', name: 'money' }
export const DATE: CelType = { k: 'opaque', name: 'date' }
export const DYN: CelType = { k: 'dyn' }
export const ERROR: CelType = { k: 'error' }

export function list(elem: CelType): CelType {
  return { k: 'list', elem }
}

/** The CEL type of a declared value type; structs are named as cel-java names them. */
export function celTypeOf(type: ValueType, structName: string): CelType {
  if (isPrimitive(type)) {
    switch (type) {
      case 'bool':
        return BOOL
      case 'int':
        return INT
      case 'string':
        return STRING
      case 'decimal':
        return DECIMAL
      case 'money':
        return MONEY
      case 'date':
        return DATE
    }
  }
  if (type.kind === 'list') return list(celTypeOf(type.element, structName + '.item'))
  const fields = new Map<string, CelType>()
  for (const [name, field] of type.fields) fields.set(name, celTypeOf(field, structName + '.' + name))
  return { k: 'struct', name: structName, fields }
}

/** Type variable bindings. */
export class Substitution {
  constructor(readonly bindings: Map<number, CelType> = new Map()) {}

  copy(): Substitution {
    return new Substitution(new Map(this.bindings))
  }

  resolve(t: CelType): CelType {
    while (t.k === 'var') {
      const bound = this.bindings.get(t.id)
      if (!bound) return t
      t = bound
    }
    return t
  }

  /** The type with every bound variable replaced; unbound ones become dyn when `finalize`. */
  apply(t: CelType, finalize = false): CelType {
    const r = this.resolve(t)
    switch (r.k) {
      case 'var':
        return finalize ? DYN : r
      case 'list':
        return list(this.apply(r.elem, finalize))
      default:
        return r
    }
  }

  /** Makes a and b the same type if possible; dyn and error match anything. */
  unify(a: CelType, b: CelType): boolean {
    a = this.resolve(a)
    b = this.resolve(b)
    if (a.k === 'var' && b.k === 'var' && a.id === b.id) return true
    if (a.k === 'var') {
      if (occurs(a.id, b, this)) return false
      this.bindings.set(a.id, b)
      return true
    }
    if (b.k === 'var') {
      if (occurs(b.id, a, this)) return false
      this.bindings.set(b.id, a)
      return true
    }
    if (a.k === 'dyn' || b.k === 'dyn' || a.k === 'error' || b.k === 'error') return true
    if (a.k !== b.k) return false
    switch (a.k) {
      case 'prim':
      case 'opaque':
        return a.name === (b as typeof a).name
      case 'list':
        return this.unify(a.elem, (b as typeof a).elem)
      case 'struct':
        return a.name === (b as typeof a).name
    }
    return false
  }
}

function occurs(id: number, t: CelType, s: Substitution): boolean {
  const r = s.resolve(t)
  if (r.k === 'var') return r.id === id
  if (r.k === 'list') return occurs(id, r.elem, s)
  return false
}

/** The type as cel-java prints it in messages: int, list(int), rulestone.input.applicant. */
export function formatType(t: CelType): string {
  switch (t.k) {
    case 'prim':
      return t.name === 'null_type' ? 'null' : t.name
    case 'opaque':
      return t.name
    case 'list':
      return `list(${formatType(t.elem)})`
    case 'struct':
      return t.name
    case 'dyn':
      return 'dyn'
    case 'var':
      return `_var${t.id}`
    case 'error':
      return '*error*'
  }
}

/** The short type name of cel-java's CelType.name(): list, not list(int). */
export function typeName(t: CelType): string {
  if (t.k === 'prim') return t.name
  return t.k === 'list' ? 'list' : formatType(t)
}

/** Structural equality of fully resolved types. */
export function sameType(a: CelType, b: CelType): boolean {
  if (a.k !== b.k) return false
  switch (a.k) {
    case 'prim':
    case 'opaque':
      return a.name === (b as typeof a).name
    case 'list':
      return sameType(a.elem, (b as typeof a).elem)
    case 'struct':
      return a.name === (b as typeof a).name
    case 'var':
      return a.id === (b as typeof a).id
    default:
      return true
  }
}
