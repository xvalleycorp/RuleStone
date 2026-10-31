// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Inputs and outputs: field paths, schema operations, and renaming a field together with every
// expression that reads it. Renaming works on the type-checked syntax tree, never on the text.

import {
  BUSINESS_DATE, PARAMS, analyzeExpression, codePointIndex, inputStructName, localValueTypes, structOf, typeFromSpec, typeToSpec,
  type Expr, type TypeSpec as EngineTypeSpec, type ValueType,
} from '@rulestone/engine'
import { PRIMITIVES, orderTable, type RuleJson, type TableJson, type TypeSpec } from './table.js'

/** A field: input or output names, then struct field names, with '[]' for the elements of a list. */
export type FieldPath = readonly string[]

export const ELEMENT = '[]'

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/
const RESERVED = new Set([
  'true', 'false', 'null', 'in', 'as', 'break', 'const', 'continue', 'else', 'for', 'function', 'if', 'import', 'let',
  'loop', 'package', 'namespace', 'return', 'var', 'void', 'while',
])

export type NameProblem = 'empty' | 'invalid' | 'reserved' | 'businessDate' | 'duplicate'

/** Why a name cannot be used for a field, or undefined if it can. */
export function nameProblem(name: string, siblings: readonly string[], topLevelInput = false): NameProblem | undefined {
  if (name === '') return 'empty'
  if (!IDENTIFIER.test(name)) return 'invalid'
  if (RESERVED.has(name)) return 'reserved'
  if (topLevelInput && name === BUSINESS_DATE) return 'businessDate'
  if (siblings.includes(name)) return 'duplicate'
  return undefined
}

export type TypeKind = (typeof PRIMITIVES)[number] | 'list' | 'struct'

export function kindOf(spec: TypeSpec): TypeKind {
  if (typeof spec === 'string') return (PRIMITIVES as readonly string[]).includes(spec) ? (spec as TypeKind) : 'string'
  return Array.isArray(spec) ? 'list' : 'struct'
}

/** A default spec for a kind: a struct starts with one field, a list of strings. */
export function specOfKind(kind: TypeKind): TypeSpec {
  if (kind === 'list') return ['string']
  if (kind === 'struct') return { field1: 'string' }
  return kind
}

/** The type as the engine's messages write it: int, list<int>, struct. */
export function describeType(spec: TypeSpec | undefined): string {
  if (spec === undefined) return '?'
  if (typeof spec === 'string') return spec
  if (Array.isArray(spec)) return `list<${describeType(spec[0])}>`
  return 'struct'
}

/** The engine's type for a spec, or undefined if the spec is invalid. */
export function valueTypeOf(spec: TypeSpec): ValueType | undefined {
  try {
    return typeFromSpec(spec as EngineTypeSpec)
  } catch {
    return undefined
  }
}

/** The declared parameters with a valid type, name to type. */
export function paramTypes(params: Record<string, TypeSpec> | undefined): Map<string, ValueType> {
  const types = new Map<string, ValueType>()
  for (const [name, spec] of Object.entries(params ?? {})) {
    const type = valueTypeOf(spec)
    if (type && (typeof type === 'string' || type.kind === 'list') && /^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) types.set(name, type)
  }
  return types
}

/**
 * The typed variables of a table's expressions: its valid inputs, businessDate and, when given,
 * the declared parameters as `params` and the local values (let) that compile, with their
 * inferred types.
 */
export function tableVariables(
  inputs: Record<string, TypeSpec>,
  locals?: Record<string, string>,
  params?: Record<string, TypeSpec>,
): Map<string, ValueType> {
  const variables = new Map<string, ValueType>()
  for (const [name, spec] of Object.entries(inputs)) {
    if (name === BUSINESS_DATE) continue
    const type = valueTypeOf(spec)
    if (type) variables.set(name, type)
  }
  const declared = paramTypes(params)
  if (params !== undefined) variables.delete(PARAMS)
  if (declared.size > 0) variables.set(PARAMS, structOf(declared))
  if (locals && Object.keys(locals).length > 0) {
    const inputTypes = new Map(variables)
    inputTypes.delete(PARAMS)
    const types = localValueTypes(inputTypes, new Map(Object.entries(locals)), params === undefined ? undefined : declared)
    for (const [name, type] of types) variables.set(name, type)
  }
  variables.set(BUSINESS_DATE, 'date')
  return variables
}

