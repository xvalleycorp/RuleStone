// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Runtime values and their conversion from the JavaScript values a caller passes as facts.

import { LocalDate } from './date.js'
import { Decimal } from './decimal.js'
import { InvalidValueError } from './errors.js'
import { Money } from './money.js'
import { isPrimitive, typeToString, type ValueType } from './types.js'

/** An unsigned 64-bit CEL integer (only from uint literals and conversions). */
export class UInt {
  constructor(readonly value: bigint) {}
  toString(): string {
    return this.value.toString()
  }
}

/** A struct value: its fields that are set. */
export type StructValue = ReadonlyMap<string, Value>

/**
 * A value inside the engine and in results: boolean, bigint (int), string, Decimal, Money,
 * LocalDate, arrays (lists) and Maps (structs); number (double), UInt and null only inside
 * expressions.
 */
export type Value = boolean | bigint | string | Decimal | Money | LocalDate | readonly Value[] | StructValue | number | UInt | null

export const INT64_MIN = -(2n ** 63n)
export const INT64_MAX = 2n ** 63n - 1n

/** The kind of a JavaScript value, for messages; never the value itself. */
export function jsKind(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'Array'
  if (value instanceof Decimal) return 'Decimal'
  if (value instanceof Money) return 'Money'
  if (value instanceof LocalDate) return 'LocalDate'
  if (value instanceof Map) return 'Map'
  if (value instanceof Date) return 'Date'
  if (typeof value === 'object') return 'object'
  return typeof value
}

function mismatch(path: string, expected: ValueType, value: unknown): InvalidValueError {
  return new InvalidValueError(path, `expected ${typeToString(expected)} but got ${jsKind(value)}`)
}

/**
 * Converts a JavaScript value to its runtime form, checking the declared type, as the Java
 * engine's Values.toCel does. Accepted: boolean; bigint or a safe integer number for int;
 * string; Decimal, bigint or a safe integer for decimal; Money; LocalDate; arrays for lists;
 * Maps or plain objects for structs. Struct fields that are absent, null or undefined are left
 * unset. Non-integer numbers are rejected: there is no floating point.
 */
export function toValue(value: unknown, type: ValueType, path: string): Value {
  if (value === null || value === undefined) throw new InvalidValueError(path, 'value is null')
  if (isPrimitive(type)) {
    switch (type) {
      case 'bool':
        if (typeof value === 'boolean') return value
        throw mismatch(path, type, value)
      case 'int':
        return toInt(value, path)
      case 'string':
        if (typeof value === 'string') return value
        throw mismatch(path, type, value)
      case 'decimal':
        return toDecimal(value, path)
      case 'money':
        if (value instanceof Money) return value
        throw new InvalidValueError(path, `expected money but got ${jsKind(value)}; pass a Money`)
      case 'date':
        if (value instanceof LocalDate) return value
        throw new InvalidValueError(path, `expected date but got ${jsKind(value)}; pass a LocalDate`)
    }
  }
  if (type.kind === 'list') {
    if (!Array.isArray(value)) throw mismatch(path, type, value)
    return Object.freeze(value.map((item, i) => toValue(item, type.element, `${path}[${i}]`)))
  }
  if (typeof value !== 'object' || Array.isArray(value) || value instanceof Decimal || value instanceof Money || value instanceof LocalDate) {
    throw new InvalidValueError(path, `expected a Map or an object but got ${jsKind(value)}`)
  }
  const fields = new Map<string, Value>()
  for (const [name, fieldType] of type.fields) {
    const field = value instanceof Map ? value.get(name) : Object.prototype.hasOwnProperty.call(value, name) ? (value as Record<string, unknown>)[name] : undefined
    if (field !== null && field !== undefined) fields.set(name, toValue(field, fieldType, `${path}.${name}`))
  }
  return fields
}

function toInt(value: unknown, path: string): bigint {
  if (typeof value === 'bigint') {
    if (value < INT64_MIN || value > INT64_MAX) throw new InvalidValueError(path, 'integer out of 64-bit range')
    return value
  }
  if (typeof value === 'number' && Number.isSafeInteger(value)) return BigInt(value)
  if (typeof value === 'number') throw new InvalidValueError(path, 'expected int but got a number that is not a safe integer; pass a bigint')
  throw mismatch(path, 'int', value)
}

