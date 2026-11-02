// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Replaying and re-scoring recorded decisions, as RuleStoneEngine.replay and rescore in Java, and
// the comparison of a record with a new evaluation, as Comparisons and DecisionComparison.

import { Attempt, normalizeRef, type DecisionRef, type DecisionResult, type ModelsInput, type RuleStoneEngine } from './engine.js'
import { DecisionNotFoundError } from './errors.js'
import { Deadline, NO_OVERRIDES, Progress, type FactsInput } from './executable.js'
import { valueToJson } from './facts.js'
import { writeJson } from './json.js'
import type { DecisionRecord, ModelStep, ParamSetVersion, PathStep, ReasonCode } from './record.js'
import type { Value } from './values.js'

/** What a difference between a record and a new evaluation is about, as DecisionComparison.Kind. */
export type DifferenceKind = 'FACTS' | 'STATUS' | 'VERSION' | 'BUSINESS_DATE' | 'PARAMS' | 'OUTPUT' | 'REASON_CODES' | 'PATH'

/** One difference; the description names outputs, nodes, rules, versions and codes, never values. */
export interface Difference {
  readonly kind: DifferenceKind
  readonly description: string
}

/** Which model results a re-score uses, as ModelSource. */
export type ModelSource = 'RECORDED' | 'LIVE'

/** A recorded decision compared with a new evaluation, as DecisionComparison. */
export interface DecisionComparison {
  readonly recorded: DecisionRecord
  /** The record of the new evaluation, which is not written to the audit sinks. */
  readonly evaluated: DecisionRecord
  /** The result of the new evaluation, absent if it failed. */
  readonly result?: DecisionResult
  /** Every difference, in the order of the record's fields; empty when the decision is reproduced. */
  readonly differences: readonly Difference[]
  /** Whether there are no differences. */
  readonly matches: boolean
}

function comparison(recorded: DecisionRecord, evaluated: DecisionRecord, result: DecisionResult | undefined, differences: Difference[]): DecisionComparison {
  return { recorded, evaluated, ...(result ? { result } : {}), differences, matches: differences.length === 0 }
}

/** The label of an exact version: 4 for 4@sha256:… */
function label(exact: string): string {
  const at = exact.indexOf('@')
  return at < 0 ? exact : exact.slice(0, at)
}

function modelsOf(models: ModelsInput | undefined): Map<string, unknown> {
  return models === undefined ? new Map() : models instanceof Map ? new Map(models) : new Map(Object.entries(models))
}

/** The recorded step of every model node, by node id. */
function recordedModels(record: DecisionRecord): Map<string, ModelStep> {
  const models = new Map<string, ModelStep>()
  for (const step of record.path) if (step.model) models.set(step.node, step.model)
  return models
}

/** Runs an attempt without writing to the audit sinks, keeping its record on failure. */
async function run(engine: RuleStoneEngine, attempt: Attempt, facts: FactsInput, models: ModelsInput | undefined, deadline: Deadline): Promise<DecisionResult | undefined> {
  try {
    return await engine.run(attempt, facts, models, deadline)
  } catch (e) {
    attempt.failure = attempt.failed(e)
    return undefined
  }
}

/** The registered version of decision `id` whose exact version is `exact`. */
function exact(engine: RuleStoneEngine, id: string, exactVersion: string) {
  const versions = engine.versionsOf(id)
  if (!versions) throw new DecisionNotFoundError(`unknown decision '${id}': the record names version ${exactVersion}`)
  const found = versions.find((d) => d.ruleVersion === exactVersion)
  if (found) return found
  const same = versions.map((d) => d.ruleVersion).find((v) => label(v) === label(exactVersion))
  throw new DecisionNotFoundError(
    `decision '${id}' has no version ${exactVersion}` +
      (same !== undefined ? `: version '${label(exactVersion)}' is registered with other content, ${same}; register the content the record names` : ''),
  )
}

/** Checks that the parameter set version a record names is registered with exactly its hash. */
function exactParams(engine: RuleStoneEngine, used: ParamSetVersion): void {
  const versions = engine.parameterSets.compiled.get(used.set) ?? []
  if (versions.some((c) => c.version === used.version)) return
  const same = versions.map((c) => c.version).find((v) => label(v) === label(used.version))
  throw new DecisionNotFoundError(
    `parameter set '${used.set}' has no version ${used.version}` +
      (same !== undefined ? `: version '${label(used.version)}' is registered with other content, ${same}; register the content the record names` : ''),
  )
}

/** An attempt that evaluates exactly what a record names. */
function replayAttempt(engine: RuleStoneEngine, record: DecisionRecord): Attempt {
  if (record.version === undefined) {
    throw new RangeError(
      `record ${record.recordId} names no version of decision '${record.decision}': none was found when it was made, so there is nothing to replay`,
    )
  }
  const decision = exact(engine, record.decision, record.version)
  const tables = new Map<string, string>()
  for (const step of record.path) {
    if (step.type === 'table' && step.decision !== undefined && step.version !== undefined) {
      exact(engine, step.decision, step.version)
      tables.set(step.node, step.version)
    }
  }
  const params = new Map<string, string>()
  for (const used of record.params ?? []) {
    exactParams(engine, used)
    params.set(used.set, label(used.version))
  }
  const ref = normalizeRef({
    id: record.decision,
    version: label(record.version),
    asOf: record.businessDate,
    ...(record.correlationId !== undefined ? { correlationId: record.correlationId } : {}),
    params,
  })
  const attempt = engine.attempt(ref, new Progress(params, { tables, models: recordedModels(record), recordedModels: true }))
  attempt.decision = decision
  return attempt
}

