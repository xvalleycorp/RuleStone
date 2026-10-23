// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Fuzzy name matching, as vn.io.dxa.rulestone.cel.FuzzyLibrary: normalization, Jaro-Winkler
// computed as an exact fraction and rounded once to four decimals, word-order-insensitive
// name similarity and Levenshtein distance, all over Unicode code points.

import { Decimal, divideAndRound } from '../decimal.js'

export const MAX_LIST_SIZE = 100_000
export const MAX_NAME_LENGTH = 1_000

const PREFIX_LIMIT = 4
const ZERO_SCORE = Decimal.of(0n, 4)
const ONE_SCORE = Decimal.of(10000n, 4)
const MARKS = /\p{M}+/gu
const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u

/** A fuzzy function's argument is too large; the message names no value. */
export class FuzzyLimitError extends Error {}

/** NFD, drop combining marks, đ/Đ to d, lowercase, non-letters and non-digits to single spaces, trim. */
export function normalizeName(s: string): string {
  const stripped = s.normalize('NFD').replace(MARKS, '').replace(/[đĐ]/g, 'd').toLowerCase()
  let out = ''
  let pendingSpace = false
  for (const ch of stripped) {
    if (LETTER_OR_DIGIT.test(ch)) {
      if (pendingSpace && out.length > 0) out += ' '
      pendingSpace = false
      out += ch
    } else {
      pendingSpace = true
    }
  }
  return out
}

function codePoints(normalized: string): number[] {
  const cps = Array.from(normalized, (c) => c.codePointAt(0)!)
  if (cps.length > MAX_NAME_LENGTH) {
    throw new FuzzyLimitError(`fuzzy matching arguments must not be longer than ${MAX_NAME_LENGTH} characters after normalization`)
  }
  return cps
}

/** Jaro-Winkler of two code point sequences, exact, rounded half-even to four decimals. */
function jaroWinkler(a: readonly number[], b: readonly number[]): Decimal {
  if (a.length === 0 || b.length === 0) return a.length === b.length ? ONE_SCORE : ZERO_SCORE
  const window = Math.max(Math.trunc(Math.max(a.length, b.length) / 2) - 1, 0)
  const matchedA = new Array<boolean>(a.length).fill(false)
  const matchedB = new Array<boolean>(b.length).fill(false)
  let matches = 0
  for (let i = 0; i < a.length; i++) {
    const end = Math.min(i + window + 1, b.length)
    for (let j = Math.max(0, i - window); j < end; j++) {
      if (!matchedB[j] && a[i] === b[j]) {
        matchedA[i] = true
        matchedB[j] = true
        matches++
        break
      }
    }
  }
  if (matches === 0) return ZERO_SCORE
  let outOfOrder = 0
  for (let i = 0, j = 0; i < a.length; i++) {
    if (matchedA[i]) {
      while (!matchedB[j]) j++
      if (a[i] !== b[j]) outOfOrder++
      j++
    }
  }
  const transpositions = BigInt(Math.trunc(outOfOrder / 2))
  let prefix = 0
  while (prefix < PREFIX_LIMIT && prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++

  const m = BigInt(matches)
  const lenA = BigInt(a.length)
  const lenB = BigInt(b.length)
  let num = m * m * lenB + m * m * lenA + (m - transpositions) * lenA * lenB
  let den = 3n * m * lenA * lenB
  if (num * 10n > den * 7n) {
    num = num * 10n + BigInt(prefix) * (den - num)
    den = den * 10n
  }
  return Decimal.of(divideAndRound(num * 10000n, den, 'HALF_EVEN'), 4)
}

/** A normalized name and, lazily, the same name with its words sorted. */
class Name {
  private cps: number[] | undefined
  private sortedCps: number[] | undefined

  constructor(readonly normalized: string) {}

  codePoints(): number[] {
    return (this.cps ??= codePoints(this.normalized))
  }

  sortedCodePoints(): number[] {
    if (!this.sortedCps) {
      this.codePoints()
      const tokens = this.normalized === '' ? [] : this.normalized.split(' ')
      tokens.sort()
      this.sortedCps = Array.from(tokens.join(' '), (c) => c.codePointAt(0)!)
    }
    return this.sortedCps
  }
}

function max(a: Decimal, b: Decimal): Decimal {
  return a.compareTo(b) >= 0 ? a : b
}

function nameScore(a: Name, b: Name): Decimal {
  const direct = jaroWinkler(a.codePoints(), b.codePoints())
  const sorted = jaroWinkler(a.sortedCodePoints(), b.sortedCodePoints())
  return max(direct, sorted)
}

export function similarity(a: string, b: string): Decimal {
  return jaroWinkler(codePoints(normalizeName(a)), codePoints(normalizeName(b)))
}

export function nameSimilarity(a: string, b: string): Decimal {
  return nameScore(new Name(normalizeName(a)), new Name(normalizeName(b)))
}

export function levenshtein(a: string, b: string): number {
  let x = codePoints(normalizeName(a))
  let y = codePoints(normalizeName(b))
  if (x.length < y.length) [x, y] = [y, x]
  let previous = Array.from({ length: y.length + 1 }, (_, j) => j)
  let current = new Array<number>(y.length + 1).fill(0)
  for (let i = 1; i <= x.length; i++) {
    current[0] = i
    for (let j = 1; j <= y.length; j++) {
      const substitution = previous[j - 1]! + (x[i - 1] === y[j - 1] ? 0 : 1)
      current[j] = Math.min(substitution, Math.min(previous[j]!, current[j - 1]!) + 1)
    }
    ;[previous, current] = [current, previous]
  }
  return previous[y.length]!
}

export function maxNameSimilarity(name: string, names: readonly string[]): Decimal {
  if (names.length > MAX_LIST_SIZE) {
    throw new FuzzyLimitError(`maxNameSimilarity() list must not have more than ${MAX_LIST_SIZE} elements`)
  }
  const target = new Name(normalizeName(name))
  target.codePoints()
  let best = ZERO_SCORE
  for (const candidate of names) {
    const score = nameScore(target, new Name(normalizeName(candidate)))
    if (score.compareTo(best) > 0) {
      best = score
      if (best.compareTo(Decimal.ONE) === 0) break
    }
  }
  return best
}
