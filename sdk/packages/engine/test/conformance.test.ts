// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The conformance suite: every example table and every case of every example test file give
// the same rule versions, results and decision records as the Java engine (fixtures written by
// rulestone-test's SdkFixturesTest).

import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import {
  LocalDate, RuleStoneError, decisionRecordToJson, explainDecision, explanationText, parameterSetVersionOf,
  parseDecisionTable, parseParameterSet, reasonCodeCatalogVersionOf, ruleVersionOf, writeDecisionTable,
  writeExplanation, writeJson, writeParameterSet, writeReasonCodeCatalog, type DecisionRecord, type DecisionTable,
  type ExplainableRecord, type ParamSetVersion, type RankedAttribution, type RuleStoneEngine, type Value,
} from '../src/index.js'
import { checkStoneTestCase, parseStoneTests, type StoneTestCase } from '../src/testing.js'
import { EXAMPLES, FIXTURES, exampleCatalog, exampleEngine, exampleTables, files, fixture, read } from './helpers.js'

const hashes = fixture<Record<string, { ruleVersion: string; canonical: string }>>('java-hashes.json')
const records = fixture<Record<string, Record<string, Record<string, unknown>>>>('java-records.json')

describe('content hash', () => {
  const tableFiles = [
    ...files(EXAMPLES, '.stone.json').map((f) => join(EXAMPLES, f)),
    ...files(join(FIXTURES, 'tables'), '.stone.json').map((f) => join(FIXTURES, 'tables', f)),
  ]

  const paramFiles = [
    ...files(EXAMPLES, '.params.json').map((f) => join(EXAMPLES, f)),
    ...files(join(FIXTURES, 'tables'), '.params.json').map((f) => join(FIXTURES, 'tables', f)),
  ]

  it('covers every table and parameter set the Java fixture covers', () => {
    expect([...tableFiles, ...paramFiles].map((f) => f.split('/').pop()).sort()).toEqual(Object.keys(hashes).sort())
  })

  for (const file of paramFiles) {
    const name = file.split('/').pop()!
    it(`${name} has the Java canonical form and version`, async () => {
      const set = parseParameterSet(read(file))
      expect(writeParameterSet(set)).toBe(hashes[name]!.canonical)
      expect(await parameterSetVersionOf(set)).toBe(hashes[name]!.ruleVersion)
    })
  }

  for (const file of tableFiles) {
    const name = file.split('/').pop()!
    it(`${name} has the Java canonical form and rule version`, async () => {
      const table = parseDecisionTable(read(file))
      expect(writeDecisionTable(table)).toBe(hashes[name]!.canonical)
      expect(await ruleVersionOf(table)).toBe(hashes[name]!.ruleVersion)
    })
  }
})

/** The record as the fixture holds it: without the id, the instant and the engine name. */
function comparable(record: DecisionRecord, trace?: readonly { ruleId: string; matched: boolean }[]): unknown {
  const json = JSON.parse(writeJson(decisionRecordToJson(record))) as Record<string, unknown>
  delete json.recordId
  delete json.evaluatedAt
  delete json.engine
  if (trace) json.trace = trace.map((t) => [t.ruleId, t.matched])
  return json
}

describe('example test files', () => {
  let tables: DecisionTable[]
  let engine: RuleStoneEngine
  const covered = new Map<string, Set<string>>()

  beforeAll(async () => {
    tables = exampleTables()
    engine = await exampleEngine(tables)
  })

  const testFiles = files(EXAMPLES, '.stone-test.json')
  it('has a Java record for every test file', () => {
    expect(testFiles).toEqual(Object.keys(records).sort())
  })

  let total = 0
  for (const file of testFiles) {
    const text = read(join(EXAMPLES, file))
    // Cases are read once tables are available; names come from the Java fixture.
    const names = Object.keys(records[file] ?? {})
    total += names.length
    describe(file, () => {
      let cases: Map<string, StoneTestCase>
      beforeAll(() => {
        cases = new Map(parseStoneTests(text, file, tables).map((c) => [c.name, c]))
      })
      it('has the cases the Java fixture has', () => {
        expect([...cases.keys()]).toEqual(names)
      })
      for (const name of names) {
        it(name, async () => {
          const testCase = cases.get(name)
          expect(testCase, 'case read from the test file').toBeDefined()
          const outcome = await checkStoneTestCase(engine, testCase!)
          const record = outcome.result?.record ?? outcome.error!.record!
          expect(comparable(record, outcome.result?.trace)).toEqual(records[file]![name])
          for (const rule of outcome.result?.matchedRules ?? []) {
            const key = `${testCase!.decision}@${outcome.result!.ruleVersion.split('@')[0]}`
            if (!covered.has(key)) covered.set(key, new Set())
            covered.get(key)!.add(rule)
          }
        })
      }
    })
  }

  it(`runs all ${total} cases`, () => {
    expect(total).toBeGreaterThanOrEqual(231)
  })

  it('matches every rule of every example table in at least one case', () => {
    const missing: string[] = []
    for (const table of tables) {
      const matched = covered.get(`${table.id}@${table.version}`) ?? new Set()
      for (const rule of table.rules) if (!matched.has(rule.id)) missing.push(`${table.id}@${table.version} ${rule.id}`)
    }
    expect(missing).toEqual([])
  })

  it('attaches the record to errors', async () => {
    await expect(engine.evaluate({ id: 'loan_eligibility', asOf: '2025-12-31' }, {})).rejects.toSatisfy(
      (e: unknown) => e instanceof RuleStoneError && e.record?.status === 'ERROR' && e.record.version === undefined,
    )
  })
})

