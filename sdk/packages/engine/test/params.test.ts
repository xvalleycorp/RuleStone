// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  Decimal, DecisionEvaluationError, DecisionNotFoundError, createEngine, parseDecisionTable, parseParameterSet,
  writeDecisionRecord,
} from '../src/index.js'

const set = (version: string, from: string, maxDti: string) =>
  parseParameterSet(`{ "id": "limits", "version": "${version}", "effectiveFrom": "${from}",
    "values": { "maxDti": { "type": "decimal", "value": "${maxDti}" } } }`)

const TABLE = `{ "id": "dti", "version": "1", "hitPolicy": "FIRST",
  "inputs": { "dti": "decimal" }, "outputs": { "decision": "string" },
  "params": { "set": "limits", "values": { "maxDti": "decimal" } },
  "rules": [ { "id": "HIGH", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } },
             { "id": "OK", "when": "true", "then": { "decision": "'APPROVE'" } } ] }`

describe('parameter sets', () => {
  const sets = [set('1', '2027-01-01', '0.50'), set('2', '2027-07-01', '0.45')]
  const facts = { dti: Decimal.parse('0.48')! }

  it('binds the version in force and records it, without the values', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(TABLE)], paramSets: sets })
    const before = await engine.evaluate({ id: 'dti', asOf: '2027-06-30' }, facts)
    const after = await engine.evaluate({ id: 'dti', asOf: '2027-07-01' }, facts)
    expect(before.output.get('decision')).toBe('APPROVE')
    expect(after.output.get('decision')).toBe('REJECT')
    expect(after.ruleVersion).toBe(before.ruleVersion)
    expect(after.record.factsHash).toBe(before.record.factsHash)
    expect(before.record.params?.map((p) => p.version.split('@')[0])).toEqual(['1'])
    expect(writeDecisionRecord(after.record)).toContain(`"version":"${after.ruleVersion}","params":[{"set":"limits","version":"2@sha256:`)
    expect(writeDecisionRecord(after.record)).not.toContain('0.45')
  })

  it('forces a version for replay, but not over a pin', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(TABLE)], paramSets: sets })
    const replay = await engine.evaluate({ id: 'dti', asOf: '2027-09-01', params: { limits: '1' } }, facts)
    expect(replay.output.get('decision')).toBe('APPROVE')
    await expect(engine.evaluate({ id: 'dti', asOf: '2027-09-01', params: { limits: '9' } }, facts)).rejects.toSatisfy(
      (e: unknown) => e instanceof DecisionNotFoundError && e.message === "parameter set 'limits' has no version '9'",
    )
    const pinned = await createEngine({
      tables: [parseDecisionTable(TABLE.replace('"set": "limits"', '"set": "limits", "version": "2"'))],
      paramSets: sets,
    })
    const result = await pinned.evaluate({ id: 'dti', asOf: '2027-02-01', params: new Map([['limits', '1']]) }, facts)
    expect(result.output.get('decision')).toBe('REJECT')
  })

  it('fails without a version in force, and rejects params as a fact', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(TABLE)], paramSets: sets })
    await expect(engine.evaluate({ id: 'dti', asOf: '2026-12-31' }, facts)).rejects.toThrow(
      "no version of parameter set 'limits' is effective on 2026-12-31",
    )
    await expect(engine.evaluate({ id: 'dti', asOf: '2027-02-01' }, { ...facts, params: {} })).rejects.toBeInstanceOf(
      DecisionEvaluationError,
    )
  })
})
