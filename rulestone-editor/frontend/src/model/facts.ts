// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The test form: a tree of text fields shaped like the input schema, written as JSON text in the
// shared facts format (money "1500000 VND", dates "yyyy-MM-dd", decimals as strings). The JSON is
// assembled as text, so numbers never pass through JavaScript floating point.

import type { TypeSpec } from '../types'

export type FormValue =
  | { kind: 'scalar'; text: string }
  | { kind: 'list'; items: FormValue[] }
  | { kind: 'struct'; fields: Record<string, FormValue> }

export function emptyValue(spec: TypeSpec): FormValue {
  if (Array.isArray(spec)) return { kind: 'list', items: [] }
  if (typeof spec === 'object') {
    const fields: Record<string, FormValue> = {}
    for (const [name, type] of Object.entries(spec)) fields[name] = emptyValue(type)
    return { kind: 'struct', fields }
  }
  return { kind: 'scalar', text: '' }
}

export function emptyForm(inputs: Record<string, TypeSpec>): Record<string, FormValue> {
  const form: Record<string, FormValue> = {}
  for (const [name, type] of Object.entries(inputs)) form[name] = emptyValue(type)
  return form
}

/** JSON text for one value, or undefined when it is left empty (an unset field or missing input). */
export function valueJson(value: FormValue | undefined, spec: TypeSpec): string | undefined {
  if (!value) return undefined
  if (Array.isArray(spec)) {
    if (value.kind !== 'list') return undefined
    const items = value.items.map((item) => valueJson(item, spec[0])).filter((v): v is string => v !== undefined)
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
  if (value.kind !== 'scalar') return undefined
  const text = value.text.trim()
  if (text === '') return undefined
  switch (spec) {
    case 'bool':
      return text === 'true' ? 'true' : text === 'false' ? 'false' : JSON.stringify(text)
    case 'int':
      // verbatim when it is an integer, so large values keep every digit; otherwise the server reports it
      return /^-?[0-9]+$/.test(text) ? text : JSON.stringify(text)
    default:
      // string, decimal (numeric string), money ("1500000 VND"), date ("2026-11-01") and types added later
      return JSON.stringify(spec === 'money' ? text.replace(/\s+/, ' ') : text)
  }
}

/** The facts object as pretty JSON text. */
export function factsJson(form: Record<string, FormValue>, inputs: Record<string, TypeSpec>): string {
  const parts: string[] = []
  for (const [name, type] of Object.entries(inputs)) {
    const v = valueJson(form[name], type)
    if (v !== undefined) parts.push(`  ${JSON.stringify(name)}: ${v}`)
  }
  return parts.length === 0 ? '{}' : `{\n${parts.join(',\n')}\n}`
}

/** Fills a form from facts JSON text, for switching from the JSON view back to the form. Numbers keep their text. */
export function formFromJson(text: string, inputs: Record<string, TypeSpec>): Record<string, FormValue> {
  const parsed = JSON.parse(text, (_key, value, context?: { source?: string }) =>
    typeof value === 'number' && context?.source !== undefined ? { __number: context.source } : value)
  const read = (json: unknown, spec: TypeSpec): FormValue => {
    if (Array.isArray(spec)) {
      return { kind: 'list', items: Array.isArray(json) ? json.map((item) => read(item, spec[0])) : [] }
    }
    if (typeof spec === 'object') {
      const fields: Record<string, FormValue> = {}
      const obj = json && typeof json === 'object' ? (json as Record<string, unknown>) : {}
      for (const [name, type] of Object.entries(spec)) fields[name] = read(obj[name], type)
      return { kind: 'struct', fields }
    }
    if (json && typeof json === 'object' && '__number' in json) return { kind: 'scalar', text: String((json as { __number: string }).__number) }
    if (json === null || json === undefined) return { kind: 'scalar', text: '' }
    return { kind: 'scalar', text: String(json) }
  }
  const form: Record<string, FormValue> = {}
  for (const [name, type] of Object.entries(inputs)) form[name] = read((parsed ?? {})[name], type)
  return form
}
