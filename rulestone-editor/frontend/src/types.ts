// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/** A type as written in a .stone.json file: "decimal", ["string"], or { "field": type }. */
export type TypeSpec = string | TypeSpec[] | { [field: string]: TypeSpec }

export interface RuleJson {
  id: string
  description?: string
  when: string
  then?: Record<string, string>
  reasonCode?: string
}

export interface TableJson {
  id: string
  version: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  hitPolicy: string
  inputs: Record<string, TypeSpec>
  outputs: Record<string, TypeSpec>
  rules: RuleJson[]
}

export interface ProblemLocation {
  path?: string
  rule?: string
  ruleIndex?: number
  field?: string
  output?: string
  input?: string
  file?: string
  line?: number
  column?: number
}

export interface Problem {
  message: string
  detail: string
  location: ProblemLocation
}

export interface FunctionInfo {
  name: string
  kind: 'function' | 'method' | 'macro' | 'operator'
  signature: string
  insert: string
  description: string
}

export interface Meta {
  editorVersion: string
  types: { name: string; description: string }[]
  hitPolicies: { name: string; description: string }[]
  functions: FunctionInfo[]
  implicitInputs: { name: string; type: string }[]
  roundingModes: string[]
  limits: { maxBodyBytes: number; maxFileBytes: number }
  fileSuffix: string
}

export interface TableSummary {
  file: string
  id?: string
  version?: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  hitPolicy?: string
  ruleCount?: number
  ruleVersion?: string
  problems: Problem[]
}

export interface LoadedTable {
  file: string
  etag: string
  content: string
  table: TableJson | null
  valid: boolean
  problems: Problem[]
  ruleVersion: string | null
}

export interface ValidationResult {
  valid: boolean
  problems: Problem[]
  ruleVersion: string | null
}

export interface SaveResult {
  saved: boolean
  file?: string
  etag?: string
  valid: boolean
  problems: Problem[]
  ruleVersion?: string | null
  previous?: { file: string; etag: string; effectiveTo: string }
  error?: { code: string; message: string }
}

export interface EvaluationResult {
  decisionId: string
  ruleVersion: string
  asOf?: string
  effectiveOnAsOf?: boolean
  matched: boolean
  output: Record<string, unknown>
  reasonCodes: string[]
  matchedRules: string[]
  trace: { ruleId: string; matched: boolean }[]
}
