// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision records, as vn.io.dxa.rulestone.api.DecisionRecord, and their JSON form, as
// DecisionRecordJson.

import { instantToString, type LocalDate } from './date.js'
import { valuesToJson } from './facts.js'
import { writeJson, type Writable } from './json.js'
import type { Value } from './values.js'

/** One reason for the outcome, with the node that produced it (the decision id). */
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

/** One node visited during the evaluation: today, the decision table. */
export interface PathStep {
  readonly node: string
  readonly type: string
  readonly version: string
  readonly matchedRules: readonly string[]
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
  readonly status: 'OK' | 'ERROR'
  /** sha256:… of the declared inputs in canonical form; absent if the inputs were invalid. */
  readonly factsHash?: string
  /** The outputs; empty when the status is ERROR. */
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly ReasonCode[]
  readonly path: readonly PathStep[]
  /** Why the evaluation failed; present exactly when the status is ERROR. */
  readonly error?: string
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
  json.set(
    'path',
    record.path.map(
      (step) =>
        new Map<string, Writable>([
          ['node', step.node],
          ['type', step.type],
          ['version', step.version],
          ['matchedRules', [...step.matchedRules]],
        ]),
    ),
  )
  if (record.error !== undefined) json.set('error', record.error)
  return json
}

/** The record as one line of compact JSON, as DecisionRecordJson.write. */
export function writeDecisionRecord(record: DecisionRecord): string {
  return writeJson(decisionRecordToJson(record))
}