/** The local values (let) that compile, with their inferred types as type specs, for field pickers. */
export function localSpecs(
  inputs: Record<string, TypeSpec>,
  locals: Record<string, string> | undefined,
  params?: Record<string, TypeSpec>,
): Map<string, TypeSpec> {
  const specs = new Map<string, TypeSpec>()
  if (!locals) return specs
  const variables = tableVariables(inputs, locals, params)
  for (const name of Object.keys(locals)) {
    const type = variables.get(name)
    if (type !== undefined && !(name in inputs)) specs.set(name, typeToSpec(type) as TypeSpec)
  }
  return specs
}

/** The spec at a path, or undefined. */
export function specAt(specs: Record<string, TypeSpec>, path: FieldPath): TypeSpec | undefined {
  let current: TypeSpec | undefined = { ...specs }
  for (const step of path) {
    if (current === undefined || typeof current === 'string') return undefined
    if (Array.isArray(current)) current = step === ELEMENT ? current[0] : undefined
    else current = step === ELEMENT ? undefined : current[step]
  }
  return current
}

/** Replaces the spec at a path (path non-empty); returns new objects along the path. */
function replaceAt(specs: Record<string, TypeSpec>, path: FieldPath, update: (parent: TypeSpec) => TypeSpec): Record<string, TypeSpec> {
  const go = (spec: TypeSpec, rest: FieldPath): TypeSpec => {
    if (rest.length === 0) return update(spec)
    const [step, ...tail] = rest as [string, ...string[]]
    if (Array.isArray(spec)) {
      if (step !== ELEMENT) throw new RangeError('not a struct')
      return [go(spec[0]!, tail)]
    }
    if (typeof spec === 'string' || !(step in spec)) throw new RangeError(`no field '${step}'`)
    return { ...spec, [step]: go(spec[step]!, tail) }
  }
  return go(specs, path) as Record<string, TypeSpec>
}

function structAt(specs: Record<string, TypeSpec>, parent: FieldPath, update: (fields: Record<string, TypeSpec>) => Record<string, TypeSpec>) {
  return replaceAt(specs, parent, (spec) => {
    if (typeof spec === 'string' || Array.isArray(spec)) throw new RangeError('not a struct')
    return update(spec)
  })
}

/** A field name not used among siblings: field1, field2, … */
export function newFieldName(fields: Record<string, TypeSpec>, prefix = 'field'): string {
  for (let n = 1; ; n++) if (!(`${prefix}${n}` in fields)) return `${prefix}${n}`
}

export function addField(specs: Record<string, TypeSpec>, parent: FieldPath, name: string, spec: TypeSpec = 'string'): Record<string, TypeSpec> {
  return structAt(specs, parent, (fields) => ({ ...fields, [name]: spec }))
}

export function removeField(specs: Record<string, TypeSpec>, path: FieldPath): Record<string, TypeSpec> {
  const name = path.at(-1)!
  return structAt(specs, path.slice(0, -1), (fields) => Object.fromEntries(Object.entries(fields).filter(([k]) => k !== name)))
}

export function setFieldType(specs: Record<string, TypeSpec>, path: FieldPath, spec: TypeSpec): Record<string, TypeSpec> {
  return replaceAt(specs, path, () => spec)
}

/** Moves a field among its siblings to a new index. */
export function moveField(specs: Record<string, TypeSpec>, path: FieldPath, to: number): Record<string, TypeSpec> {
  const name = path.at(-1)!
  return structAt(specs, path.slice(0, -1), (fields) => {
    const entries = Object.entries(fields)
    const from = entries.findIndex(([k]) => k === name)
    if (from < 0) return fields
    const target = Math.max(0, Math.min(entries.length - 1, to))
    const [entry] = entries.splice(from, 1)
    entries.splice(target, 0, entry!)
    return Object.fromEntries(entries)
  })
}

/** Renames a key of a struct, keeping its position. Does not touch expressions. */
export function renameKey(specs: Record<string, TypeSpec>, path: FieldPath, newName: string): Record<string, TypeSpec> {
  const name = path.at(-1)!
  return structAt(specs, path.slice(0, -1), (fields) =>
    Object.fromEntries(Object.entries(fields).map(([k, v]) => [k === name ? newName : k, v])),
  )
}

/** A field that a condition can test, for the field picker. */
export interface FieldInfo {
  /** The expression that reads it, e.g. applicant.age, or a.balance inside a.exists(…). */
  readonly expression: string
  readonly spec: TypeSpec
  /** True for struct fields, which may be unset: has() applies to them. */
  readonly optional: boolean
}

/**
 * Every field reachable from the given roots without going through a list: primitives, lists
 * and structs. Lists are offered as a whole; their elements are reached through exists/all.
 */
