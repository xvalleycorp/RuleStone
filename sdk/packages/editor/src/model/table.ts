// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision tables as the editor holds them: the parsed JSON of a .stone.json file, with
// operations that return a new table and never change their argument.

/** A type as written in .stone.json: "decimal", ["string"] or { "age": "int" }. */
export type TypeSpec = string | TypeSpec[] | { [field: string]: TypeSpec }

/** One rule, as in .stone.json. Unknown keys are kept, so the strict reader can report them. */
export interface RuleJson {
  id: string
  description?: string
  when: string
  then?: Record<string, string>
  reasonCode?: string
  [key: string]: unknown
}

/** The parameters a table reads from a parameter set, as the "params" object of .stone.json. */
export interface ParamsJson {
  /** The parameter set id. */
  set: string
  /** A version of the set to use whatever the business date. */
  version?: string
  /** The parameters read, name to type. */
  values: Record<string, TypeSpec>
  [key: string]: unknown
}

/** A decision table, as in .stone.json. */
export interface TableJson {
  id: string
  version: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  hitPolicy: string
  inputs: Record<string, TypeSpec>
  outputs: Record<string, TypeSpec>
  /** The parameters the rules read as params.<name>, from a parameter set. */
  params?: ParamsJson
  /** Local values: name to CEL expression, read by the rules by name. */
  let?: Record<string, string>
  rules: RuleJson[]
  [key: string]: unknown
}

export const HIT_POLICIES = ['FIRST', 'UNIQUE', 'COLLECT'] as const
export const PRIMITIVES = ['bool', 'int', 'string', 'decimal', 'money', 'date'] as const
export type Primitive = (typeof PRIMITIVES)[number]

const TABLE_ORDER = ['id', 'version', 'description', 'effectiveFrom', 'effectiveTo', 'hitPolicy', 'inputs', 'outputs', 'params', 'let', 'rules']
const RULE_ORDER = ['id', 'description', 'when', 'then', 'reasonCode']

/** A new, empty table: one catch-all rule, so that it is valid as soon as it has an id. */
export function emptyTable(id = 'new_decision'): TableJson {
  return { id, version: '1', hitPolicy: 'FIRST', inputs: {}, outputs: {}, rules: [{ id: 'R1', when: 'true' }] }
}

/** A deep copy of plain JSON data. */
export function cloneJson<T>(value: T): T {
  return value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T)
}

/**
 * Reads a table from parsed JSON, as a host passes it. Missing collections become empty, so
 * the editor can show a half-written file; the strict reader still reports what is missing.
 */
export function normalizeTable(value: unknown): TableJson {
  const source = (value !== null && typeof value === 'object' && !Array.isArray(value) ? value : {}) as Record<string, unknown>
  const table = cloneJson(source) as Record<string, unknown>
  const object = (v: unknown) => (v !== null && typeof v === 'object' && !Array.isArray(v) ? v : {})
  table['id'] = typeof table['id'] === 'string' ? table['id'] : ''
  table['version'] = typeof table['version'] === 'string' ? table['version'] : ''
  table['hitPolicy'] = typeof table['hitPolicy'] === 'string' ? table['hitPolicy'] : 'FIRST'
  table['inputs'] = object(table['inputs'])
  table['outputs'] = object(table['outputs'])
  table['rules'] = Array.isArray(table['rules'])
    ? (table['rules'] as unknown[]).map((r) => {
        const rule = object(r) as Record<string, unknown>
        return { ...rule, id: typeof rule['id'] === 'string' ? rule['id'] : '', when: typeof rule['when'] === 'string' ? rule['when'] : '' }
      })
    : []
  return orderTable(table as TableJson)
}

function ordered<T extends Record<string, unknown>>(value: T, order: readonly string[]): T {
  const out: Record<string, unknown> = {}
  for (const key of order) if (value[key] !== undefined) out[key] = value[key]
  for (const key of Object.keys(value)) if (!order.includes(key) && value[key] !== undefined) out[key] = value[key]
  return out as T
}

/** The table with its keys in the order of the file format, for saving and display. */
export function orderTable(table: TableJson): TableJson {
  const out = ordered(table, TABLE_ORDER)
  if (out.let !== undefined && out.let !== null && typeof out.let === 'object' && Object.keys(out.let).length === 0) delete out.let
  out.rules = table.rules.map((rule) => {
    const r = ordered(rule, RULE_ORDER)
    if (r.then !== undefined && Object.keys(r.then).length === 0) delete r.then
    return r
  })
  return out
}

/** The table as the pretty-printed JSON text of a .stone.json file. */
export function tableText(table: TableJson): string {
  return JSON.stringify(orderTable(table), null, 2) + '\n'
}

/** A rule id not used in the table: R1, R2, … */
export function newRuleId(table: TableJson, prefix = 'R'): string {
  const used = new Set(table.rules.map((r) => r.id))
  for (let n = table.rules.length + 1; ; n++) if (!used.has(`${prefix}${n}`)) return `${prefix}${n}`
}

function withRules(table: TableJson, rules: RuleJson[]): TableJson {
  return { ...table, rules }
}

/** Adds a rule at index (default: the end). Under FIRST a new rule goes before a final catch-all. */
export function addRule(table: TableJson, index?: number, rule?: Partial<RuleJson>): { table: TableJson; index: number } {
  let at = index ?? table.rules.length
  if (index === undefined && table.hitPolicy === 'FIRST' && table.rules.at(-1)?.when.trim() === 'true') at = table.rules.length - 1
  const added: RuleJson = { id: newRuleId(table), when: 'true', ...rule } as RuleJson
  const rules = [...table.rules]
  rules.splice(at, 0, added)
  return { table: withRules(table, rules), index: at }
}

