// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The engine: compiles decision tables (reporting every problem together), selects the
// version by id, version or effective date, evaluates with the FIRST, UNIQUE and COLLECT hit
// policies and produces a decision record for every evaluation, as the Java engine does.

import { Bindings, CompiledExpression, ExpressionEnvironment, validateCurrencyScale } from './cel/environment.js'
import { LocalDate, dateInZone } from './date.js'
import {
  AuditError, DecisionEvaluationError, DecisionNotFoundError, ExpressionError, InvalidDecisionError, RuleStoneError,
} from './errors.js'
import { readFacts, writeValues, type JsonInput } from './facts.js'
import type { DecisionRecord, PathStep, ReasonCode } from './record.js'
import { ruleVersionOf, sha256Hex, tableRef, type DecisionTable } from './table.js'
import { selectVersion, versionProblems } from './versions.js'
import { containsStruct, type ValueType } from './types.js'
import type { Value } from './values.js'

/** Implicit input of every decision: the date the decision is made for. */
export const BUSINESS_DATE = 'businessDate'

/** Names the engine in decision records. */
export const ENGINE_NAME = 'rulestone-js 0.1.0'

const ID = /^[A-Za-z][A-Za-z0-9_.-]*$/
const VERSION_FROM_DIGIT = /^[0-9][A-Za-z0-9_.-]*$/
const REASON_CODE = /^[A-Z][A-Z0-9_]*$/

/** Which decision to evaluate, and which version of it. */
export interface DecisionRef {
  readonly id: string
  /** Exactly this version, regardless of dates. */
  readonly version?: string
  /** The business date: selects the version in force and is the rules' businessDate. */
  readonly asOf?: LocalDate | string
  /** The caller's id for the business transaction, copied into the record. No personal data. */
  readonly correlationId?: string
}

/** One rule examined, and whether it matched. */
export interface TraceStep {
  readonly ruleId: string
  readonly matched: boolean
}

/** The outcome of evaluating one decision, as DecisionResult. */
export interface DecisionResult {
  readonly decisionId: string
  /** <version>@sha256:<hash>: the exact rule content used. */
  readonly ruleVersion: string
  /** Outputs in declaration order; under COLLECT every output is a list. */
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly string[]
  readonly trace: readonly TraceStep[]
  /** Whether at least one rule matched. */
  readonly matched: boolean
  /** Ids of the matched rules, in evaluation order. */
  readonly matchedRules: readonly string[]
  readonly record: DecisionRecord
}

/** Receives the record of every evaluation; throwing (or rejecting) fails the evaluation. */
export type AuditSink = (record: DecisionRecord) => void | Promise<void>

export interface EngineOptions {
  /** Every version of every decision; the engine selects one per request. */
  readonly tables: Iterable<DecisionTable>
  /** Upper bound on the iterations of the list macros in one expression evaluation. Default 10,000. */
  readonly maxIterations?: number
  /** Minor units for money.round(), overriding ISO 4217, e.g. { XAU: 4 }. */
  readonly currencyScales?: ReadonlyMap<string, number> | { readonly [currency: string]: number }
  /** The current instant; default new Date(). */
  readonly clock?: () => Date
  /** IANA time zone for "today" when a request has no asOf; default the runtime's zone. */
  readonly timeZone?: string
  /** Source of record ids; default random UUIDs. */
  readonly recordIds?: () => string
  /** Called with every record, in order, before evaluate returns or throws. */
  readonly auditSinks?: readonly AuditSink[]
}

/** A compiled decision version. */
export interface CompiledDecision {
  readonly table: DecisionTable
  readonly ruleVersion: string
}

interface CompiledRule {
  readonly id: string
  readonly when: CompiledExpression
  readonly then: ReadonlyMap<string, CompiledExpression>
  readonly reasonCode: string | undefined
}

interface Compiled extends CompiledDecision {
  readonly environment: ExpressionEnvironment
  readonly rules: readonly CompiledRule[]
}

/**
 * Compiles and type-checks every table, checks versions for duplicates and contradictory dates, and
 * returns an immutable engine.
 * @throws InvalidDecisionError listing every problem in every table
 * @throws RangeError for an invalid currency scale
 */
export async function createEngine(options: EngineOptions): Promise<RuleStoneEngine> {
  const scales = options.currencyScales instanceof Map ? options.currencyScales : new Map(Object.entries(options.currencyScales ?? {}))
  for (const [currency, digits] of scales) validateCurrencyScale(currency, digits)
  const maxIterations = options.maxIterations ?? 10_000
  const problems: string[] = []
  const byId = new Map<string, Compiled[]>()
  for (const table of options.tables) {
    const compiled = await compile(table, maxIterations, scales, problems)
    if (compiled) {
      let versions = byId.get(table.id)
      if (!versions) byId.set(table.id, (versions = []))
      versions.push(compiled)
    }
  }
  for (const versions of byId.values()) checkVersions(versions, problems)
  if (problems.length > 0) throw new InvalidDecisionError(problems)
  return new RuleStoneEngine(byId, options)
}

