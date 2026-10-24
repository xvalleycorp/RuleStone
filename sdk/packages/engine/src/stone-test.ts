// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Test cases in the .stone-test.json format, read and checked as StoneTestJson and
// StoneTestCase do in Java, so the same files run in both engines.

import { LocalDate } from './date.js'
import { Decimal, javaIsBlank } from './decimal.js'
import type { DecisionResult, RuleStoneEngine } from './engine.js'
import { InvalidFactsError, RuleStoneError } from './errors.js'
import { readValues, resultTypes } from './facts.js'
import { JsonSyntaxError, parseJson, type JsonNode } from './json.js'
import { Money } from './money.js'
import { allowedList, type DecisionTable } from './table.js'
import { versionInForce } from './versions.js'
import { typesEqual, type ValueType } from './types.js'
import type { Value } from './values.js'

const FILE_FIELDS = ['decision', 'description', 'cases']
const CASE_FIELDS = ['name', 'description', 'version', 'asOf', 'facts', 'expect']
const EXPECT_FIELDS = ['output', 'exactOutput', 'reasonCodes', 'matchedRules', 'ruleVersion', 'error']

/** One test case: a request and the expected outcome. Absent expectations are not checked. */
export interface StoneTestCase {
  readonly source: string
  readonly decision: string
  readonly name: string
  readonly description?: string
  readonly version?: string
  readonly asOf?: LocalDate
  readonly facts: ReadonlyMap<string, Value>
  /** Expected outputs; null means the output must be absent. */
  readonly output: ReadonlyMap<string, Value | null>
  readonly exactOutput: boolean
  readonly reasonCodes?: readonly string[]
  readonly matchedRules?: readonly string[]
  readonly ruleVersion?: string
  /** Text the failure message must contain. */
  readonly error?: string
}

/** A test file is invalid, as InvalidStoneTestException. Lists every problem. */
export class InvalidStoneTestError extends RuleStoneError {
  constructor(
    readonly source: string,
    readonly problems: readonly string[],
  ) {
    super(
      `invalid test file ${source} (${problems.length} ${problems.length === 1 ? 'problem' : 'problems'}):` +
        problems.map((p) => '\n  - ' + p.replaceAll('\n', '\n    ')).join(''),
    )
    this.name = 'InvalidStoneTestError'
  }
}

/** A case whose expectations were not met; the message lists every mismatch. */
export class StoneTestFailure extends Error {
  constructor(
    message: string,
    readonly problems: readonly string[],
  ) {
    super(message)
    this.name = 'StoneTestFailure'
  }
}

/**
 * Reads the cases of a .stone-test.json file. With the tables, facts and expected outputs are
 * read with the declared types (money as "1500000 VND", dates as "2026-11-01"); without them,
 * numbers become bigint or Decimal and objects Maps.
 * @throws InvalidStoneTestError listing every problem
 */
export function parseStoneTests(text: string, source: string, tables?: readonly DecisionTable[]): StoneTestCase[] {
  let root: JsonNode | undefined
  try {
    root = parseJson(text, { failOnTrailingTokens: true })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidStoneTestError(source, [`invalid JSON: ${e.message}`])
    throw e
  }
  return new Reader(source, tables).file(root)
}

interface Schema {
  readonly inputs: ReadonlyMap<string, ValueType>
  readonly results: ReadonlyMap<string, ValueType>
}

type ObjectNode = JsonNode & { type: 'object' }

class Reader {
  private readonly problems: string[] = []

  constructor(
    private readonly source: string,
    private readonly tables: readonly DecisionTable[] | undefined,
  ) {}

  file(root: JsonNode | undefined): StoneTestCase[] {
    if (!root || root.type !== 'object') throw new InvalidStoneTestError(this.source, ['$: expected a JSON object'])
    this.unknownFields(root, FILE_FIELDS, '$')
    const decision = this.requiredText(root, 'decision', '$')
    this.optionalText(root, 'description', '$')
    const cases = root.entries.get('cases')
    const result: StoneTestCase[] = []
    if (!cases || cases.type !== 'array' || cases.items.length === 0) {
      this.problem('$.cases', 'required non-empty array of test cases')
    } else {
      const names = new Set<string>()
      cases.items.forEach((node, i) => {
        const c = this.testCase(node, `$.cases[${i}]`, decision)
        if (c && names.has(c.name)) this.problem(`$.cases[${i}].name`, `duplicate case name "${c.name}"`)
        if (c) {
          names.add(c.name)
          result.push(c)
        }
      })
    }
    if (this.problems.length > 0) throw new InvalidStoneTestError(this.source, this.problems)
    return result
  }

