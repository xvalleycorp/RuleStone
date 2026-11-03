// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The engine: compiles decision tables and flows (reporting every problem together), selects the
// version by id, version or effective date, evaluates tables with the FIRST, UNIQUE and COLLECT hit
// policies and flows node by node, model nodes included, and produces a decision record for every
// evaluation, as the Java engine does.

import { validateCurrencyScale } from './cel/environment.js'
import { LocalDate, dateInZone } from './date.js'
import { AuditError, DecisionNotFoundError, DecisionTimeoutError, InvalidDecisionError, InvalidFactsError, RuleStoneError } from './errors.js'
import {
  BUSINESS_DATE, CompiledTable, Deadline, FactsHasher, NO_OVERRIDES, Progress, compileTable, factsMap, monotonicNow,
  type Executable, type FactsInput, type MonotonicClock, type Overrides, type TraceStep,
} from './executable.js'
import { readFacts, writeValues, type JsonInput } from './facts.js'
import { APPROVAL_CATALOG_ID, type ApprovalArtifact, type ApprovalVerifier, type NamedApproval } from './approvals.js'
import { flowVersionOf, parseDecisionFlow, type DecisionFlow } from './flow.js'
import { CompiledFlow, compileFlow, type Models } from './flow-engine.js'
import { readModels, type ModelInvoker, type SuppliedModel } from './models.js'
import { compileParameterSets, parameterSetVersionOf, parseParameterSet, type ParameterSet, type Registry } from './params.js'
import type { Release } from './releases.js'
import type { RepositorySnapshot } from './repository.js'
import type { DecisionRecord, ModelStep, ReasonCode } from './record.js'
import { parseDecisionTable, ruleVersionOf, type DecisionTable } from './table.js'
import { replay, rescore, type DecisionComparison, type ModelSource } from './replay.js'
import { selectVersion, versionProblems } from './versions.js'
import {
  MISSING_CATALOG, explainRecord, missingReasonCodes, parseReasonCodeCatalog, reasonCodeCatalogVersionOf,
  type ExplainableRecord, type Explanation, type ReasonCodeCatalog,
} from './explanation.js'
import type { Value } from './values.js'

export { BUSINESS_DATE }
export type { TraceStep }

/** Names the engine in decision records. */
export const ENGINE_NAME = 'rulestone-js 0.1.0'

/** Which decision to evaluate, and which version of it. */
export interface DecisionRef {
  readonly id: string
  /** Exactly this version, regardless of dates. */
  readonly version?: string
  /** The business date: selects the version in force and is the rules' businessDate. */
  readonly asOf?: LocalDate | string
  /** The caller's id for the business transaction, copied into the record. No personal data. */
  readonly correlationId?: string
  /**
   * Parameter set versions to use whatever their dates, by set id, e.g. { retail_credit: '1' }, to
   * replay a decision with the versions its record lists. A table that pins a version keeps it.
   */
  readonly params?: ReadonlyMap<string, string> | { readonly [set: string]: string }
  /** The timeout of this evaluation, in milliseconds, instead of the engine's; see EngineOptions.timeoutMs. */
  readonly timeoutMs?: number
}

/** The outcome of evaluating one decision, as DecisionResult. */
export interface DecisionResult {
  readonly decisionId: string
  /** <version>@sha256:<hash>: the exact rule content used. */
  readonly ruleVersion: string
  /** Outputs in declaration order; under COLLECT every output is a list. */
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly string[]
  /** Every rule examined, in order; for a flow, across all its table nodes. */
  readonly trace: readonly TraceStep[]
  /** Whether at least one rule matched. */
  readonly matched: boolean
  /** Ids of the matched rules, in evaluation order. */
  readonly matchedRules: readonly string[]
  readonly record: DecisionRecord
}

/** Receives the record of every evaluation; throwing (or rejecting) fails the evaluation. */
export type AuditSink = (record: DecisionRecord) => void | Promise<void>

/** Model results passed with the facts, by model node id. */
export type ModelsInput = ReadonlyMap<string, SuppliedModel> | { readonly [node: string]: SuppliedModel }

