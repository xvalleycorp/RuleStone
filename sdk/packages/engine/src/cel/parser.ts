// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Parser for the CEL grammar (cel-spec CEL.g4), with the standard macros expanded into
// comprehension nodes. Offsets are in code points, as cel-java reports them.

import type { Call, Comprehension, Expr } from './ast.js'

/** A problem at an offset in the source, in code points. */
export interface Issue {
  readonly pos: number
  readonly message: string
}

export class CelParseError extends Error {
  constructor(readonly issues: readonly Issue[]) {
    super(issues.map((i) => i.message).join('; '))
    this.name = 'CelParseError'
  }
}

type TokenType = 'int' | 'uint' | 'double' | 'string' | 'bytes' | 'ident' | 'keyword' | 'punct' | 'eof'

interface Token {
  readonly type: TokenType
  readonly text: string
  readonly pos: number
  readonly value?: string | bigint | number
}

const KEYWORDS = new Set(['true', 'false', 'null', 'in'])
const RESERVED = new Set([
  'as', 'break', 'const', 'continue', 'else', 'for', 'function', 'if', 'import', 'let', 'loop', 'package',
  'namespace', 'return', 'var', 'void', 'while',
])
const PUNCT = ['==', '!=', '<=', '>=', '&&', '||', '<', '>', '!', '+', '-', '*', '/', '%', '?', ':', '.', ',', '(', ')', '[', ']', '{', '}', '=']
const EXPECTING =
  "{'[', '{', '(', '.', '-', '!', 'true', 'false', 'null', NUM_FLOAT, NUM_INT, NUM_UINT, STRING, BYTES, IDENTIFIER}"

const INT64_MIN = -(2n ** 63n)
const INT64_MAX = 2n ** 63n - 1n
const UINT64_MAX = 2n ** 64n - 1n

function isDigit(c: string | undefined): boolean {
  return c !== undefined && c >= '0' && c <= '9'
}

function isHex(c: string | undefined): boolean {
  return c !== undefined && /^[0-9a-fA-F]$/.test(c)
}

function isIdentStart(c: string | undefined): boolean {
  return c !== undefined && /^[A-Za-z_]$/.test(c)
}

function tokenize(cps: readonly string[]): Token[] {
  const tokens: Token[] = []
  let i = 0
  function fail(at: number): never {
    throw new CelParseError([{ pos: at, message: `token recognition error at: '${cps.slice(at).join('')}'` }])
  }
  while (i < cps.length) {
    const c = cps[i]!
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f') {
      i++
      continue
    }
    if (c === '/' && cps[i + 1] === '/') {
      while (i < cps.length && cps[i] !== '\n') i++
      continue
    }
    const start = i
    // String and bytes literals, with optional r/b prefixes in either order.
    let j = i
    let raw = false
    let bytes = false
    while (j < i + 2 && (cps[j] === 'r' || cps[j] === 'R' || cps[j] === 'b' || cps[j] === 'B')) {
      if (cps[j] === 'r' || cps[j] === 'R') raw = true
      else bytes = true
      j++
    }
    if (cps[j] === '"' || cps[j] === "'") {
      const { value, end } = readString(cps, j, raw, () => fail(start))
      tokens.push({ type: bytes ? 'bytes' : 'string', text: cps.slice(start, end).join(''), pos: start, value })
      i = end
      continue
    }
    if (isDigit(c) || (c === '.' && isDigit(cps[i + 1]))) {
      i = number(cps, i, tokens)
      continue
    }
    if (isIdentStart(c)) {
      while (i < cps.length && /^[A-Za-z0-9_]$/.test(cps[i]!)) i++
      const text = cps.slice(start, i).join('')
      tokens.push({ type: KEYWORDS.has(text) ? 'keyword' : 'ident', text, pos: start })
      continue
    }
    const rest2 = c + (cps[i + 1] ?? '')
    const punct = PUNCT.find((p) => (p.length === 2 ? rest2 === p : c === p))
    if (punct && punct !== '=') {
      tokens.push({ type: 'punct', text: punct, pos: start })
      i += punct.length
      continue
    }
    fail(start)
  }
  tokens.push({ type: 'eof', text: '<EOF>', pos: cps.length })
  return tokens
}