export function fieldsOf(roots: ReadonlyMap<string, TypeSpec> | Record<string, TypeSpec>): FieldInfo[] {
  const out: FieldInfo[] = []
  const entries = roots instanceof Map ? [...roots] : Object.entries(roots)
  const visit = (expression: string, spec: TypeSpec, optional: boolean) => {
    out.push({ expression, spec, optional })
    if (typeof spec === 'object' && !Array.isArray(spec)) {
      for (const [name, field] of Object.entries(spec)) visit(`${expression}.${name}`, field, true)
    }
  }
  for (const [name, spec] of entries) visit(name, spec, false)
  return out
}

// --- Renaming ---------------------------------------------------------------------------------

/** One expression the rename changed. */
export interface RenameChange {
  /** The rule's index, or -1 for a local value. */
  readonly rule: number
  /** The rule's id, or '' for a local value. */
  readonly ruleId: string
  /** 'when', 'then.<output>', or 'let.<name>' for a local value. */
  readonly cell: string
  readonly before: string
  readonly after: string
  readonly occurrences: number
}

/** An expression that refers to the old name but could not be analyzed, so was left unchanged. */
export interface RenameSkip {
  readonly rule: number
  readonly ruleId: string
  readonly cell: string
  readonly reason: 'syntax'
}

export type RenameResult =
  | { readonly ok: true; readonly table: TableJson; readonly changes: readonly RenameChange[]; readonly skipped: readonly RenameSkip[] }
  | { readonly ok: false; readonly reason: NameProblem | 'notFound' | 'capture'; readonly ruleId?: string }

function cells(rule: RuleJson): [string, string][] {
  return [['when', rule.when], ...Object.entries(rule.then ?? {}).map(([o, e]) => [`then.${o}`, e] as [string, string])]
}

/** Visits every node with the iteration variables in scope. */
export function walk(node: Expr, visit: (node: Expr, scope: readonly string[]) => void, scope: readonly string[] = []): void {
  visit(node, scope)
  switch (node.kind) {
    case 'select':
      walk(node.operand, visit, scope)
      break
    case 'call':
      if (node.target) walk(node.target, visit, scope)
      node.args.forEach((a) => walk(a, visit, scope))
      break
    case 'list':
      node.items.forEach((a) => walk(a, visit, scope))
      break
    case 'comprehension': {
      walk(node.range, visit, scope)
      const inner = [...scope, node.variable]
      if (node.predicate) walk(node.predicate, visit, inner)
      if (node.transform) walk(node.transform, visit, inner)
      break
    }
    default:
      break
  }
}

/** The CEL struct name of the struct that holds the field at path. */
function parentStructName(path: FieldPath): string {
  return inputStructName(path.slice(0, -1).map((s) => (s === ELEMENT ? 'item' : s)))
}

interface Edit {
  readonly pos: number
  readonly length: number
}

/** The positions in an expression's tree that refer to the input field at path. */
function occurrences(ast: Expr, path: FieldPath): { edits: Edit[]; captured: (newName: string) => boolean } {
  const name = path.at(-1)!
  const edits: Edit[] = []
  const scopes: (readonly string[])[] = []
  if (path.length === 1) {
    walk(ast, (node, scope) => {
      if (node.kind === 'ident' && node.name === name && (node.global || !scope.includes(name))) {
        edits.push({ pos: node.pos, length: Array.from(name).length })
        scopes.push(scope)
      }
    })
  } else {
    const structName = parentStructName(path)
    walk(ast, (node) => {
      if (node.kind !== 'select' || node.field !== name) return
      const operand = node.operand.type
      if (operand?.k === 'struct' && operand.name === structName) edits.push({ pos: node.fieldPos, length: Array.from(name).length })
    })
  }
  return { edits, captured: (newName) => scopes.some((s) => s.includes(newName)) }
}

function applyEdits(source: string, edits: readonly Edit[], replacement: string): string {
  let out = source
  for (const edit of [...edits].sort((a, b) => b.pos - a.pos)) {
    const start = codePointIndex(source, edit.pos)
    const end = codePointIndex(source, edit.pos + edit.length)
    out = out.slice(0, start) + replacement + out.slice(end)
  }
  return out
}

/**
 * Renames an input or a struct field of an input, and every reference to it in the rules'
 * conditions and outputs. References are found in the type-checked syntax tree: an iteration
 * variable with the same name, a field of the same name in another struct, or the name inside
 * a string literal is never changed.
 */
export function renameInput(table: TableJson, path: FieldPath, newName: string): RenameResult {
  try {
    return rename(table, path, newName)
  } catch (e) {
    if (e instanceof CaptureError) return { ok: false, reason: 'capture', ruleId: e.ruleId }
    throw e
  }
}

