// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// JSON facts and results, as FactsJson: money as "1500000.50 VND", dates as "2026-11-01",
// decimals as numbers or numeric strings, read exactly and never through floating point.

import { LocalDate } from './date.js'
import { Decimal } from './decimal.js'
import { InvalidFactsError } from './errors.js'
import { JsonSyntaxError, isFloatingNode, parseJson, toJsonNode, writeJson, type JsonNode, type Writable } from './json.js'
import { Money, isCurrencyCode } from './money.js'
import { isPrimitive, listOf, type StructType, type ValueType } from './types.js'
import { INT64_MAX, INT64_MIN, type Value } from './values.js'
import type { DecisionTable } from './table.js'

const DECIMAL = /^-?[0-9]+(\.[0-9]+)?$/
const MONEY = /^(-?[0-9]+(?:\.[0-9]+)?) ([A-Z]{3})$/
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/

const MONEY_FORM = 'money as "<amount> <CCY>", e.g. "1500000 VND"'
const DATE_FORM = 'a date as "yyyy-MM-dd"'

/** JSON text, a parsed JsonNode, or a plain JavaScript value (numbers must be safe integers). */
export type JsonInput = string | JsonNode | unknown

function toNode(json: JsonInput): JsonNode {
  if (typeof json === 'string') {
    let node: JsonNode | undefined
    try {
      node = parseJson(json, { failOnTrailingTokens: true })
    } catch (e) {
      // The parser's own message may quote the text, which may be customer data.
      if (e instanceof JsonSyntaxError) throw new InvalidFactsError([`$: invalid JSON at line ${e.line}, column ${e.column}`])
      throw e
    }
    if (!node) throw new InvalidFactsError(['$: expected a JSON object'])
    return node
  }
  return toJsonNode(json)
}

/**
 * Reads a JSON object of named values (facts, or the outputs of a decision) with their declared types.
 * Struct fields that are absent or null are left unset; absent names are absent from the result.
 * @throws InvalidFactsError listing every problem; messages never quote values
 */
export function readValues(json: JsonInput, types: ReadonlyMap<string, ValueType>): Map<string, Value> {
  const node = toNode(json)
  const reader = new Reader()
  const values = new Map<string, Value>()
  if (node.type !== 'object') {
    reader.problem('$', 'expected an object of names to values')
  } else {
    for (const [name, value] of node.entries) {
      const type = types.get(name)
      if (type === undefined) reader.problem(name, `not declared (declared: ${[...types.keys()].join(', ')})`)
      else if (value.type === 'null') reader.problem(name, 'null is not a value; leave it out instead')
      else {
        const converted = reader.value(value, type, name)
        if (converted !== undefined) values.set(name, converted)
      }
    }
  }
  reader.throwIfAny()
  return values
}

/** Reads facts for a decision: the same as readValues(json, table.inputs). */
export function readFacts(json: JsonInput, inputs: ReadonlyMap<string, ValueType> | DecisionTable): Map<string, Value> {
  return readValues(json, inputs instanceof Map ? inputs : (inputs as DecisionTable).inputs)
}

/** Reads one value of a declared type. @throws InvalidFactsError, located at "value" */
export function readValue(json: JsonInput, type: ValueType): Value {
  const node = toNode(json)
  const reader = new Reader()
  let value: Value | undefined
  if (node.type === 'null') reader.problem('value', 'null is not a value')
  else value = reader.value(node, type, 'value')
  reader.throwIfAny()
  return value!
}

/**
 * @internal Reads one value of a declared type located at `path`, adding every problem to
 * `problems`, as FactsJson.readValue with a problem list; for readers of other files.
 */
export function readValueAt(node: JsonNode, type: ValueType, path: string, problems: string[]): Value | undefined {
  const reader = new Reader()
  const value = reader.value(node, type, path)
  problems.push(...reader.problems)
  return reader.problems.length === 0 ? value : undefined
}

/** The types of a result's outputs: the declared outputs, each a list under COLLECT. */
export function resultTypes(table: DecisionTable): Map<string, ValueType> {
  const types = new Map<string, ValueType>()
  for (const [name, type] of table.outputs) types.set(name, table.hitPolicy === 'COLLECT' ? listOf(type) : type)
  return types
}

class Reader {
  readonly problems: string[] = []

  problem(path: string, message: string): void {
    this.problems.push(`${path}: ${message}`)
  }

  throwIfAny(): void {
    if (this.problems.length > 0) throw new InvalidFactsError(this.problems)
  }

