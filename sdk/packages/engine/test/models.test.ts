// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Model nodes in invoke mode, timeouts and keyed facts hashes: what the Java engine's ModelNodeTest,
// TimeoutTest and DecisionRecordTest check, with the browser engine's async invokers.

import { createHmac } from 'node:crypto'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  Decimal, DecisionEvaluationError, DecisionTimeoutError, InvalidDecisionError, InvalidFactsError, Money, createEngine,
  parseDecisionFlow, parseDecisionTable, writeDecisionRecord, type DecisionRecord, type EngineOptions, type ModelCall,
  type ModelInvoker, type ModelStep,
} from '../src/index.js'
import { EXAMPLES, files, read } from './helpers.js'

const FLOWS = join(EXAMPLES, 'flows')
const tables = files(FLOWS, '.stone.json').map((f) => parseDecisionTable(read(join(FLOWS, f))))
const scored = parseDecisionFlow(read(join(FLOWS, 'card_origination_scored.flow.json')))
const plain = parseDecisionFlow(read(join(FLOWS, 'card_origination.flow.json')))

const REF = { id: 'card_origination_scored', asOf: '2026-12-01', correlationId: 'CARD-1' }
const FRAUD = { fraud: { modelId: 'card_application_fraud', modelVersion: '1.2.0', outputs: { score: '0.05' } } }

function facts(existingCustomer: boolean) {
  return {
    applicant: { id: 'C-1', age: 35, income: Money.of(Decimal.parse('20000000')!, 'VND'), existingCustomer },
    bureau: { dpd30Count12m: 0, utilisation: Decimal.parse('0.40')! },
  }
}

/** A monotonic clock the invokers advance, so that latencies and timeouts are exact. */
function fakeClock() {
  let now = 1000
  return { now: () => now, advance: (ms: number) => void (now += ms) }
}

function engine(invokers: Record<string, ModelInvoker>, extra: Partial<EngineOptions> = {}) {
  const records: DecisionRecord[] = []
  const created = createEngine({
    tables,
    flows: [scored, plain],
    modelInvokers: invokers,
    recordIds: () => 'r',
    clock: () => new Date('2026-12-01T03:00:00Z'),
    auditSinks: [(r) => void records.push(r)],
    ...extra,
  })
  return { created, records }
}

const application: ModelInvoker = (call) => ({ modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd: '0.0400' }, confidence: Decimal.parse('0.9')! })

function modelStep(record: DecisionRecord, node: string): ModelStep {
  return record.path.find((s) => s.node === node)!.model!
}