interface JavaExplanation {
  readonly record?: Record<string, unknown>
  readonly [locale: string]: { readonly json: string; readonly text: string } | Record<string, unknown> | undefined
}
const explanations = fixture<{
  catalog: { file: string; version: string; canonical: string }
  files: Record<string, Record<string, JavaExplanation>>
}>('java-explanations.json')

/** A Java record from DecisionRecordJson, as an explanation reads it: ints as bigint, other values as their JSON strings. */
function recordFromJson(json: Record<string, unknown>): ExplainableRecord {
  const value = (v: unknown): Value =>
    typeof v === 'number' ? BigInt(v) : Array.isArray(v) ? v.map(value) : (v as Value)
  const path = (json.path as Record<string, unknown>[]).map((step) => {
    const model = step.model as { id: string; version: string } | undefined
    return {
      node: step.node as string,
      type: step.type as string,
      ...(step.decision !== undefined ? { decision: step.decision as string } : {}),
      ...(step.version !== undefined ? { version: step.version as string } : {}),
      ...(step.matchedRules !== undefined ? { matchedRules: step.matchedRules as string[] } : {}),
      ...(step.taken !== undefined ? { taken: step.taken as string } : {}),
      ...(model
        ? { model: { id: model.id, version: model.version, status: step.status as string, attributions: (step.attributions ?? []) as RankedAttribution[] } }
        : {}),
    }
  })
  return {
    recordId: json.recordId as string,
    ...(json.correlationId !== undefined ? { correlationId: json.correlationId as string } : {}),
    decision: json.decision as string,
    ...(json.version !== undefined ? { version: json.version as string } : {}),
    ...(json.params !== undefined ? { params: json.params as ParamSetVersion[] } : {}),
    businessDate: LocalDate.parse(json.businessDate as string)!,
    status: json.status as 'OK' | 'ERROR' | 'TIMEOUT',
    output: new Map(Object.entries(json.output as Record<string, unknown>).map(([k, v]) => [k, value(v)])),
    reasonCodes: json.reasonCodes as { code: string; source: string }[],
    path,
  }
}

describe('explanations', () => {
  let engine: RuleStoneEngine
  let tables: DecisionTable[]
  beforeAll(async () => {
    tables = exampleTables()
    engine = await exampleEngine(tables)
  })

  it('reads the catalog with the Java canonical form and version', async () => {
    const catalog = exampleCatalog()
    expect(writeReasonCodeCatalog(catalog)).toBe(explanations.catalog.canonical)
    expect(await reasonCodeCatalogVersionOf(catalog)).toBe(explanations.catalog.version)
    expect(engine.reasonCodeCatalogVersion).toBe(explanations.catalog.version)
  })

  for (const [file, cases] of Object.entries(explanations.files)) {
    describe(file, () => {
      for (const [name, entry] of Object.entries(cases)) {
        it(name, async () => {
          let record: ExplainableRecord
          if (entry.record) {
            record = recordFromJson(entry.record)
          } else {
            const testCase = parseStoneTests(read(join(EXAMPLES, file)), file, tables).find((c) => c.name === name)!
            const outcome = await checkStoneTestCase(engine, testCase)
            record = outcome.result?.record ?? outcome.error!.record!
          }
          for (const locale of exampleCatalog().locales) {
            const expected = entry[locale] as { json: string; text: string }
            const explanation = engine.explain(record, locale)
            expect(writeExplanation(explanation)).toBe(expected.json)
            expect(explanationText(explanation)).toBe(expected.text)
            expect(writeExplanation(await explainDecision(record, exampleCatalog(), locale))).toBe(expected.json)
          }
        })
      }
    })
  }
})