async function compile(
  table: DecisionTable,
  maxIterations: number,
  scales: ReadonlyMap<string, number>,
  problems: string[],
): Promise<Compiled | undefined> {
  const at = tableRef(table) + ' '
  const before = problems.length
  if (!ID.test(table.id)) problems.push(`${at}id: must match [A-Za-z][A-Za-z0-9_.-]*`)
  if (!ID.test(table.version) && !VERSION_FROM_DIGIT.test(table.version)) {
    problems.push(`${at}version: must be letters, digits, '.', '_' or '-'`)
  }
  if (table.effectiveFrom && table.effectiveTo && table.effectiveFrom.compareTo(table.effectiveTo) >= 0) {
    problems.push(`${at}effectiveTo: must be after effectiveFrom`)
  }
  for (const [name, type] of table.outputs) {
    if (containsStruct(type)) problems.push(`${at}outputs.${name}: outputs must be bool, int, string, decimal, money, date or lists of them`)
  }
  if (table.rules.length === 0) problems.push(`${at}rules: a decision table needs at least one rule`)

  const inputs = new Map(table.inputs)
  if (inputs.delete(BUSINESS_DATE)) {
    problems.push(
      `${at}inputs.${BUSINESS_DATE}: reserved name; every decision receives ${BUSINESS_DATE} implicitly (DecisionRef.asOf, else the engine clock)`,
    )
  }
  let environment: ExpressionEnvironment
  try {
    const variables = new Map<string, ValueType>(inputs)
    variables.set(BUSINESS_DATE, 'date')
    environment = new ExpressionEnvironment({ variables, maxIterations, currencyScales: scales })
  } catch (e) {
    if (e instanceof RangeError) {
      problems.push(`${at}inputs: ${e.message}`)
      return undefined
    }
    throw e
  }

  const rules: CompiledRule[] = []
  const ids = new Set<string>()
  for (const rule of table.rules) {
    const ruleAt = `${at}rules[${rule.id}]`
    if (ids.has(rule.id)) problems.push(`${ruleAt}: duplicate rule id`)
    ids.add(rule.id)
    if (rule.reasonCode !== undefined && !REASON_CODE.test(rule.reasonCode)) {
      problems.push(`${ruleAt}.reasonCode: must be UPPER_SNAKE_CASE, e.g. DTI_HIGH`)
    }
    const when = compileExpression(environment, rule.when, 'bool', `${ruleAt}.when`, problems)
    const then = new Map<string, CompiledExpression>()
    for (const [output, source] of rule.then) {
      const type = table.outputs.get(output)
      if (type === undefined) {
        problems.push(`${ruleAt}.then.${output}: undeclared output (declared: [${[...table.outputs.keys()].join(', ')}])`)
      } else if (!containsStruct(type)) {
        const expression = compileExpression(environment, source, type, `${ruleAt}.then.${output}`, problems)
        if (expression) then.set(output, expression)
      }
    }
    if (when) rules.push({ id: rule.id, when, then, reasonCode: rule.reasonCode })
  }
  if (problems.length > before) return undefined
  return { table, ruleVersion: await ruleVersionOf(table), environment, rules }
}

function compileExpression(
  environment: ExpressionEnvironment,
  source: string,
  type: ValueType,
  at: string,
  problems: string[],
): CompiledExpression | undefined {
  try {
    return environment.compile(source, type)
  } catch (e) {
    if (e instanceof ExpressionError) {
      problems.push(`${at}: ${e.message}`)
      return undefined
    }
    throw e
  }
}

/** Versions of one decision must be consistent; see versionProblems. */
function checkVersions(versions: Compiled[], problems: string[]): void {
  problems.push(...versionProblems(versions.map((c) => c.table)))
}

