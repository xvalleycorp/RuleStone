// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import type { TypeSpec } from '../types'

/** An editable type: the tree behind a TypeSpec, with struct fields as an ordered list. */
export type TypeNode =
  | { kind: 'primitive'; name: string }
  | { kind: 'list'; element: TypeNode }
  | { kind: 'struct'; fields: Field[] }

export interface Field {
  name: string
  type: TypeNode
}

export function toNode(spec: TypeSpec): TypeNode {
  if (typeof spec === 'string') return { kind: 'primitive', name: spec }
  if (Array.isArray(spec)) return { kind: 'list', element: toNode(spec[0] ?? 'string') }
  return { kind: 'struct', fields: Object.entries(spec).map(([name, type]) => ({ name, type: toNode(type) })) }
}

export function toSpec(node: TypeNode): TypeSpec {
  switch (node.kind) {
    case 'primitive':
      return node.name
    case 'list':
      return [toSpec(node.element)]
    case 'struct': {
      const spec: Record<string, TypeSpec> = {}
      for (const f of node.fields) spec[f.name] = toSpec(f.type)
      return spec
    }
  }
}

export function fieldsOf(record: Record<string, TypeSpec>): Field[] {
  return Object.entries(record).map(([name, type]) => ({ name, type: toNode(type) }))
}

export function recordOf(fields: Field[]): Record<string, TypeSpec> {
  const record: Record<string, TypeSpec> = {}
  for (const f of fields) record[f.name] = toSpec(f.type)
  return record
}

/** "decimal", "list<string>", "struct" — for labels. */
export function describe(spec: TypeSpec): string {
  if (typeof spec === 'string') return spec
  if (Array.isArray(spec)) return `list<${describe(spec[0])}>`
  return 'struct'
}

export function isPrimitive(spec: TypeSpec | undefined): spec is string {
  return typeof spec === 'string'
}

export function listElement(spec: TypeSpec | undefined): TypeSpec | undefined {
  return Array.isArray(spec) ? spec[0] : undefined
}

/** One readable path into the inputs, e.g. applicant.monthlyIncome. */
export interface InputPath {
  path: string
  type: TypeSpec
  /** Top-level inputs are always set; only struct fields can be tested with has(). */
  topLevel: boolean
  implicit?: boolean
}

/**
 * Every path an expression can read without indexing a list: each input and, recursively, each field of a
 * struct. Implicit inputs (businessDate) come last.
 */
export function inputPaths(inputs: Record<string, TypeSpec>, implicit: { name: string; type: string }[] = []): InputPath[] {
  const paths: InputPath[] = []
  const walk = (prefix: string, spec: TypeSpec, topLevel: boolean) => {
    paths.push({ path: prefix, type: spec, topLevel })
    if (typeof spec === 'object' && !Array.isArray(spec)) {
      for (const [field, type] of Object.entries(spec)) walk(`${prefix}.${field}`, type, false)
    }
  }
  for (const [name, spec] of Object.entries(inputs)) walk(name, spec, true)
  for (const i of implicit) {
    if (!(i.name in inputs)) paths.push({ path: i.name, type: i.type, topLevel: true, implicit: true })
  }
  return paths
}

/** Fields of the struct at `path` (for autocomplete after a dot); empty if it is not a struct. */
export function fieldsAt(inputs: Record<string, TypeSpec>, path: string[]): [string, TypeSpec][] {
  let spec: TypeSpec | undefined = { ...inputs }
  for (const part of path) {
    if (!spec || typeof spec !== 'object' || Array.isArray(spec)) return []
    spec = (spec as Record<string, TypeSpec>)[part]
  }
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) return []
  return Object.entries(spec)
}

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/
const RESERVED = new Set(['true', 'false', 'null', 'in', 'as', 'break', 'const', 'continue', 'else', 'for', 'function',
  'if', 'import', 'let', 'loop', 'package', 'namespace', 'return', 'var', 'void', 'while'])

/** Problem with a name for an input, output or field, or null if it is valid. */
export function nameProblem(name: string): string | null {
  if (!IDENTIFIER.test(name)) return 'identifier'
  if (RESERVED.has(name)) return 'reserved'
  return null
}
