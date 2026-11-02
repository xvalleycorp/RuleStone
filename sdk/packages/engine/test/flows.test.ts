// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The conformance suite for decision flows: every example flow and the tables it uses have the
// Java canonical form and rule version; every case of every example flow test file gives the Java
// record, trace and explanation; and every invalid flow of invalid-flows.json gives the problems
// Java reports (fixtures written by rulestone-test's SdkFixturesTest).

import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import {
  InvalidDecisionError, checkStoneTestCase, createEngine, decisionRecordToJson, explanationText, flowVersionOf,
  parseDecisionFlow, parseDecisionTable, parseParameterSet, parseReasonCodeCatalog, parseStoneTests, ruleVersionOf,
  writeDecisionFlow, writeDecisionTable, writeExplanation, writeJson, type DecisionFlow, type DecisionRecord,
  type ModelInvoker, type ParameterSet, type RuleStoneEngine,
} from '../src/index.js'
import { FLOWS, exampleCatalog, exampleFlowEngine, exampleFlows, exampleTables, files, fixture, flowTables, read } from './helpers.js'

const hashes = fixture<Record<string, { ruleVersion: string; canonical: string }>>('java-flow-hashes.json')
const records = fixture<Record<string, Record<string, Record<string, unknown>>>>('java-flow-records.json')
const explanations = fixture<{ files: Record<string, Record<string, Record<string, { json: string; text: string }>>> }>(
  'java-explanations.json',
)

/** The record as the fixture holds it: without the id, the instant and the engine name. */
function comparable(record: DecisionRecord, trace?: readonly { ruleId: string; matched: boolean }[]): unknown {
  const json = JSON.parse(writeJson(decisionRecordToJson(record))) as Record<string, unknown>
  delete json.recordId
  delete json.evaluatedAt
  delete json.engine
  if (trace) json.trace = trace.map((t) => [t.ruleId, t.matched])
  return json
}

describe('flow content hashes', () => {
  const names = [...files(FLOWS, '.stone.json'), ...files(FLOWS, '.flow.json')]
  it('covers every file the Java fixture covers', () => {
    expect([...names].sort()).toEqual(Object.keys(hashes).sort())
  })
  for (const name of names) {
    it(`${name} has the Java canonical form and rule version`, async () => {
      const text = read(join(FLOWS, name))
      if (name.endsWith('.flow.json')) {
        const flow = parseDecisionFlow(text)
        expect(writeDecisionFlow(flow)).toBe(hashes[name]!.canonical)
        expect(await flowVersionOf(flow)).toBe(hashes[name]!.ruleVersion)
      } else {
        const table = parseDecisionTable(text)
        expect(writeDecisionTable(table)).toBe(hashes[name]!.canonical)
        expect(await ruleVersionOf(table)).toBe(hashes[name]!.ruleVersion)
      }
    })
  }
})

describe('example flow test files', () => {
  let engine: RuleStoneEngine
  const tables = [...exampleTables(), ...flowTables()]
  const flows = exampleFlows()
  beforeAll(async () => {
    engine = await exampleFlowEngine()
  })
  const testFiles = files(FLOWS, '.stone-test.json')
  it('has a Java record for every flow test file', () => {
    expect(testFiles).toEqual(Object.keys(records).sort())
  })
  for (const file of testFiles) {
    const cases = parseStoneTests(read(join(FLOWS, file)), file, tables, flows)
    describe(file, () => {
      it('has the cases the Java fixture has', () => {
        expect(cases.map((c) => c.name)).toEqual(Object.keys(records[file]!))
      })
      for (const testCase of cases) {
        it(testCase.name, async () => {
          const outcome = await checkStoneTestCase(engine, testCase)
          const record = outcome.result?.record ?? outcome.error!.record!
          expect(comparable(record, outcome.result?.trace)).toEqual(records[file]![testCase.name])
          // The explanation of the browser engine's own record is the Java one, in every locale.
          const expected = explanations.files[`flows/${file}`]![testCase.name]!
          for (const locale of exampleCatalog().locales) {
            const explanation = engine.explain(record, locale)
            expect(writeExplanation(explanation)).toBe(expected[locale]!.json)
            expect(explanationText(explanation)).toBe(expected[locale]!.text)
          }
        })
      }
    })
  }
})

interface InvalidFlowCase {
  readonly name: string
  readonly tables?: readonly unknown[]
  readonly flows: readonly unknown[]
  readonly paramSets?: readonly unknown[]
  readonly reasonCodes?: unknown
  readonly invokers?: readonly string[]
  readonly suppliedModelsOnly?: boolean
}

interface JavaInvalidFlow {
  readonly name: string
  readonly read: readonly (readonly string[] | null)[]
  readonly build: readonly string[]
}

const text = (definition: unknown): string => (typeof definition === 'string' ? definition : JSON.stringify(definition))

/** The Java message where the browser engine names its own API instead of the Java builder's. */
function inBrowserTerms(message: string): string {
  return message
    .replace(
      /no ModelInvoker registered for model '([^']*)'; register one with RuleStone\.builder\(\)\.modelInvoker\("[^"]*", invoker\), or build with suppliedModelsOnly\(\) for tests/,
      "no model invoker registered for model '$1'; register one with modelInvokers: { '$1': invoker }, or create the engine with suppliedModelsOnly for tests",
    )
    .replace(/ \(candidates: [^\n]*\)/g, '')
}

describe('invalid flows report the problems Java reports', () => {
  const cases = fixture<InvalidFlowCase[]>('invalid-flows.json')
  const java = fixture<JavaInvalidFlow[]>('java-invalid-flows.json')
  it('has a Java result for every case', () => {
    expect(java.map((c) => c.name)).toEqual(cases.map((c) => c.name))
  })
  cases.forEach((testCase, index) => {
    it(testCase.name, async () => {
      const expected = java[index]!
      const tables = (testCase.tables ?? []).map((t) => parseDecisionTable(text(t)))
      const paramSets: ParameterSet[] = (testCase.paramSets ?? []).map((p) => parseParameterSet(text(p)))
      const flows: DecisionFlow[] = []
      const readProblems = testCase.flows.map((f) => {
        try {
          flows.push(parseDecisionFlow(text(f)))
          return null
        } catch (e) {
          if (!(e instanceof InvalidDecisionError)) throw e
          return e.problems
        }
      })
      expect(readProblems).toEqual(expected.read)
      const invoker: ModelInvoker = () => null
      let build: readonly string[] = []
      try {
        await createEngine({
          tables,
          flows,
          paramSets,
          modelInvokers: Object.fromEntries((testCase.invokers ?? []).map((id) => [id, invoker])),
          ...(testCase.suppliedModelsOnly ? { suppliedModelsOnly: true } : {}),
          ...(testCase.reasonCodes !== undefined ? { reasonCodeCatalog: parseReasonCodeCatalog(text(testCase.reasonCodes)) } : {}),
        })
      } catch (e) {
        if (!(e instanceof InvalidDecisionError)) throw e
        build = e.problems
      }
      expect(build).toEqual(expected.build.map(inBrowserTerms))
    })
  })
})
