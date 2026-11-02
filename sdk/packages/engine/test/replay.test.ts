// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Replay and re-score: records of the Java engine, read back from JSON, replay in the browser engine
// with no differences; changed facts, rules and models show up as the Java engine reports them.

import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import {
  Decimal, DecisionNotFoundError, InvalidRecordError, LocalDate, Money, createEngine, parseDecisionFlow, parseDecisionTable,
  parseStoneTests, readDecisionRecord, writeDecisionRecord, type DecisionTable, type ModelInvoker, type RuleStoneEngine,
} from '../src/index.js'
import { EXAMPLES, FLOWS, exampleEngine, exampleFlowEngine, exampleFlows, exampleTables, files, fixture, flowTables, read } from './helpers.js'

const flowRecords = fixture<Record<string, Record<string, Record<string, unknown>>>>('java-flow-records.json')
const tableRecords = fixture<Record<string, Record<string, Record<string, unknown>>>>('java-records.json')

/** A fixture record as the Java engine wrote it, with the fields the fixture leaves out. */
function javaRecord(entry: Record<string, unknown>): string {
  const { trace: _trace, ...rest } = entry
  const { recordId: _, ...fields } = { recordId: '', ...rest }
  const ordered: Record<string, unknown> = { recordId: 'java-1' }
  for (const [k, v] of Object.entries(fields)) {
    if (k === 'businessDate') {
      ordered[k] = v
      ordered.evaluatedAt = '2026-12-01T03:00:00Z'
      ordered.engine = 'rulestone 0.1.0'
    } else {
      ordered[k] = v
    }
  }
  return JSON.stringify(ordered)
}

describe('records of the Java engine replay in the browser engine', () => {
  let flowEngine: RuleStoneEngine
  let tableEngine: RuleStoneEngine
  const tables = [...exampleTables(), ...flowTables()]
  beforeAll(async () => {
    flowEngine = await exampleFlowEngine()
    tableEngine = await exampleEngine()
  })

  for (const file of files(FLOWS, '.stone-test.json')) {
    for (const testCase of parseStoneTests(read(join(FLOWS, file)), file, tables, exampleFlows())) {
      it(`${file}: ${testCase.name}`, async () => {
        const text = javaRecord(flowRecords[file]![testCase.name]!)
        const record = readDecisionRecord(text)
        expect(writeDecisionRecord(record)).toBe(text.replace('"2026-12-01T03:00:00Z"', '"2026-12-01T03:00:00Z"'))
        if (record.version === undefined) {
          await expect(flowEngine.replay(record, testCase.facts)).rejects.toThrow('so there is nothing to replay')
          return
        }
        const comparison = await flowEngine.replay(record, testCase.facts)
        expect(comparison.differences).toEqual([])
        expect(comparison.matches).toBe(true)
      })
    }
  }

  for (const file of files(EXAMPLES, '.stone-test.json')) {
    for (const testCase of parseStoneTests(read(join(EXAMPLES, file)), file, exampleTables())) {
      it(`${file}: ${testCase.name}`, async () => {
        const record = readDecisionRecord(javaRecord(tableRecords[file]![testCase.name]!))
        // A record with no version has nothing to replay; one that failed because the request forced a
        // parameter set version that does not exist records no parameter set version to force again.
        if (record.version === undefined || (record.status !== 'OK' && testCase.params.size > 0)) return
        const comparison = await tableEngine.replay(record, testCase.facts)
        expect(comparison.differences).toEqual([])
      })
    }
  }
})

