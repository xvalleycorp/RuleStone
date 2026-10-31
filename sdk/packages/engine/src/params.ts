// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Parameter sets and the .params.json format, as ParameterSetJson; and, as the Java engine's
// Parameters, the checks of every set, the checks of a table's "params" declaration against
// every version of its set, and the selection of the version bound as params.

import type { ExpressionEnvironment } from './cel/environment.js'
import { isValidName } from './cel/environment.js'
import type { LocalDate } from './date.js'
import { ExpressionError, InvalidDecisionError } from './errors.js'
import { readValueAt, valueToJson } from './facts.js'
import { JsonSyntaxError, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import { TableReader, sha256Hex, sortedMap, typeJson, type ParamsDeclaration } from './table.js'
import { containsStruct, structOf, typeToString, typesEqual, type ValueType } from './types.js'
import { toValue, type Value } from './values.js'
import { selectVersion, versionProblems } from './versions.js'

/** The variable that tables read their parameters from. */
export const PARAMS = 'params'

const KIND = 'parameter set'
const NO_STRUCTS = 'parameters must be bool, int, string, decimal, money, date or lists of them'
const INVALID_NAME = "must be an identifier (a letter or '_', then letters, digits or '_') and not a reserved word"
const ID = /^[A-Za-z][A-Za-z0-9_.-]*$/
const VERSION_FROM_DIGIT = /^[0-9][A-Za-z0-9_.-]*$/

/** One parameter: its type and value. */
export interface Parameter {
  readonly type: ValueType
  readonly value: Value
}

/**
 * A versioned set of named, typed business values, as ParameterSet: a .params.json file. Versioned
 * and effective-dated as tables are. Parameter set ids are a namespace of their own.
 */
export interface ParameterSet {
  readonly id: string
  readonly version: string
  readonly description?: string
  /** First day in force, inclusive. */
  readonly effectiveFrom?: LocalDate
  /** First day no longer in force, exclusive. */
  readonly effectiveTo?: LocalDate
  /** The parameters by name, in file order. */
  readonly values: ReadonlyMap<string, Parameter>
}

const SET_FIELDS = ['id', 'version', 'description', 'effectiveFrom', 'effectiveTo', 'values']
const VALUE_FIELDS = ['type', 'value']

/**
 * Reads a parameter set from .params.json text. Unknown and duplicate keys, and values that do not
 * match their type, are errors.
 * @throws InvalidDecisionError listing every problem found
 */
export function parseParameterSet(text: string): ParameterSet {
  let root: JsonNode | undefined
  try {
    // Like the Java reader, anything after the first JSON value is ignored.
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
  const reader = new TableReader()
  reader.unknownFields(root, SET_FIELDS, '$')
  const id = reader.requiredText(root, 'id', '$')
  const version = reader.requiredText(root, 'version', '$')
  const description = reader.optionalText(root, 'description', '$')
  const effectiveFrom = reader.optionalDate(root, 'effectiveFrom')
  const effectiveTo = reader.optionalDate(root, 'effectiveTo')
  if (effectiveFrom && effectiveTo && effectiveFrom.compareTo(effectiveTo) >= 0) {
    reader.problem('$.effectiveTo', 'must be after effectiveFrom')
  }
  const values = new Map<string, Parameter>()
  const node = root.entries.get('values')
  if (!node || node.type !== 'object') {
    reader.problem('$.values', 'required object of name to { "type", "value" }')
  } else {
    for (const [name, entry] of node.entries) {
      const parameter = readParameter(reader, entry, `$.values.${name}`)
      if (parameter) values.set(name, parameter)
    }
  }
  if (reader.problems.length > 0) throw new InvalidDecisionError(reader.problems)
  return {
    id,
    version,
    ...(description !== undefined ? { description } : {}),
    ...(effectiveFrom ? { effectiveFrom } : {}),
    ...(effectiveTo ? { effectiveTo } : {}),
    values,
  }
}

function readParameter(reader: TableReader, node: JsonNode, path: string): Parameter | undefined {
  if (node.type !== 'object') {
    reader.problem(path, 'expected an object with "type" and "value"')
    return undefined
  }
  reader.unknownFields(node, VALUE_FIELDS, path)
  const typeNode = node.entries.get('type')
  let type: ValueType | undefined
  if (!typeNode || typeNode.type === 'null') {
    reader.problem(`${path}.type`, 'required: the parameter\'s type, e.g. "decimal"')
  } else {
    type = reader.type(typeNode, `${path}.type`)
    if (type !== undefined && containsStruct(type)) {
      reader.problem(`${path}.type`, NO_STRUCTS)
      type = undefined
    }
  }
  const valueNode = node.entries.get('value')
  if (!valueNode || valueNode.type === 'null') {
    reader.problem(`${path}.value`, "required: the parameter's value")
    return undefined
  }
  if (type === undefined) return undefined
  const value = readValueAt(valueNode, type, `${path}.value`, reader.problems)
  return value === undefined ? undefined : { type, value }
}

/**
 * The canonical form, as ParameterSetJson.write: compact JSON with sorted keys, absent optional
 * fields omitted and values in the FactsJson forms. The content hash is computed over it.
 */
export function writeParameterSet(set: ParameterSet): string {
  const json = new Map<string, Writable>()
  json.set('id', set.id)
  json.set('version', set.version)
  if (set.description !== undefined) json.set('description', set.description)
  if (set.effectiveFrom) json.set('effectiveFrom', set.effectiveFrom.toString())
  if (set.effectiveTo) json.set('effectiveTo', set.effectiveTo.toString())
  json.set(
    'values',
    sortedMap(
      [...set.values].map(
        ([name, p]) =>
          [name, sortedMap<Writable>([['type', typeJson(p.type)], ['value', valueToJson(p.value)]])] as [string, Writable],
      ),
    ),
  )
  return writeJson(sortedMap(json))
}

/** The set's exact version: <version>@sha256:<hash of the canonical form>. */
export async function parameterSetVersionOf(set: ParameterSet): Promise<string> {
  return `${set.version}@sha256:${await sha256Hex(writeParameterSet(set))}`
}

function setRef(set: ParameterSet): string {
  return `${set.id}@${set.version}`
}

/** @internal a parameter set version that passed its checks, with its exact version */
export interface CompiledSet {
  readonly set: ParameterSet
  readonly version: string
}

/** @internal every registered parameter set */
export interface Registry {
  readonly registered: ReadonlyMap<string, readonly ParameterSet[]>
  readonly compiled: ReadonlyMap<string, readonly CompiledSet[]>
}

/** @internal checks every set and its versions, as Parameters.Registry.compile */
export async function compileParameterSets(sets: Iterable<ParameterSet>, problems: string[]): Promise<Registry> {
  const registered = new Map<string, ParameterSet[]>()
  const compiled = new Map<string, CompiledSet[]>()
  for (const set of sets) {
    if (!registered.has(set.id)) registered.set(set.id, [])
    registered.get(set.id)!.push(set)
    const at = `${KIND} ${setRef(set)} `
    const before = problems.length
    if (!ID.test(set.id)) problems.push(`${at}id: must match [A-Za-z][A-Za-z0-9_.-]*`)
    if (!ID.test(set.version) && !VERSION_FROM_DIGIT.test(set.version)) {
      problems.push(`${at}version: must be letters, digits, '.', '_' or '-'`)
    }
    if (set.effectiveFrom && set.effectiveTo && set.effectiveFrom.compareTo(set.effectiveTo) >= 0) {
      problems.push(`${at}effectiveTo: must be after effectiveFrom`)
    }
    for (const [name, parameter] of set.values) {
      const path = `values.${name}`
      if (!isValidName(name)) problems.push(`${at}${path}: ${INVALID_NAME}`)
      if (containsStruct(parameter.type)) {
        problems.push(`${at}${path}: ${NO_STRUCTS}`)
        continue
      }
      try {
        toValue(parameter.value, parameter.type, path)
      } catch (e) {
        if (!(e instanceof ExpressionError)) throw e
        problems.push(`${at}${e.message}`)
      }
    }
    if (problems.length > before) continue
    if (!compiled.has(set.id)) compiled.set(set.id, [])
    compiled.get(set.id)!.push({ set, version: await parameterSetVersionOf(set) })
  }
  for (const versions of registered.values()) for (const p of versionProblems(versions)) problems.push(`${KIND} ${p}`)
  return { registered, compiled }
}

/** @internal the type of params: a struct of the declared parameters with a valid name and type */
export function paramsType(params: ParamsDeclaration): ValueType | undefined {
  const fields = new Map<string, ValueType>()
  for (const [name, type] of params.values) if (isValidName(name) && !containsStruct(type)) fields.set(name, type)
  return fields.size === 0 ? undefined : structOf(fields)
}

/** @internal a checked declaration, ready for evaluation */
export interface ParamsBinding {
  readonly declaration: ParamsDeclaration
  /** The versions it may use: every version of the set, or the pinned one. */
  readonly versions: readonly CompiledSet[]
}

/** @internal checks a declaration against every version of its set it may use, as Parameters.check */
export function checkParams(
  at: string,
  params: ParamsDeclaration,
  inputs: ReadonlyMap<string, ValueType>,
  registry: Registry,
  problems: string[],
): ParamsBinding | undefined {
  const before = problems.length
  if (inputs.has(PARAMS)) {
    problems.push(`${at}inputs.${PARAMS}: reserved name: the parameters declared in "params" are read as params.<name>`)
  }
  if (params.values.size === 0) problems.push(`${at}params.values: declare at least one parameter`)
  const invalid = new Set<string>()
  for (const [name, type] of params.values) {
    if (!isValidName(name)) {
      problems.push(`${at}params.values.${name}: ${INVALID_NAME}`)
      invalid.add(name)
    } else if (containsStruct(type)) {
      problems.push(`${at}params.values.${name}: ${NO_STRUCTS}`)
      invalid.add(name)
    }
  }
  const registered = registry.registered.get(params.set)
  if (!registered) {
    problems.push(`${at}params.set: no parameter set '${params.set}' is registered`)
    return undefined
  }
  let candidates = registered
  if (params.version !== undefined) {
    candidates = registered.filter((s) => s.version === params.version)
    if (candidates.length === 0) {
      problems.push(`${at}params.version: parameter set '${params.set}' has no version '${params.version}'`)
      return undefined
    }
  }
  for (const set of candidates) {
    for (const [name, type] of params.values) {
      if (invalid.has(name)) continue
      const parameter = set.values.get(name)
      if (!parameter) {
        problems.push(`${at}params.values.${name}: parameter set ${setRef(set)} has no parameter '${name}'`)
      } else if (!typesEqual(parameter.type, type)) {
        problems.push(
          `${at}params.values.${name}: declared ${typeToString(type)} but parameter set ${setRef(set)} has it as ${typeToString(parameter.type)}`,
        )
      }
    }
  }
  if (problems.length > before) return undefined
  const compiled = registry.compiled.get(params.set) ?? []
  if (compiled.length < registered.length) return undefined // a version failed its checks, which is reported
  return { declaration: params, versions: compiled.filter((c) => candidates.includes(c.set)) }
}

/**
 * @internal why source cannot read what it reads from params, or undefined if it can, as
 * Parameters.undeclared
 */
export function undeclaredParams(
  environment: ExpressionEnvironment,
  source: string,
  params: ParamsDeclaration | undefined,
  paramsInput: boolean,
): string | undefined {
  if (paramsInput && !params) return undefined
  let fields: readonly string[]
  try {
    if (!params && !environment.references(source).includes(PARAMS)) return undefined
    fields = environment.selectedFields(source, PARAMS)
  } catch (e) {
    if (e instanceof ExpressionError) return undefined // reported when the expression is compiled
    throw e
  }
  if (!params) {
    const read = fields.length === 0 ? PARAMS : fields.map((f) => `${PARAMS}.${f}`).join(', ')
    return `reads ${read}, but no parameters are declared; declare them in "params"`
  }
  const missing = fields.filter((f) => !params.values.has(f))
  if (missing.length === 0) return undefined
  return (
    `reads ${missing.map((f) => `${PARAMS}.${f}`).join(', ')}, which params.values does not declare ` +
    `(declared: [${[...params.values.keys()].join(', ')}])`
  )
}

/**
 * @internal the declared values of the version used on date: the pinned version, else the forced
 * one, else the version in force. Calls `used` with the exact version.
 * @throws DecisionNotFoundError
 */
export function selectParams(
  binding: ParamsBinding,
  date: LocalDate,
  forced: ReadonlyMap<string, string>,
  used: (set: string, version: string) => void,
): Map<string, Value> {
  const set = binding.declaration.set
  const version = binding.declaration.version ?? forced.get(set)
  const chosen = selectVersion(set, binding.versions.map((c) => c.set), version, date, KIND)
  const compiled = binding.versions.find((c) => c.set === chosen)!
  used(set, compiled.version)
  const values = new Map<string, Value>()
  for (const name of binding.declaration.values.keys()) values.set(name, chosen.values.get(name)!.value)
  return values
}
