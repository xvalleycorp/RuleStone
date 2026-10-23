// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The conformance suite: every example table and every case of every example test file give
// the same rule versions, results and decision records as the Java engine (fixtures written by
// rulestone-test's SdkFixturesTest).

import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import {
  RuleStoneError, checkStoneTestCase, decisionRecordToJson, parseDecisionTable, parseStoneTests, ruleVersionOf,
  writeDecisionTable, writeJson, type DecisionRecord, type DecisionTable, type RuleStoneEngine, type StoneTestCase,
} from '../src/index.js'
import { EXAMPLES, FIXTURES, exampleEngine, exampleTables, files, fixture, read } from './helpers.js'

const hashes = fixture<Record<string, { ruleVersion: string; canonical: string }>>('java-hashes.json')
const records = fixture<Record<string, Record<string, Record<string, unknown>>>>('java-records.json')

describe('content hash', () => {
  const tableFiles = [
    ...files(EXAMPLES, '.stone.json').map((f) => join(EXAMPLES, f)),
    ...files(join(FIXTURES, 'tables'), '.stone.json').map((f) => join(FIXTURES, 'tables', f)),
  ]

  it('covers every table the Java fixture covers', () => {
    expect(tableFiles.map((f) => f.split('/').pop()).sort()).toEqual(Object.keys(hashes).sort())
  })

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
