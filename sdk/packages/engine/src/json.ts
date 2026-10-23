// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Strict JSON: a parser that keeps numbers as written (never through floating point), keeps
// key order and rejects duplicate keys, as the Java engine's Jackson configuration does; and
// a writer that produces the exact bytes Jackson writes, which content hashes depend on.

/** A parsed JSON value. Objects keep their key order; numbers keep their text. */
export type JsonNode =
  | { readonly type: 'object'; readonly entries: ReadonlyMap<string, JsonNode> }
  | { readonly type: 'array'; readonly items: readonly JsonNode[] }
  | { readonly type: 'string'; readonly value: string }
  | { readonly type: 'number'; readonly text: string; readonly integral: boolean; readonly floating?: boolean }
  | { readonly type: 'boolean'; readonly value: boolean }
  | { readonly type: 'null' }

/** Invalid JSON text, with the 1-based line and column where reading stopped. */
export class JsonSyntaxError extends Error {
  constructor(
    readonly reason: string,
    readonly line: number,
    readonly column: number,
  ) {
    super(`${reason} (line ${line}, column ${column})`)
    this.name = 'JsonSyntaxError'
  }
}

export interface ParseOptions {
  /** Fail when anything but whitespace follows the value. Default true. */
  readonly failOnTrailingTokens?: boolean
}

/**
 * Parses JSON text strictly: duplicate keys, comments, trailing commas, NaN, leading zeros and
 * unescaped control characters are errors. Returns undefined for text that is empty or only
 * whitespace, as Jackson's readTree does.
 */
export function parseJson(text: string, options: ParseOptions = {}): JsonNode | undefined {
  const parser = new Parser(text)
  parser.skipWhitespace()
  if (parser.atEnd()) return undefined
  const value = parser.value()
  if (options.failOnTrailingTokens ?? true) {
    parser.skipWhitespace()
    if (!parser.atEnd()) parser.fail(`Trailing token (of type ${parser.tokenName()}) found after value`)
  }
  return value
}

class Parser {
  private pos = 0

  constructor(private readonly text: string) {}

  atEnd(): boolean {
    return this.pos >= this.text.length
  }

