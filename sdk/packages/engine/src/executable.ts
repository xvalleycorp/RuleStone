// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// What the engine evaluates, as the Java engine's Executable, Progress, Deadline and FactsHasher;
// and compiled decision tables, as CompiledDecision.

import { Bindings, CompiledExpression, ExpressionEnvironment } from './cel/environment.js'
import type { LocalDate } from './date.js'
import { DecisionEvaluationError, DecisionTimeoutError, ExpressionError } from './errors.js'
import { writeValues } from './facts.js'
import { compareJavaStrings, javaUtf8 } from './json.js'
import { compileLocals } from './locals.js'
import type { ModelStep, ParamSetVersion, PathStep, ReasonCode } from './record.js'
import type { SuppliedModel } from './models.js'
import {
  PARAMS, checkParams, paramsType, selectParams, undeclaredParams, type ParamsBinding, type Registry,
} from './params.js'
import { ruleVersionOf, tableRef, type DecisionTable } from './table.js'
import { containsStruct, type ValueType } from './types.js'
import type { Value } from './values.js'
import type { Versioned } from './versions.js'

/** Implicit input of every decision: the date the decision is made for. */
export const BUSINESS_DATE = 'businessDate'

export const ID = /^[A-Za-z][A-Za-z0-9_.-]*$/
export const VERSION_FROM_DIGIT = /^[0-9][A-Za-z0-9_.-]*$/
export const REASON_CODE = /^[A-Z][A-Z0-9_]*$/

/** One rule examined, and whether it matched. */
export interface TraceStep {
  readonly ruleId: string
  readonly matched: boolean
}

/** A monotonic clock in milliseconds, e.g. performance.now. */
export type MonotonicClock = () => number

/** The default monotonic clock: performance.now, available in browsers, Node.js and edge runtimes. */
export const monotonicNow: MonotonicClock = () => globalThis.performance.now()

/** When an evaluation must be done, as Deadline. */
export class Deadline {
  static readonly NONE = new Deadline(undefined, 0, Infinity)

  private constructor(
    private readonly now: MonotonicClock | undefined,
    private readonly start: number,
    /** The timeout, in milliseconds. */
    readonly timeoutMs: number,
  ) {}

  /** A deadline timeoutMs from now. */
  static after(timeoutMs: number, now: MonotonicClock): Deadline {
    return new Deadline(now, now(), timeoutMs)
  }

  /** Whether the time is up. */
  passed(): boolean {
    return this.now !== undefined && this.now() - this.start > this.timeoutMs
  }

  /** The time left, never negative; max when there is no timeout or more than max is left. */
  remaining(maxMs: number): number {
    if (this.now === undefined) return maxMs
    const left = this.timeoutMs - (this.now() - this.start)
    return left <= 0 ? 0 : left < maxMs ? left : maxMs
  }
}

/** Computes factsHash: SHA-256, or HMAC-SHA256 with a key, of the canonical inputs, as FactsHasher. */
export class FactsHasher {
  private hmacKey: Promise<CryptoKey> | undefined

  constructor(private readonly key?: Uint8Array) {}

