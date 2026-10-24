// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// A tolerant CEL tokenizer for highlighting: it never fails, whatever the text, and needs no
// engine, so that elements that only display CEL stay small.

export type TokenKind = 'string' | 'number' | 'keyword' | 'operator' | 'punct' | 'function' | 'field' | 'variable' | 'comment' | 'space' | 'error'

export interface Token {
  readonly kind: TokenKind
  readonly text: string
  /** UTF-16 index of the token's first character. */
  readonly start: number
}

const PATTERNS: [TokenKind, RegExp][] = [
  ['space', /^\s+/],
  ['comment', /^\/\/[^\n]*/],
  ['string', /^[rRbB]{0,2}('''[\s\S]*?(?:'''|$)|"""[\s\S]*?(?:"""|$)|'(?:\\.|[^'\\\n])*'?|"(?:\\.|[^"\\\n])*"?)/],
  ['number', /^(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|\.[0-9]+(?:[eE][+-]?[0-9]+)?)[uU]?/],
  ['variable', /^[A-Za-z_][A-Za-z0-9_]*/],
  ['operator', /^(?:==|!=|<=|>=|&&|\|\||[<>!+\-*/%?:])/],
  ['punct', /^[.,()[\]{}]/],
]

const KEYWORDS = new Set(['true', 'false', 'null', 'in'])

/** Splits text into tokens for highlighting. The tokens' texts concatenate to the input. */
export function tokenize(text: string): Token[] {
  const tokens: Token[] = []
  let i = 0
  while (i < text.length) {
    const rest = text.slice(i)
    let matched = false
    for (const [kind, pattern] of PATTERNS) {
      const m = pattern.exec(rest)
      if (!m || m[0].length === 0) continue
      let k: TokenKind = kind
      if (kind === 'variable') {
        const before = tokens.filter((t) => t.kind !== 'space').at(-1)
        const after = rest.slice(m[0].length).trimStart()
        if (KEYWORDS.has(m[0])) k = 'keyword'
        else if (after.startsWith('(')) k = 'function'
        else if (before?.text === '.') k = 'field'
      }
      tokens.push({ kind: k, text: m[0], start: i })
      i += m[0].length
      matched = true
      break
    }
    if (!matched) {
      const ch = String.fromCodePoint(text.codePointAt(i)!)
      tokens.push({ kind: 'error', text: ch, start: i })
      i += ch.length
    }
  }
  return tokens
}
