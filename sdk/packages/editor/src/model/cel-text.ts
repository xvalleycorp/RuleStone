// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// CEL text support for the lightweight CEL input: a tolerant tokenizer for highlighting (it
// never fails, whatever the text) and autocomplete driven by the engine's type checker.

import { analyzeExpression, formatCelType, functionCatalog, type CelType, type ValueType } from '@rulestone/engine'

export { tokenize } from './cel-tokens.js'
export type { Token, TokenKind } from './cel-tokens.js'

export interface Completion {
  readonly label: string
  /** The text that replaces the word being typed. */
  readonly insert: string
  /** Where the caret goes, relative to the start of `insert`; default its end. */
  readonly caret?: number
  readonly detail: string
  readonly kind: 'variable' | 'field' | 'function' | 'method' | 'macro' | 'keyword'
}

export interface CompletionResult {
  /** UTF-16 range of the text that a completion replaces. */
  readonly from: number
  readonly to: number
  readonly items: readonly Completion[]
}

const MACROS = ['exists', 'all', 'exists_one', 'filter', 'map']

function describe(type: ValueType): string {
  if (typeof type === 'string') return type
  if (type.kind === 'list') return `list<${describe(type.element)}>`
  return 'struct'
}

/** The value type of a dotted path among variables, or undefined. */
function pathType(variables: ReadonlyMap<string, ValueType>, path: string): ValueType | undefined {
  const [first, ...rest] = path.split('.')
  let type = variables.get(first ?? '')
  for (const name of rest) {
    if (type === undefined || typeof type === 'string' || type.kind !== 'struct') return undefined
    type = type.fields.get(name)
  }
  return type
}

/**
 * The variables in scope at the caret: the given ones plus the iteration variables of the list
 * macros that are still open there, typed by the list they range over.
 */
export function scopeAt(text: string, caret: number, variables: ReadonlyMap<string, ValueType>): Map<string, ValueType> {
  const scope = new Map(variables)
  const before = text.slice(0, caret)
  const macro = /([A-Za-z_][A-Za-z0-9_.]*)\.(?:exists|all|exists_one|filter|map)\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,/g
  for (let m = macro.exec(before); m; m = macro.exec(before)) {
    let depth = 0
    for (const ch of before.slice(m.index + m[0].indexOf('('))) {
      if (ch === '(') depth++
      else if (ch === ')') depth--
    }
    if (depth <= 0) continue
    const list = pathType(scope, m[1]!)
    if (list && typeof list !== 'string' && list.kind === 'list') scope.set(m[2]!, list.element)
  }
  return scope
}

/** Start of the member expression that ends at index: a.b[0].c(x) and so on. */
function receiverStart(text: string, end: number): number {
  let i = end
  let depth = 0
  while (i > 0) {
    const ch = text[i - 1]!
    if (ch === ')' || ch === ']') depth++
    else if (ch === '(' || ch === '[') {
      if (depth === 0) break
      depth--
    } else if (depth === 0 && !/[A-Za-z0-9_.]/.test(ch)) break
    i--
  }
  return i
}

function matchesReceiver(param: string, type: CelType): boolean {
  if (param === 'T' || param === 'dyn') return true
  const shown = formatCelType(type)
  if (param === shown) return true
  if (type.k === 'list' && param.startsWith('list(')) return true
  return false
}

/** Completions at the caret: fields and methods after a dot, otherwise variables, functions and keywords. */
export function completionsAt(text: string, caret: number, variables: ReadonlyMap<string, ValueType>): CompletionResult {
  let from = caret
  while (from > 0 && /[A-Za-z0-9_]/.test(text[from - 1]!)) from--
  const prefix = text.slice(from, caret).toLowerCase()
  const scope = scopeAt(text, caret, variables)
  const items: Completion[] = []
  const catalog = functionCatalog()
  if (from > 0 && text[from - 1] === '.') {
    const start = receiverStart(text, from - 1)
    const receiver = text.slice(start, from - 1)
    const analysis = receiver === '' ? undefined : analyzeExpression(receiver, { variables: scope })
    const type = analysis?.type
    if (type && type.k === 'struct') {
      for (const [name, field] of type.fields) items.push({ label: name, insert: name, detail: formatCelType(field).replace(/^rulestone\.input\..*/, 'struct'), kind: 'field' })
    }
    if (type && type.k !== 'error') {
      const seen = new Set<string>()
      for (const f of catalog) {
        if (!f.member || !matchesReceiver(f.params[0]!, type)) continue
        const signature = `${f.name}(${f.params.slice(1).join(', ')}) → ${f.result}`
        if (seen.has(signature)) continue
        seen.add(signature)
        items.push({ label: f.name, insert: `${f.name}()`, caret: f.params.length > 1 ? f.name.length + 1 : f.name.length + 2, detail: signature, kind: 'method' })
      }
      if (type.k === 'list' || type.k === 'dyn') {
        for (const m of MACROS) items.push({ label: m, insert: `${m}(x, )`, caret: m.length + 4, detail: `${m}(x, condition)`, kind: 'macro' })
      }
    }
  } else {
    for (const [name, type] of scope) items.push({ label: name, insert: name, detail: describe(type), kind: 'variable' })
    const seen = new Set<string>()
    for (const f of catalog) {
      if (f.member || seen.has(f.name)) continue
      seen.add(f.name)
      const overloads = catalog.filter((o) => !o.member && o.name === f.name).map((o) => `(${o.params.join(', ')})`)
      items.push({ label: f.name, insert: `${f.name}()`, caret: f.name.length + 1, detail: `${f.name}${overloads[0]}${overloads.length > 1 ? ` +${overloads.length - 1}` : ''} → ${f.result}`, kind: 'function' })
    }
    items.push({ label: 'has', insert: 'has()', caret: 4, detail: 'has(x.field) → bool', kind: 'macro' })
    for (const k of ['true', 'false', 'in']) items.push({ label: k, insert: k, detail: 'keyword', kind: 'keyword' })
  }
  const filtered = items.filter((c) => c.label.toLowerCase().startsWith(prefix))
  return { from, to: caret, items: filtered }
}