  async hash(bindings: Bindings, withoutParams = false): Promise<string> {
    const inputs = new Map(bindings.canonicalValues())
    inputs.delete(BUSINESS_DATE)
    if (withoutParams) inputs.delete(PARAMS)
    const canonical = javaUtf8(writeValues(inputs)) as Uint8Array<ArrayBuffer>
    const subtle = globalThis.crypto.subtle
    if (!this.key) return 'sha256:' + hex(await subtle.digest('SHA-256', canonical))
    this.hmacKey ??= subtle.importKey('raw', this.key as Uint8Array<ArrayBuffer>, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
    return 'hmac-sha256:' + hex(await subtle.sign('HMAC', await this.hmacKey, canonical))
  }
}

function hex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** What a replay or re-score fixes for one evaluation, apart from the parameter set versions, as Progress.Overrides. */
export interface Overrides {
  /** Node id to the exact version a table node of a flow must use. */
  readonly tables: ReadonlyMap<string, string>
  /** Node id to the recorded step whose result a model node uses. */
  readonly models: ReadonlyMap<string, ModelStep>
  /** Whether model nodes take their results from `models` and the supplied results only, calling no model. */
  readonly recordedModels: boolean
}

export const NO_OVERRIDES: Overrides = { tables: new Map(), models: new Map(), recordedModels: false }

/** What one evaluation has done so far: enough to record it, also when it fails half-way. */
export class Progress {
  /** Every rule examined, in order, across all tables. */
  readonly trace: TraceStep[] = []
  private readonly steps: PathStep[] = []
  private current: (() => PathStep) | undefined
  factsHash: string | undefined
  private readonly used: ParamSetVersion[] = []

  constructor(
    /** The parameter set versions the request forces, by set id. */
    readonly forcedParams: ReadonlyMap<string, string> = new Map(),
    readonly overrides: Overrides = NO_OVERRIDES,
  ) {}

  /** Records that the evaluation used `version` of parameter set `set`. */
  useParams(set: string, version: string): void {
    if (!this.used.some((p) => p.set === set && p.version === version)) this.used.push({ set, version })
  }

  /** The parameter set versions used, each once, sorted by set id and then version. */
  params(): ParamSetVersion[] {
    return [...this.used].sort((a, b) => compareJavaStrings(a.set, b.set) || compareJavaStrings(a.version, b.version))
  }

  /** Starts evaluating a node; until close(), path() ends with `step`. */
  open(step: () => PathStep): void {
    this.current = step
  }

  /** Finishes the node opened last. */
  close(): void {
    if (this.current) {
      this.steps.push(this.current())
      this.current = undefined
    }
  }

  /** The nodes visited, ending with the node being evaluated, if any. */
  path(): PathStep[] {
    return this.current ? [...this.steps, this.current()] : [...this.steps]
  }

  /** Ids of the matched rules in the trace from index `from`. */
  matchedSince(from: number): string[] {
    return this.trace.slice(from).filter((t) => t.matched).map((t) => t.ruleId)
  }
}

/** Facts as a caller passes them. */
export type FactsInput = ReadonlyMap<string, unknown> | { readonly [name: string]: unknown }

export function factsMap(facts: FactsInput): Map<string, unknown> {
  return new Map<string, unknown>(facts instanceof Map ? facts : Object.entries(facts))
}

/** One evaluation of an executable. */
export interface RunContext {
  readonly facts: ReadonlyMap<string, unknown>
  readonly models: ReadonlyMap<string, SuppliedModel>
  readonly businessDate: LocalDate
  readonly correlationId: string | undefined
  readonly deadline: Deadline
  readonly progress: Progress
  readonly hasher: FactsHasher
}

/** The outputs and reason codes of a successful run. */
export interface Run {
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly ReasonCode[]
}

/** A compiled decision table or flow, as Executable. */
export interface Executable {
  readonly kind: 'table' | 'flow'
  readonly definition: Versioned & { readonly inputs: ReadonlyMap<string, ValueType>; readonly outputs: ReadonlyMap<string, ValueType> }
  readonly ruleVersion: string
  run(context: RunContext): Promise<Run>
}

// --- Decision tables ---------------------------------------------------------------------------

interface CompiledRule {
  readonly id: string
  readonly when: CompiledExpression
  readonly then: ReadonlyMap<string, CompiledExpression>
  readonly reasonCode: string | undefined
}

/** The outputs and reason codes of one table evaluation. */
export interface Outcome {
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly string[]
}

/** A compiled decision table, as CompiledDecision. */
export class CompiledTable implements Executable {
  readonly kind = 'table'