describe('model nodes in invoke mode', () => {
  it('calls the invoker with the declared features and the time available, and records the latency', async () => {
    const clock = fakeClock()
    const calls: ModelCall[] = []
    const behaviour: ModelInvoker = async (call) => {
      calls.push(call)
      clock.advance(23)
      return {
        modelId: call.modelId,
        modelVersion: call.modelVersion,
        outputs: new Map([['pd', Decimal.parse('0.0150')!]]),
        confidence: Decimal.parse('0.95')!,
        attributionMethod: 'SHAP',
        attributions: [
          { feature: 'dpd30Count12m', contribution: Decimal.parse('-0.0010')! },
          { feature: 'utilisation', contribution: Decimal.parse('0.0040')! },
        ],
      }
    }
    const { created } = engine({ retail_pd_behaviour: behaviour, retail_pd_application: application }, { monotonicClock: clock.now })
    const result = await (await created).evaluate(REF, facts(true), FRAUD)
    expect(result.output.get('decision')).toBe('APPROVE')
    expect(result.reasonCodes).toEqual(['HIGH_UTILISATION'])
    expect(calls).toHaveLength(1)
    expect(calls[0]!.modelId).toBe('retail_pd_behaviour')
    expect(calls[0]!.modelVersion).toBe('2.4.0')
    expect([...calls[0]!.features.keys()]).toEqual(['utilisation', 'dpd30Count12m'])
    expect(calls[0]!.features.get('dpd30Count12m')).toBe(0n)
    expect((calls[0]!.features.get('utilisation') as Decimal).toPlainString()).toBe('0.40')
    expect(calls[0]!.timeoutMs).toBe(80)
    expect(calls[0]!.correlationId).toBe('CARD-1')
    expect(calls[0]!.signal).toBeInstanceOf(AbortSignal)
    const json = JSON.parse(writeDecisionRecord(result.record)) as { path: unknown[] }
    expect(json.path[3]).toEqual({
      node: 'pd_behaviour', type: 'model', mode: 'invoke', model: { id: 'retail_pd_behaviour', version: '2.4.0' },
      status: 'OK', latencyMs: 23, outputs: { pd: '0.0150' }, confidence: '0.95', attributionMethod: 'SHAP',
      attributions: [
        { feature: 'utilisation', rank: 1, direction: 'INCREASES_RISK', reasonCode: 'HIGH_UTILISATION' },
        { feature: 'dpd30Count12m', rank: 2, direction: 'DECREASES_RISK' },
      ],
    })
  })

  it('treats a late answer as a timeout and takes onFailure', async () => {
    const clock = fakeClock()
    const late: ModelInvoker = (call) => {
      clock.advance(81)
      return { modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd: '0.01' } }
    }
    const { created } = engine({ retail_pd_behaviour: late, retail_pd_application: application }, { monotonicClock: clock.now })
    const result = await (await created).evaluate(REF, facts(true), FRAUD)
    expect(result.output.get('decision')).toBe('REFER')
    expect(result.reasonCodes).toEqual(['MODEL_UNAVAILABLE'])
    const step = modelStep(result.record, 'pd_behaviour')
    expect(step).toMatchObject({ mode: 'invoke', status: 'TIMEOUT', detail: 'no result within 80 ms', latencyMs: 81 })
    expect(step.outputs.size).toBe(0)
    expect(result.record.path.map((s) => s.node)).toEqual(['policy', 'fraud', 'segment', 'pd_behaviour', 'referred'])
  })

  it('records an error by its name only, and a missing answer as an error', async () => {
    const throwing: ModelInvoker = () => {
      throw new TypeError('utilisation 0.40 is secret')
    }
    const rejecting: ModelInvoker = () => Promise.reject(new RangeError('no'))
    const empty: ModelInvoker = async () => undefined
    for (const [invoker, detail] of [
      [throwing, 'the invoker threw TypeError'],
      [rejecting, 'the invoker threw RangeError'],
      [empty, 'the invoker returned no result'],
    ] as const) {
      const { created } = engine({ retail_pd_behaviour: invoker, retail_pd_application: application })
      const result = await (await created).evaluate(REF, facts(true), FRAUD)
      expect(modelStep(result.record, 'pd_behaviour')).toMatchObject({ status: 'ERROR', detail })
      expect(writeDecisionRecord(result.record)).not.toContain('secret')
    }
  })

  it('checks the answer against the contract', async () => {
    const cases: [ReturnType<ModelInvoker>, string, string][] = [
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.5.0', outputs: { pd: '0.01' } }, 'CONTRACT_VIOLATION',
        'answered by retail_pd_behaviour@2.5.0, not the pinned retail_pd_behaviour@2.4.0'],
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.4.0', outputs: {} }, 'CONTRACT_VIOLATION', "output 'pd' is missing"],
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.4.0', outputs: { pd: 0.01 } }, 'CONTRACT_VIOLATION', "output 'pd' is not decimal"],
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.4.0', outputs: { pd: 'low' } }, 'CONTRACT_VIOLATION', "output 'pd' is not decimal"],
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.4.0', outputs: { pd: '0.01', extra: 1 } }, 'CONTRACT_VIOLATION', "undeclared output 'extra'"],
      [{ modelId: 'retail_pd_behaviour', modelVersion: '2.4.0', outputs: { pd: '1.5' } }, 'INVALID_OUTPUT', 'the valid condition is false'],
    ]
    for (const [answer, status, detail] of cases) {
      const { created } = engine({ retail_pd_behaviour: () => answer, retail_pd_application: application })
      const result = await (await created).evaluate(REF, facts(true), FRAUD)
      expect(modelStep(result.record, 'pd_behaviour')).toMatchObject({ status, detail })
      expect(result.output.get('decision')).toBe('REFER')
    }
  })

  it('requires an invoker for every invoked model unless the engine is for tests', async () => {
    await expect(createEngine({ tables, flows: [scored] })).rejects.toThrow(InvalidDecisionError)
    await expect(createEngine({ tables, flows: [scored] })).rejects.toThrow(
      "card_origination_scored@1 nodes.pd_behaviour.model.id: no model invoker registered for model 'retail_pd_behaviour'",
    )
    await expect(createEngine({ tables, flows: [scored], suppliedModelsOnly: true })).resolves.toBeDefined()
  })

  it('rejects supplied results for nodes that call their model, and for nodes that are not model nodes', async () => {
    const { created } = engine({ retail_pd_behaviour: application, retail_pd_application: application })
    const e = await created
    await expect(
      e.evaluate(REF, facts(true), { ...FRAUD, pd_behaviour: { failure: 'TIMEOUT' } }),
    ).rejects.toThrow("model result supplied for node 'pd_behaviour', which calls its model (mode invoke)")
    await expect(e.evaluate(REF, facts(true), { policy: { failure: 'ERROR' } })).rejects.toThrow(
      "model result supplied for 'policy', which is not a model node of this flow",
    )
    await expect(e.evaluate({ id: 'card_policy', asOf: '2026-12-01' }, facts(true), FRAUD)).rejects.toThrow(
      'model results supplied for [fraud], but a decision table has no model nodes',
    )
  })

  it('reads supplied model results in JSON with the facts', async () => {
    const { created } = engine({ retail_pd_behaviour: application, retail_pd_application: application })
    const e = await created
    const result = await e.evaluateJson(
      REF,
      '{ "applicant": { "id": "C-1", "age": 23, "income": "20000000 VND", "existingCustomer": false }, "bureau": { "dpd30Count12m": 0, "utilisation": "0.6" } }',
      '{ "fraud": { "modelId": "card_application_fraud", "modelVersion": "1.2.0", "outputs": { "score": 0.05 } } }',
    )
    expect(result.output.get('riskGrade')).toBe('B')
    await expect(e.evaluateJson(REF, '{ "applicant": 1 }', '{ "fraud": { "failure": "LATE" }, "x": 2 }')).rejects.toThrow(
      new InvalidFactsError(['applicant: expected an object', 'models.fraud.failure: expected "TIMEOUT" or "ERROR"',
        'models.x: expected an object with "modelId", "modelVersion" and "outputs", or with "failure"']),
    )
  })
})

