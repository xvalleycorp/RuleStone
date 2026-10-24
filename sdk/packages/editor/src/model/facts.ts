// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The facts form of the Try it panel: a tree of text values shaped like the inputs, written as
// JSON text in the FactsJson forms (money "1500000 VND", dates "2026-11-01", decimals as
// strings). The JSON is assembled as text, so numbers never pass through floating point.

import { parseJson, type JsonNode } from '@rulestone/engine'
import type { TypeSpec } from './table.js'

export type FormValue =
  | { readonly kind: 'scalar'; readonly text: string }
  | { readonly kind: 'money'; readonly amount: string; readonly currency: string }
  | { readonly kind: 'list'; readonly items: readonly FormValue[] }
  | { readonly kind: 'struct'; readonly fields: Readonly<Record<string, FormValue>> }

export type FormValues = Readonly<Record<string, FormValue>>

export function emptyValue(spec: TypeSpec, currency = 'VND'): FormValue {
  if (Array.isArray(spec)) return { kind: 'list', items: [] }
  if (typeof spec === 'object') {
    return { kind: 'struct', fields: Object.fromEntries(Object.entries(spec).map(([n, t]) => [n, emptyValue(t, currency)])) }
  }
  if (spec === 'money') return { kind: 'money', amount: '', currency }
  return { kind: 'scalar', text: '' }
}

export function emptyForm(inputs: Record<string, TypeSpec>): FormValues {
  return Object.fromEntries(Object.entries(inputs).map(([n, t]) => [n, emptyValue(t)]))
}

/** JSON text for one value, or undefined when it is left empty (an unset field or a missing input). */
export function valueJson(value: FormValue | undefined, spec: TypeSpec): string | undefined {
  if (!value) return undefined
  if (Array.isArray(spec)) {
    if (value.kind !== 'list') return undefined
    const items = value.items.map((item) => valueJson(item, spec[0]!) ?? 'null')
    return `[${items.join(', ')}]`
  }
  if (typeof spec === 'object') {
    if (value.kind !== 'struct') return undefined
    const parts: string[] = []
    for (const [name, type] of Object.entries(spec)) {
      const v = valueJson(value.fields[name], type)
      if (v !== undefined) parts.push(`${JSON.stringify(name)}: ${v}`)
    }
    return `{${parts.join(', ')}}`
  }
  if (value.kind === 'money') {
    const amount = value.amount.trim()
    if (amount === '') return undefined
    return JSON.stringify(`${amount} ${value.currency.trim()}`)
  }
  if (value.kind !== 'scalar') return undefined
  const text = value.text.trim()
  if (text === '') return undefined
  switch (spec) {
    case 'bool':
      return text === 'true' || text === 'false' ? text : JSON.stringify(text)
    case 'int':
      // Verbatim when it is an integer, so large values keep every digit; otherwise the reader reports it.
      return /^-?[0-9]+$/.test(text) ? text : JSON.stringify(text)
    default:
      return JSON.stringify(text)
  }
}

/** The facts as pretty JSON text. */
export function factsJson(form: FormValues, inputs: Record<string, TypeSpec>): string {
  const parts: string[] = []
  for (const [name, type] of Object.entries(inputs)) {
    const v = valueJson(form[name], type)
    if (v !== undefined) parts.push(`  ${JSON.stringify(name)}: ${v}`)
  }
  return parts.length === 0 ? '{}' : `{\n${parts.join(',\n')}\n}`
}

/**
 * Fills a form from facts JSON text, for switching from the JSON view back to the form.
 * Numbers keep their text. @throws the engine's JsonSyntaxError for invalid JSON
 */
export function formFromJson(text: string, inputs: Record<string, TypeSpec>): FormValues {
  const root = parseJson(text)
  const read = (node: JsonNode | undefined, spec: TypeSpec): FormValue => {
    if (Array.isArray(spec)) return { kind: 'list', items: node?.type === 'array' ? node.items.map((i) => read(i, spec[0]!)) : [] }
    if (typeof spec === 'object') {
      const entries = node?.type === 'object' ? node.entries : new Map<string, JsonNode>()
      return { kind: 'struct', fields: Object.fromEntries(Object.entries(spec).map(([n, t]) => [n, read(entries.get(n), t)])) }
    }
    const scalar = node === undefined || node.type === 'null' ? '' : node.type === 'number' ? node.text : node.type === 'string' || node.type === 'boolean' ? String(node.value) : ''
    if (spec === 'money') {
      const match = /^(\S+)\s+(\S+)$/.exec(scalar)
      return match ? { kind: 'money', amount: match[1]!, currency: match[2]! } : { kind: 'money', amount: scalar, currency: 'VND' }
    }
    return { kind: 'scalar', text: scalar }
  }
  const entries = root?.type === 'object' ? root.entries : new Map<string, JsonNode>()
  return Object.fromEntries(Object.entries(inputs).map(([n, t]) => [n, read(entries.get(n), t)]))
}

/** Replaces the value at a path of names and list indexes. */
export function setFormValue(form: FormValues, path: readonly (string | number)[], value: FormValue): FormValues {
  const go = (current: FormValue, rest: readonly (string | number)[]): FormValue => {
    if (rest.length === 0) return value
    const [step, ...tail] = rest
    if (current.kind === 'struct' && typeof step === 'string') return { ...current, fields: { ...current.fields, [step]: go(current.fields[step]!, tail) } }
    if (current.kind === 'list' && typeof step === 'number') {
      const items = [...current.items]
      items[step] = go(items[step]!, tail)
      return { ...current, items }
    }
    return current
  }
  const [first, ...rest] = path
  if (typeof first !== 'string' || !(first in form)) return form
  return { ...form, [first]: go(form[first]!, rest) }
}

/** The form value at a path, or undefined. */
export function formValueAt(form: FormValues, path: readonly (string | number)[]): FormValue | undefined {
  let current: FormValue | undefined = form[path[0] as string]
  for (const step of path.slice(1)) {
    if (current?.kind === 'struct' && typeof step === 'string') current = current.fields[step]
    else if (current?.kind === 'list' && typeof step === 'number') current = current.items[step]
    else return undefined
  }
  return current
}

/** Brings a form in line with changed inputs, keeping the values of fields that remain. */
export function reconcileForm(form: FormValues, inputs: Record<string, TypeSpec>): FormValues {
  const fit = (value: FormValue | undefined, spec: TypeSpec): FormValue => {
    const empty = emptyValue(spec)
    if (!value || value.kind !== empty.kind) return empty
    if (value.kind === 'struct' && typeof spec === 'object' && !Array.isArray(spec)) {
      return { kind: 'struct', fields: Object.fromEntries(Object.entries(spec).map(([n, t]) => [n, fit(value.fields[n], t)])) }
    }
    if (value.kind === 'list' && Array.isArray(spec)) return { kind: 'list', items: value.items.map((i) => fit(i, spec[0]!)) }
    return value
  }
  return Object.fromEntries(Object.entries(inputs).map(([n, t]) => [n, fit(form[n], t)]))
}
