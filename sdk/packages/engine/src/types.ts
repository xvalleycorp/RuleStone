// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/** The types of decision inputs and outputs, as vn.io.dxa.rulestone.cel.ValueType. */
export type ValueType = PrimitiveType | ListType | StructType

export type PrimitiveType = 'bool' | 'int' | 'string' | 'decimal' | 'money' | 'date'

export interface ListType {
  readonly kind: 'list'
  readonly element: ValueType
}

/** An object with named, typed fields, in declaration order. */
export interface StructType {
  readonly kind: 'struct'
  readonly fields: ReadonlyMap<string, ValueType>
}

/** A type as written in .stone.json: "decimal", ["string"] or { "age": "int" }. */
export type TypeSpec = string | readonly TypeSpec[] | { readonly [field: string]: TypeSpec }

export const PRIMITIVE_TYPES: readonly PrimitiveType[] = ['bool', 'int', 'string', 'decimal', 'money', 'date']

export function listOf(element: ValueType): ListType {
  return { kind: 'list', element }
}

export function structOf(fields: ReadonlyMap<string, ValueType> | { readonly [field: string]: ValueType }): StructType {
  const map = fields instanceof Map ? new Map(fields) : new Map(Object.entries(fields))
  if (map.size === 0) throw new RangeError('struct must declare at least one field')
  return { kind: 'struct', fields: map }
}

export function isPrimitive(type: ValueType): type is PrimitiveType {
  return typeof type === 'string'
}

/** Converts a type as written in .stone.json. @throws RangeError for an invalid type */
export function typeFromSpec(spec: TypeSpec): ValueType {
  if (typeof spec === 'string') {
    if ((PRIMITIVE_TYPES as readonly string[]).includes(spec)) return spec as PrimitiveType
    throw new RangeError(`unknown type '${spec}'`)
  }
  if (Array.isArray(spec)) {
    if (spec.length !== 1) throw new RangeError('a list type is written as a one-element array, e.g. ["decimal"]')
    return listOf(typeFromSpec(spec[0] as TypeSpec))
  }
  const fields = new Map<string, ValueType>()
  for (const [name, field] of Object.entries(spec as { [field: string]: TypeSpec })) fields.set(name, typeFromSpec(field))
  return structOf(fields)
}

/** The type as written in .stone.json. */
export function typeToSpec(type: ValueType): TypeSpec {
  if (isPrimitive(type)) return type
  if (type.kind === 'list') return [typeToSpec(type.element)]
  const fields: { [field: string]: TypeSpec } = {}
  for (const [name, field] of type.fields) fields[name] = typeToSpec(field)
  return fields
}

/** The type as Java's ValueType.toString() writes it: int, list<int>, struct{age=int}. */
export function typeToString(type: ValueType): string {
  if (isPrimitive(type)) return type
  if (type.kind === 'list') return `list<${typeToString(type.element)}>`
  return `struct{${[...type.fields].map(([n, t]) => `${n}=${typeToString(t)}`).join(', ')}}`
}

/** Structural equality; struct fields compare as maps, regardless of order. */
export function typesEqual(a: ValueType, b: ValueType): boolean {
  if (isPrimitive(a) || isPrimitive(b)) return a === b
  if (a.kind === 'list' || b.kind === 'list') {
    return a.kind === 'list' && b.kind === 'list' && typesEqual(a.element, b.element)
  }
  if (a.fields.size !== b.fields.size) return false
  for (const [name, type] of a.fields) {
    const other = b.fields.get(name)
    if (other === undefined || !typesEqual(type, other)) return false
  }
  return true
}

/** Whether the type is a struct or contains one. */
export function containsStruct(type: ValueType): boolean {
  if (isPrimitive(type)) return false
  return type.kind === 'struct' || containsStruct(type.element)
}
