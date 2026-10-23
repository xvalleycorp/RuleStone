// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  AuditError, Decimal, DecisionEvaluationError, InvalidDecisionError, InvalidFactsError, LocalDate,
  Money, createEngine, decisionRecordToJson, outputJson, parseDecisionTable, readFacts, writeDecisionRecord, writeJson,
  type DecisionRecord, type DecisionTable,
} from '../src/index.js'
import { exampleEngine, fixture } from './helpers.js'

interface InvalidCase {
  name: string
  tables: string[]
}
interface JavaInvalid {
  name: string
  read: (string[] | null)[]
  build: string[]
}

/** Java's message without the list of candidate overloads, which names protobuf types. */
function withoutCandidates(message: string): string {
  return message.replace(/ \(candidates: [^\n]*\)/g, '')
}

describe('invalid tables report the problems Java reports', () => {
  const cases = fixture<InvalidCase[]>('invalid-tables.json')
  const java = fixture<JavaInvalid[]>('java-invalid-tables.json')
  cases.forEach((testCase, index) => {
    it(testCase.name, async () => {
      const expected = java[index]!
      const tables: DecisionTable[] = []
      const read = testCase.tables.map((text) => {
        try {
          tables.push(parseDecisionTable(text))
          return null
        } catch (e) {
          if (!(e instanceof InvalidDecisionError)) throw e
          return e.problems
        }
      })
      expect(read).toEqual(expected.read)
      let build: readonly string[] = []
      try {
        await createEngine({ tables })
      } catch (e) {
        if (!(e instanceof InvalidDecisionError)) throw e
        build = e.problems
      }
      expect(build).toEqual(expected.build.map(withoutCandidates))
    })
  })

  it('formats the message as InvalidDecisionException does', () => {
    expect(new InvalidDecisionError(['a: x', 'b: y\n | z']).message).toBe(
      'invalid decision definition (2 problems):\n  - a: x\n  - b: y\n     | z',
    )
  })
})

const CARD_FEE = `{
  "id": "card_fee",
  "version": "1",
  "hitPolicy": "FIRST",
  "inputs":  { "customer": { "birthDate": "date" }, "spend": "money" },
  "outputs": { "fee": "money", "nextReview": "date" },
  "rules": [
    { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
      "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)" },
      "reasonCode": "YOUTH_WAIVER" },
    { "id": "STANDARD", "when": "true",
      "then": { "fee": "(spend * decimal('0.001')).round()", "nextReview": "businessDate.plusMonths(6)" } }
  ]
}`