function toDecimal(value: unknown, path: string): Decimal {
  if (value instanceof Decimal) return value
  if (typeof value === 'bigint') return Decimal.fromBigInt(value)
  if (typeof value === 'number') {
    if (Number.isSafeInteger(value)) return Decimal.fromBigInt(BigInt(value))
    throw new InvalidValueError(path, 'floating-point value is not accepted for decimal; pass a Decimal')
  }
  throw mismatch(path, 'decimal', value)
}

/** The value's type name for messages, as the Java engine describes runtime values. */
export function describe(value: Value): string {
  if (value === null) return 'null'
  if (value instanceof Decimal) return 'decimal'
  if (value instanceof Money) return 'money'
  if (value instanceof LocalDate) return 'date'
  if (value instanceof UInt) return 'UnsignedLong'
  if (Array.isArray(value)) return 'List'
  if (value instanceof Map) return 'Map'
  switch (typeof value) {
    case 'boolean':
      return 'Boolean'
    case 'bigint':
      return 'Long'
    case 'string':
      return 'String'
    case 'number':
      return 'Double'
  }
  return 'Object'
}

/**
 * Checks that an expression's result has the expected type, as Values.toJava does.
 * @throws InvalidValueError, located at "result"
 */
export function checkResult(value: Value, expected: ValueType, path = 'result'): Value {
  if (isPrimitive(expected)) {
    const ok =
      (expected === 'bool' && typeof value === 'boolean') ||
      (expected === 'int' && typeof value === 'bigint') ||
      (expected === 'string' && typeof value === 'string') ||
      (expected === 'decimal' && value instanceof Decimal) ||
      (expected === 'money' && value instanceof Money) ||
      (expected === 'date' && value instanceof LocalDate)
    if (ok) return value
  } else if (expected.kind === 'list' && Array.isArray(value)) {
    return Object.freeze((value as readonly Value[]).map((item, i) => checkResult(item, expected.element, `${path}[${i}]`)))
  }
  throw new InvalidValueError(path, `expected ${typeToString(expected)} but expression produced ${describe(value)}`)
}

/** CEL equality: numeric across int, uint and double; decimals and money by value; deep for lists and structs. */
export function valuesEqual(a: Value, b: Value): boolean {
  if (a === b) return true
  const na = numeric(a)
  const nb = numeric(b)
  if (na !== undefined && nb !== undefined) {
    // eslint-disable-next-line eqeqeq -- loose equality compares bigint and number exactly
    return na == nb
  }
  if (a instanceof Decimal) return b instanceof Decimal && a.equals(b)
  if (a instanceof Money) return b instanceof Money && a.equals(b)
  if (a instanceof LocalDate) return b instanceof LocalDate && a.equals(b)
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false
    return a.every((item, i) => valuesEqual(item as Value, (b as readonly Value[])[i]!))
  }
  if (a instanceof Map) {
    if (!(b instanceof Map) || a.size !== b.size) return false
    for (const [k, v] of a) {
      if (!b.has(k) || !valuesEqual(v as Value, b.get(k) as Value)) return false
    }
    return true
  }
  return false
}

function numeric(v: Value): bigint | number | undefined {
  if (typeof v === 'bigint' || typeof v === 'number') return v
  if (v instanceof UInt) return v.value
  return undefined
}

/**
 * The canonical form of a value for factsHash, as Bindings.canonicalValues in Java: decimals
 * and money amounts without trailing zeros, struct fields sorted by name.
 */
export function canonicalValue(value: Value): Value {
  if (value instanceof Decimal) return value.stripTrailingZeros()
  if (value instanceof Money) return Money.of(value.amount.stripTrailingZeros(), value.currency)
  if (Array.isArray(value)) return (value as readonly Value[]).map(canonicalValue)
  if (value instanceof Map) {
    const sorted = new Map<string, Value>()
    for (const key of [...value.keys()].sort()) sorted.set(key, canonicalValue(value.get(key) as Value))
    return sorted
  }
  return value
}