/** Replays a record, as RuleStoneEngine.replay; see RuleStoneEngine.replay. */
export async function replay(engine: RuleStoneEngine, record: DecisionRecord, facts: FactsInput, models?: ModelsInput): Promise<DecisionComparison> {
  const supplied = modelsOf(models)
  if (supplied.size > 0) {
    throw new RangeError(
      `a replay takes the model results from the record; the facts must not carry any, but carry [${[...supplied.keys()].join(', ')}]`,
    )
  }
  const attempt = replayAttempt(engine, record)
  const result = await run(engine, attempt, facts, undefined, Deadline.NONE)
  const evaluated = result?.record ?? attempt.failure!
  const differences: Difference[] = []
  const hash = factsHashDifference(record.factsHash, evaluated.factsHash)
  if (hash) differences.push(hash)
  differences.push(...compareRecords(record, evaluated))
  return comparison(record, evaluated, result, differences)
}

/** Re-scores a record, as RuleStoneEngine.rescore; see RuleStoneEngine.rescore. */
export async function rescore(
  engine: RuleStoneEngine,
  record: DecisionRecord,
  facts: FactsInput,
  target: DecisionRef | string,
  source: ModelSource,
  models?: ModelsInput,
): Promise<DecisionComparison> {
  const t = normalizeRef(target)
  if (t.id !== record.decision) {
    throw new RangeError(`a re-score evaluates the recorded decision '${record.decision}', not '${t.id}'`)
  }
  // The facts are checked with the recorded version, whose inputs the hash covers.
  const check = replayAttempt(engine, record)
  const original = await run(engine, check, facts, models, Deadline.NONE)
  const originalHash = original ? original.record.factsHash : check.failure!.factsHash
  const ref = t.correlationId !== undefined || record.correlationId === undefined ? t : { ...t, correlationId: record.correlationId }
  const overrides = source === 'RECORDED' ? { tables: new Map<string, string>(), models: recordedModels(record), recordedModels: true } : NO_OVERRIDES
  const attempt = engine.attempt(ref, new Progress(ref.params, overrides))
  attempt.decision = engine.select(ref, attempt.businessDate)
  const result = await run(engine, attempt, facts, models, engine.deadline(ref))
  const evaluated = result?.record ?? attempt.failure!
  const differences: Difference[] = []
  const hash = factsHashDifference(record.factsHash, originalHash)
  if (hash) differences.push(hash)
  differences.push(...compareRecords(record, evaluated))
  return comparison(record, evaluated, result, differences)
}

// --- Comparison ---------------------------------------------------------------------------------

function algorithm(hash: string): string {
  const colon = hash.indexOf(':')
  return colon < 0 ? 'unknown' : hash.slice(0, colon)
}

/** The difference between the record's facts hash and the hash of the facts given now, if any. */
export function factsHashDifference(recorded: string | undefined, evaluated: string | undefined): Difference | undefined {
  if (recorded === evaluated) return undefined
  if (recorded === undefined) return { kind: 'FACTS', description: 'factsHash: the record has none, because its inputs were not valid' }
  if (evaluated === undefined) return { kind: 'FACTS', description: 'factsHash: the facts are not valid inputs of the recorded version' }
  const was = algorithm(recorded)
  const now = algorithm(evaluated)
  if (was !== now) {
    return {
      kind: 'FACTS',
      description: `factsHash: the record's hash is ${was} but this engine computes ${now}; verify with the factsHashKey the record was made with`,
    }
  }
  return {
    kind: 'FACTS',
    description: `factsHash: the facts are not those the record was made on${was.startsWith('hmac') ? ', or the factsHashKey is not the one it was made with' : ''}`,
  }
}

/** Whether two values are the same in their JSON forms, so that "0.0310" read from a record equals the decimal. */
function same(a: Value, b: Value): boolean {
  try {
    return writeJson(valueToJson(a)) === writeJson(valueToJson(b))
  } catch {
    return false
  }
}

function listEquals<T>(a: readonly T[], b: readonly T[], eq: (x: T, y: T) => boolean = (x, y) => x === y): boolean {
  return a.length === b.length && a.every((x, i) => eq(x, b[i]!))
}

const javaList = (items: readonly string[]): string => `[${items.join(', ')}]`

