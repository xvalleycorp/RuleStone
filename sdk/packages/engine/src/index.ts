// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * The RuleStone decision engine for JavaScript and TypeScript, in Node.js and browsers: decision
 * tables and flows, parameter sets, model nodes, decision records, explanations and replay, with
 * the same results as the Java engine on everything the conformance suite covers.
 *
 * Expressions alone are also available from `@dxa/rulestone-engine/cel`, and test files and an
 * issuer of approval statements for tests from `@dxa/rulestone-engine/testing`.
 *
 * @packageDocumentation
 */

export { createEngine, outputJson, RuleStoneEngine, BUSINESS_DATE, ENGINE_NAME } from './engine.js'
export type {
  AuditSink, CompiledDecision, DecisionRef, DecisionResult, EngineOptions, ModelsInput, TraceStep,
} from './engine.js'

export { parseDecisionFlow, writeDecisionFlow, flowVersionOf, FLOW_FILE_SUFFIX } from './flow.js'
export type {
  DecisionFlow, FlowNode, Branch, Bucket, TableFlowNode, SwitchFlowNode, DeriveFlowNode, ModelFlowNode, SplitFlowNode,
  EndFlowNode, ModelMode,
} from './flow.js'
export { readModels, isModelFailure } from './models.js'
export type { Attribution, ModelCall, ModelFailure, ModelInvoker, ModelResult, SuppliedModel } from './models.js'

export { parseDecisionTable, writeDecisionTable, ruleVersionOf, isEffectiveOn, tableRef } from './table.js'
export { versionProblems, versionInForce, selectVersion } from './versions.js'
export type { DecisionTable, HitPolicy, ParamsDeclaration, Rule } from './table.js'
export type { Versioned } from './versions.js'

export { parseParameterSet, writeParameterSet, parameterSetVersionOf, PARAMS } from './params.js'
export type { ParameterSet, Parameter } from './params.js'

export { readFacts, readValues, readValue, resultTypes, valueToJson, valuesToJson, writeValues, valueToPlain } from './facts.js'
export type { JsonInput } from './facts.js'

export { decisionRecordToJson, writeDecisionRecord } from './record.js'
export { readDecisionRecord, InvalidRecordError } from './record-json.js'
export { compareRecords } from './replay.js'
export type { DecisionComparison, Difference, DifferenceKind, ModelSource } from './replay.js'
export type {
  DecisionRecord, ParamSetVersion, PathStep, ReasonCode, ModelStep, ModelStatus, ModelStepMode, Routing,
} from './record.js'

export {
  parseReasonCodeCatalog, writeReasonCodeCatalog, reasonCodeCatalogVersionOf, explainRecord, explainDecision,
  explanationToJson, writeExplanation, explanationText, REASON_CODES_FILE,
} from './explanation.js'
export type {
  ReasonCodeCatalog, Explanation, ExplanationReason, ExplanationStep, ExplainableRecord, RankedAttribution, Direction,
} from './explanation.js'

export {
  ExpressionEnvironment, CompiledExpression, Bindings, DEFAULT_MAX_ITERATIONS, LocalValueError, isValidName,
} from './cel/environment.js'
export { localValueTypes } from './locals.js'
export type { EnvironmentOptions } from './cel/environment.js'

export {
  analyzeExpression, functionCatalog, codePointIndex, inputStructName, variableCelType,
} from './cel/analysis.js'
export type { AnalysisOptions, ExpressionAnalysis, ExpressionIssue, FunctionSignature } from './cel/analysis.js'
export type { Expr, Literal, Ident, Select, Call, ListExpr, Comprehension } from './cel/ast.js'
export type { CelType } from './cel/types.js'
export { formatType as formatCelType } from './cel/types.js'

export { Decimal, ROUNDING_MODES } from './decimal.js'
export type { RoundingMode } from './decimal.js'
export { Money, isCurrencyCode, minorUnits, currencyTable, ISO4217_AMENDMENT } from './money.js'
export { LocalDate } from './date.js'
export { UInt, valuesEqual } from './values.js'
export type { Value, StructValue } from './values.js'
export { listOf, structOf, typeFromSpec, typeToSpec, typeToString, typesEqual } from './types.js'
export type { ValueType, PrimitiveType, ListType, StructType, TypeSpec } from './types.js'

export {
  ApprovalVerifier, parseApprovalStatement, writeApprovalStatement, APPROVAL_STATEMENT_TYPE, APPROVAL_JWS_TYPE,
  APPROVAL_FILE_SUFFIX, APPROVAL_CATALOG_ID,
} from './approvals.js'
export type {
  ApprovalAlgorithm, ApprovalStatement, ApprovalSubject, ApprovalVerifierOptions, Approver, ArtifactKind, NamedApproval,
} from './approvals.js'

export { parseJson, writeJson, JsonSyntaxError } from './json.js'
export type { JsonNode, Writable } from './json.js'

export {
  RuleStoneError, InvalidDecisionError, InvalidFactsError, DecisionNotFoundError, DecisionEvaluationError, AuditError,
  DecisionTimeoutError,
  ExpressionError, InvalidValueError,
} from './errors.js'