export interface EngineOptions {
  /** Every version of every decision table; the engine selects one per request. */
  readonly tables?: Iterable<DecisionTable>
  /** Every version of every decision flow, which use the tables; see parseDecisionFlow. */
  readonly flows?: Iterable<DecisionFlow>
  /** Every version of every parameter set that the tables and flows read; see parseParameterSet. */
  readonly paramSets?: Iterable<ParameterSet>
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
  /**
   * The reviewed messages of reason codes, for engine.explain; see parseReasonCodeCatalog. Every
   * reason code of every table and model node must then have a message.
   */
  readonly reasonCodeCatalog?: ReasonCodeCatalog
  /** Fail unless a reasonCodeCatalog is given. Default false. */
  readonly requireReasonCodeCatalog?: boolean
  /** The invoker of each model that model nodes in invoke mode call, by model id. */
  readonly modelInvokers?: ReadonlyMap<string, ModelInvoker> | { readonly [modelId: string]: ModelInvoker }
  /**
   * Run every model node in supplied mode, for tests and replays: the engine calls no model and
   * needs no invoker. Do not use it in production.
   */
  readonly suppliedModelsOnly?: boolean
  /**
   * Key for the record's factsHash, at least 16 bytes: the hash is then HMAC-SHA256, so that nobody
   * without the key can test guesses of low-variety inputs against a record.
   */
  readonly factsHashKey?: Uint8Array
  /**
   * Default timeout of every evaluation, in milliseconds, for requests without one. The engine checks
   * the time before each node and each rule, so it never interrupts a rule. Default: none.
   */
  readonly timeoutMs?: number
  /** The monotonic clock timeouts and model latencies are measured with, in milliseconds; default performance.now. For tests. */
  readonly monotonicClock?: MonotonicClock
  /**
   * Fail unless every table, flow, parameter set and reason-code catalog is covered by a signed
   * approval statement (see approvals) that this verifier verifies, whose subject names its kind,
   * id, version label and the content hash the engine computed itself, in which no approver is a
   * maker, and which names at least minApprovers approvers. Every statement given must verify.
   * Without it, approvals are ignored. As RuleStone.Builder.requireApproved.
   */
  readonly requireApproved?: ApprovalVerifier
  /**
   * Signed approval statements, in JWS compact serialization: the text of .approval.jws files,
   * named by their file name, or bare text, named approvals[i] by position.
   */
  readonly approvals?: Iterable<string | NamedApproval>
  /**
   * The id under which approval statements and releases name the reason-code catalog, which has no id
   * of its own; default 'reason-codes'. A repository names it.
   */
  readonly reasonCodeCatalogId?: string
  /**
   * Fail unless a signed release of this target (see release and snapshot) verifies with the keys of
   * requireApproved, which is then required, and lists exactly the tables, flows, parameter sets and
   * catalog given, at the exact versions the engine computed, every version of a decision together;
   * its sequence must not be lower than that of lastAcceptedRelease and, when it follows that release
   * directly, its previous must be that release's hash. Without it, a release is ignored. As
   * RuleStone.Builder.requireRelease. The release's canary and shadow versions are checked as in the
   * Java engine, but this engine does not route traffic to them.
   */
  readonly requireRelease?: string
  /** The signed release, in JWS compact serialization: the text of a .release.jws file, named by its file name, or bare text. */
  readonly release?: string | NamedApproval
  /** The release that this engine's predecessor accepted, or that a store kept, verified; see requireRelease. */
  readonly lastAcceptedRelease?: Release
  /**
   * Everything a repository returned for one target: its tables, flows, parameter sets and catalog, read
   * here and added to those given, its approval statements and its release. Content that cannot be read
   * is reported with every other problem, named by its source.
   */
  readonly snapshot?: RepositorySnapshot
}

/** A compiled version of a decision table or flow. */
export type CompiledDecision =
  | { readonly kind: 'table'; readonly table: DecisionTable; readonly definition: DecisionTable; readonly ruleVersion: string }
  | { readonly kind: 'flow'; readonly flow: DecisionFlow; readonly definition: DecisionFlow; readonly ruleVersion: string }

type Compiled = CompiledTable | CompiledFlow

/**
 * Compiles and type-checks every parameter set, table and flow, checks versions for duplicates and
 * contradictory dates, and returns an immutable engine.
 * @throws InvalidDecisionError listing every problem in every definition
 * @throws RangeError for an invalid currency scale, timeout or factsHashKey
 */