  skipWhitespace(): void {
    for (;;) {
      const c = this.text.charCodeAt(this.pos)
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d) this.pos++
      else return
    }
  }

  fail(reason: string, at = this.pos): never {
    let line = 1
    let lineStart = 0
    for (let i = 0; i < at && i < this.text.length; i++) {
      if (this.text.charCodeAt(i) === 0x0a) {
        line++
        lineStart = i + 1
      }
    }
    throw new JsonSyntaxError(reason, line, at - lineStart + 1)
  }

  tokenName(): string {
    const c = this.text[this.pos]
    if (c === '{') return 'START_OBJECT'
    if (c === '[') return 'START_ARRAY'
    if (c === '"') return 'VALUE_STRING'
    return 'VALUE'
  }

  value(): JsonNode {
    this.skipWhitespace()
    if (this.atEnd()) this.fail('Unexpected end-of-input: expected a value')
    const c = this.text[this.pos]
    switch (c) {
      case '{':
        return this.object()
      case '[':
        return this.array()
      case '"':
        return { type: 'string', value: this.string() }
      case 't':
        return this.keyword('true', { type: 'boolean', value: true })
      case 'f':
        return this.keyword('false', { type: 'boolean', value: false })
      case 'n':
        return this.keyword('null', { type: 'null' })
      default:
        if (c === '-' || (c !== undefined && c >= '0' && c <= '9')) return this.number()
        return this.fail(`Unexpected character ('${c}' (code ${this.text.charCodeAt(this.pos)}))`)
    }
  }

  private keyword(word: string, node: JsonNode): JsonNode {
    if (!this.text.startsWith(word, this.pos)) {
      const m = /^[A-Za-z0-9_$]+/.exec(this.text.slice(this.pos))
      this.fail(`Unrecognized token '${m ? m[0] : this.text[this.pos]}': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')`)
    }
    this.pos += word.length
    const next = this.text[this.pos]
    if (next !== undefined && /[A-Za-z0-9_$]/.test(next)) {
      const m = /^[A-Za-z0-9_$]+/.exec(this.text.slice(this.pos - word.length))!
      this.fail(`Unrecognized token '${m[0]}': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')`)
    }
    return node
  }

  private object(): JsonNode {
    this.pos++ // {
    const entries = new Map<string, JsonNode>()
    this.skipWhitespace()
    if (this.text[this.pos] === '}') {
      this.pos++
      return { type: 'object', entries }
    }
    for (;;) {
      this.skipWhitespace()
      if (this.text[this.pos] !== '"') {
        if (this.atEnd()) this.fail('Unexpected end-of-input: expected a field name')
        this.fail(`Unexpected character ('${this.text[this.pos]}' (code ${this.text.charCodeAt(this.pos)})): was expecting double-quote to start field name`)
      }
      const key = this.string()
      if (entries.has(key)) this.fail(`Duplicate field '${key}'`)
      this.skipWhitespace()
      if (this.text[this.pos] !== ':') {
        if (this.atEnd()) this.fail('Unexpected end-of-input: expected a colon to separate field name and value')
        this.fail(`Unexpected character ('${this.text[this.pos]}' (code ${this.text.charCodeAt(this.pos)})): was expecting a colon to separate field name and value`)
      }
      this.pos++
      entries.set(key, this.value())
      this.skipWhitespace()
      const c = this.text[this.pos]
      if (c === ',') {
        this.pos++
        continue
      }
      if (c === '}') {
        this.pos++
        return { type: 'object', entries }
      }
      if (c === undefined) this.fail('Unexpected end-of-input: expected close marker for Object')
      this.fail(`Unexpected character ('${c}' (code ${this.text.charCodeAt(this.pos)})): was expecting comma to separate Object entries`)
    }
  }

  private array(): JsonNode {
    this.pos++ // [
    const items: JsonNode[] = []
    this.skipWhitespace()
    if (this.text[this.pos] === ']') {
      this.pos++
      return { type: 'array', items }
    }
    for (;;) {
      items.push(this.value())
      this.skipWhitespace()
      const c = this.text[this.pos]
      if (c === ',') {
        this.pos++
        continue
      }
      if (c === ']') {
        this.pos++
        return { type: 'array', items }
      }
      if (c === undefined) this.fail('Unexpected end-of-input: expected close marker for Array')
      this.fail(`Unexpected character ('${c}' (code ${this.text.charCodeAt(this.pos)})): was expecting comma to separate Array entries`)
    }
  }

  private string(): string {
    const start = this.pos
    this.pos++ // opening quote
    let out = ''
    let chunk = this.pos
    for (;;) {
      if (this.atEnd()) this.fail('Unexpected end-of-input in a String value', start)
      const code = this.text.charCodeAt(this.pos)
      if (code === 0x22) {
        out += this.text.slice(chunk, this.pos)
        this.pos++
        return out
      }
      if (code < 0x20) {
        this.fail(`Illegal unquoted character (CTRL-CHAR, code ${code}): has to be escaped using backslash to be included in string value`)
      }
      if (code !== 0x5c) {
        this.pos++
        continue
      }
      out += this.text.slice(chunk, this.pos)
      const e = this.text[this.pos + 1]
      this.pos += 2
      switch (e) {
        case '"':
          out += '"'
          break
        case '\\':
          out += '\\'
          break
        case '/':
          out += '/'
          break
        case 'b':
          out += '\b'
          break
        case 'f':
          out += '\f'
          break
        case 'n':
          out += '\n'
          break
        case 'r':
          out += '\r'
          break
        case 't':
          out += '\t'
          break
        case 'u': {
          const hex = this.text.slice(this.pos, this.pos + 4)
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail('Unexpected character in a \\u escape: expected a hex-digit')
          out += String.fromCharCode(parseInt(hex, 16))
          this.pos += 4
          break
        }
        default:
          this.fail(`Unrecognized character escape '${e ?? ''}'`, this.pos - 1)
      }
      chunk = this.pos
    }
  }

  private number(): JsonNode {
    const m = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/.exec(this.text.slice(this.pos))
    if (!m || m[0] === '-') {
      this.fail('Invalid numeric value: expected digit')
    }
    const after = this.text[this.pos + m[0].length]
    if (after !== undefined && /[0-9.eE]/.test(after)) {
      if (m[1] === '0' && /[0-9]/.test(after)) this.fail('Invalid numeric value: Leading zeroes not allowed')
      this.fail(`Unexpected character ('${after}'): expected a digit for number`, this.pos + m[0].length)
    }
    this.pos += m[0].length
    return { type: 'number', text: m[0], integral: m[2] === undefined && m[3] === undefined }
  }
}

// --- Writing ---------------------------------------------------------------------------------

const HEX = '0123456789ABCDEF'

