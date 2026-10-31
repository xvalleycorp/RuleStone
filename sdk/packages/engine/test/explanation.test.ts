// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  StoneTestFailure, checkStoneTestCase, createEngine, explainDecision, explanationText, parseDecisionTable,
  parseReasonCodeCatalog, parseStoneTests, writeExplanation,
} from '../src/index.js'

const TABLE = parseDecisionTable(`{
  "id": "loan", "version": "1", "hitPolicy": "FIRST",
  "inputs": { "dti": "decimal" }, "outputs": { "decision": "string" },
  "rules": [
    { "id": "HIGH", "when": "dti > decimal('0.5')", "then": { "decision": "'REJECT'" }, "reasonCode": "DTI_HIGH" },
    { "id": "OK", "when": "true", "then": { "decision": "'APPROVE'" } } ] }`)

const CATALOG = parseReasonCodeCatalog(`{
  "version": "1", "locales": ["en", "vi"],
  "codes": { "DTI_HIGH": { "en": "Your debt is high.", "vi": "Nợ của quý khách cao." } } }`)

const TESTS = (explanation: string) => `{ "decision": "loan", "cases": [
  { "name": "high", "asOf": "2026-12-01", "facts": { "dti": "0.6" }, "expect": { "explanation": ${explanation} } } ] }`

describe('explanations', () => {
  it('explains a record in each locale, without input values', async () => {
    const engine = await createEngine({ tables: [TABLE], reasonCodeCatalog: CATALOG, recordIds: () => 'r' })
    const result = await engine.evaluateJson({ id: 'loan', asOf: '2026-12-01' }, '{ "dti": "0.6" }')
    const vi = engine.explain(result.record, 'vi')
    expect(vi.reasons).toEqual([{ code: 'DTI_HIGH', source: 'loan', message: 'Nợ của quý khách cao.' }])
    expect(explanationText(vi)).toBe(
      `Quyết định: loan ${result.ruleVersion}\nNgày nghiệp vụ: 2026-12-01\nKết quả: decision = "REJECT"\nLý do:\n` +
        `  1. Nợ của quý khách cao. (DTI_HIGH, loan)\nCăn cứ quyết định:\n  - loan: table ${result.ruleVersion}; khớp HIGH\n` +
        `Danh mục mã lý do: ${engine.reasonCodeCatalogVersion}\n`,
    )
    expect(writeExplanation(vi)).not.toContain('0.6')
    expect(writeExplanation(await explainDecision(result.record, CATALOG, 'vi'))).toBe(writeExplanation(vi))
    expect(() => engine.explain(result.record, 'fr')).toThrow("locale 'fr' is not in the reason-code catalog, which has en, vi")
    const without = await createEngine({ tables: [TABLE] })
    expect(() => without.explain(result.record, 'en')).toThrow(RangeError)
  })

  it('checks the explanation of a test case', async () => {
    const engine = await createEngine({ tables: [TABLE], reasonCodeCatalog: CATALOG })
    const [ok] = parseStoneTests(TESTS('{ "locale": "en", "reasons": ["DTI_HIGH"], "messages": ["Your debt is high."] }'), 't', [TABLE])
    await expect(checkStoneTestCase(engine, ok!)).resolves.toBeDefined()
    const [bad] = parseStoneTests(TESTS('{ "locale": "vi", "reasons": ["X"], "messages": ["y"] }'), 't', [TABLE])
    await expect(checkStoneTestCase(engine, bad!)).rejects.toSatisfy(
      (e: unknown) =>
        e instanceof StoneTestFailure &&
        e.message.includes('explanation reasons: expected [X] but was [DTI_HIGH]') &&
        e.message.includes('explanation messages (vi): expected [y] but was [Nợ của quý khách cao.]'),
    )
    const without = await createEngine({ tables: [TABLE] })
    await expect(checkStoneTestCase(without, ok!)).rejects.toThrow('explanation: the engine has no reason-code catalog')
    expect(() => parseStoneTests(TESTS('{ "reasons": [], "x": 1 }'), 't', [TABLE])).toThrow(
      '$.cases[0].expect.explanation.x: unknown field (allowed: locale, messages, reasons)',
    )
  })
})
