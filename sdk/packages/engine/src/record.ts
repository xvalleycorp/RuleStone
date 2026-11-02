// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision records, as vn.io.dxa.rulestone.api.DecisionRecord, and their JSON form, as
// DecisionRecordJson.

import { instantToString, type LocalDate } from './date.js'
import type { Decimal } from './decimal.js'
import { valuesToJson } from './facts.js'
import { writeJson, type Writable } from './json.js'
import type { Value } from './values.js'

/** One reason for the outcome, with the node that produced it (the decision id, or a flow's node id). */
export interface ReasonCode {
  readonly code: string
  readonly source: string
}

/** A parameter set version that an evaluation used. */
export interface ParamSetVersion {
  readonly set: string
  /** The exact version, e.g. 7@sha256:… */
  readonly version: string
}

/** The sign of a feature's contribution, as RankedAttribution.Direction. */
export type Direction = 'INCREASES_RISK' | 'DECREASES_RISK' | 'NONE'

/** One attribution of a model's result, by rank, without its contribution value. */
export interface RankedAttribution {
  readonly feature: string
  readonly rank: number
  readonly direction: Direction
  readonly reasonCode?: string
}

/** How a model step's result was obtained, as ModelMode. */
export type ModelStepMode = 'invoke' | 'supplied'

/** The outcome of a model node, as ModelStatus: OK, or the kind of failure. */
export type ModelStatus = 'OK' | 'TIMEOUT' | 'ERROR' | 'CONTRACT_VIOLATION' | 'INVALID_OUTPUT' | 'LOW_CONFIDENCE' | 'MISSING'

/**
 * What a model node was asked and answered, as DecisionRecord.ModelStep. It never holds the
 * features sent to the model or the raw contributions.
 */
export interface ModelStep {
  readonly mode: ModelStepMode
  /** The model's id. */
  readonly id: string
  /** The model version the node pins. */
  readonly version: string
  readonly status: ModelStatus
  /** For a failure, what failed, without values. */
  readonly detail?: string
  /** For a model the engine called, how long the call took, in whole milliseconds. */
  readonly latencyMs?: number
  /** For OK, the outputs later nodes read; empty for a failure. */
  readonly outputs: ReadonlyMap<string, Value>
  readonly confidence?: Decimal
  readonly attributionMethod?: string
  /** For OK, every attribution by rank; empty for a failure. */
  readonly attributions: readonly RankedAttribution[]
}

/**
 * One node visited during the evaluation, as DecisionRecord.PathStep: the decision table of a
 * table evaluation, or a node of a flow.
 */
export interface PathStep {
  readonly node: string
  /** table, switch, derive, model, split or end. */
  readonly type: string
  /** For a table node of a flow, the table evaluated. */
  readonly decision?: string
  /** For a table, the exact version evaluated; absent if none was found. */
  readonly version?: string
  /** For a table, the rules that matched, in order; empty for other steps. */
  readonly matchedRules: readonly string[]
  /** For a switch, the node chosen; for a split, the bucket chosen. */
  readonly taken?: string
  /** For a model node. */
  readonly model?: ModelStep
}

/** How an evaluation was routed to a version other than the one in force, as DecisionRecord.Routing. */
export interface Routing {
  readonly kind: 'CANARY' | 'SHADOW'
  /** The exact version in force, which the request would otherwise have used. */
  readonly champion: string
  /** For a canary, the share of correlation ids routed to it. */
  readonly share?: number
  /** For a shadow, the record id of the request it shadows. */
  readonly shadowOf?: string
}