  private testCase(node: JsonNode, path: string, decision: string): StoneTestCase | undefined {
    if (node.type !== 'object') {
      this.problem(path, 'expected an object')
      return undefined
    }
    const before = this.problems.length
    this.unknownFields(node, CASE_FIELDS, path)
    const name = this.requiredText(node, 'name', path)
    const description = this.optionalText(node, 'description', path)
    const version = this.optionalText(node, 'version', path)
    const asOfText = this.optionalText(node, 'asOf', path)
    let asOf: LocalDate | undefined
    if (asOfText !== undefined) {
      asOf = LocalDate.parse(asOfText)
      if (!asOf) this.problem(`${path}.asOf`, `expected an ISO date (yyyy-MM-dd) but got '${asOfText}'`)
    }
    const schema = this.schema(decision, version, asOf, path)
    const facts = this.facts(node.entries.get('facts'), `${path}.facts`, schema)

    const expect = node.entries.get('expect')
    const at = `${path}.expect`
    if (!expect || expect.type !== 'object') {
      this.problem(at, 'required object (use {} to check only that the evaluation succeeds)')
      return undefined
    }
    this.unknownFields(expect, EXPECT_FIELDS, at)
    let output = new Map<string, Value | null>()
    const outputNode = expect.entries.get('output')
    if (outputNode !== undefined) {
      if (outputNode.type === 'object') output = this.outputs(outputNode, `${at}.output`, schema)
      else this.problem(`${at}.output`, 'expected an object of output names to values')
    }
    let exactOutput = false
    const exactNode = expect.entries.get('exactOutput')
    if (exactNode !== undefined) {
      if (exactNode.type !== 'boolean') this.problem(`${at}.exactOutput`, 'expected true or false')
      else if (outputNode === undefined) this.problem(`${at}.exactOutput`, 'requires "output"')
      exactOutput = exactNode.type === 'boolean' && exactNode.value
    }
    const reasonCodes = this.stringList(expect, 'reasonCodes', at)
    const matchedRules = this.stringList(expect, 'matchedRules', at)
    const ruleVersion = this.optionalText(expect, 'ruleVersion', at)
    const error = this.optionalText(expect, 'error', at)
    if (error !== undefined) {
      if (javaIsBlank(error)) this.problem(`${at}.error`, 'required non-empty text that the error message must contain')
      const others = [...expect.entries.keys()].filter((f) => f !== 'error' && EXPECT_FIELDS.includes(f))
      if (others.length > 0) {
        this.problem(at, `"error" cannot be combined with [${others.join(', ')}]: a failed evaluation has no output, reason codes or matched rules`)
      }
    }
    if (this.problems.length > before) return undefined
    return {
      source: this.source,
      decision,
      name,
      ...(description !== undefined ? { description } : {}),
      ...(version !== undefined ? { version } : {}),
      ...(asOf ? { asOf } : {}),
      facts,
      output,
      exactOutput,
      ...(reasonCodes ? { reasonCodes } : {}),
      ...(matchedRules ? { matchedRules } : {}),
      ...(ruleVersion !== undefined ? { ruleVersion } : {}),
      ...(error !== undefined ? { error } : {}),
    }
  }

  private schema(decision: string, version: string | undefined, asOf: LocalDate | undefined, path: string): Schema | undefined {
    if (!this.tables) return undefined
    const versions = this.tables.filter((t) => t.id === decision)
    const inForce = asOf ? versionInForce(versions, asOf) : undefined
    const candidates =
      version !== undefined ? versions.filter((t) => t.version === version) : asOf ? (inForce ? [inForce] : []) : versions
    if (candidates.length === 0) return undefined
    const first = candidates[0]!
    const schema: Schema = { inputs: first.inputs, results: resultTypes(first) }
    for (const table of candidates) {
      if (!sameTypes(table.inputs, schema.inputs) || !sameTypes(resultTypes(table), schema.results)) {
        this.problem(path, `set "version" or "asOf": the versions of "${decision}" declare different inputs or outputs`)
        return undefined
      }
    }
    return schema
  }

  private facts(node: JsonNode | undefined, path: string, schema: Schema | undefined): Map<string, Value> {
    if (!node || node.type !== 'object') {
      this.problem(path, 'required object of fact names to values')
      return new Map()
    }
    if (schema) {
      if (this.nulls(node, path)) return new Map()
      try {
        return readValues(node, schema.inputs)
      } catch (e) {
        if (e instanceof InvalidFactsError) {
          e.problems.forEach((p) => this.problems.push(`${path}.${p}`))
          return new Map()
        }
        throw e
      }
    }
    const values = new Map<string, Value>()
    for (const [name, value] of node.entries) {
      const v = this.untyped(value, `${path}.${name}`)
      if (v !== undefined) values.set(name, v)
    }
    return values
  }

