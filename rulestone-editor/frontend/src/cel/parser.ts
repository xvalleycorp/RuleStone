// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// A small parser for the CEL expression language, enough to recognise the shapes the condition
// builder can edit. It does not type-check: the server does that. Any input it cannot parse is
// simply shown as raw CEL.

export interface Span {
  start: number
  end: number
  /** Written in parentheses; the span then includes them. */
  parenthesized?: boolean
}

export type Node = Span &
  (
    | { kind: 'literal'; type: 'int' | 'uint' | 'double' | 'string' | 'bytes' | 'bool' | 'null'; value: string | boolean | null }
    | { kind: 'ident'; name: string }
    | { kind: 'select'; operand: Node; field: string }
    | { kind: 'index'; operand: Node; index: Node }
    | { kind: 'call'; target: Node | null; fn: string; args: Node[] }
    | { kind: 'list'; items: Node[] }
    | { kind: 'map'; entries: { key: Node; value: Node }[] }
    | { kind: 'unary'; op: '!' | '-'; operand: Node }
    | { kind: 'binary'; op: string; left: Node; right: Node }
    | { kind: 'ternary'; cond: Node; then: Node; otherwise: Node }
  )

export type TokenType = 'number' | 'string' | 'ident' | 'keyword' | 'punct' | 'eof'

export interface Token extends Span {
  type: TokenType
  text: string
  /** For strings: the decoded value; for numbers: 'int' | 'uint' | 'double'. */
  value?: string
  numberType?: 'int' | 'uint' | 'double'
  bytes?: boolean
}

export class CelSyntaxError extends Error {
  constructor(
    message: string,
    readonly position: number,
  ) {
    super(message)
  }
}

const KEYWORDS = new Set(['true', 'false', 'null', 'in'])
const PUNCT = ['==', '!=', '<=', '>=', '&&', '||', '<', '>', '!', '+', '-', '*', '/', '%', '?', ':', '.', ',', '(', ')', '[', ']', '{', '}']

/** Splits CEL source into tokens. Throws CelSyntaxError on an unterminated string or unknown character. */
export function tokenize(source: string): Token[] {
  const tokens: Token[] = []
  let i = 0
  while (i < source.length) {
    const c = source[i]
    if (/\s/.test(c)) {
      i++
      continue
    }
    if (c === '/' && source[i + 1] === '/') {
      while (i < source.length && source[i] !== '\n') i++
      continue
    }
    const start = i
    // string literals, with optional r/b prefixes
    const prefix = /^([rRbB]{0,2})(['"])/.exec(source.slice(i, i + 3))
    if (prefix && (prefix[1].length > 0 || c === '"' || c === "'")) {
      const flags = prefix[1].toLowerCase()
      i += prefix[1].length
      const { value, end } = readString(source, i, flags.includes('r'))
      tokens.push({ type: 'string', text: source.slice(start, end), value, bytes: flags.includes('b'), start, end })
      i = end
      continue
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(source[i + 1] ?? ''))) {
      const m = /^(0[xX][0-9a-fA-F]+[uU]?|[0-9]*\.[0-9]+([eE][+-]?[0-9]+)?|[0-9]+[eE][+-]?[0-9]+|[0-9]+[uU]?)/.exec(source.slice(i))!
      const text = m[0]
      const numberType = /[.eE]/.test(text) && !/^0[xX]/.test(text) ? 'double' : /[uU]$/.test(text) ? 'uint' : 'int'
      tokens.push({ type: 'number', text, numberType, start, end: i + text.length })
      i += text.length
      continue
    }
    if (/[A-Za-z_]/.test(c)) {
      const m = /^[A-Za-z_][A-Za-z0-9_]*/.exec(source.slice(i))!
      const text = m[0]
      tokens.push({ type: KEYWORDS.has(text) ? 'keyword' : 'ident', text, start, end: i + text.length })
      i += text.length
      continue
    }
    const punct = PUNCT.find((p) => source.startsWith(p, i))
    if (punct) {
      tokens.push({ type: 'punct', text: punct, start, end: i + punct.length })
      i += punct.length
      continue
    }
    throw new CelSyntaxError(`unexpected character '${c}'`, i)
  }
  tokens.push({ type: 'eof', text: '', start: source.length, end: source.length })
  return tokens
}

function readString(source: string, i: number, raw: boolean): { value: string; end: number } {
  const quote = source[i]
  const triple = source.startsWith(quote.repeat(3), i)
  const delimiter = triple ? quote.repeat(3) : quote
  let j = i + delimiter.length
  let value = ''
  while (j < source.length) {
    if (source.startsWith(delimiter, j)) {
      return { value, end: j + delimiter.length }
    }
    const ch = source[j]
    if (!triple && ch === '\n') break
    if (ch === '\\' && !raw) {
      const next = source[j + 1]
      const simple: Record<string, string> = { n: '\n', t: '\t', r: '\r', '\\': '\\', "'": "'", '"': '"', '`': '`', '?': '?', a: '\x07', b: '\b', f: '\f', v: '\v' }
      if (next in simple) {
        value += simple[next]
        j += 2
        continue
      }
      const hex = /^(?:[xX]([0-9a-fA-F]{2})|u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8})|([0-3][0-7]{2}))/.exec(source.slice(j + 1))
      if (hex) {
        const code = hex[1] ?? hex[2] ?? hex[3]
        value += code ? String.fromCodePoint(parseInt(code, 16)) : String.fromCodePoint(parseInt(hex[4], 8))
        j += 1 + hex[0].length
        continue
      }
      throw new CelSyntaxError('invalid escape sequence', j)
    }
    value += ch
    j++
  }
  throw new CelSyntaxError('unterminated string', i)
}

