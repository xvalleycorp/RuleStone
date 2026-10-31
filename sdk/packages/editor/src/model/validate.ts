// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Validation of a table as the engine validates it, with every problem placed on the cell it
// belongs to and, for expressions, at the exact position in the text.

import {
  InvalidDecisionError, analyzeExpression, createEngine, parseDecisionTable, ruleVersionOf, writeDecisionTable,
  type DecisionTable, type ExpressionIssue, type ValueType,
} from '@rulestone/engine'
import { tableVariables, valueTypeOf } from './schema.js'
import type { TableJson } from './table.js'

/** Where a problem is. */
export type ProblemTarget =
  | { readonly kind: 'table'; readonly field?: string }
  | { readonly kind: 'schema'; readonly side: 'inputs' | 'outputs'; readonly name?: string }
  /** A local value (let), or the let object itself without a name. */
  | { readonly kind: 'let'; readonly name?: string }
  | {
      readonly kind: 'rule'
      readonly rule: number
      readonly field: 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'rule'
      /** For field 'then': the output. */
      readonly output?: string
    }

export interface Problem {
  readonly severity: 'error' | 'warning'
  readonly message: string
  readonly target: ProblemTarget
  /** For expressions: where in the text, as the engine reports it. */
  readonly issue?: Pick<ExpressionIssue, 'pos' | 'index' | 'line' | 'column'>
  /** Who found it: the strict file reader, the engine, the editor, or the host's validator hook. */
  readonly source: 'reader' | 'engine' | 'expression' | 'editor' | 'host'
}

export interface ValidationResult {
  readonly valid: boolean
  readonly problems: readonly Problem[]
  /** The canonical form the rule version is computed over, when the file can be read. */
  readonly canonical?: string
  /** <version>@sha256:<hash>, when the file can be read. */
  readonly ruleVersion?: string
  /** The table as the engine reads it, when it can be read. */
  readonly parsed?: DecisionTable
}

/** A cell of the rules grid that holds an expression: the rule's condition, or one output. */
export interface ExpressionCell {
  readonly rule: number
  readonly field: 'when' | 'then'
  readonly output?: string
  readonly source: string
  readonly expected: ValueType | undefined
}

export function expressionCells(table: TableJson): ExpressionCell[] {
  const cells: ExpressionCell[] = []
  table.rules.forEach((rule, index) => {
    cells.push({ rule: index, field: 'when', source: rule.when, expected: 'bool' })
    for (const [output, source] of Object.entries(rule.then ?? {})) {
      const spec = table.outputs[output]
      const type = spec === undefined ? undefined : valueTypeOf(spec)
      const expected = type && (typeof type === 'string' || type.kind !== 'struct') ? type : undefined
      cells.push({ rule: index, field: 'then', output, source, expected })
    }
  })
  return cells
}

/**
 * The problems of every expression cell, each at its position. Synchronous and cheap enough to
 * run on every keystroke.
 */
export function expressionProblems(table: TableJson): Problem[] {
  const locals = localsOf(table)
  const variables = tableVariables(table.inputs, locals)
  const problems: Problem[] = []
  for (const [name, source] of Object.entries(locals)) {
    // The other local values are in scope with their inferred types; this one is not.
    const scope = new Map(variables)
    scope.delete(name)
    for (const issue of analyzeExpression(source, { variables: scope }).issues) {
      problems.push({
        severity: 'error',
        message: issue.message,
        target: { kind: 'let', name },
        issue: { pos: issue.pos, index: issue.index, line: issue.line, column: issue.column },
        source: 'expression',
      })
    }
  }
  for (const cell of expressionCells(table)) {
    const analysis = analyzeExpression(cell.source, cell.expected === undefined ? { variables } : { variables, expected: cell.expected })
    const target: ProblemTarget = cell.output === undefined ? { kind: 'rule', rule: cell.rule, field: cell.field } : { kind: 'rule', rule: cell.rule, field: cell.field, output: cell.output }
    for (const issue of analysis.issues) {
      problems.push({
        severity: 'error',
        message: issue.message,
        target,
        issue: { pos: issue.pos, index: issue.index, line: issue.line, column: issue.column },
        source: 'expression',
      })
    }
  }
  return problems
}

/** The table's local values with string expressions; anything else is left to the strict reader. */
export function localsOf(table: TableJson): Record<string, string> {
  const value = table.let
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(Object.entries(value).filter((e): e is [string, string] => typeof e[1] === 'string'))
}

/** Rules that can never match: under FIRST, the rules after one whose condition is `true`. */
function unreachableRules(table: TableJson): Problem[] {
  if (table.hitPolicy !== 'FIRST') return []
  const index = table.rules.findIndex((r) => r.when.trim() === 'true')
  if (index < 0) return []
  return table.rules.slice(index + 1).map((_, k) => ({
    severity: 'warning' as const,
    message: `never evaluated: under FIRST, rule ${table.rules[index]!.id} above always matches`,
    target: { kind: 'rule' as const, rule: index + 1 + k, field: 'rule' as const },
    source: 'editor' as const,
  }))
}

