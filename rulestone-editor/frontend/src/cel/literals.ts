// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Literal values by type, in two forms: the text a business user types ("0.5", "1500000 VND",
// "2026-11-01", RETAIL) and the CEL source that RuleStone reads (decimal('0.5'),
// money('1500000', 'VND'), date('2026-11-01'), 'RETAIL'). The user forms match the JSON facts format.

import type { TypeSpec } from '../types'
import { type Node, parse } from './parser'

export interface LiteralCodec {
  /** CEL source for the user's text, or an error key if the text is not a valid value. */
  encode(text: string): { cel: string } | { error: string }
  /** The user's text for a CEL literal node, or undefined if the node is not a literal of this type. */
  decode(node: Node): string | undefined
}

const DECIMAL = /^-?[0-9]+(\.[0-9]+)?$/
const INT = /^-?[0-9]+$/
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
const MONEY = /^(-?[0-9]+(?:\.[0-9]+)?) ([A-Z]{3})$/
const INT64_MAX = 9223372036854775807n

/** CEL text literal with single quotes. */
export function quote(text: string): string {
  let out = "'"
  for (const ch of text) {
    switch (ch) {
      case '\\':
        out += '\\\\'
        break
      case "'":
        out += "\\'"
        break
      case '\n':
        out += '\\n'
        break
      case '\r':
        out += '\\r'
        break
      case '\t':
        out += '\\t'
        break
      default:
        out += ch
    }
  }
  return out + "'"
}

function stringArg(node: Node | undefined): string | undefined {
  return node?.kind === 'literal' && node.type === 'string' ? (node.value as string) : undefined
}

function intLiteral(node: Node | undefined): string | undefined {
  if (!node) return undefined
  if (node.kind === 'literal' && node.type === 'int' && /^[0-9]+$/.test(node.value as string)) return String(BigInt(node.value as string))
  if (node.kind === 'unary' && node.op === '-') {
    const inner = intLiteral(node.operand)
    return inner === undefined || inner.startsWith('-') ? undefined : inner === '0' ? '0' : `-${inner}`
  }
  return undefined
}

function validDate(text: string): boolean {
  if (!DATE.test(text)) return false
  const [y, m, d] = text.split('-').map(Number)
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
}

const CODECS: Record<string, LiteralCodec> = {
  bool: {
    encode: (text) => (text === 'true' || text === 'false' ? { cel: text } : { error: 'bool' }),
    decode: (node) => (node.kind === 'literal' && node.type === 'bool' ? String(node.value) : undefined),
  },
  int: {
    encode: (text) => {
      const t = text.trim()
      if (!INT.test(t)) return { error: 'int' }
      const value = BigInt(t)
      if (value > INT64_MAX || value < -INT64_MAX - 1n) return { error: 'int' }
      return { cel: value.toString() }
    },
    decode: intLiteral,
  },
  string: {
    encode: (text) => ({ cel: quote(text) }),
    decode: (node) => (node.kind === 'literal' && node.type === 'string' ? (node.value as string) : undefined),
  },
  decimal: {
    encode: (text) => {
      const t = text.trim()
      return DECIMAL.test(t) ? { cel: `decimal('${t}')` } : { error: 'decimal' }
    },
    decode: (node) => {
      if (node.kind !== 'call' || node.target !== null || node.fn !== 'decimal' || node.args.length !== 1) return undefined
      const text = stringArg(node.args[0])?.trim()
      if (text !== undefined) return DECIMAL.test(text) ? text : undefined
      return intLiteral(node.args[0])
    },
  },
  money: {
    encode: (text) => {
      const m = MONEY.exec(text.trim().replace(/\s+/, ' '))
      return m ? { cel: `money('${m[1]}', '${m[2]}')` } : { error: 'money' }
    },
    decode: (node) => {
      if (node.kind !== 'call' || node.target !== null || node.fn !== 'money' || node.args.length !== 2) return undefined
      const currency = stringArg(node.args[1])
      if (!currency || !/^[A-Z]{3}$/.test(currency)) return undefined
      const first = node.args[0]
      const amount = stringArg(first)?.trim() ?? intLiteral(first) ?? CODECS.decimal.decode(first)
      return amount !== undefined && DECIMAL.test(amount) ? `${amount} ${currency}` : undefined
    },
  },
  date: {
    encode: (text) => (validDate(text.trim()) ? { cel: `date('${text.trim()}')` } : { error: 'date' }),
    decode: (node) => {
      if (node.kind !== 'call' || node.target !== null || node.fn !== 'date' || node.args.length !== 1) return undefined
      const text = stringArg(node.args[0])
      return text !== undefined && validDate(text) ? text : undefined
    },
  },
}

/** The codec for a primitive type name, or undefined for types the builder has no widget for. */
export function codecFor(spec: TypeSpec | undefined): LiteralCodec | undefined {
  return typeof spec === 'string' ? CODECS[spec] : undefined
}

/** How an output cell is edited. */
export type OutputValue =
  | { mode: 'empty' }
  | { mode: 'literal'; text: string }
  | { mode: 'list'; items: string[] }
  | { mode: 'expr'; cel: string }

/** Recognises a literal of the output's type in a `then` expression, so the cell can show a typed widget. */
export function parseOutput(cel: string | undefined, spec: TypeSpec): OutputValue {
  if (cel === undefined || cel.trim() === '') return { mode: 'empty' }
  let node: Node
  try {
    node = parse(cel)
  } catch {
    return { mode: 'expr', cel }
  }
  const codec = codecFor(spec)
  if (codec) {
    const text = codec.decode(node)
    return text === undefined ? { mode: 'expr', cel } : { mode: 'literal', text }
  }
  const element = Array.isArray(spec) ? codecFor(spec[0]) : undefined
  if (element && node.kind === 'list') {
    const items = node.items.map((item) => element.decode(item))
    if (items.every((i) => i !== undefined)) return { mode: 'list', items: items as string[] }
  }
  return { mode: 'expr', cel }
}

/** CEL for an output cell; `error` names the type whose value is invalid. */
export function encodeOutput(value: OutputValue, spec: TypeSpec): { cel: string | undefined } | { error: string } {
  switch (value.mode) {
    case 'empty':
      return { cel: undefined }
    case 'expr':
      return { cel: value.cel.trim() === '' ? undefined : value.cel }
    case 'literal': {
      const codec = codecFor(spec)
      if (!codec) return { error: 'type' }
      const r = codec.encode(value.text)
      return 'cel' in r ? r : r
    }
    case 'list': {
      const codec = Array.isArray(spec) ? codecFor(spec[0]) : undefined
      if (!codec) return { error: 'type' }
      const parts: string[] = []
      for (const item of value.items) {
        const r = codec.encode(item)
        if ('error' in r) return r
        parts.push(r.cel)
      }
      return { cel: `[${parts.join(', ')}]` }
    }
  }
}