describe('replay and re-score', () => {
  const policy = parseDecisionTable(`{ "id": "policy", "version": "1", "hitPolicy": "FIRST",
    "inputs": { "age": "int" }, "outputs": { "decision": "string" },
    "rules": [ { "id": "MINOR", "when": "age < 18", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" },
               { "id": "ADULT", "when": "true", "then": { "decision": "'APPROVE'" } } ] }`)
  const policy2: DecisionTable = { ...policy, version: '2', effectiveFrom: LocalDate.parse('2027-01-01')!, rules: [{ ...policy.rules[0]!, when: 'age < 21' }, policy.rules[1]!] }

  it('reports changed facts, and a version that is registered with other content', async () => {
    const engine = await createEngine({ tables: [policy], clock: () => new Date('2026-12-01T03:00:00Z') })
    const { record } = await engine.evaluate({ id: 'policy', asOf: '2026-12-01' }, { age: 19 })
    expect((await engine.replay(record, { age: 19 })).matches).toBe(true)
    const changed = await engine.replay(record, { age: 17 })
    expect(changed.differences.map((d) => `${d.kind}: ${d.description}`)).toEqual([
      'FACTS: factsHash: the facts are not those the record was made on',
      "OUTPUT: output 'decision' differs",
      'REASON_CODES: reasonCodes: recorded [], now [UNDERAGE from policy]',
      'PATH: path step 1 (policy): recorded matched rules [ADULT], now [MINOR]',
    ])
    const other = await createEngine({ tables: [{ ...policy, description: 'edited' }] })
    await expect(other.replay(record, { age: 19 })).rejects.toThrow(DecisionNotFoundError)
    await expect(other.replay(record, { age: 19 })).rejects.toThrow(/version '1' is registered with other content/)
    await expect(engine.replay(record, { age: 19 }, { x: { failure: 'ERROR' } })).rejects.toThrow(
      'a replay takes the model results from the record; the facts must not carry any, but carry [x]',
    )
  })

  it('re-scores a record with a new version of the rules', async () => {
    const engine = await createEngine({ tables: [policy, policy2] })
    const { record } = await engine.evaluate({ id: 'policy', version: '1', asOf: '2026-12-01', correlationId: 'C-9' }, { age: 19 })
    const comparison = await engine.rescore(record, { age: 19 }, { id: 'policy', version: '2', asOf: '2026-12-01' }, 'RECORDED')
    expect(comparison.evaluated.correlationId).toBe('C-9')
    expect(comparison.differences.map((d) => d.kind)).toEqual(['VERSION', 'OUTPUT', 'REASON_CODES', 'PATH', 'PATH'])
    await expect(engine.rescore(record, { age: 19 }, 'other', 'RECORDED')).rejects.toThrow(
      "a re-score evaluates the recorded decision 'policy', not 'other'",
    )
  })

  it('re-scores a flow with the recorded model results or with live models', async () => {
    const tables = files(FLOWS, '.stone.json').map((f) => parseDecisionTable(read(join(FLOWS, f))))
    const flow = parseDecisionFlow(read(join(FLOWS, 'card_origination_scored.flow.json')))
    let pd = '0.0150'
    const invoker: ModelInvoker = (call) => ({ modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd } })
    const engine = await createEngine({ tables, flows: [flow], modelInvokers: { retail_pd_behaviour: invoker, retail_pd_application: invoker } })
    const facts = {
      applicant: { id: 'C-1', age: 35, income: Money.of(Decimal.parse('20000000')!, 'VND'), existingCustomer: true },
      bureau: { dpd30Count12m: 0, utilisation: Decimal.parse('0.40')! },
    }
    const fraud = { fraud: { modelId: 'card_application_fraud', modelVersion: '1.2.0', outputs: { score: '0.05' } } }
    const ref = { id: 'card_origination_scored', asOf: '2026-12-01' }
    const { record } = await engine.evaluate(ref, facts, fraud)
    // Read back from JSON, as from an audit log.
    const stored = readDecisionRecord(writeDecisionRecord(record))
    expect((await engine.replay(stored, facts)).differences).toEqual([])
    pd = '0.1500'
    expect((await engine.rescore(stored, facts, ref, 'RECORDED')).matches).toBe(true)
    const live = await engine.rescore(stored, facts, ref, 'LIVE', fraud)
    expect(live.differences.map((d) => d.description)).toContain("path step 4 (pd_behaviour): model output 'pd' differs")
    expect(live.evaluated.output.get('decision')).toBe('REFER')
  })
})

describe('reading records', () => {
  it('reports every problem, never quoting values', () => {
    expect(() => readDecisionRecord('{ "recordId": "x", "status": "MAYBE", "output": { "a": null }, "path": [ { "node": "n", "type": "table", "status": "OK" } ], "extra": 1 }')).toThrow(
      new InvalidRecordError([
        '$.extra: unknown field (allowed: businessDate, correlationId, decision, engine, error, evaluatedAt, factsHash, output, params, path, reasonCodes, recordId, routing, status, version)',
        '$.decision: required non-empty string',
        '$.businessDate: required non-empty string',
        '$.evaluatedAt: required non-empty string',
        '$.engine: required non-empty string',
        '$.status: expected one of OK, ERROR, TIMEOUT',
        '$.output.a: expected a value; null is not one',
        '$.reasonCodes: required array of { "code", "source" }',
        '$.path[0].status: only a model step has this field',
      ]),
    )
    expect(() => readDecisionRecord('{ "secret": ')).toThrow(new InvalidRecordError(['$: invalid JSON at line 1, column 13']))
  })
})