function ruleIndex(table: TableJson, id: string, last: boolean): number {
  const indexes = table.rules.flatMap((r, i) => (r.id === id ? [i] : []))
  return (last ? indexes.at(-1) : indexes[0]) ?? -1
}

/** Places a problem of the reader ($.rules[2].then.limit: …) or of the engine (rules[R1].when: …). */
function place(table: TableJson, location: string, readerPath: boolean): ProblemTarget {
  const rule = readerPath ? /^\$\.rules\[(\d+)\](?:\.(\w+))?(?:\.(\w+))?/.exec(location) : /^rules\[(.*?)\](?:\.(\w+))?(?:\.(\w+))?$/.exec(location)
  if (rule) {
    const index = readerPath ? Number(rule[1]) : ruleIndex(table, rule[1]!, true)
    const field = (rule[2] ?? 'rule') as 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'rule'
    const known = ['id', 'when', 'then', 'reasonCode', 'description'].includes(field) ? field : 'rule'
    return rule[3] !== undefined && known === 'then' ? { kind: 'rule', rule: index, field: 'then', output: rule[3] } : { kind: 'rule', rule: index, field: known }
  }
  const local = (readerPath ? /^\$\.let(?:\.(\w+))?/ : /^let(?:\.(\w+))?/).exec(location)
  if (local) return local[1] !== undefined ? { kind: 'let', name: local[1] } : { kind: 'let' }
  const schema = (readerPath ? /^\$\.(inputs|outputs)(?:\.(\w+))?/ : /^(inputs|outputs)(?:\.(\w+))?/).exec(location)
  if (schema) return schema[2] !== undefined ? { kind: 'schema', side: schema[1] as 'inputs', name: schema[2] } : { kind: 'schema', side: schema[1] as 'inputs' }
  const field = (readerPath ? /^\$\.(\w+)/ : /^(\w+)/).exec(location)
  return field ? { kind: 'table', field: field[1]! } : { kind: 'table' }
}

function sameCell(a: ProblemTarget, b: ProblemTarget): boolean {
  if (a.kind === 'let' && b.kind === 'let') return a.name === b.name
  return a.kind === 'rule' && b.kind === 'rule' && a.rule === b.rule && a.field === b.field && a.output === b.output
}

/**
 * Validates a table exactly as the engine does when it loads it: the strict reader, then
 * compilation. Expression problems come with their positions. Never throws for a bad table.
 */
export async function validateTable(table: TableJson): Promise<ValidationResult> {
  const problems: Problem[] = []
  const expressions = expressionProblems(table)
  let parsed: DecisionTable | undefined
  try {
    parsed = parseDecisionTable(JSON.stringify(table))
  } catch (e) {
    if (!(e instanceof InvalidDecisionError)) throw e
    for (const text of e.problems) {
      const colon = text.indexOf(': ')
      const location = colon < 0 ? '' : text.slice(0, colon)
      problems.push({ severity: 'error', message: colon < 0 ? text : text.slice(colon + 2), target: place(table, location, true), source: 'reader' })
    }
  }
  let canonical: string | undefined
  let ruleVersion: string | undefined
  if (parsed) {
    canonical = writeDecisionTable(parsed)
    ruleVersion = await ruleVersionOf(parsed)
    try {
      await createEngine({ tables: [parsed] })
    } catch (e) {
      if (!(e instanceof InvalidDecisionError)) throw e
      const prefix = `${parsed.id}@${parsed.version} `
      for (const text of e.problems) {
        const body = text.startsWith(prefix) ? text.slice(prefix.length) : text
        const colon = body.indexOf(': ')
        const location = colon < 0 ? '' : body.slice(0, colon)
        const target = place(table, location, false)
        // Expression problems are reported per cell, with positions, by expressionProblems.
        if (body.slice(colon + 2).startsWith('ERROR: <input>') || body.slice(colon + 2).startsWith('expression must produce')) {
          if (expressions.some((p) => sameCell(p.target, target))) continue
        }
        problems.push({ severity: 'error', message: colon < 0 ? body : body.slice(colon + 2), target, source: 'engine' })
      }
    }
  }
  // Without a readable file, the expression problems are still shown.
  problems.push(...expressions)
  problems.push(...unreachableRules(table))
  const valid = !problems.some((p) => p.severity === 'error')
  return {
    valid,
    problems,
    ...(canonical !== undefined ? { canonical } : {}),
    ...(ruleVersion !== undefined ? { ruleVersion } : {}),
    ...(parsed ? { parsed } : {}),
  }
}

/** Orders problems as the grid shows them: table, schema, then rules in order. */
export function sortProblems(problems: readonly Problem[]): Problem[] {
  const rank = (p: Problem) =>
    p.target.kind === 'table' ? -3 : p.target.kind === 'schema' ? -2 : p.target.kind === 'let' ? -1 : p.target.rule
  return [...problems].sort((a, b) => rank(a) - rank(b) || (a.issue?.pos ?? 0) - (b.issue?.pos ?? 0))
}
