// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * RuleStone expressions without decision tables or flows: type-checked CEL with exact
 * decimal, money and date types, for example to check a formula entered in an admin screen.
 * The same as `rulestone-cel` in Java.
 *
 * @packageDocumentation
 */

export {
  ExpressionEnvironment, CompiledExpression, Bindings, DEFAULT_MAX_ITERATIONS, LocalValueError, isValidName,
} from './cel/environment.js'
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

export { RuleStoneError, ExpressionError, InvalidValueError } from './errors.js'