/** A JSON string literal escaped exactly as Jackson escapes it by default. */
export function jacksonString(text: string): string {
  let out = '"'
  let chunk = 0
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    let escape: string | undefined
    if (c === 0x22) escape = '\\"'
    else if (c === 0x5c) escape = '\\\\'
    else if (c < 0x20) {
      switch (c) {
        case 0x08:
          escape = '\\b'
          break
        case 0x09:
          escape = '\\t'
          break
        case 0x0a:
          escape = '\\n'
          break
        case 0x0c:
          escape = '\\f'
          break
        case 0x0d:
          escape = '\\r'
          break
        default:
          escape = '\\u00' + HEX[c >> 4] + HEX[c & 0xf]
      }
    }
    if (escape !== undefined) {
      out += text.slice(chunk, i) + escape
      chunk = i + 1
    }
  }
  return out + text.slice(chunk) + '"'
}

/**
 * A value the writer accepts: strings, booleans, null, bigint and raw number nodes as numbers,
 * arrays, and Maps or plain objects (written in their iteration order).
 */
export type Writable =
  | string
  | boolean
  | null
  | bigint
  | { readonly rawNumber: string }
  | readonly Writable[]
  | ReadonlyMap<string, Writable>
  | { readonly [key: string]: Writable }

/** Compact JSON text exactly as Jackson writes it (no spaces, Jackson's string escaping). */
export function writeJson(value: Writable): string {
  if (value === null) return 'null'
  switch (typeof value) {
    case 'string':
      return jacksonString(value)
    case 'boolean':
      return value ? 'true' : 'false'
    case 'bigint':
      return value.toString()
  }
  if (Array.isArray(value)) return '[' + value.map((v) => writeJson(v)).join(',') + ']'
  if (value instanceof Map) {
    const parts: string[] = []
    for (const [k, v] of value as ReadonlyMap<string, Writable>) parts.push(jacksonString(k) + ':' + writeJson(v))
    return '{' + parts.join(',') + '}'
  }
  if (typeof (value as { rawNumber?: unknown }).rawNumber === 'string') return (value as { rawNumber: string }).rawNumber
  const parts: string[] = []
  for (const [k, v] of Object.entries(value as { [key: string]: Writable })) parts.push(jacksonString(k) + ':' + writeJson(v))
  return '{' + parts.join(',') + '}'
}

/** Compares strings by UTF-16 code units, as Java's String.compareTo and TreeMap do. */
export function compareJavaStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/** UTF-8 bytes of a Java string: lone surrogates become '?', as String.getBytes(UTF_8) does. */
export function javaUtf8(text: string): Uint8Array {
  let fixed = text
  if (/[\uD800-\uDFFF]/.test(text)) {
    fixed = text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '?')
  }
  return new TextEncoder().encode(fixed)
}

/** Converts a plain JavaScript value (e.g. from JSON.parse) to a JsonNode. */
export function toJsonNode(value: unknown, path = '$'): JsonNode {
  if (value === null) return { type: 'null' }
  switch (typeof value) {
    case 'string':
      return { type: 'string', value }
    case 'boolean':
      return { type: 'boolean', value }
    case 'bigint':
      return { type: 'number', text: value.toString(), integral: true }
    case 'number':
      if (!Number.isFinite(value)) throw new TypeError(`${path}: not a finite number`)
      if (Number.isSafeInteger(value)) return { type: 'number', text: String(value), integral: true }
      // Not exactly representable as written: keep it as a floating-point number, which the
      // readers reject for decimals just as the Java engine rejects a double.
      return { type: 'number', text: String(value), integral: false, floating: true }
    case 'object':
      if (Array.isArray(value)) return { type: 'array', items: value.map((v, i) => toJsonNode(v, `${path}[${i}]`)) }
      if (value instanceof Map) {
        const entries = new Map<string, JsonNode>()
        for (const [k, v] of value) entries.set(String(k), toJsonNode(v, `${path}.${String(k)}`))
        return { type: 'object', entries }
      }
      if (isJsonNode(value)) return value
      {
        const entries = new Map<string, JsonNode>()
        for (const [k, v] of Object.entries(value as object)) entries.set(k, toJsonNode(v, `${path}.${k}`))
        return { type: 'object', entries }
      }
  }
  throw new TypeError(`${path}: unsupported JavaScript value of type ${typeof value}`)
}

function isJsonNode(value: object): value is JsonNode {
  const type = (value as { type?: unknown }).type
  return (
    (type === 'object' && (value as { entries?: unknown }).entries instanceof Map) ||
    (type === 'array' && Array.isArray((value as { items?: unknown }).items)) ||
    (type === 'number' && typeof (value as { text?: unknown }).text === 'string')
  )
}

/** Whether a number node came from a JavaScript number that is not an exact integer. */
export function isFloatingNode(node: JsonNode): boolean {
  return node.type === 'number' && (node as { floating?: boolean }).floating === true
}
