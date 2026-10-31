// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision tables and the .stone.json format, as DecisionTableJson: a strict reader that
// reports every problem with its JSON path, and the canonical form the content hash is
// computed over.

import { LocalDate } from './date.js'
import { javaIsBlank } from './decimal.js'
import { InvalidDecisionError } from './errors.js'
import { JsonSyntaxError, compareJavaStrings, javaUtf8, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import { PRIMITIVE_TYPES, isPrimitive, listOf, type PrimitiveType, type ValueType } from './types.js'

export type HitPolicy = 'FIRST' | 'UNIQUE' | 'COLLECT'

/** One row of a decision table. */
export interface Rule {
  readonly id: string
  readonly description?: string
  /** CEL condition; the rule matches when it is true. */
  readonly when: string
  /** Output name to CEL expression, in file order. */
  readonly then: ReadonlyMap<string, string>
  /** Reported when the rule matches, e.g. DTI_HIGH. */
  readonly reasonCode?: string
}

/** A versioned decision table. Structural and type errors are reported when an engine is built. */
export interface DecisionTable {
  readonly id: string
  readonly version: string
  readonly description?: string
  /** First day in force, inclusive. */
  readonly effectiveFrom?: LocalDate
  /** First day no longer in force, exclusive. */
  readonly effectiveTo?: LocalDate
  readonly hitPolicy: HitPolicy
  readonly inputs: ReadonlyMap<string, ValueType>
  readonly outputs: ReadonlyMap<string, ValueType>
  /**
   * Local values: name to CEL expression, in file order. Every rule can read them by name; each is
   * computed when first read, at most once per evaluation. Absent or empty when there are none.
   */
  readonly let?: ReadonlyMap<string, string>
  readonly rules: readonly Rule[]
}

/**
 * Whether date falls within the table's own declared period, effectiveFrom <= date < effectiveTo.
 * This ignores other versions: a later version supersedes this one from its effectiveFrom. To
 * find the version in force, use versionInForce.
 */
export function isEffectiveOn(table: DecisionTable, date: LocalDate): boolean {
  return (
    (table.effectiveFrom === undefined || date.compareTo(table.effectiveFrom) >= 0) &&
    (table.effectiveTo === undefined || date.compareTo(table.effectiveTo) < 0)
  )
}

/** id@version, for messages. */
export function tableRef(table: DecisionTable): string {
  return `${table.id}@${table.version}`
}

const TABLE_FIELDS = ['id', 'version', 'description', 'effectiveFrom', 'effectiveTo', 'hitPolicy', 'inputs', 'outputs', 'let', 'rules']
const RULE_FIELDS = ['id', 'description', 'when', 'then', 'reasonCode']
const HIT_POLICIES: readonly HitPolicy[] = ['UNIQUE', 'FIRST', 'COLLECT']

/** Allowed field names as Java lists them: sorted, comma-separated. */
export function allowedList(fields: readonly string[]): string {
  return [...fields].sort(compareJavaStrings).join(', ')
}

/**
 * Reads a decision table from .stone.json text. Unknown and duplicate keys are errors.
 * @throws InvalidDecisionError listing every problem found
 */
export function parseDecisionTable(text: string): DecisionTable {
  let root: JsonNode | undefined
  try {
    // Like the Java reader, anything after the first JSON value is ignored.
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  return new TableReader().table(root)
}

class TableReader {
  private readonly problems: string[] = []

  table(root: JsonNode | undefined): DecisionTable {
    if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
    this.unknownFields(root, TABLE_FIELDS, '$')
    const id = this.requiredText(root, 'id', '$')
    const version = this.requiredText(root, 'version', '$')
    const description = this.optionalText(root, 'description', '$')
    const effectiveFrom = this.optionalDate(root, 'effectiveFrom')
    const effectiveTo = this.optionalDate(root, 'effectiveTo')
    if (effectiveFrom && effectiveTo && effectiveFrom.compareTo(effectiveTo) >= 0) {
      this.problem('$.effectiveTo', 'must be after effectiveFrom')
    }
    const hitPolicy = this.hitPolicy(root)
    const inputs = this.typeMap(root, 'inputs')
    const outputs = this.typeMap(root, 'outputs')
    const locals = this.localValues(root)
    const rules = this.rules(root)
    if (this.problems.length > 0) throw new InvalidDecisionError(this.problems)
    return {
      id,
      version,
      ...(description !== undefined ? { description } : {}),
      ...(effectiveFrom ? { effectiveFrom } : {}),
      ...(effectiveTo ? { effectiveTo } : {}),
      hitPolicy,
      inputs,
      outputs,
      ...(locals.size > 0 ? { let: locals } : {}),
      rules,
    }
  }

  private localValues(root: JsonNode & { type: 'object' }): Map<string, string> {
    const node = root.entries.get('let')
    const locals = new Map<string, string>()
    if (!node || node.type === 'null') return locals
    if (node.type !== 'object') {
      this.problem('$.let', 'expected an object of name to CEL expression')
      return locals
    }
    for (const [name, value] of node.entries) {
      if (value.type === 'string') locals.set(name, value.value)
      else this.problem(`$.let.${name}`, 'expected a CEL expression string')
    }
    return locals
  }

  private rules(root: JsonNode & { type: 'object' }): Rule[] {
    const node = root.entries.get('rules')
    if (!node || node.type !== 'array') {
      this.problem('$.rules', 'required array')
      return []
    }
    const rules: Rule[] = []
    node.items.forEach((r, i) => {
      const path = `$.rules[${i}]`
      if (r.type !== 'object') {
        this.problem(path, 'expected an object')
        return
      }
      this.unknownFields(r, RULE_FIELDS, path)
      const id = this.requiredText(r, 'id', path)
      const description = this.optionalText(r, 'description', path)
      const when = this.requiredText(r, 'when', path)
      const then = new Map<string, string>()
      const thenNode = r.entries.get('then')
      if (thenNode !== undefined && thenNode.type !== 'object') {
        this.problem(`${path}.then`, 'expected an object of output expressions')
      } else if (thenNode !== undefined) {
        for (const [name, value] of thenNode.entries) {
          if (value.type === 'string') then.set(name, value.value)
          else {
            this.problem(
              `${path}.then.${name}`,
              `expected a CEL expression string (string literals need inner quotes, e.g. "'APPROVE'")`,
            )
          }
        }
      }
      const reasonCode = this.optionalText(r, 'reasonCode', path)
      rules.push({
        id,
        ...(description !== undefined ? { description } : {}),
        when,
        then,
        ...(reasonCode !== undefined ? { reasonCode } : {}),
      })
    })
    return rules
  }

  private hitPolicy(root: JsonNode & { type: 'object' }): HitPolicy {
    const text = this.requiredText(root, 'hitPolicy', '$')
    if (text === '') return 'FIRST'
    if ((HIT_POLICIES as readonly string[]).includes(text)) return text as HitPolicy
    this.problem('$.hitPolicy', `unknown hit policy '${text}' (expected UNIQUE, FIRST or COLLECT)`)
    return 'FIRST'
  }

  private typeMap(root: JsonNode & { type: 'object' }, field: string): Map<string, ValueType> {
    const node = root.entries.get(field)
    const types = new Map<string, ValueType>()
    if (!node || node.type !== 'object') {
      this.problem(`$.${field}`, 'required object of name to type')
      return types
    }
    for (const [name, value] of node.entries) {
      const type = this.type(value, `$.${field}.${name}`)
      if (type) types.set(name, type)
    }
    return types
  }

  private type(node: JsonNode, path: string): ValueType | undefined {
    if (node.type === 'string') {
      if ((PRIMITIVE_TYPES as readonly string[]).includes(node.value)) return node.value as PrimitiveType
      const hint = ['double', 'float', 'number'].includes(node.value) ? ' (floating point is not supported; use decimal)' : ''
      this.problem(path, `unknown type '${node.value}'${hint}`)
      return undefined
    }
    if (node.type === 'array') {
      if (node.items.length !== 1) {
        this.problem(path, 'a list type is written as a one-element array, e.g. ["decimal"]')
        return undefined
      }
      const element = this.type(node.items[0]!, `${path}[]`)
      return element === undefined ? undefined : listOf(element)
    }
    if (node.type === 'object') {
      if (node.entries.size === 0) {
        this.problem(path, 'a struct type must declare at least one field')
        return undefined
      }
      const fields = new Map<string, ValueType>()
      let valid = true
      for (const [name, value] of node.entries) {
        const fieldType = this.type(value, `${path}.${name}`)
        if (fieldType === undefined) valid = false
        else fields.set(name, fieldType)
      }
      return valid ? { kind: 'struct', fields } : undefined
    }
    this.problem(path, 'expected a type name, a struct object or a one-element list array')
    return undefined
  }

  private optionalDate(root: JsonNode & { type: 'object' }, field: string): LocalDate | undefined {
    const text = this.optionalText(root, field, '$')
    if (text === undefined) return undefined
    const date = LocalDate.parse(text)
    if (!date) this.problem(`$.${field}`, `expected an ISO date (yyyy-MM-dd) but got '${text}'`)
    return date
  }

  private requiredText(node: JsonNode & { type: 'object' }, field: string, path: string): string {
    const value = node.entries.get(field)
    if (!value || value.type !== 'string' || javaIsBlank(value.value)) {
      this.problem(`${path}.${field}`, 'required non-empty string')
      return ''
    }
    return value.value
  }

  private optionalText(node: JsonNode & { type: 'object' }, field: string, path: string): string | undefined {
    const value = node.entries.get(field)
    if (!value || value.type === 'null') return undefined
    if (value.type !== 'string') {
      this.problem(`${path}.${field}`, 'expected a string')
      return undefined
    }
    return value.value
  }

  private unknownFields(node: JsonNode & { type: 'object' }, allowed: readonly string[], path: string): void {
    for (const name of node.entries.keys()) {
      if (!allowed.includes(name)) this.problem(`${path}.${name}`, `unknown field (allowed: ${allowedList(allowed)})`)
    }
  }

  private problem(path: string, message: string): void {
    this.problems.push(`${path}: ${message}`)
  }
}

// --- Canonical form and content hash -----------------------------------------------------------

function sortedMap<V>(entries: Iterable<[string, V]>): Map<string, V> {
  return new Map([...entries].sort((a, b) => compareJavaStrings(a[0], b[0])))
}

function typeJson(type: ValueType): Writable {
  if (isPrimitive(type)) return type
  if (type.kind === 'list') return [typeJson(type.element)]
  return sortedMap([...type.fields].map(([n, t]) => [n, typeJson(t)] as [string, Writable]))
}

function typesJson(types: ReadonlyMap<string, ValueType>): Writable {
  return sortedMap([...types].map(([n, t]) => [n, typeJson(t)] as [string, Writable]))
}

/**
 * The canonical form, as DecisionTableJson.write: compact JSON with sorted keys and absent
 * optional fields omitted. The content hash is computed over it.
 */
export function writeDecisionTable(table: DecisionTable): string {
  const json = new Map<string, Writable>()
  json.set('id', table.id)
  json.set('version', table.version)
  if (table.description !== undefined) json.set('description', table.description)
  if (table.effectiveFrom) json.set('effectiveFrom', table.effectiveFrom.toString())
  if (table.effectiveTo) json.set('effectiveTo', table.effectiveTo.toString())
  json.set('hitPolicy', table.hitPolicy)
  json.set('inputs', typesJson(table.inputs))
  json.set('outputs', typesJson(table.outputs))
  // Omitted when empty, so that tables without local values keep their hash.
  if (table.let && table.let.size > 0) json.set('let', sortedMap(table.let))
  json.set(
    'rules',
    table.rules.map((rule) => {
      const r = new Map<string, Writable>()
      r.set('id', rule.id)
      if (rule.description !== undefined) r.set('description', rule.description)
      r.set('when', rule.when)
      r.set('then', sortedMap(rule.then))
      if (rule.reasonCode !== undefined) r.set('reasonCode', rule.reasonCode)
      return sortedMap(r)
    }),
  )
  return writeJson(sortedMap(json))
}

/** Lowercase hex SHA-256 of a Java string's UTF-8 bytes, with WebCrypto. */
export async function sha256Hex(text: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', javaUtf8(text) as Uint8Array<ArrayBuffer>)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** The table's rule version: <version>@sha256:<hash of the canonical form>. */
export async function ruleVersionOf(table: DecisionTable): Promise<string> {
  return `${table.version}@sha256:${await sha256Hex(writeDecisionTable(table))}`
}