const RELATIONS = new Set(['==', '!=', '<', '<=', '>', '>=', 'in'])

/** Parses a CEL expression. Throws CelSyntaxError. */
export function parse(source: string): Node {
  const tokens = tokenize(source)
  let pos = 0
  const peek = () => tokens[pos]
  const next = () => tokens[pos++]
  const is = (text: string) => (peek().type === 'punct' || peek().type === 'keyword') && peek().text === text
  const expect = (text: string) => {
    if (!is(text)) throw new CelSyntaxError(`expected '${text}'`, peek().start)
    return next()
  }

  function expression(): Node {
    const cond = or()
    if (is('?')) {
      next()
      const then = or()
      expect(':')
      const otherwise = expression()
      return { kind: 'ternary', cond, then, otherwise, start: cond.start, end: otherwise.end }
    }
    return cond
  }
  function or(): Node {
    let left = and()
    while (is('||')) {
      next()
      const right = and()
      left = { kind: 'binary', op: '||', left, right, start: left.start, end: right.end }
    }
    return left
  }
  function and(): Node {
    let left = relation()
    while (is('&&')) {
      next()
      const right = relation()
      left = { kind: 'binary', op: '&&', left, right, start: left.start, end: right.end }
    }
    return left
  }
  function relation(): Node {
    let left = additive()
    while ((peek().type === 'punct' || peek().type === 'keyword') && RELATIONS.has(peek().text)) {
      const op = next().text
      const right = additive()
      left = { kind: 'binary', op, left, right, start: left.start, end: right.end }
    }
    return left
  }
  function additive(): Node {
    let left = multiplicative()
    while (is('+') || is('-')) {
      const op = next().text
      const right = multiplicative()
      left = { kind: 'binary', op, left, right, start: left.start, end: right.end }
    }
    return left
  }
  function multiplicative(): Node {
    let left = unary()
    while (is('*') || is('/') || is('%')) {
      const op = next().text
      const right = unary()
      left = { kind: 'binary', op, left, right, start: left.start, end: right.end }
    }
    return left
  }
  function unary(): Node {
    if (is('!') || is('-')) {
      const token = next()
      const operand = unary()
      return { kind: 'unary', op: token.text as '!' | '-', operand, start: token.start, end: operand.end }
    }
    return member()
  }
  function member(): Node {
    let node = primary()
    for (;;) {
      if (is('.')) {
        next()
        const name = next()
        if (name.type !== 'ident') throw new CelSyntaxError('expected a field or method name', name.start)
        if (is('(')) {
          const args = callArguments()
          node = { kind: 'call', target: node, fn: name.text, args, start: node.start, end: tokens[pos - 1].end }
        } else {
          node = { kind: 'select', operand: node, field: name.text, start: node.start, end: name.end }
        }
      } else if (is('[')) {
        next()
        const index = expression()
        const close = expect(']')
        node = { kind: 'index', operand: node, index, start: node.start, end: close.end }
      } else {
        return node
      }
    }
  }
  function callArguments(): Node[] {
    expect('(')
    const args: Node[] = []
    if (!is(')')) {
      args.push(expression())
      while (is(',')) {
        next()
        args.push(expression())
      }
    }
    expect(')')
    return args
  }
  function primary(): Node {
    const token = peek()
    if (token.type === 'number') {
      next()
      return { kind: 'literal', type: token.numberType!, value: token.text, start: token.start, end: token.end }
    }
    if (token.type === 'string') {
      next()
      return { kind: 'literal', type: token.bytes ? 'bytes' : 'string', value: token.value!, start: token.start, end: token.end }
    }
    if (token.type === 'keyword') {
      if (token.text === 'true' || token.text === 'false') {
        next()
        return { kind: 'literal', type: 'bool', value: token.text === 'true', start: token.start, end: token.end }
      }
      if (token.text === 'null') {
        next()
        return { kind: 'literal', type: 'null', value: null, start: token.start, end: token.end }
      }
    }
    if (token.type === 'ident') {
      next()
      if (is('(')) {
        const args = callArguments()
        return { kind: 'call', target: null, fn: token.text, args, start: token.start, end: tokens[pos - 1].end }
      }
      return { kind: 'ident', name: token.text, start: token.start, end: token.end }
    }
    if (is('.') && tokens[pos + 1]?.type === 'ident') {
      // leading-dot qualified name: .applicant
      next()
      const name = next()
      return { kind: 'ident', name: name.text, start: token.start, end: name.end }
    }
    if (is('(')) {
      const open = next()
      const inner = expression()
      const close = expect(')')
      return { ...inner, start: open.start, end: close.end, parenthesized: true }
    }
    if (is('[')) {
      const open = next()
      const items: Node[] = []
      if (!is(']')) {
        items.push(expression())
        while (is(',')) {
          next()
          if (is(']')) break
          items.push(expression())
        }
      }
      const close = expect(']')
      return { kind: 'list', items, start: open.start, end: close.end }
    }
    if (is('{')) {
      const open = next()
      const entries: { key: Node; value: Node }[] = []
      if (!is('}')) {
        do {
          if (is('}')) break
          const key = expression()
          expect(':')
          entries.push({ key, value: expression() })
        } while (is(',') && next())
      }
      const close = expect('}')
      return { kind: 'map', entries, start: open.start, end: close.end }
    }
    throw new CelSyntaxError(token.type === 'eof' ? 'unexpected end of expression' : `unexpected '${token.text}'`, token.start)
  }

  const node = expression()
  if (peek().type !== 'eof') throw new CelSyntaxError(`unexpected '${peek().text}'`, peek().start)
  return node
}

/** The dotted path of an identifier or a chain of field selections, e.g. "applicant.age"; otherwise null. */
export function pathOf(node: Node): string | null {
  if (node.kind === 'ident') return node.name
  if (node.kind === 'select') {
    const base = pathOf(node.operand)
    return base === null ? null : `${base}.${node.field}`
  }
  return null
}

/** Whether the node was written in parentheses (the parser marks it). */
export function isParenthesized(node: Node): boolean {
  return node.parenthesized === true
}