function number(cps: readonly string[], start: number, tokens: Token[]): number {
  let i = start
  const text = () => cps.slice(start, i).join('')
  if (cps[i] === '0' && (cps[i + 1] === 'x' || cps[i + 1] === 'X') && isHex(cps[i + 2])) {
    i += 2
    while (isHex(cps[i])) i++
    const digits = cps.slice(start + 2, i).join('')
    if (cps[i] === 'u' || cps[i] === 'U') {
      i++
      tokens.push({ type: 'uint', text: text(), pos: start, value: BigInt('0x' + digits) })
    } else {
      tokens.push({ type: 'int', text: text(), pos: start, value: BigInt('0x' + digits) })
    }
    return i
  }
  while (isDigit(cps[i])) i++
  let isFloat = false
  if (cps[i] === '.' && isDigit(cps[i + 1])) {
    isFloat = true
    i++
    while (isDigit(cps[i])) i++
  }
  if ((cps[i] === 'e' || cps[i] === 'E') && (isDigit(cps[i + 1]) || ((cps[i + 1] === '+' || cps[i + 1] === '-') && isDigit(cps[i + 2])))) {
    isFloat = true
    i += 2
    while (isDigit(cps[i])) i++
  }
  if (isFloat) {
    tokens.push({ type: 'double', text: text(), pos: start, value: Number(text()) })
    return i
  }
  if (cps[i] === 'u' || cps[i] === 'U') {
    const digits = text()
    i++
    tokens.push({ type: 'uint', text: text(), pos: start, value: BigInt(digits) })
    return i
  }
  tokens.push({ type: 'int', text: text(), pos: start, value: BigInt(text()) })
  return i
}

function readString(
  cps: readonly string[],
  i: number,
  raw: boolean,
  fail: () => never,
): { value: string; end: number } {
  const quote = cps[i]!
  const triple = cps[i + 1] === quote && cps[i + 2] === quote
  let j = i + (triple ? 3 : 1)
  let value = ''
  for (;;) {
    if (j >= cps.length) fail()
    const ch = cps[j]!
    if (triple ? ch === quote && cps[j + 1] === quote && cps[j + 2] === quote : ch === quote) {
      return { value, end: j + (triple ? 3 : 1) }
    }
    if (!triple && (ch === '\n' || ch === '\r')) fail()
    if (ch === '\\' && !raw) {
      const next = cps[j + 1]
      const simple: Record<string, string> = {
        n: '\n', t: '\t', r: '\r', '\\': '\\', "'": "'", '"': '"', '`': '`', '?': '?', a: '\x07', b: '\b', f: '\f', v: '\v',
      }
      if (next !== undefined && next in simple) {
        value += simple[next]
        j += 2
        continue
      }
      const rest = cps.slice(j + 1, j + 10).join('')
      const hex = /^(?:[xX]([0-9a-fA-F]{2})|u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8})|([0-3][0-7]{2}))/.exec(rest)
      if (!hex) fail()
      const code = hex[1] ?? hex[2] ?? hex[3]
      const cp = code !== undefined ? parseInt(code, 16) : parseInt(hex[4]!, 8)
      if (cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) fail()
      value += String.fromCodePoint(cp)
      j += 1 + hex[0].length
      continue
    }
    value += ch
    j++
  }
}

const RELATIONS = new Set(['==', '!=', '<', '<=', '>', '>=', 'in'])
const OPERATORS: Record<string, string> = {
  '==': '_==_', '!=': '_!=_', '<': '_<_', '<=': '_<=_', '>': '_>_', '>=': '_>=_', in: '@in',
  '+': '_+_', '-': '_-_', '*': '_*_', '/': '_/_', '%': '_%_', '&&': '_&&_', '||': '_||_',
}