  value(node: JsonNode, type: ValueType, path: string): Value | undefined {
    if (isPrimitive(type)) {
      switch (type) {
        case 'bool':
          if (node.type === 'boolean') return node.value
          return this.fail(path, 'expected true or false')
        case 'int':
          if (node.type === 'number' && node.integral) {
            const v = BigInt(node.text)
            if (v >= INT64_MIN && v <= INT64_MAX) return v
            return this.fail(path, `expected an integer between ${INT64_MIN} and ${INT64_MAX}`)
          }
          return this.fail(path, 'expected an integer')
        case 'string':
          if (node.type === 'string') return node.value
          return this.fail(path, 'expected a string')
        case 'decimal':
          return this.decimal(node, path)
        case 'money':
          return this.money(node, path)
        case 'date':
          return this.date(node, path)
      }
    }
    if (type.kind === 'list') {
      if (node.type !== 'array') return this.fail(path, 'expected an array')
      const items: Value[] = []
      node.items.forEach((item, i) => {
        const at = `${path}[${i}]`
        if (item.type === 'null') this.problem(at, 'null is not a value')
        else {
          const v = this.value(item, type.element, at)
          if (v !== undefined) items.push(v)
        }
      })
      return Object.freeze(items)
    }
    return this.struct(node, type, path)
  }

  private fail(path: string, message: string): undefined {
    this.problem(path, message)
    return undefined
  }

  private decimal(node: JsonNode, path: string): Decimal | undefined {
    if (node.type === 'number') {
      if (isFloatingNode(node)) {
        return this.fail(path, 'floating-point number; pass the JSON text or a bigint, or write the decimal as a string, e.g. "0.0980"')
      }
      return Decimal.parse(node.text)
    }
    if (node.type === 'string' && DECIMAL.test(node.value)) return Decimal.parse(node.value)
    return this.fail(path, 'expected a decimal as a number or a string in plain notation, e.g. "0.0980"')
  }

  private money(node: JsonNode, path: string): Money | undefined {
    const m = node.type === 'string' ? MONEY.exec(node.value) : null
    if (!m) return this.fail(path, `expected ${MONEY_FORM}`)
    if (!isCurrencyCode(m[2]!)) return this.fail(path, `expected ${MONEY_FORM}, with an ISO 4217 currency code`)
    return Money.of(Decimal.parse(m[1]!)!, m[2]!)
  }

  private date(node: JsonNode, path: string): LocalDate | undefined {
    if (node.type !== 'string' || !DATE.test(node.value)) return this.fail(path, `expected ${DATE_FORM}`)
    const date = LocalDate.parse(node.value)
    if (!date) return this.fail(path, 'expected an existing date as "yyyy-MM-dd"')
    return date
  }

  private struct(node: JsonNode, type: StructType, path: string): Value | undefined {
    if (node.type !== 'object') return this.fail(path, 'expected an object')
    const fields = new Map<string, Value>()
    for (const [name, value] of node.entries) {
      const at = `${path}.${name}`
      const fieldType = type.fields.get(name)
      if (fieldType === undefined) this.problem(at, `unknown field (declared: ${[...type.fields.keys()].join(', ')})`)
      else if (value.type !== 'null') {
        const v = this.value(value, fieldType, at)
        if (v !== undefined) fields.set(name, v)
      }
    }
    return fields
  }
}

// --- Writing -----------------------------------------------------------------------------------

/**
 * A value in its JSON form: money "<amount> <CCY>", dates "yyyy-MM-dd", decimals as strings
 * in plain notation, ints as numbers, lists as arrays and structs as objects.
 */
export function valueToJson(value: Value, path = 'value'): Writable {
  if (value === null) throw new RangeError(`${path}: null is not a value`)
  if (typeof value === 'boolean' || typeof value === 'string' || typeof value === 'bigint') return value
  if (value instanceof Decimal) return value.toPlainString()
  if (value instanceof Money || value instanceof LocalDate) return value.toString()
  if (Array.isArray(value)) return (value as readonly Value[]).map((item, i) => valueToJson(item, `${path}[${i}]`))
  if (value instanceof Map) {
    const out = new Map<string, Writable>()
    for (const [k, v] of value) out.set(k, valueToJson(v as Value, `${path}.${k}`))
    return out
  }
  if (typeof value === 'number') throw new RangeError(`${path}: floating-point numbers are not supported; use Decimal`)
  throw new RangeError(`${path}: unsupported value`)
}

/** Named values as a JSON-ready map, in the given order. */
export function valuesToJson(values: ReadonlyMap<string, Value>): Map<string, Writable> {
  const out = new Map<string, Writable>()
  for (const [name, value] of values) out.set(name, valueToJson(value, name))
  return out
}

/** Named values as compact JSON text, e.g. {"fee":"25000 VND","settles":"2026-11-02"}. */
export function writeValues(values: ReadonlyMap<string, Value>): string {
  return writeJson(valuesToJson(values))
}

/**
 * Plain JavaScript data for a value, for JSON.stringify and display: ints become numbers when
 * they are safe integers and strings otherwise, decimals, money and dates their text forms.
 */
export function valueToPlain(value: Value): unknown {
  if (typeof value === 'bigint') return value >= -(2n ** 53n) + 1n && value <= 2n ** 53n - 1n ? Number(value) : value.toString()
  if (value instanceof Decimal) return value.toPlainString()
  if (value instanceof Money || value instanceof LocalDate) return value.toString()
  if (Array.isArray(value)) return (value as readonly Value[]).map(valueToPlain)
  if (value instanceof Map) return Object.fromEntries([...value].map(([k, v]) => [k, valueToPlain(v as Value)]))
  return value
}