  private outputs(node: ObjectNode, path: string, schema: Schema | undefined): Map<string, Value | null> {
    const output = new Map<string, Value | null>()
    if (!schema) {
      for (const [name, value] of node.entries) output.set(name, value.type === 'null' ? null : (this.untyped(value, `${path}.${name}`) ?? null))
      return output
    }
    const values = new Map<string, JsonNode>()
    for (const [name, value] of node.entries) {
      if (value.type === 'null') {
        if (!schema.results.has(name)) this.problem(`${path}.${name}`, `not declared (declared: ${[...schema.results.keys()].join(', ')})`)
      } else {
        values.set(name, value)
      }
    }
    let converted = new Map<string, Value>()
    try {
      converted = readValues({ type: 'object', entries: values }, schema.results)
    } catch (e) {
      if (e instanceof InvalidFactsError) e.problems.forEach((p) => this.problems.push(`${path}.${p}`))
      else throw e
    }
    for (const [name, value] of node.entries) output.set(name, value.type === 'null' ? null : (converted.get(name) ?? null))
    return output
  }

  private nulls(node: JsonNode, path: string): boolean {
    if (node.type === 'null') {
      this.problem(path, 'null is not a value; leave the field out instead')
      return true
    }
    let found = false
    if (node.type === 'array') node.items.forEach((item, i) => (found = this.nulls(item, `${path}[${i}]`) || found))
    else if (node.type === 'object') for (const [k, v] of node.entries) found = this.nulls(v, `${path}.${k}`) || found
    return found
  }

  /** A JSON value without types: integers as bigint, other numbers as Decimal, objects as Maps. */
  private untyped(node: JsonNode, path: string): Value | undefined {
    switch (node.type) {
      case 'null':
        this.problem(path, 'null is not a value; leave the field out instead')
        return undefined
      case 'string':
      case 'boolean':
        return node.value
      case 'number':
        return node.integral ? BigInt(node.text) : Decimal.parse(node.text)!
      case 'array':
        return node.items.map((item, i) => this.untyped(item, `${path}[${i}]`) ?? null)
      case 'object': {
        const fields = new Map<string, Value>()
        for (const [k, v] of node.entries) fields.set(k, this.untyped(v, `${path}.${k}`) ?? null)
        return fields
      }
    }
  }

  private stringList(node: ObjectNode, field: string, path: string): string[] | undefined {
    const value = node.entries.get(field)
    if (value === undefined) return undefined
    if (value.type !== 'array') {
      this.problem(`${path}.${field}`, 'expected an array of strings ([] for none)')
      return undefined
    }
    const items: string[] = []
    value.items.forEach((item, i) => {
      if (item.type === 'string') items.push(item.value)
      else this.problem(`${path}.${field}[${i}]`, 'expected a string')
    })
    return items
  }

  private requiredText(node: ObjectNode, field: string, path: string): string {
    const value = node.entries.get(field)
    if (!value || value.type !== 'string' || javaIsBlank(value.value)) {
      this.problem(`${path}.${field}`, 'required non-empty string')
      return ''
    }
    return value.value
  }

  private optionalText(node: ObjectNode, field: string, path: string): string | undefined {
    const value = node.entries.get(field)
    if (value === undefined) return undefined
    if (value.type !== 'string') {
      this.problem(`${path}.${field}`, 'expected a string')
      return undefined
    }
    return value.value
  }

  private unknownFields(node: ObjectNode, allowed: readonly string[], path: string): void {
    for (const name of node.entries.keys()) {
      if (!allowed.includes(name)) this.problem(`${path}.${name}`, `unknown field (allowed: ${allowedList(allowed)})`)
    }
  }

  private problem(path: string, message: string): void {
    this.problems.push(`${path}: ${message}`)
  }
}

function sameTypes(a: ReadonlyMap<string, ValueType>, b: ReadonlyMap<string, ValueType>): boolean {
  if (a.size !== b.size) return false
  for (const [name, type] of a) {
    const other = b.get(name)
    if (other === undefined || !typesEqual(type, other)) return false
  }
  return true
}

// --- Checking ----------------------------------------------------------------------------------

/** The result of a successful check: the decision result, or none when the case expected an error. */
export interface StoneTestOutcome {
  readonly result?: DecisionResult
  readonly error?: RuleStoneError
}

/**
 * Evaluates the case and checks every expectation.
 * @throws StoneTestFailure listing every expectation that was not met
 */