function randomId(): string {
  const c = globalThis.crypto
  if (typeof c.randomUUID === 'function') return c.randomUUID()
  const b = c.getRandomValues(new Uint8Array(16))
  b[6] = (b[6]! & 0x0f) | 0x40
  b[8] = (b[8]! & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** An immutable set of compiled decisions. */
export class RuleStoneEngine {
  private readonly clock: () => Date
  private readonly timeZone: string | undefined
  private readonly recordIds: () => string
  private readonly sinks: readonly AuditSink[]

  /** @internal use createEngine */
  constructor(
    private readonly byId: ReadonlyMap<string, readonly Compiled[]>,
    options: EngineOptions,
  ) {
    this.clock = options.clock ?? (() => new Date())
    this.timeZone = options.timeZone
    this.recordIds = options.recordIds ?? randomId
    this.sinks = [...(options.auditSinks ?? [])]
  }

  /** Every compiled version, by decision id, sorted by effectiveFrom. */
  decisions(): ReadonlyMap<string, readonly CompiledDecision[]> {
    return this.byId
  }

  /** The version a reference selects on its business date. @throws DecisionNotFoundError */
  resolve(ref: DecisionRef | string): CompiledDecision {
    const r = normalizeRef(ref)
    return this.select(r, r.asOf ?? dateInZone(this.clock(), this.timeZone))
  }

  /**
   * Evaluates a decision with JSON facts, read with the declared inputs of the version the
   * reference selects (FactsJson forms: "1500000 VND", "2026-11-01", decimals as numbers or strings).
   * @throws InvalidFactsError for facts that do not match the declared types
   */
  async evaluateJson(ref: DecisionRef | string, json: JsonInput): Promise<DecisionResult> {
    const facts = readFacts(json, this.resolve(ref).table.inputs)
    return this.evaluate(ref, facts)
  }

  /**
   * Evaluates a decision. Facts the decision does not declare are ignored.
   * @throws DecisionNotFoundError, DecisionEvaluationError or AuditError; the error's `record` holds the decision record
   */
  async evaluate(ref: DecisionRef | string, facts: ReadonlyMap<string, unknown> | { readonly [name: string]: unknown }): Promise<DecisionResult> {
    const r = normalizeRef(ref)
    const evaluatedAt = this.clock()
    const businessDate = r.asOf ?? dateInZone(evaluatedAt, this.timeZone)
    const recordId = this.recordIds()
    if (!recordId || recordId.trim() === '') throw new Error('recordIds supplied an empty record id')
    const trace: TraceStep[] = []
    let decision: Compiled | undefined
    let factsHash: string | undefined
    const record = (status: 'OK' | 'ERROR', output: ReadonlyMap<string, Value>, reasonCodes: ReasonCode[], error?: string): DecisionRecord => {
      const path: PathStep[] = decision
        ? [{ node: decision.table.id, type: 'table', version: decision.ruleVersion, matchedRules: trace.filter((t) => t.matched).map((t) => t.ruleId) }]
        : []
      return {
        recordId,
        ...(r.correlationId !== undefined ? { correlationId: r.correlationId } : {}),
        decision: r.id,
        ...(decision ? { version: decision.ruleVersion } : {}),
        businessDate,
        evaluatedAt,
        engine: ENGINE_NAME,
        status,
        ...(factsHash !== undefined ? { factsHash } : {}),
        output,
        reasonCodes,
        path,
        ...(error !== undefined ? { error } : {}),
      }
    }
    let result: DecisionResult
    try {
      decision = this.select(r, businessDate)
      const bindings = bind(decision, facts, businessDate)
      factsHash = await hashFacts(bindings)
      const outcome = run(decision, bindings, trace)
      const source = decision.table.id
      result = {
        decisionId: decision.table.id,
        ruleVersion: decision.ruleVersion,
        output: outcome.output,
        reasonCodes: outcome.reasonCodes,
        trace,
        matched: trace.some((t) => t.matched),
        matchedRules: trace.filter((t) => t.matched).map((t) => t.ruleId),
        record: record('OK', outcome.output, outcome.reasonCodes.map((code) => ({ code, source }))),
      }
    } catch (e) {
      // RuleStone messages never contain input values; other errors might, so only their type is recorded.
      const message = e instanceof RuleStoneError ? e.message : `internal error: ${e instanceof Error ? e.name : typeof e}`
      const failed = record('ERROR', new Map(), [], message)
      if (e instanceof RuleStoneError) e.record = failed
      try {
        await this.audit(failed)
      } catch (audit) {
        if (e instanceof Error) (e as { suppressed?: unknown }).suppressed = audit
      }
      throw e
    }
    await this.audit(result.record)
    return result
  }

  private async audit(record: DecisionRecord): Promise<void> {
    for (const sink of this.sinks) {
      try {
        await sink(record)
      } catch (e) {
        const error = new AuditError(record.recordId, e)
        error.record = record
        throw error
      }
    }
  }

  private select(ref: NormalizedRef, date: LocalDate): Compiled {
    const versions = this.byId.get(ref.id)
    if (!versions) throw new DecisionNotFoundError(`unknown decision '${ref.id}'`)
    const table = selectVersion(ref.id, versions.map((d) => d.table), ref.version, date)
    return versions.find((d) => d.table === table)!
  }
}

interface NormalizedRef {
  readonly id: string
  readonly version: string | undefined
  readonly asOf: LocalDate | undefined
  readonly correlationId: string | undefined
}

function normalizeRef(ref: DecisionRef | string): NormalizedRef {
  const r: DecisionRef = typeof ref === 'string' ? { id: ref } : ref
  if (r.id.trim() === '') throw new RangeError('id must not be blank')
  if (r.correlationId !== undefined && r.correlationId.trim() === '') throw new RangeError('correlationId must not be blank')
  let asOf: LocalDate | undefined
  if (typeof r.asOf === 'string') {
    asOf = LocalDate.parse(r.asOf)
    if (!asOf) throw new RangeError('asOf must be an ISO date (yyyy-MM-dd)')
  } else {
    asOf = r.asOf
  }
  return { id: r.id, version: r.version, asOf, correlationId: r.correlationId }
}

function failure(decision: Compiled, message: string, cause?: unknown): DecisionEvaluationError {
  return new DecisionEvaluationError(decision.table.id, decision.ruleVersion, message, cause === undefined ? undefined : { cause })
}

function bind(
  decision: Compiled,
  facts: ReadonlyMap<string, unknown> | { readonly [name: string]: unknown },
  businessDate: LocalDate,
): Bindings {
  const has = facts instanceof Map ? facts.has(BUSINESS_DATE) : Object.prototype.hasOwnProperty.call(facts, BUSINESS_DATE)
  if (has) {
    throw failure(
      decision,
      `invalid input ${BUSINESS_DATE}: reserved name; the business date comes from DecisionRef.asOf or the engine clock and cannot be passed as a fact`,
    )
  }
  const inputs = new Map<string, unknown>(facts instanceof Map ? facts : Object.entries(facts))
  inputs.set(BUSINESS_DATE, businessDate)
  try {
    return decision.environment.bind(inputs)
  } catch (e) {
    if (e instanceof ExpressionError) throw failure(decision, `invalid input ${e.message}`, e)
    throw e
  }
}

/** The SHA-256 of the declared inputs in canonical JSON form, without businessDate. */
async function hashFacts(bindings: Bindings): Promise<string> {
  const inputs = new Map(bindings.canonicalValues())
  inputs.delete(BUSINESS_DATE)
  return 'sha256:' + (await sha256Hex(writeValues(inputs)))
}

interface Outcome {
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly string[]
}

function run(decision: Compiled, bindings: Bindings, trace: TraceStep[]): Outcome {
  const matches = (rule: CompiledRule): boolean => {
    let matched: Value
    try {
      matched = rule.when.evaluate(bindings)
    } catch (e) {
      if (e instanceof ExpressionError) throw failure(decision, `rule ${rule.id} condition failed: ${e.message}`, e)
      throw e
    }
    trace.push({ ruleId: rule.id, matched: matched === true })
    return matched === true
  }
  const outputs = (rule: CompiledRule): Map<string, Value> => {
    const output = new Map<string, Value>()
    for (const [name, expression] of rule.then) {
      try {
        output.set(name, expression.evaluate(bindings))
      } catch (e) {
        if (e instanceof ExpressionError) throw failure(decision, `rule ${rule.id} output '${name}' failed: ${e.message}`, e)
        throw e
      }
    }
    return output
  }
  const reasons = (rules: readonly CompiledRule[]) => rules.flatMap((r) => (r.reasonCode !== undefined ? [r.reasonCode] : []))
  const matchAll = () => decision.rules.filter((rule) => matches(rule))

  switch (decision.table.hitPolicy) {
    case 'FIRST':
      for (const rule of decision.rules) {
        if (matches(rule)) return { output: outputs(rule), reasonCodes: reasons([rule]) }
      }
      return { output: new Map(), reasonCodes: [] }
    case 'UNIQUE': {
      const matched = matchAll()
      if (matched.length > 1) {
        throw failure(decision, `UNIQUE hit policy violated: rules [${matched.map((r) => r.id).join(', ')}] all matched`)
      }
      if (matched.length === 0) return { output: new Map(), reasonCodes: [] }
      return { output: outputs(matched[0]!), reasonCodes: reasons(matched) }
    }
    case 'COLLECT': {
      const matched = matchAll()
      const collected = new Map<string, Value[]>()
      for (const name of decision.table.outputs.keys()) collected.set(name, [])
      for (const rule of matched) for (const [name, value] of outputs(rule)) collected.get(name)!.push(value)
      const output = new Map<string, Value>()
      for (const [name, values] of collected) output.set(name, Object.freeze(values))
      return { output, reasonCodes: reasons(matched) }
    }
  }
}

/** The JSON text of a result's output, e.g. {"fee":"25000 VND"}. */
export function outputJson(result: DecisionResult): string {
  return writeValues(result.output)
}