export async function createEngine(options: EngineOptions): Promise<RuleStoneEngine> {
  const scales = options.currencyScales instanceof Map ? options.currencyScales : new Map(Object.entries(options.currencyScales ?? {}))
  for (const [currency, digits] of scales) validateCurrencyScale(currency, digits)
  if (options.timeoutMs !== undefined) checkTimeout(options.timeoutMs)
  if (options.factsHashKey !== undefined && options.factsHashKey.length < 16) throw new RangeError('factsHashKey must be at least 16 bytes')
  const maxIterations = options.maxIterations ?? 10_000
  if (options.snapshot && options.release !== undefined) throw new TypeError('a release is already registered')
  if (options.requireRelease !== undefined) {
    if (!options.requireApproved) throw new TypeError('requireRelease needs requireApproved, whose keys verify the release')
    if (options.lastAcceptedRelease && options.lastAcceptedRelease.target !== options.requireRelease) {
      throw new TypeError(`the last accepted release is for target '${options.lastAcceptedRelease.target}', not '${options.requireRelease}'`)
    }
  }
  const content = readSnapshot(options)
  const problems: string[] = [...content.problems]
  const paramSets = [...(options.paramSets ?? []), ...content.paramSets]
  const registry: Registry = await compileParameterSets(paramSets, problems)
  const compiledTables = new Map<string, CompiledTable[]>()
  const registered = new Map<string, DecisionTable[]>()
  const tables = [...(options.tables ?? []), ...content.tables]
  for (const table of tables) {
    if (!registered.has(table.id)) registered.set(table.id, [])
    registered.get(table.id)!.push(table)
    const compiled = await compileTable(table, maxIterations, scales, registry, problems)
    if (compiled) {
      if (!compiledTables.has(table.id)) compiledTables.set(table.id, [])
      compiledTables.get(table.id)!.push(compiled)
    }
  }
  for (const versions of compiledTables.values()) problems.push(...versionProblems(versions.map((c) => c.table)))
  const flows = [...(options.flows ?? []), ...content.flows]
  const byId = new Map<string, Compiled[]>()
  for (const [id, versions] of compiledTables) byId.set(id, [...versions])
  const invokers = options.modelInvokers instanceof Map ? options.modelInvokers : new Map(Object.entries(options.modelInvokers ?? {}))
  const now = options.monotonicClock ?? monotonicNow
  const models: Models = { invokers, suppliedOnly: options.suppliedModelsOnly ?? false, now }
  const flowContext = {
    tables: compiledTables, registered, flowIds: new Set(flows.map((f) => f.id)), maxIterations, currencyScales: scales, models, registry,
  }
  const flowVersions = new Map<string, DecisionFlow[]>()
  for (const flow of flows) {
    if (!flowVersions.has(flow.id)) flowVersions.set(flow.id, [])
    flowVersions.get(flow.id)!.push(flow)
    const compiled = await compileFlow(flow, flowContext, problems)
    if (compiled) {
      if (!byId.has(flow.id)) byId.set(flow.id, [])
      byId.get(flow.id)!.push(compiled)
    }
  }
  for (const versions of flowVersions.values()) problems.push(...versionProblems(versions))
  const reasonCodeCatalog = content.catalog ?? options.reasonCodeCatalog
  const catalogId = content.catalogId ?? options.reasonCodeCatalogId ?? APPROVAL_CATALOG_ID
  if (reasonCodeCatalog) problems.push(...missingReasonCodes(tables, reasonCodeCatalog, flows))
  else if (options.requireReasonCodeCatalog) problems.push(MISSING_CATALOG)
  const loaded = options.requireApproved ? await approvalArtifacts(paramSets, tables, flows, reasonCodeCatalog, catalogId) : []
  if (options.requireApproved) {
    const approvals = [...(options.approvals ?? []), ...(options.snapshot?.approvals ?? [])]
      .map((a, i) => (typeof a === 'string' ? { name: `approvals[${i}]`, jws: a } : a))
    problems.push(...(await options.requireApproved.approvalProblems(approvals, loaded)))
  }
  if (options.requireRelease !== undefined) {
    const release = options.snapshot?.release ?? (typeof options.release === 'string' ? { jws: options.release } : options.release)
    problems.push(...(await options.requireApproved!.releaseProblems(options.requireRelease, release, options.lastAcceptedRelease, loaded)))
  }
  if (problems.length > 0) {
    throw new InvalidDecisionError(problems)
  }
  const catalog = reasonCodeCatalog
    ? { catalog: reasonCodeCatalog, version: await reasonCodeCatalogVersionOf(reasonCodeCatalog) }
    : undefined
  return new RuleStoneEngine(byId, registry, options, catalog)
}