export async function checkStoneTestCase(engine: RuleStoneEngine, testCase: StoneTestCase): Promise<StoneTestOutcome> {
  let result: DecisionResult | undefined
  let failure: RuleStoneError | undefined
  try {
    result = await engine.evaluate(
      {
        id: testCase.decision,
        ...(testCase.version !== undefined ? { version: testCase.version } : {}),
        ...(testCase.asOf ? { asOf: testCase.asOf } : {}),
      },
      testCase.facts,
    )
  } catch (e) {
    if (e instanceof RuleStoneError) failure = e
    else throw e
  }
  const fail = (problems: string[], evaluated?: DecisionResult): never => {
    let message = `${testCase.source}, case "${testCase.name}"`
    if (evaluated) message += `, evaluated with ${testCase.decision}@${versionLabel(evaluated.ruleVersion)}`
    message += ':' + problems.map((p) => '\n  - ' + p).join('')
    throw new StoneTestFailure(message, problems)
  }
  if (testCase.error !== undefined) {
    if (!failure) {
      fail([
        `expected the evaluation to fail with a message containing "${testCase.error}" but it succeeded with ` +
          `output ${format(result!.output)}, matched rules [${result!.matchedRules.join(', ')}]`,
      ])
    }
    if (!failure!.message.includes(testCase.error)) {
      fail([`expected a failure message containing "${testCase.error}" but the message was: ${failure!.message}`])
    }
    return { error: failure! }
  }
  if (failure) fail([`evaluation failed: ${failure.message}`])
  const actual = result!
  const problems: string[] = []
  for (const [name, expected] of testCase.output) {
    const value = actual.output.get(name)
    if (expected === null) {
      if (value !== undefined) problems.push(`output "${name}": expected no value but was ${format(value)}`)
    } else if (value === undefined) {
      problems.push(`output "${name}": expected ${format(expected)} but the decision produced no such output`)
    } else if (!same(expected, value)) {
      problems.push(`output "${name}": expected ${format(expected)} but was ${format(value)}`)
    }
  }
  if (testCase.exactOutput) {
    for (const [name, value] of actual.output) {
      if (testCase.output.get(name) == null) problems.push(`output "${name}": not expected but was ${format(value)}`)
    }
  }
  const list = (what: string, expected: readonly string[] | undefined, got: readonly string[]) => {
    if (expected && (expected.length !== got.length || expected.some((e, k) => e !== got[k]))) {
      problems.push(`${what}: expected [${expected.join(', ')}] but was [${got.join(', ')}]`)
    }
  }
  list('reason codes', testCase.reasonCodes, actual.reasonCodes)
  list('matched rules', testCase.matchedRules, actual.matchedRules)
  if (testCase.ruleVersion !== undefined) {
    const ok = testCase.ruleVersion.includes('@')
      ? actual.ruleVersion === testCase.ruleVersion
      : versionLabel(actual.ruleVersion) === testCase.ruleVersion
    if (!ok) problems.push(`rule version: expected ${testCase.ruleVersion} but was ${actual.ruleVersion}`)
  }
  if (problems.length > 0) fail(problems, actual)
  return { result: actual }
}

function versionLabel(ruleVersion: string): string {
  const at = ruleVersion.indexOf('@')
  return at < 0 ? ruleVersion : ruleVersion.slice(0, at)
}

function exactNumber(value: Value): Decimal | undefined {
  if (value instanceof Decimal) return value
  if (typeof value === 'bigint') return Decimal.fromBigInt(value)
  return undefined
}

/** Numbers by value, money by currency and value, lists and maps element by element. */
function same(expected: Value, actual: Value): boolean {
  const e = exactNumber(expected)
  const a = exactNumber(actual)
  if (e && a) return e.compareTo(a) === 0
  if (expected instanceof Money && actual instanceof Money) return expected.currency === actual.currency && expected.amount.compareTo(actual.amount) === 0
  if (Array.isArray(expected) && Array.isArray(actual)) {
    return expected.length === actual.length && expected.every((item, k) => same(item as Value, (actual as readonly Value[])[k]!))
  }
  if (expected instanceof Map && actual instanceof Map) {
    if (expected.size !== actual.size) return false
    for (const [k, v] of expected) if (!actual.has(k) || !same(v as Value, actual.get(k) as Value)) return false
    return true
  }
  if (expected instanceof LocalDate && actual instanceof LocalDate) return expected.equals(actual)
  return expected === actual
}

/** A value as it would be written in JSON: strings quoted, decimals in plain notation. */
function format(value: Value | ReadonlyMap<string, Value>): string {
  if (typeof value === 'string') return `"${value}"`
  if (value instanceof Decimal) return value.toPlainString()
  if (value instanceof Money || value instanceof LocalDate) return `"${value.toString()}"`
  if (Array.isArray(value)) return '[' + (value as readonly Value[]).map(format).join(', ') + ']'
  if (value instanceof Map) return '{' + [...value].map(([k, v]) => `${k}=${format(v as Value)}`).join(', ') + '}'
  return String(value)
}