describe('engine', () => {
  it('evaluates the card_fee example of docs/java-api.md', async () => {
    const records: DecisionRecord[] = []
    const engine = await createEngine({
      tables: [parseDecisionTable(CARD_FEE)],
      clock: () => new Date('2026-11-01T02:15:04.218Z'),
      recordIds: () => '3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75',
      auditSinks: [(r) => void records.push(r)],
    })
    const result = await engine.evaluateJson(
      { id: 'card_fee', asOf: '2026-11-01', correlationId: 'CARD-2026-000123' },
      '{ "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }',
    )
    expect(outputJson(result)).toBe('{"fee":"123457 VND","nextReview":"2027-05-01"}')
    expect(result.ruleVersion).toBe('1@sha256:e38b29d8a5086a1e73ed2f1b54bba1a3fc32167000eebab77ca579516e4a198e')
    expect(records).toEqual([result.record])
    const record = JSON.parse(writeDecisionRecord(result.record)) as Record<string, unknown>
    expect(Object.keys(record)).toEqual([
      'recordId', 'correlationId', 'decision', 'version', 'businessDate', 'evaluatedAt', 'engine', 'status', 'factsHash',
      'output', 'reasonCodes', 'path',
    ])
    expect(record).toMatchObject({
      recordId: '3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75',
      evaluatedAt: '2026-11-01T02:15:04.218Z',
      engine: 'rulestone-js 0.1.0',
      status: 'OK',
      reasonCodes: [],
      path: [{ node: 'card_fee', type: 'table', version: result.ruleVersion, matchedRules: ['STANDARD'] }],
    })
    expect(record.factsHash).toMatch(/^sha256:[0-9a-f]{64}$/)
  })

  it('hashes facts canonically: equal decimals and field order give the same hash', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(CARD_FEE)] })
    const hash = async (json: string) =>
      (await engine.evaluateJson({ id: 'card_fee', asOf: '2026-11-01' }, json)).record.factsHash
    const a = await hash('{ "spend": "123456789.00 VND", "customer": { "birthDate": "1990-05-20" } }')
    const b = await hash('{ "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }')
    expect(a).toBe(b)
  })

  it('selects versions by date and by version, and passes the business date', async () => {
    const engine = await exampleEngine()
    const facts = {
      applicant: { age: 30, monthlyIncome: Decimal.parse('25750000.50')!, monthlyDebt: 5000000, segment: 'RETAIL', blacklisted: false },
      amount: 300000000,
    }
    expect((await engine.evaluate({ id: 'loan_eligibility', asOf: '2026-10-31' }, facts)).ruleVersion).toMatch(/^2@/)
    expect((await engine.evaluate({ id: 'loan_eligibility', asOf: LocalDate.of(2026, 11, 1) }, facts)).ruleVersion).toMatch(/^3@/)
    expect((await engine.evaluate({ id: 'loan_eligibility', version: '2', asOf: '2027-01-01' }, facts)).ruleVersion).toMatch(/^2@/)
    // Without asOf, the clock decides: 2026-12-01 in Hanoi.
    const today = await engine.evaluate('loan_eligibility', facts)
    expect(today.record.businessDate.toString()).toBe('2026-12-01')
    await expect(engine.evaluate({ id: 'loan_eligibility', version: '9' }, facts)).rejects.toThrow(
      "decision 'loan_eligibility' has no version '9'",
    )
    await expect(engine.evaluate('nope', facts)).rejects.toThrow("unknown decision 'nope'")
  })

  it('rejects a businessDate fact, missing inputs, floating point and wrong types', async () => {
    const engine = await createEngine({ tables: [parseDecisionTable(CARD_FEE)] })
    const ref = { id: 'card_fee', asOf: '2026-11-01' }
    const customer = { birthDate: LocalDate.of(1990, 5, 20) }
    const spend = Money.of(Decimal.parse('1000')!, 'VND')
    await expect(engine.evaluate(ref, { customer, spend, businessDate: LocalDate.of(2026, 1, 1) })).rejects.toThrow(
      /invalid input businessDate: reserved name/,
    )
    await expect(engine.evaluate(ref, { customer })).rejects.toThrow(/: invalid input spend: missing input$/)
    await expect(engine.evaluate(ref, { customer, spend: 1000 })).rejects.toThrow(/invalid input spend: expected money/)
    const error = await engine.evaluate(ref, { customer: { birthDate: '1990-05-20' }, spend }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(DecisionEvaluationError)
    expect((error as DecisionEvaluationError).record?.status).toBe('ERROR')
    expect((error as DecisionEvaluationError).record?.factsHash).toBeUndefined()
    expect(() => readFacts('{ "customer": { "birthDate": "1990-02-30" }, "spend": 1.5, "x": 1 }', engine.resolve(ref).table)).toThrow(
      new InvalidFactsError([
        'customer.birthDate: expected an existing date as "yyyy-MM-dd"',
        'spend: expected money as "<amount> <CCY>", e.g. "1500000 VND"',
        'x: not declared (declared: customer, spend)',
      ]),
    )
  })

  it('fails UNIQUE tables with several matches and collects under COLLECT', async () => {
    const unique = parseDecisionTable(`{ "id": "u", "version": "1", "hitPolicy": "UNIQUE", "inputs": { "n": "int" },
      "outputs": { "band": "string" }, "rules": [ { "id": "A", "when": "n > 1", "then": { "band": "'A'" } },
      { "id": "B", "when": "n > 2", "then": { "band": "'B'" } } ] }`)
    const collect = parseDecisionTable(`{ "id": "c", "version": "1", "hitPolicy": "COLLECT", "inputs": { "n": "int" },
      "outputs": { "fee": "decimal", "code": "string" }, "rules": [ { "id": "A", "when": "n > 1", "then": { "fee": "decimal('1.50')" } },
      { "id": "B", "when": "n > 2", "then": { "fee": "decimal(2)", "code": "'B'" }, "reasonCode": "BIG" } ] }`)
    const engine = await createEngine({ tables: [unique, collect] })
    await expect(engine.evaluate('u', { n: 5 })).rejects.toThrow(/UNIQUE hit policy violated: rules \[A, B\] all matched$/)
    expect(outputJson(await engine.evaluate('u', { n: 2 }))).toBe('{"band":"A"}')
    const collected = await engine.evaluate('c', { n: 5n })
    expect(outputJson(collected)).toBe('{"fee":["1.50","2"],"code":["B"]}')
    expect(collected.reasonCodes).toEqual(['BIG'])
    expect(outputJson(await engine.evaluate('c', { n: 0 }))).toBe('{"fee":[],"code":[]}')
  })

  it('limits iterations and applies currency scales', async () => {
    const table = parseDecisionTable(`{ "id": "t", "version": "1", "hitPolicy": "FIRST", "inputs": { "xs": ["int"], "gold": "money" },
      "outputs": { "rounded": "money" }, "rules": [ { "id": "R", "when": "xs.all(x, x > 0)", "then": { "rounded": "gold.round()" } } ] }`)
    const engine = await createEngine({ tables: [table], maxIterations: 3, currencyScales: { XAU: 2 } })
    const gold = Money.of(Decimal.parse('1.005')!, 'XAU')
    expect(outputJson(await engine.evaluate('t', { xs: [1, 2, 3], gold }))).toBe('{"rounded":"1.00 XAU"}')
    await expect(engine.evaluate('t', { xs: [1, 2, 3, 4], gold })).rejects.toThrow(
      'rule R condition failed: evaluation error at <input>:6: Iteration budget exceeded: 3 in: xs.all(x, x > 0)',
    )
    await expect(createEngine({ tables: [table], currencyScales: { ZZZ: 2 } })).rejects.toThrow(RangeError)
    await expect(createEngine({ tables: [table], maxIterations: 0 })).rejects.toThrow(
      new InvalidDecisionError(['t@1 inputs: maxIterations must be positive']),
    )
  })

  it('fails the evaluation when an audit sink fails, keeping the record', async () => {
    const engine = await createEngine({
      tables: [parseDecisionTable(CARD_FEE)],
      auditSinks: [() => Promise.reject(new TypeError('disk full'))],
    })
    const error = await engine
      .evaluateJson({ id: 'card_fee', asOf: '2026-11-01' }, '{ "customer": { "birthDate": "1990-05-20" }, "spend": "1 VND" }')
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(AuditError)
    expect((error as AuditError).message).toMatch(/^audit sink rejected decision record [0-9a-f-]{36} \(TypeError\); the decision was not returned$/)
    expect(JSON.parse(writeJson(decisionRecordToJson((error as AuditError).record!))).status).toBe('OK')
  })
})