  constructor(
    readonly table: DecisionTable,
    readonly ruleVersion: string,
    private readonly environment: ExpressionEnvironment,
    private readonly rules: readonly CompiledRule[],
    /** The parameters the table reads, if it declares any. */
    readonly params: ParamsBinding | undefined,
  ) {}

  get definition(): DecisionTable {
    return this.table
  }

  async run(context: RunContext): Promise<Run> {
    const { progress } = context
    const table = this.table
    // The step shows the rules matched so far, also when the evaluation fails.
    progress.open(() => ({ node: table.id, type: 'table', version: this.ruleVersion, matchedRules: progress.matchedSince(0) }))
    const bindings = this.bind(context.facts, context.businessDate, progress)
    progress.factsHash = await context.hasher.hash(bindings, this.params !== undefined)
    if (context.models.size > 0) {
      throw this.failure(`model results supplied for [${[...context.models.keys()].join(', ')}], but a decision table has no model nodes`)
    }
    const outcome = this.evaluate(bindings, progress.trace, context.deadline)
    progress.close()
    return { output: outcome.output, reasonCodes: outcome.reasonCodes.map((code) => ({ code, source: table.id })) }
  }

  /** Converts and checks the facts, and binds the parameters, as CompiledDecision.bind. */
  bind(facts: ReadonlyMap<string, unknown>, businessDate: LocalDate, progress: Progress): Bindings {
    if (facts.has(BUSINESS_DATE)) {
      throw this.failure(
        `invalid input ${BUSINESS_DATE}: reserved name; the business date comes from DecisionRef.asOf or the engine clock and cannot be passed as a fact`,
      )
    }
    if (this.params && facts.has(PARAMS)) {
      throw this.failure(
        `invalid input ${PARAMS}: reserved name; the parameters come from parameter set ${this.params.declaration.set} and cannot be passed as a fact`,
      )
    }
    const inputs = new Map<string, unknown>(facts)
    inputs.set(BUSINESS_DATE, businessDate)
    if (this.params) {
      inputs.set(PARAMS, selectParams(this.params, businessDate, progress.forcedParams, (set, version) => progress.useParams(set, version)))
    }
    try {
      return this.environment.bind(inputs)
    } catch (e) {
      if (e instanceof ExpressionError) throw this.failure(`invalid input ${e.message}`, e)
      throw e
    }
  }

  /** Applies the rules, checking the deadline before each rule, as CompiledDecision.evaluate. */
  evaluate(bindings: Bindings, trace: TraceStep[], deadline: Deadline): Outcome {
    const checkTime = (): void => {
      if (deadline.passed()) throw new DecisionTimeoutError(this.table.id, this.ruleVersion, deadline.timeoutMs, trace)
    }
    const matches = (rule: CompiledRule): boolean => {
      let matched: Value
      try {
        matched = rule.when.evaluate(bindings)
      } catch (e) {
        if (e instanceof ExpressionError) throw this.failure(`rule ${rule.id} condition failed: ${e.message}`, e)
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
          if (e instanceof ExpressionError) throw this.failure(`rule ${rule.id} output '${name}' failed: ${e.message}`, e)
          throw e
        }
      }
      return output
    }
    const reasons = (rules: readonly CompiledRule[]) => rules.flatMap((r) => (r.reasonCode !== undefined ? [r.reasonCode] : []))
    const matchAll = (): CompiledRule[] => {
      const matched: CompiledRule[] = []
      for (const rule of this.rules) {
        checkTime()
        if (matches(rule)) matched.push(rule)
      }
      return matched
    }