/** Parses a CEL expression. @throws CelParseError */
export function parseCel(source: string): Expr {
  const cps = Array.from(source)
  const tokens = tokenize(cps)
  let p = 0
  const peek = () => tokens[p]!
  const next = () => tokens[p++]!
  const is = (text: string) => {
    const t = peek()
    return (t.type === 'punct' || t.type === 'keyword') && t.text === text
  }
  function fail(pos: number, message: string): never {
    throw new CelParseError([{ pos, message }])
  }
  const expect = (text: string) => {
    if (!is(text)) fail(peek().pos, `missing '${text}' at '${peek().text}'`)
    return next()
  }

  function expr(): Expr {
    const cond = or()
    if (is('?')) {
      const q = next()
      const then = or()
      expect(':')
      const otherwise = expr()
      return call('_?_:_', q.pos, undefined, [cond, then, otherwise])
    }
    return cond
  }
  function binaryLevel(operand: () => Expr, ops: (t: Token) => boolean): Expr {
    let left = operand()
    for (;;) {
      const t = peek()
      if (!((t.type === 'punct' || t.type === 'keyword') && ops(t))) return left
      next()
      const right = operand()
      left = call(OPERATORS[t.text]!, t.pos, undefined, [left, right])
    }
  }
  function or(): Expr {
    return binaryLevel(and, (t) => t.text === '||')
  }
  function and(): Expr {
    return binaryLevel(relation, (t) => t.text === '&&')
  }
  function relation(): Expr {
    return binaryLevel(additive, (t) => RELATIONS.has(t.text))
  }
  function additive(): Expr {
    return binaryLevel(multiplicative, (t) => t.text === '+' || t.text === '-')
  }
  function multiplicative(): Expr {
    return binaryLevel(unary, (t) => t.text === '*' || t.text === '/' || t.text === '%')
  }
  function unary(): Expr {
    if (is('!') || is('-')) {
      const op = peek().text
      // A minus directly before a number is part of the literal: -9223372036854775808.
      if (op === '-' && (tokens[p + 1]!.type === 'int' || tokens[p + 1]!.type === 'double')) return member()
      const ops: Token[] = []
      while (is(op)) ops.push(next())
      if (op === '-' && (peek().type === 'int' || peek().type === 'double')) {
        // --1: the last minus belongs to the literal.
        p--
        ops.pop()
      }
      let operand = member()
      // As cel-java does, pairs of the same unary operator cancel out.
      if (ops.length % 2 === 0) return operand
      operand = call(op === '!' ? '!_' : '-_', ops[0]!.pos, undefined, [operand])
      return operand
    }
    return member()
  }
  function member(): Expr {
    let node = primary()
    for (;;) {
      if (is('.')) {
        const dot = next()
        const name = peek()
        if (name.type !== 'ident') {
          if (name.type === 'eof') fail(name.pos, `mismatched input '<EOF>' expecting IDENTIFIER`)
          fail(dot.pos, `no viable alternative at input '.${name.text}'`)
        }
        next()
        reservedCheck(name)
        if (is('(')) {
          const open = next()
          const args = argumentList(')')
          node = macroOrCall(name.text, open.pos, node, args)
        } else {
          node = { kind: 'select', pos: dot.pos, operand: node, field: name.text, test: false }
        }
      } else if (is('[')) {
        const open = next()
        const index = expr()
        expect(']')
        node = call('_[_]', open.pos, undefined, [node, index])
      } else {
        return node
      }
    }
  }
  function argumentList(close: string): Expr[] {
    const args: Expr[] = []
    if (!is(close)) {
      args.push(expr())
      while (is(',')) {
        next()
        args.push(expr())
      }
    }
    expect(close)
    return args
  }
  function reservedCheck(token: Token): void {
    if (RESERVED.has(token.text)) fail(token.pos, `reserved identifier: ${token.text}`)
  }
  function primary(): Expr {
    const t = peek()
    switch (t.type) {
      case 'int':
      case 'double': {
        next()
        return literal(t, false)
      }
      case 'uint':
        next()
        return { kind: 'literal', pos: t.pos, literal: 'uint', value: checkRange(t, t.value as bigint, 0n, UINT64_MAX) }
      case 'string':
        next()
        return { kind: 'literal', pos: t.pos, literal: 'string', value: t.value as string }
      case 'bytes':
        next()
        return { kind: 'literal', pos: t.pos, literal: 'bytes', value: t.value as string }
      case 'keyword':
        if (t.text === 'true' || t.text === 'false') {
          next()
          return { kind: 'literal', pos: t.pos, literal: 'bool', value: t.text === 'true' }
        }
        if (t.text === 'null') {
          next()
          return { kind: 'literal', pos: t.pos, literal: 'null', value: null }
        }
        break
      case 'ident': {
        next()
        reservedCheck(t)
        if (is('(')) {
          const open = next()
          return macroOrCall(t.text, open.pos, undefined, argumentList(')'))
        }
        return { kind: 'ident', pos: t.pos, name: t.text, global: false }
      }
      case 'punct':
        if (t.text === '-' && (tokens[p + 1]!.type === 'int' || tokens[p + 1]!.type === 'double')) {
          next()
          const number = next()
          return literal(number, true, t.pos)
        }
        if (t.text === '.' && tokens[p + 1]!.type === 'ident') {
          next()
          const name = next()
          reservedCheck(name)
          if (is('(')) {
            const open = next()
            return macroOrCall(name.text, open.pos, undefined, argumentList(')'))
          }
          return { kind: 'ident', pos: name.pos, name: name.text, global: true }
        }
        if (t.text === '(') {
          next()
          const inner = expr()
          expect(')')
          return inner
        }
        if (t.text === '[') {
          const open = next()
          const items: Expr[] = []
          if (!is(']')) {
            items.push(expr())
            while (is(',')) {
              next()
              if (is(']')) break
              items.push(expr())
            }
          }
          expect(']')
          return { kind: 'list', pos: open.pos, items }
        }
        if (t.text === '{') {
          fail(t.pos, 'map literals are not supported by the browser engine')
        }
        break
      case 'eof':
        break
    }
    return fail(t.pos, `mismatched input '${t.text}' expecting ${EXPECTING}`)
  }
  function literal(t: Token, negative: boolean, pos = t.pos): Expr {
    if (t.type === 'double') {
      const v = t.value as number
      return { kind: 'literal', pos, literal: 'double', value: negative ? -v : v }
    }
    const v = negative ? -(t.value as bigint) : (t.value as bigint)
    return { kind: 'literal', pos, literal: 'int', value: checkRange(t, v, INT64_MIN, INT64_MAX, negative, pos) }
  }
  function checkRange(t: Token, v: bigint, min: bigint, max: bigint, negative = false, pos = t.pos): bigint {
    if (v < min || v > max) fail(pos, `For input string: "${negative ? '-' : ''}${t.text}"`)
    return v
  }
  function call(fn: string, pos: number, target: Expr | undefined, args: Expr[]): Call {
    return { kind: 'call', pos, fn, target, args }
  }
  function macroOrCall(name: string, pos: number, target: Expr | undefined, args: Expr[]): Expr {
    if (target === undefined && name === 'has' && args.length === 1) {
      const arg = args[0]!
      if (arg.kind !== 'select') fail(pos, 'invalid argument to has() macro')
      // As in cel-java, the presence test takes the position of the has() call.
      return { ...arg, pos, test: true }
    }
    if (target !== undefined) {
      const two = args.length === 2
      const macro =
        (two && (name === 'all' || name === 'exists' || name === 'exists_one' || name === 'filter' || name === 'map')) ||
        (args.length === 3 && name === 'map')
      if (macro) {
        const variable = args[0]!
        if (variable.kind !== 'ident' || variable.global) fail(variable.pos, 'The argument must be a simple name')
        const node: Comprehension = {
          kind: 'comprehension',
          pos,
          macro: name as Comprehension['macro'],
          range: target,
          variable: variable.name,
          predicate: name === 'map' ? (args.length === 3 ? args[1] : undefined) : args[1],
          transform: name === 'map' ? args[args.length - 1] : undefined,
        }
        return node
      }
    }
    return call(name, pos, target, args)
  }

  const root = expr()
  if (peek().type !== 'eof') fail(peek().pos, `extraneous input '${peek().text}' expecting <EOF>`)
  return root
}