/** The explanation of one evaluation, successful or not. It never contains input values. */
export interface DecisionRecord {
  readonly recordId: string
  readonly correlationId?: string
  /** The requested decision id. */
  readonly decision: string
  /** The exact version used, e.g. 3@sha256:…; absent if no version was found. */
  readonly version?: string
  /**
   * Every parameter set version used, each once, sorted by set and version; absent if none. The
   * values are not recorded: the version identifies them.
   */
  readonly params?: readonly ParamSetVersion[]
  readonly businessDate: LocalDate
  /** When the evaluation started. */
  readonly evaluatedAt: Date
  /** The engine that produced the record, e.g. rulestone-js 0.1.0. */
  readonly engine: string
  readonly status: 'OK' | 'ERROR' | 'TIMEOUT'
  /** sha256:… (or hmac-sha256:… with a key) of the declared inputs in canonical form; absent if the inputs were invalid. */
  readonly factsHash?: string
  /** The outputs; empty when the status is not OK. */
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly ReasonCode[]
  readonly path: readonly PathStep[]
  /** Why the evaluation failed; present exactly when the status is not OK. */
  readonly error?: string
  /** Present when the evaluation was routed to a version other than the one in force. */
  readonly routing?: Routing
}

/** The record as a JSON object with DecisionRecordJson's field order. */
export function decisionRecordToJson(record: DecisionRecord): Map<string, Writable> {
  const json = new Map<string, Writable>()
  json.set('recordId', record.recordId)
  if (record.correlationId !== undefined) json.set('correlationId', record.correlationId)
  json.set('decision', record.decision)
  if (record.version !== undefined) json.set('version', record.version)
  if (record.params && record.params.length > 0) {
    json.set('params', record.params.map((p) => new Map<string, Writable>([['set', p.set], ['version', p.version]])))
  }
  json.set('businessDate', record.businessDate.toString())
  json.set('evaluatedAt', instantToString(record.evaluatedAt))
  json.set('engine', record.engine)
  json.set('status', record.status)
  if (record.factsHash !== undefined) json.set('factsHash', record.factsHash)
  json.set('output', valuesToJson(record.output))
  json.set(
    'reasonCodes',
    record.reasonCodes.map((r) => new Map<string, Writable>([['code', r.code], ['source', r.source]])),
  )
  json.set('path', record.path.map(stepJson))
  if (record.error !== undefined) json.set('error', record.error)
  if (record.routing) {
    const routing = new Map<string, Writable>([['kind', record.routing.kind], ['champion', record.routing.champion]])
    if (record.routing.share !== undefined) routing.set('share', { rawNumber: javaDouble(record.routing.share) })
    if (record.routing.shadowOf !== undefined) routing.set('shadowOf', record.routing.shadowOf)
    json.set('routing', routing)
  }
  return json
}

/** A double as Jackson writes it for the common shares, such as 0.05 or 1.0. */
function javaDouble(value: number): string {
  const text = String(value)
  return /[.eE]/.test(text) ? text : `${text}.0`
}

function stepJson(step: PathStep): Map<string, Writable> {
  const json = new Map<string, Writable>([['node', step.node], ['type', step.type]])
  if (step.decision !== undefined) json.set('decision', step.decision)
  if (step.version !== undefined) json.set('version', step.version)
  // A table step always lists its matched rules, even none; other steps have none to list.
  if (step.type === 'table' || step.matchedRules.length > 0) json.set('matchedRules', [...step.matchedRules])
  if (step.taken !== undefined) json.set('taken', step.taken)
  const model = step.model
  if (model) {
    json.set('mode', model.mode)
    json.set('model', new Map<string, Writable>([['id', model.id], ['version', model.version]]))
    json.set('status', model.status)
    if (model.detail !== undefined) json.set('detail', model.detail)
    if (model.latencyMs !== undefined) json.set('latencyMs', BigInt(model.latencyMs))
    if (model.status === 'OK') {
      json.set('outputs', valuesToJson(model.outputs))
      if (model.confidence !== undefined) json.set('confidence', model.confidence.toPlainString())
      if (model.attributionMethod !== undefined) json.set('attributionMethod', model.attributionMethod)
      json.set(
        'attributions',
        model.attributions.map((a) => {
          const attribution = new Map<string, Writable>([['feature', a.feature], ['rank', BigInt(a.rank)], ['direction', a.direction]])
          if (a.reasonCode !== undefined) attribution.set('reasonCode', a.reasonCode)
          return attribution
        }),
      )
    }
  }
  return json
}

/** The record as one line of compact JSON, as DecisionRecordJson.write. */
export function writeDecisionRecord(record: DecisionRecord): string {
  return writeJson(decisionRecordToJson(record))
}