/** The content of options.snapshot, read, as RuleStone.Builder.snapshot reads it. */
function readSnapshot(options: EngineOptions): {
  tables: DecisionTable[]; flows: DecisionFlow[]; paramSets: ParameterSet[]; catalog?: ReasonCodeCatalog; catalogId?: string
  problems: string[]
} {
  const content = { tables: [] as DecisionTable[], flows: [] as DecisionFlow[], paramSets: [] as ParameterSet[], problems: [] as string[] }
  let catalog: { catalog: ReasonCodeCatalog; id: string } | undefined
  for (const artifact of options.snapshot?.artifacts ?? []) {
    try {
      switch (artifact.kind) {
        case 'table':
          content.tables.push(parseDecisionTable(artifact.content))
          break
        case 'flow':
          content.flows.push(parseDecisionFlow(artifact.content))
          break
        case 'params':
          content.paramSets.push(parseParameterSet(artifact.content))
          break
        case 'catalog':
          if (catalog || options.reasonCodeCatalog) content.problems.push(`${artifact.source}: a reason-code catalog is already registered`)
          else catalog = { catalog: parseReasonCodeCatalog(artifact.content), id: artifact.id }
      }
    } catch (e) {
      if (!(e instanceof InvalidDecisionError)) throw e
      for (const p of e.problems) content.problems.push(`${artifact.source}: ${p}`)
    }
  }
  return { ...content, ...(catalog ? { catalog: catalog.catalog, catalogId: catalog.id } : {}) }
}

/** The registered artifacts at their exact versions, in the order problems list them, as Approvals.artifacts. */
async function approvalArtifacts(
  sets: readonly ParameterSet[], tables: readonly DecisionTable[], flows: readonly DecisionFlow[], catalog: ReasonCodeCatalog | undefined,
  catalogId: string,
): Promise<ApprovalArtifact[]> {
  const artifacts = new Map<string, ApprovalArtifact>()
  const add = (artifact: ApprovalArtifact) => {
    artifacts.set(`${artifact.kind} ${artifact.id} ${artifact.label} ${artifact.exactVersion}`, artifact)
  }
  for (const s of sets) add({ kind: 'params', id: s.id, label: s.version, exactVersion: await parameterSetVersionOf(s) })
  for (const t of tables) add({ kind: 'table', id: t.id, label: t.version, exactVersion: await ruleVersionOf(t) })
  for (const f of flows) add({ kind: 'flow', id: f.id, label: f.version, exactVersion: await flowVersionOf(f) })
  if (catalog) add({ kind: 'catalog', id: catalogId, label: catalog.version, exactVersion: await reasonCodeCatalogVersionOf(catalog) })
  return [...artifacts.values()]
}