/** Copies the rule at index to just below it, with a new id. */
export function duplicateRule(table: TableJson, index: number): { table: TableJson; index: number } {
  const source = table.rules[index]
  if (!source) return { table, index }
  const copy = { ...cloneJson(source), id: copyId(table, source.id) }
  const rules = [...table.rules]
  rules.splice(index + 1, 0, copy)
  return { table: withRules(table, rules), index: index + 1 }
}

function copyId(table: TableJson, id: string): string {
  const used = new Set(table.rules.map((r) => r.id))
  for (let n = 2; ; n++) if (!used.has(`${id}_${n}`)) return `${id}_${n}`
}

export function deleteRule(table: TableJson, index: number): TableJson {
  return withRules(table, table.rules.filter((_, i) => i !== index))
}

/** Moves an item of a list to a new index; out-of-range targets are clamped. */
export function moveItem<T>(items: readonly T[], from: number, to: number): T[] {
  const out = [...items]
  if (from < 0 || from >= out.length) return out
  const target = Math.max(0, Math.min(out.length - 1, to))
  const [item] = out.splice(from, 1)
  out.splice(target, 0, item as T)
  return out
}

/** Moves the rule at `from` to `to`, as drag and drop or Alt+Arrow keys do. */
export function moveRule(table: TableJson, from: number, to: number): TableJson {
  return withRules(table, moveItem(table.rules, from, to))
}

/** Changes fields of one rule. An undefined or empty optional field is removed. */
export function updateRule(table: TableJson, index: number, patch: Partial<RuleJson>): TableJson {
  const rule = table.rules[index]
  if (!rule) return table
  const next: RuleJson = { ...rule }
  for (const [key, value] of Object.entries(patch)) {
    if ((key === 'description' || key === 'reasonCode') && (value === undefined || value === '')) delete next[key]
    else if (value === undefined) delete next[key]
    else next[key] = value
  }
  const rules = [...table.rules]
  rules[index] = next
  return withRules(table, rules)
}

/** Sets or, with undefined, clears what a rule assigns to an output. */
export function setOutput(table: TableJson, index: number, output: string, expression: string | undefined): TableJson {
  const rule = table.rules[index]
  if (!rule) return table
  const then = { ...(rule.then ?? {}) }
  if (expression === undefined) delete then[output]
  else then[output] = expression
  // Keep the outputs in declaration order, then any undeclared ones.
  const orderedThen: Record<string, string> = {}
  for (const name of Object.keys(table.outputs)) if (then[name] !== undefined) orderedThen[name] = then[name]!
  for (const [name, value] of Object.entries(then)) if (orderedThen[name] === undefined) orderedThen[name] = value
  const next: RuleJson = { ...rule, then: orderedThen }
  if (Object.keys(orderedThen).length === 0) delete next.then
  const rules = [...table.rules]
  rules[index] = next
  return withRules(table, rules)
}

/** A local value name not used in the table: value1, value2, … */
export function newLocalName(table: TableJson): string {
  const used = new Set([...Object.keys(table.let ?? {}), ...Object.keys(table.inputs)])
  for (let n = 1; ; n++) if (!used.has(`value${n}`)) return `value${n}`
}

/**
 * Adds, renames or changes a local value (let). With `name` undefined, adds one at the end.
 * Renaming keeps the entry's position; it does not change the expressions that read it.
 */
export function setLocal(table: TableJson, name: string | undefined, newName: string, expression: string): TableJson {
  const entries = Object.entries(table.let ?? {})
  const index = name === undefined ? -1 : entries.findIndex(([n]) => n === name)
  if (index < 0) entries.push([newName, expression])
  else entries[index] = [newName, expression]
  return orderTable({ ...table, let: Object.fromEntries(entries) })
}

/** Removes a local value. */
export function deleteLocal(table: TableJson, name: string): TableJson {
  const locals = Object.fromEntries(Object.entries(table.let ?? {}).filter(([n]) => n !== name))
  return orderTable({ ...table, let: locals })
}

/** Sets the table's parameter declaration, or removes it when `params` is undefined. */
export function setParams(table: TableJson, params: ParamsJson | undefined): TableJson {
  const next: TableJson = { ...table }
  if (params === undefined) delete next.params
  else {
    // In the order of the file format: set, version, values; unknown keys are kept for the reader.
    const { set, version, values, ...rest } = params
    next.params = { set, ...(version !== undefined && version !== '' ? { version } : {}), values, ...rest }
  }
  return orderTable(next)
}

/** A parameter name not declared in the table: param1, param2, … */
export function newParamName(table: TableJson): string {
  const used = new Set(Object.keys(table.params?.values ?? {}))
  for (let n = 1; ; n++) if (!used.has(`param${n}`)) return `param${n}`
}

/** Sets a table-level field; an empty optional field is removed. */
export function updateTable(table: TableJson, patch: Partial<Pick<TableJson, 'id' | 'version' | 'description' | 'effectiveFrom' | 'effectiveTo' | 'hitPolicy'>>): TableJson {
  const next: TableJson = { ...table }
  for (const [key, value] of Object.entries(patch)) {
    if ((key === 'description' || key === 'effectiveFrom' || key === 'effectiveTo') && (value === undefined || value === '')) delete next[key]
    else next[key] = value
  }
  return orderTable(next)
}