    switch (this.table.hitPolicy) {
      case 'FIRST':
        for (const rule of this.rules) {
          checkTime()
          if (matches(rule)) return { output: outputs(rule), reasonCodes: reasons([rule]) }
        }
        return { output: new Map(), reasonCodes: [] }
      case 'UNIQUE': {
        const matched = matchAll()
        if (matched.length > 1) {
          throw this.failure(`UNIQUE hit policy violated: rules [${matched.map((r) => r.id).join(', ')}] all matched`)
        }
        if (matched.length === 0) return { output: new Map(), reasonCodes: [] }
        return { output: outputs(matched[0]!), reasonCodes: reasons(matched) }
      }
      case 'COLLECT': {
        const matched = matchAll()
        const collected = new Map<string, Value[]>()
        for (const name of this.table.outputs.keys()) collected.set(name, [])
        for (const rule of matched) for (const [name, value] of outputs(rule)) collected.get(name)!.push(value)
        const output = new Map<string, Value>()
        for (const [name, values] of collected) output.set(name, Object.freeze(values))
        return { output, reasonCodes: reasons(matched) }
      }
    }
  }

  private failure(message: string, cause?: unknown): DecisionEvaluationError {
    return new DecisionEvaluationError(this.table.id, this.ruleVersion, message, cause === undefined ? undefined : { cause })
  }
}

/** Compiles one table, adding every problem to `problems`, as CompiledDecision.compile. */
export async function compileTable(
  table: DecisionTable,
  maxIterations: number,
  scales: ReadonlyMap<string, number>,
  registry: Registry,
  problems: string[],
): Promise<CompiledTable | undefined> {
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
  let params: ParamsBinding | undefined
  let declared: ValueType | undefined
  if (table.params) {
    params = checkParams(at, table.params, inputs, registry, problems)
    declared = paramsType(table.params)
    // Reported, then compiled with the parameters in its place so that other problems are found too.
    inputs.delete(PARAMS)
  }
  const paramsInput = inputs.has(PARAMS)
  let environment: ExpressionEnvironment
  const variables = new Map<string, ValueType>(inputs)
  variables.set(BUSINESS_DATE, 'date')
  if (declared !== undefined) variables.set(PARAMS, declared)
  const options = { variables, maxIterations, currencyScales: scales }
  try {
    environment = new ExpressionEnvironment(options)
  } catch (e) {
    if (e instanceof RangeError) {
      problems.push(`${at}inputs: ${e.message}`)
      return undefined
    }
    throw e
  }
  const parsing = environment
  const undeclared = (source: string): string | undefined => undeclaredParams(parsing, source, table.params, paramsInput)
  const failedLocals = new Set<string>()
  if (table.let && table.let.size > 0) {
    environment = compileLocals(table, environment, options, at, problems, failedLocals, undeclared)
  }
  const compileExpression = (source: string, type: ValueType, where: string): CompiledExpression | undefined => {
    const paramsProblem = undeclared(source)
    if (paramsProblem !== undefined) {
      problems.push(`${where}: ${paramsProblem}`)
      return undefined
    }
    if (failedLocals.size > 0) {
      try {
        // A rule that reads a local value that could not be compiled: that problem is reported.
        if (environment.references(source).some((n) => failedLocals.has(n))) return undefined
      } catch (e) {
        if (!(e instanceof ExpressionError)) throw e
      }
    }
    try {
      return environment.compile(source, type)
    } catch (e) {
      if (e instanceof ExpressionError) {
        problems.push(`${where}: ${e.message}`)
        return undefined
      }
      throw e
    }
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
    const when = compileExpression(rule.when, 'bool', `${ruleAt}.when`)
    const then = new Map<string, CompiledExpression>()
    for (const [output, source] of rule.then) {
      const type = table.outputs.get(output)
      if (type === undefined) {
        problems.push(`${ruleAt}.then.${output}: undeclared output (declared: [${[...table.outputs.keys()].join(', ')}])`)
      } else if (!containsStruct(type)) {
        const expression = compileExpression(source, type, `${ruleAt}.then.${output}`)
        if (expression) then.set(output, expression)
      }
    }
    if (when) rules.push({ id: rule.id, when, then, reasonCode: rule.reasonCode })
  }
  if (problems.length > before) return undefined
  return new CompiledTable(table, await ruleVersionOf(table), environment, rules, params)
}
