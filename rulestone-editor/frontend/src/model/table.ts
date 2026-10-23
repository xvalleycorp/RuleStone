// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import type { Problem, RuleJson, TableJson, TableSummary } from '../types'

/** The next version label: 3 → 4, 2026.11 → 2026.12, v2 → v3, beta → beta.1. */
export function nextVersion(version: string): string {
  const m = /^(.*?)(\d+)$/.exec(version)
  if (!m) return `${version}.1`
  const digits = m[2]
  const next = (BigInt(digits) + 1n).toString().padStart(digits.length, '0')
  return m[1] + next
}

/** A rule id not used yet: R1, R2, ... after the highest Rn. */
export function nextRuleId(rules: RuleJson[]): string {
  let max = 0
  for (const r of rules) {
    const m = /^R(\d+)$/.exec(r.id)
    if (m) max = Math.max(max, Number(m[1]))
  }
  let n = max + 1
  while (rules.some((r) => r.id === `R${n}`)) n++
  return `R${n}`
}

/** A new table: valid as it stands, with one catch-all rule to start from. */
export function newTable(id: string, version: string, hitPolicy: string, effectiveFrom?: string, effectiveTo?: string): TableJson {
  const table: TableJson = { id, version, hitPolicy, inputs: {}, outputs: {}, rules: [{ id: 'R1', when: 'true' }] }
  if (effectiveFrom) table.effectiveFrom = effectiveFrom
  if (effectiveTo) table.effectiveTo = effectiveTo
  return table
}

/** The table as it is saved: empty optional fields and empty `then` objects left out, keys in file order. */
export function cleanTable(table: TableJson): TableJson {
  const clean: TableJson = { id: table.id, version: table.version } as TableJson
  if (table.description) clean.description = table.description
  if (table.effectiveFrom) clean.effectiveFrom = table.effectiveFrom
  if (table.effectiveTo) clean.effectiveTo = table.effectiveTo
  clean.hitPolicy = table.hitPolicy
  clean.inputs = table.inputs
  clean.outputs = table.outputs
  clean.rules = table.rules.map((r) => {
    const rule: RuleJson = { id: r.id } as RuleJson
    if (r.description) rule.description = r.description
    rule.when = r.when
    const then: Record<string, string> = {}
    for (const [k, v] of Object.entries(r.then ?? {})) if (v !== undefined && v !== '') then[k] = v
    if (Object.keys(then).length > 0) rule.then = then
    if (r.reasonCode) rule.reasonCode = r.reasonCode
    return rule
  })
  return clean
}

export function moveItem<T>(items: T[], from: number, to: number): void {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return
  const [item] = items.splice(from, 1)
  items.splice(to, 0, item)
}

/** Whether a version is in force on `date` (yyyy-MM-dd): effectiveFrom <= date < effectiveTo. */
export function inForce(t: Pick<TableSummary, 'effectiveFrom' | 'effectiveTo'>, date: string): boolean {
  return (!t.effectiveFrom || t.effectiveFrom <= date) && (!t.effectiveTo || date < t.effectiveTo)
}

/** Today in the browser's time zone, yyyy-MM-dd. */
export function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const t = new Date(Date.UTC(y, m - 1, d + days))
  return t.toISOString().slice(0, 10)
}

/** Decisions grouped by id, versions ordered by effectiveFrom then version. */
export function groupById(tables: TableSummary[]): { id: string; versions: TableSummary[] }[] {
  const groups = new Map<string, TableSummary[]>()
  for (const t of tables) {
    const id = t.id ?? t.file
    if (!groups.has(id)) groups.set(id, [])
    groups.get(id)!.push(t)
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([id, versions]) => ({
      id,
      versions: versions.sort((a, b) => (a.effectiveFrom ?? '').localeCompare(b.effectiveFrom ?? '')
        || (a.version ?? '').localeCompare(b.version ?? '', undefined, { numeric: true })),
    }))
}

/** Key of the cell a problem belongs to, as used by the grid and forms. */
export function problemKey(p: Problem): string {
  const l = p.location
  if (l.ruleIndex !== undefined && l.ruleIndex !== null) {
    const field = l.field ?? 'row'
    return field === 'then' && l.output ? `rule:${l.ruleIndex}:then:${l.output}` : `rule:${l.ruleIndex}:${field}`
  }
  if (l.field === 'inputs' && l.input) return `input:${l.input}`
  if (l.field === 'outputs' && l.output) return `output:${l.output}`
  return `table:${l.field ?? ''}`
}

export function groupProblems(problems: Problem[]): Map<string, Problem[]> {
  const map = new Map<string, Problem[]>()
  for (const p of problems) {
    const key = problemKey(p)
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(p)
  }
  return map
}

/** Suggested file name for a version: id.v<version>.stone.json. */
export function fileNameFor(id: string, version: string): string {
  return `${id}.v${version}.stone.json`
}