/** The differences between a record and a new evaluation, in the order of the record's fields; not the facts hash. */
export function compareRecords(recorded: DecisionRecord, evaluated: DecisionRecord): Difference[] {
  const differences: Difference[] = []
  const add = (kind: DifferenceKind, description: string) => differences.push({ kind, description })
  if (recorded.status !== evaluated.status) add('STATUS', `status: recorded ${recorded.status}, now ${evaluated.status}`)
  else if (recorded.error !== evaluated.error) add('STATUS', 'error: the recorded and the new error differ')
  if (recorded.version !== evaluated.version) {
    add('VERSION', `version: recorded ${recorded.version ?? 'none'}, now ${evaluated.version ?? 'none'}`)
  }
  if (!recorded.businessDate.equals(evaluated.businessDate)) {
    add('BUSINESS_DATE', `businessDate: recorded ${recorded.businessDate.toString()}, now ${evaluated.businessDate.toString()}`)
  }
  const params = (p: readonly ParamSetVersion[]) => (p.length === 0 ? 'none' : p.map((x) => `${x.set} ${x.version}`).join(', '))
  const rp = recorded.params ?? []
  const ep = evaluated.params ?? []
  if (!listEquals(rp, ep, (a, b) => a.set === b.set && a.version === b.version)) add('PARAMS', `params: recorded ${params(rp)}, now ${params(ep)}`)
  if (recorded.status === 'OK' && evaluated.status === 'OK') {
    const names = new Set([...recorded.output.keys(), ...evaluated.output.keys()])
    for (const name of names) {
      const was = recorded.output.get(name)
      const now = evaluated.output.get(name)
      if (now === undefined) add('OUTPUT', `output '${name}': recorded, not set now`)
      else if (was === undefined) add('OUTPUT', `output '${name}': set now, not recorded`)
      else if (!same(was, now)) add('OUTPUT', `output '${name}' differs`)
    }
    const reasons = (r: readonly ReasonCode[]) => javaList(r.map((x) => `${x.code} from ${x.source}`))
    if (!listEquals(recorded.reasonCodes, evaluated.reasonCodes, (a, b) => a.code === b.code && a.source === b.source)) {
      add('REASON_CODES', `reasonCodes: recorded ${reasons(recorded.reasonCodes)}, now ${reasons(evaluated.reasonCodes)}`)
    }
  }
  const common = Math.min(recorded.path.length, evaluated.path.length)
  for (let i = 0; i < common; i++) step(i + 1, recorded.path[i]!, evaluated.path[i]!, add)
  if (recorded.path.length !== evaluated.path.length) {
    const nodes = (p: readonly PathStep[]) => p.map((s) => s.node).join(', ')
    add('PATH', `path: recorded ${recorded.path.length} steps (${nodes(recorded.path)}), now ${evaluated.path.length} (${nodes(evaluated.path)})`)
  }
  return differences
}

function step(index: number, was: PathStep, now: PathStep, add: (kind: DifferenceKind, description: string) => void): void {
  const at = `path step ${index} (${was.node})`
  if (was.node !== now.node || was.type !== now.type) {
    add('PATH', `path step ${index}: recorded ${was.type} node '${was.node}', now ${now.type} node '${now.node}'`)
    return
  }
  const version = (s: PathStep) => (s.decision !== undefined ? `${s.decision} ` : '') + (s.version ?? 'no version')
  if (was.decision !== now.decision || was.version !== now.version) add('PATH', `${at}: recorded ${version(was)}, now ${version(now)}`)
  if (!listEquals(was.matchedRules, now.matchedRules)) {
    add('PATH', `${at}: recorded matched rules ${javaList(was.matchedRules)}, now ${javaList(now.matchedRules)}`)
  }
  if (was.taken !== now.taken) add('PATH', `${at}: recorded taken '${was.taken ?? ''}', now '${now.taken ?? ''}'`)
  if (was.model && now.model) model(at, was.model, now.model, add)
  else if (!!was.model !== !!now.model) add('PATH', `${at}: only one of the steps has a model result`)
}

/** Compares what a model returned; the mode, latency and failure detail are how, not what. */
function model(at: string, was: ModelStep, now: ModelStep, add: (kind: DifferenceKind, description: string) => void): void {
  if (was.id !== now.id || was.version !== now.version) {
    add('PATH', `${at}: recorded model ${was.id}@${was.version}, now ${now.id}@${now.version}`)
    return
  }
  if (was.status !== now.status) {
    add('PATH', `${at}: recorded model status ${was.status}, now ${now.status}`)
    return
  }
  const names = new Set([...was.outputs.keys(), ...now.outputs.keys()])
  for (const name of names) {
    const a = was.outputs.get(name)
    const b = now.outputs.get(name)
    if (a === undefined || b === undefined || !same(a, b)) add('PATH', `${at}: model output '${name}' differs`)
  }
  const sameConfidence =
    (was.confidence === undefined) === (now.confidence === undefined) &&
    (was.confidence === undefined || was.confidence.compareTo(now.confidence!) === 0)
  if (!sameConfidence) add('PATH', `${at}: model confidence differs`)
  const sameAttributions = listEquals(
    was.attributions,
    now.attributions,
    (a, b) => a.feature === b.feature && a.rank === b.rank && a.direction === b.direction && a.reasonCode === b.reasonCode,
  )
  if (was.attributionMethod !== now.attributionMethod || !sameAttributions) add('PATH', `${at}: model attributions differ`)
}