function rename(table: TableJson, path: FieldPath, newName: string): RenameResult {
  const oldName = path.at(-1)
  if (oldName === undefined || oldName === ELEMENT || specAt(table.inputs, path) === undefined) return { ok: false, reason: 'notFound' }
  if (newName === oldName) return { ok: true, table, changes: [], skipped: [] }
  const parent = path.length === 1 ? table.inputs : specAt(table.inputs, path.slice(0, -1))
  const siblings = parent && typeof parent === 'object' && !Array.isArray(parent) ? Object.keys(parent) : []
  // An input and a local value cannot share a name.
  const names = path.length === 1 ? [...siblings, ...Object.keys(table.let ?? {})] : siblings
  const problem = nameProblem(newName, names, path.length === 1)
  if (problem) return { ok: false, reason: problem }

  const locals = table.let
  const variables = tableVariables(table.inputs, locals)
  const renamedInputs = renameKey(table.inputs, path, newName)
  const changes: RenameChange[] = []
  const skipped: RenameSkip[] = []
  const word = new RegExp(`(^|[^A-Za-z0-9_])${oldName}([^A-Za-z0-9_]|$)`)
  // Local values first: rules are checked against the renamed local values.
  let renamedLocals = locals
  if (locals) {
    renamedLocals = { ...locals }
    for (const [name, source] of Object.entries(locals)) {
      const cell = `let.${name}`
      const analysis = analyzeExpression(source, { variables })
      if (!analysis.ast) {
        if (word.test(source)) skipped.push({ rule: -1, ruleId: '', cell, reason: 'syntax' })
        continue
      }
      const found = occurrences(analysis.ast, path)
      if (found.edits.length === 0) continue
      if (path.length === 1 && found.captured(newName)) throw new CaptureError(`let.${name}`)
      const after = applyEdits(source, found.edits, newName)
      changes.push({ rule: -1, ruleId: '', cell, before: source, after, occurrences: found.edits.length })
      renamedLocals[name] = after
    }
  }
  const newVariables = tableVariables(renamedInputs, renamedLocals)
  const rules = table.rules.map((rule, index) => {
    let updated: RuleJson = rule
    for (const [cell, source] of cells(rule)) {
      const analysis = analyzeExpression(source, { variables })
      if (!analysis.ast) {
        if (word.test(source)) skipped.push({ rule: index, ruleId: rule.id, cell, reason: 'syntax' })
        continue
      }
      const found = occurrences(analysis.ast, path)
      if (found.edits.length === 0) continue
      if (path.length === 1 && found.captured(newName)) throw new CaptureError(rule.id)
      const after = applyEdits(source, found.edits, newName)
      // The renamed expression must check exactly as well as before, against the renamed schema.
      const check = analyzeExpression(after, { variables: newVariables })
      if (check.issues.length > analysis.issues.length) throw new Error(`rename produced new problems in ${rule.id} ${cell}`)
      changes.push({ rule: index, ruleId: rule.id, cell, before: source, after, occurrences: found.edits.length })
      updated = cell === 'when' ? { ...updated, when: after } : { ...updated, then: { ...updated.then, [cell.slice(5)]: after } }
    }
    return updated
  })
  const renamed: TableJson = { ...table, inputs: renamedInputs, rules }
  if (renamedLocals) renamed.let = renamedLocals
  return { ok: true, table: orderTable(renamed), changes, skipped }
}

class CaptureError extends Error {
  constructor(readonly ruleId: string) {
    super('capture')
  }
}

/** Renames an output and the assignments to it in every rule. */
export function renameOutput(table: TableJson, oldName: string, newName: string): RenameResult {
  if (!(oldName in table.outputs)) return { ok: false, reason: 'notFound' }
  if (newName === oldName) return { ok: true, table, changes: [], skipped: [] }
  const problem = nameProblem(newName, Object.keys(table.outputs))
  if (problem) return { ok: false, reason: problem }
  const changes: RenameChange[] = []
  const rules = table.rules.map((rule, index) => {
    if (!rule.then || !(oldName in rule.then)) return rule
    changes.push({ rule: index, ruleId: rule.id, cell: `then.${oldName}`, before: oldName, after: newName, occurrences: 1 })
    return { ...rule, then: Object.fromEntries(Object.entries(rule.then).map(([k, v]) => [k === oldName ? newName : k, v])) }
  })
  return { ok: true, table: orderTable({ ...table, outputs: renameKey(table.outputs, [oldName], newName), rules }), changes, skipped: [] }
}
