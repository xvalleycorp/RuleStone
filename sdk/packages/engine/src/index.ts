// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * RuleStone decision tables in the browser: the same results as the Java engine for every
 * table, test case and documented expression that the conformance suite covers.
 *
 * @packageDocumentation
 */

export { createEngine, outputJson, RuleStoneEngine, BUSINESS_DATE, ENGINE_NAME } from './engine.js'
export type { AuditSink, CompiledDecision, DecisionRef, DecisionResult, EngineOptions, TraceStep } from './engine.js'

export { parseDecisionTable, writeDecisionTable, ruleVersionOf, isEffectiveOn, tableRef } from './table.js'
export type { DecisionTable, HitPolicy, Rule } from './table.js'

export { readFacts, readValues, readValue, resultTypes, valueToJson, valuesToJson, writeValues, valueToPlain } from './facts.js'
export type { JsonInput } from './facts.js'

export { decisionRecordToJson, writeDecisionRecord } from './record.js'
export type { DecisionRecord, PathStep, ReasonCode } from './record.js'

export { parseStoneTests, checkStoneTestCase, StoneTestFailure, InvalidStoneTestError } from './stone-test.js'
export type { StoneTestCase, StoneTestOutcome } from './stone-test.js'

export { ExpressionEnvironment, CompiledExpression, Bindings, DEFAULT_MAX_ITERATIONS } from './cel/environment.js'
export type { EnvironmentOptions } from './cel/environment.js'

export { Decimal, ROUNDING_MODES } from './decimal.js'
export type { RoundingMode } from './decimal.js'
export { Money, isCurrencyCode, minorUnits, currencyTable, ISO4217_AMENDMENT } from './money.js'
export { LocalDate } from './date.js'
export { UInt, valuesEqual } from './values.js'
export type { Value, StructValue } from './values.js'
export { listOf, structOf, typeFromSpec, typeToSpec, typeToString, typesEqual } from './types.js'
export type { ValueType, PrimitiveType, ListType, StructType, TypeSpec } from './types.js'

export { parseJson, writeJson, JsonSyntaxError } from './json.js'
export type { JsonNode, Writable } from './json.js'

export {
  RuleStoneError, InvalidDecisionError, InvalidFactsError, DecisionNotFoundError, DecisionEvaluationError, AuditError,
  ExpressionError, InvalidValueError,
} from './errors.js'