function checkTimeout(timeoutMs: number): void {
  if (!(timeoutMs > 0) || !Number.isFinite(timeoutMs)) throw new RangeError('timeout must be positive')
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

/** One evaluation, keeping what is known so far so that a failure can still be recorded, as Attempt. */
export class Attempt {
  decision: Executable | undefined
  /** The record of a failed replay or re-score. */
  failure: DecisionRecord | undefined

  constructor(
    readonly recordId: string,
    readonly ref: NormalizedRef,
    readonly businessDate: LocalDate,
    readonly evaluatedAt: Date,
    readonly progress: Progress,
  ) {}

  record(status: DecisionRecord['status'], output: ReadonlyMap<string, Value>, reasonCodes: readonly ReasonCode[], error?: string): DecisionRecord {
    const params = this.progress.params()
    return {
      recordId: this.recordId,
      ...(this.ref.correlationId !== undefined ? { correlationId: this.ref.correlationId } : {}),
      decision: this.ref.id,
      ...(this.decision ? { version: this.decision.ruleVersion } : {}),
      ...(params.length > 0 ? { params } : {}),
      businessDate: this.businessDate,
      evaluatedAt: this.evaluatedAt,
      engine: ENGINE_NAME,
      status,
      ...(this.progress.factsHash !== undefined ? { factsHash: this.progress.factsHash } : {}),
      output,
      reasonCodes,
      path: this.progress.path(),
      ...(error !== undefined ? { error } : {}),
    }
  }

  failed(e: unknown): DecisionRecord {
    // RuleStone messages never contain input values; other errors might, so only their type is recorded.
    const message = e instanceof RuleStoneError ? e.message : `internal error: ${e instanceof Error ? e.name : typeof e}`
    return this.record(e instanceof DecisionTimeoutError ? 'TIMEOUT' : 'ERROR', new Map(), [], message)
  }
}

/** An immutable set of compiled decision tables and flows. */
export class RuleStoneEngine {
  private readonly clock: () => Date
  private readonly timeZone: string | undefined
  private readonly recordIds: () => string
  private readonly sinks: readonly AuditSink[]
  private readonly hasher: FactsHasher
  private readonly timeoutMs: number | undefined
  private readonly now: MonotonicClock

  /** @internal use createEngine */
  constructor(
    private readonly byId: ReadonlyMap<string, readonly Compiled[]>,
    /** @internal */
    readonly parameterSets: Registry,
    options: EngineOptions,
    private readonly catalog?: { readonly catalog: ReasonCodeCatalog; readonly version: string },
  ) {
    this.clock = options.clock ?? (() => new Date())
    this.timeZone = options.timeZone
    this.recordIds = options.recordIds ?? randomId
    this.sinks = [...(options.auditSinks ?? [])]
    this.hasher = new FactsHasher(options.factsHashKey)
    this.timeoutMs = options.timeoutMs
    this.now = options.monotonicClock ?? monotonicNow
  }

  /**
   * Explains a decision record in one locale with the engine's reason-code catalog, as
   * RuleStoneEngine.explain. Pure: the same record and locale always give the same explanation.
   * @throws RangeError if the catalog has no such locale, or the engine has no catalog
   */
  explain(record: ExplainableRecord, locale: string): Explanation {
    if (!this.catalog) throw new RangeError('this engine has no reason-code catalog; create it with reasonCodeCatalog')
    return explainRecord(record, this.catalog.catalog, this.catalog.version, locale)
  }

  /** The exact version of the engine's reason-code catalog, e.g. 3@sha256:…; undefined without one. */
  get reasonCodeCatalogVersion(): string | undefined {
    return this.catalog?.version
  }

  /** Every compiled version of every table and flow, by decision id. */
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
   * reference selects (FactsJson forms: "1500000 VND", "2026-11-01", decimals as numbers or strings),
   * and for a flow the supplied model results, as FactsJson.readFacts(facts, inputs, models).
   * @throws InvalidFactsError for facts or model results that do not match, listing every problem
   */
  async evaluateJson(ref: DecisionRef | string, json: JsonInput, models?: JsonInput): Promise<DecisionResult> {
    const problems: string[] = []
    let facts = new Map<string, Value>()
    try {
      facts = readFacts(json, this.resolve(ref).definition.inputs)
    } catch (e) {
      if (!(e instanceof InvalidFactsError)) throw e
      problems.push(...e.problems)
    }
    let supplied = new Map<string, SuppliedModel>()
    if (models !== undefined && models !== null && !(typeof models === 'object' && (models as { type?: unknown }).type === 'null')) {
      try {
        supplied = readModels(models)
      } catch (e) {
        if (!(e instanceof InvalidFactsError)) throw e
        for (const p of e.problems) problems.push(p.startsWith('$') ? 'models' + p.slice(1) : 'models.' + p)
      }
    }
    if (problems.length > 0) throw new InvalidFactsError(problems)
    return this.evaluate(ref, facts, supplied)
  }

  /**
   * Evaluates a decision. Facts the decision does not declare are ignored. For a flow, `models` are
   * the results of its model nodes in supplied mode, by node id.
   * @throws DecisionNotFoundError, DecisionEvaluationError (DecisionTimeoutError when the timeout
   *         passes) or AuditError; the error's `record` holds the decision record
   */
  async evaluate(ref: DecisionRef | string, facts: FactsInput, models?: ModelsInput): Promise<DecisionResult> {
    const r = normalizeRef(ref)
    // The timeout counts from here, so that it covers everything the caller waits for.
    const deadline = this.deadline(r)
    const attempt = this.attempt(r, new Progress(r.params))
    let result: DecisionResult
    try {
      result = await this.run(attempt, facts, models, deadline)
    } catch (e) {
      const failed = attempt.failed(e)
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

  /**
   * Evaluates again exactly what a record names, as RuleStoneEngine.replay: its version, business date
   * and parameter set versions, the version of each table step and the recorded model results, and
   * compares the new record with it. Nothing is written to the audit sinks and no model is called.
   * The record can come from either engine, e.g. through readDecisionRecord.
   * @throws DecisionNotFoundError if a version the record names is not registered with exactly its hash
   * @throws RangeError if the record names no version, or model results are given
   */
  replay(record: DecisionRecord, facts: FactsInput, models?: ModelsInput): Promise<DecisionComparison> {
    return replay(this, record, facts, models)
  }

  /**
   * Evaluates a recorded decision with another version, date or parameters, as RuleStoneEngine.rescore,
   * with the recorded model results ('RECORDED') or today's models ('LIVE'), and compares the result
   * with the record. The facts hash is checked with the recorded version. Nothing is written to the
   * audit sinks.
   * @throws RangeError if the target names another decision
   */
  rescore(
    record: DecisionRecord,
    facts: FactsInput,
    target: DecisionRef | string,
    source: ModelSource,
    models?: ModelsInput,
  ): Promise<DecisionComparison> {
    return rescore(this, record, facts, target, source, models)
  }

  /** @internal a new attempt for a reference, with a fresh record id and the clock read once */
  attempt(r: NormalizedRef, progress: Progress): Attempt {
    // Read the clock once: the same instant dates the record, and its date in the zone selects the version.
    const evaluatedAt = this.clock()
    const businessDate = r.asOf ?? dateInZone(evaluatedAt, this.timeZone)
    const recordId = this.recordIds()
    if (!recordId || recordId.trim() === '') throw new Error('recordIds supplied an empty record id')
    return new Attempt(recordId, r, businessDate, evaluatedAt, progress)
  }

  /** @internal runs an attempt without writing to the audit sinks */
  async run(attempt: Attempt, facts: FactsInput, models: ModelsInput | undefined, deadline: Deadline): Promise<DecisionResult> {
    attempt.decision ??= this.select(attempt.ref, attempt.businessDate)
    const decision = attempt.decision
    const progress = attempt.progress
    const run = await decision.run({
      facts: factsMap(facts),
      models: models === undefined ? new Map() : models instanceof Map ? models : new Map(Object.entries(models)),
      businessDate: attempt.businessDate,
      correlationId: attempt.ref.correlationId,
      deadline,
      progress,
      hasher: this.hasher,
    })
    const record = attempt.record('OK', run.output, run.reasonCodes)
    const matchedRules = progress.trace.filter((t) => t.matched).map((t) => t.ruleId)
    return {
      decisionId: decision.definition.id,
      ruleVersion: decision.ruleVersion,
      output: run.output,
      reasonCodes: run.reasonCodes.map((r) => r.code),
      trace: progress.trace,
      matched: matchedRules.length > 0,
      matchedRules,
      record,
    }
  }

  /** @internal the deadline of a request: its own timeout, else the engine's */
  deadline(r: NormalizedRef): Deadline {
    const timeout = r.timeoutMs ?? this.timeoutMs
    return timeout === undefined ? Deadline.NONE : Deadline.after(timeout, this.now)
  }

  /** @internal */
  async audit(record: DecisionRecord): Promise<void> {
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

  /** @internal every compiled version of a decision */
  versionsOf(id: string): readonly Compiled[] | undefined {
    return this.byId.get(id)
  }

  /** @internal */
  select(ref: NormalizedRef, date: LocalDate): Compiled {
    const versions = this.byId.get(ref.id)
    if (!versions) throw new DecisionNotFoundError(`unknown decision '${ref.id}'`)
    const definition = selectVersion(ref.id, versions.map((d) => d.definition), ref.version, date)
    return versions.find((d) => d.definition === definition)!
  }
}

/** @internal */
export interface NormalizedRef {
  readonly id: string
  readonly version: string | undefined
  readonly asOf: LocalDate | undefined
  readonly correlationId: string | undefined
  readonly params: ReadonlyMap<string, string>
  readonly timeoutMs: number | undefined
}

/** @internal */
export function normalizeRef(ref: DecisionRef | string): NormalizedRef {
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
  if (r.timeoutMs !== undefined) checkTimeout(r.timeoutMs)
  const params = r.params instanceof Map ? new Map(r.params) : new Map(Object.entries(r.params ?? {}))
  return { id: r.id, version: r.version, asOf, correlationId: r.correlationId, params, timeoutMs: r.timeoutMs }
}

/** The JSON text of a result's output, e.g. {"fee":"25000 VND"}. */
export function outputJson(result: DecisionResult): string {
  return writeValues(result.output)
}

export type { Overrides, ModelStep }
export { NO_OVERRIDES }