describe('timeouts', () => {
  it('fails the whole evaluation when its timeout passes while a model is called', async () => {
    const clock = fakeClock()
    const slow: ModelInvoker = (call) => {
      clock.advance(60)
      return { modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd: '0.01' } }
    }
    const { created, records } = engine({ retail_pd_behaviour: slow, retail_pd_application: application }, { monotonicClock: clock.now, timeoutMs: 50 })
    const error = await (await created).evaluate(REF, facts(true), FRAUD).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(DecisionTimeoutError)
    expect((error as Error).message).toBe(
      `card_origination_scored@${(error as DecisionTimeoutError).ruleVersion}: evaluation timed out after 50 ms with 3 rule(s) examined`,
    )
    expect(records[0]!.status).toBe('TIMEOUT')
    expect(modelStep(records[0]!, 'pd_behaviour')).toMatchObject({
      status: 'TIMEOUT', detail: "the evaluation's timeout passed while the model was called", latencyMs: 60,
    })
  })

  it('gives a model no more time than the evaluation has left', async () => {
    const clock = fakeClock()
    let available = 0
    const fast: ModelInvoker = (call) => {
      available = call.timeoutMs
      return { modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd: '0.01' } }
    }
    const { created } = engine({ retail_pd_behaviour: fast, retail_pd_application: application }, { monotonicClock: clock.now })
    await (await created).evaluate({ ...REF, timeoutMs: 30 }, facts(true), FRAUD)
    expect(available).toBe(30)
  })

  it('checks the time before each rule of a table', async () => {
    const clock = fakeClock()
    const table = parseDecisionTable(
      '{ "id": "t", "version": "1", "hitPolicy": "COLLECT", "inputs": {}, "outputs": {}, "rules": [ { "id": "A", "when": "true" }, { "id": "B", "when": "true" } ] }',
    )
    let calls = 0
    const e = await createEngine({ tables: [table], timeoutMs: 5, monotonicClock: () => (calls++ < 2 ? clock.now() : clock.now() + 10) })
    const error = await e.evaluate('t', {}).catch((x: unknown) => x)
    expect(error).toBeInstanceOf(DecisionTimeoutError)
    expect(error).toBeInstanceOf(DecisionEvaluationError)
    expect((error as DecisionTimeoutError).trace).toEqual([{ ruleId: 'A', matched: true }])
    expect((error as DecisionTimeoutError).record?.status).toBe('TIMEOUT')
    expect((error as Error).message).toMatch(/evaluation timed out after 5 ms with 1 rule\(s\) examined$/)
    await expect(createEngine({ tables: [table], timeoutMs: 0 })).rejects.toThrow('timeout must be positive')
  })
})

describe('facts hash key', () => {
  it('makes the facts hash an HMAC-SHA256 of the canonical inputs', async () => {
    const key = new TextEncoder().encode('0123456789abcdef0123456789abcdef')
    const table = parseDecisionTable(
      '{ "id": "t", "version": "1", "hitPolicy": "FIRST", "inputs": { "amount": "decimal" }, "outputs": {}, "rules": [ { "id": "A", "when": "true" } ] }',
    )
    const result = await (await createEngine({ tables: [table], factsHashKey: key })).evaluate('t', { amount: Decimal.parse('1.50')! })
    const expected = createHmac('sha256', key).update('{"amount":"1.5"}').digest('hex')
    expect(result.record.factsHash).toBe(`hmac-sha256:${expected}`)
    await expect(createEngine({ tables: [table], factsHashKey: new Uint8Array(8) })).rejects.toThrow('factsHashKey must be at least 16 bytes')
  })
})
