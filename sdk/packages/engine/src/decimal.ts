// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Exact decimal numbers with the semantics of java.math.BigDecimal: an arbitrary-precision
// unscaled integer and a scale, so that 1.50 keeps its two decimal places as in Java. Built on
// the native BigInt; no floating point is ever involved.

/** Rounding modes accepted by round(); the names of java.math.RoundingMode except UNNECESSARY. */
export type RoundingMode = 'UP' | 'DOWN' | 'CEILING' | 'FLOOR' | 'HALF_UP' | 'HALF_DOWN' | 'HALF_EVEN'

export const ROUNDING_MODES: readonly RoundingMode[] = ['UP', 'DOWN', 'CEILING', 'FLOOR', 'HALF_UP', 'HALF_DOWN', 'HALF_EVEN']

/** Significant digits kept by division, as java.math.MathContext.DECIMAL128. */
export const DIVISION_PRECISION = 34

const INT_MIN = -2147483648
const INT_MAX = 2147483647

const powers: bigint[] = [1n]

/** 10^n for n >= 0. */
export function pow10(n: number): bigint {
  if (n < 0) throw new RangeError('negative power of ten')
  if (n < 256) {
    for (let i = powers.length; i <= n; i++) powers.push(powers[i - 1]! * 10n)
    return powers[n]!
  }
  return 10n ** BigInt(n)
}

function abs(n: bigint): bigint {
  return n < 0n ? -n : n
}

/** Number of decimal digits of |n|; 1 for zero. */
function digitCount(n: bigint): number {
  return n === 0n ? 1 : abs(n).toString().length
}

/** n / d rounded to an integer with the given mode. d must not be zero. */
export function divideAndRound(n: bigint, d: bigint, mode: RoundingMode): bigint {
  const q = n / d
  const r = n % d
  if (r === 0n) return q
  const positive = n < 0n === d < 0n
  const cmp = compareBig(abs(r) * 2n, abs(d))
  let away: boolean
  switch (mode) {
    case 'UP':
      away = true
      break
    case 'DOWN':
      away = false
      break
    case 'CEILING':
      away = positive
      break
    case 'FLOOR':
      away = !positive
      break
    case 'HALF_UP':
      away = cmp >= 0
      break
    case 'HALF_DOWN':
      away = cmp > 0
      break
    case 'HALF_EVEN':
      away = cmp > 0 || (cmp === 0 && (q & 1n) !== 0n)
      break
  }
  return away ? q + (positive ? 1n : -1n) : q
}

function compareBig(a: bigint, b: bigint): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/** Whether a code point is a Unicode decimal digit (Java's Character.isDigit). */
const UNICODE_DIGIT = /\p{Nd}/u

/** The value of a decimal digit in any script, as Character.digit(cp, 10), or -1. */
export function digitValue(ch: string): number {
  const cp = ch.codePointAt(0)!
  if (cp >= 0x30 && cp <= 0x39) return cp - 0x30
  if (!UNICODE_DIGIT.test(ch)) return -1
  // Unicode decimal digits come in complete runs of 0 to 9; find the start of the run.
  let start = cp
  while (UNICODE_DIGIT.test(String.fromCodePoint(start - 1))) start--
  return (cp - start) % 10
}

/** Java's Character.isWhitespace, used by String.strip(). */
export function isJavaWhitespace(cp: number): boolean {
  if (cp === 0x09 || cp === 0x0a || cp === 0x0b || cp === 0x0c || cp === 0x0d) return true
  if (cp >= 0x1c && cp <= 0x1f) return true
  if (cp === 0x00a0 || cp === 0x2007 || cp === 0x202f) return false
  return /[\p{Zs}\p{Zl}\p{Zp}]/u.test(String.fromCodePoint(cp))
}

/** Java's String.strip(): removes leading and trailing Character.isWhitespace code points. */
export function javaStrip(text: string): string {
  const cps = Array.from(text)
  let start = 0
  let end = cps.length
  while (start < end && isJavaWhitespace(cps[start]!.codePointAt(0)!)) start++
  while (end > start && isJavaWhitespace(cps[end - 1]!.codePointAt(0)!)) end--
  return cps.slice(start, end).join('')
}

/** Java's String.isBlank(). */
export function javaIsBlank(text: string): boolean {
  return javaStrip(text).length === 0
}

/** An exact decimal number: unscaled × 10^-scale, like java.math.BigDecimal. Immutable. */
export class Decimal {
  static readonly ZERO = new Decimal(0n, 0)
  static readonly ONE = new Decimal(1n, 0)

  private constructor(
    /** The unscaled value. */
    readonly unscaled: bigint,
    /** Digits after the decimal point; negative for multiples of powers of ten. */
    readonly scale: number,
  ) {}

  /** unscaled × 10^-scale. The scale must fit a Java int. */
  static of(unscaled: bigint, scale = 0): Decimal {
    if (!Number.isInteger(scale) || scale < INT_MIN || scale > INT_MAX) throw new RangeError('decimal scale out of range')
    return new Decimal(unscaled, scale)
  }

  static fromBigInt(value: bigint): Decimal {
    return new Decimal(value, 0)
  }

  /**
   * Parses text as new BigDecimal(text) does: an optional sign, digits with an optional
   * fraction (digits of any script), and an optional exponent. No surrounding whitespace.
   * Returns undefined for anything else.
   */
  static parse(text: string): Decimal | undefined {
    const cps = Array.from(text)
    let i = 0
    let negative = false
    if (cps[i] === '+' || cps[i] === '-') {
      negative = cps[i] === '-'
      i++
    }
    let digits = ''
    let fractionDigits = 0
    let seenPoint = false
    let any = false
    for (; i < cps.length; i++) {
      const ch = cps[i]!
      if (ch === '.') {
        if (seenPoint) return undefined
        seenPoint = true
        continue
      }
      const v = digitValue(ch)
      if (v < 0) break
      digits += String(v)
      any = true
      if (seenPoint) fractionDigits++
    }
    if (!any) return undefined
    let exponent = 0
    if (i < cps.length) {
      if (cps[i] !== 'e' && cps[i] !== 'E') return undefined
      i++
      let expNegative = false
      if (cps[i] === '+' || cps[i] === '-') {
        expNegative = cps[i] === '-'
        i++
      }
      let expDigits = ''
      for (; i < cps.length; i++) {
        const v = digitValue(cps[i]!)
        if (v < 0) return undefined
        expDigits += String(v)
      }
      if (expDigits === '') return undefined
      const e = BigInt(expDigits)
      if (e > 2147483648n) return undefined
      exponent = Number(expNegative ? -e : e)
    }
    const scale = fractionDigits - exponent
    if (scale < INT_MIN || scale > INT_MAX) return undefined
    const unscaled = BigInt(digits)
    return new Decimal(negative ? -unscaled : unscaled, scale)
  }

  signum(): number {
    return this.unscaled === 0n ? 0 : this.unscaled < 0n ? -1 : 1
  }

  isZero(): boolean {
    return this.unscaled === 0n
  }

  /** Number of significant digits of the unscaled value; 1 for zero. */
  precision(): number {
    return digitCount(this.unscaled)
  }

  negate(): Decimal {
    return new Decimal(-this.unscaled, this.scale)
  }

  abs(): Decimal {
    return this.unscaled < 0n ? this.negate() : this
  }

  /** The unscaled value at a larger scale. */
  private upscaled(scale: number): bigint {
    return this.unscaled * pow10(scale - this.scale)
  }

  add(other: Decimal): Decimal {
    if (this.scale === other.scale) return new Decimal(this.unscaled + other.unscaled, this.scale)
    const scale = Math.max(this.scale, other.scale)
    return new Decimal(this.upscaled(scale) + other.upscaled(scale), scale)
  }

  subtract(other: Decimal): Decimal {
    return this.add(other.negate())
  }

  multiply(other: Decimal): Decimal {
    return Decimal.of(this.unscaled * other.unscaled, this.scale + other.scale)
  }

  /**
   * this / divisor rounded to 34 significant digits, half-even, with the scale Java's
   * BigDecimal.divide(divisor, MathContext.DECIMAL128) gives. The divisor must not be zero.
   */
  divide(divisor: Decimal): Decimal {
    if (divisor.isZero()) throw new RangeError('division by zero')
    const preferred = clampScale(this.scale - divisor.scale)
    if (this.isZero()) return new Decimal(0n, preferred)
    const a = abs(this.unscaled)
    const b = abs(divisor.unscaled)
    // Shift so that the integer quotient has exactly 34 digits: with this shift it has 33 or 34.
    let shift = DIVISION_PRECISION - 1 - (digitCount(a) - digitCount(b))
    let quotient = shiftedQuotient(a, b, shift)
    if (digitCount(quotient.q) < DIVISION_PRECISION) {
      shift++
      quotient = shiftedQuotient(a, b, shift)
    }
    let q = quotient.q
    const exact = quotient.r === 0n
    const twice = quotient.r * 2n
    if (twice > quotient.d || (twice === quotient.d && (q & 1n) === 1n)) q++
    if (digitCount(q) > DIVISION_PRECISION) {
      q /= 10n
      shift--
    }
    let scale = this.scale - divisor.scale + shift
    // An exact quotient loses its trailing zeros down to the preferred scale; a rounded one keeps 34 digits.
    while (exact && scale > preferred && q % 10n === 0n) {
      q /= 10n
      scale--
    }
    const negative = this.unscaled < 0n !== divisor.unscaled < 0n
    return Decimal.of(negative ? -q : q, scale)
  }

  /** The value at the given scale, rounded with the mode when digits are dropped. */
  setScale(scale: number, mode: RoundingMode): Decimal {
    if (scale === this.scale) return this
    if (scale > this.scale) return Decimal.of(this.upscaled(scale), scale)
    return Decimal.of(divideAndRound(this.unscaled, pow10(this.scale - scale), mode), scale)
  }

  compareTo(other: Decimal): number {
    if (this.scale === other.scale) return compareBig(this.unscaled, other.unscaled)
    const s1 = this.signum()
    const s2 = other.signum()
    if (s1 !== s2) return s1 < s2 ? -1 : 1
    if (s1 === 0) return 0
    const scale = Math.max(this.scale, other.scale)
    return compareBig(this.upscaled(scale), other.upscaled(scale))
  }

  /** Numeric equality, ignoring scale: 1.0 equals 1.00. */
  equals(other: Decimal): boolean {
    return this.compareTo(other) === 0
  }

  /** The same value without trailing zeros; zero becomes 0 with scale 0 (BigDecimal.stripTrailingZeros). */
  stripTrailingZeros(): Decimal {
    if (this.unscaled === 0n) return Decimal.ZERO
    let u = this.unscaled
    let s = this.scale
    while (u % 10n === 0n) {
      u /= 10n
      s--
    }
    return Decimal.of(u, s)
  }

  /** Plain notation with the scale's digits, as BigDecimal.toPlainString(): "1500.50", "1000". */
  toPlainString(): string {
    const negative = this.unscaled < 0n
    const digits = abs(this.unscaled).toString()
    let body: string
    if (this.scale <= 0) {
      body = this.unscaled === 0n ? '0' : digits + '0'.repeat(-this.scale)
    } else if (digits.length > this.scale) {
      body = digits.slice(0, digits.length - this.scale) + '.' + digits.slice(digits.length - this.scale)
    } else {
      body = '0.' + '0'.repeat(this.scale - digits.length) + digits
    }
    return negative ? '-' + body : body
  }

  /** BigDecimal.toString(): scientific notation when the scale is negative or the value tiny. */
  toString(): string {
    const digits = abs(this.unscaled).toString()
    const sign = this.unscaled < 0n ? '-' : ''
    if (this.scale === 0) return sign + digits
    const adjusted = -this.scale + (digits.length - 1)
    if (this.scale > 0 && adjusted >= -6) return this.toPlainString()
    const mantissa = digits.length > 1 ? digits[0] + '.' + digits.slice(1) : digits
    return sign + mantissa + 'E' + (adjusted >= 0 ? '+' : '') + String(adjusted)
  }

  /** The value as a bigint if it is integral, else undefined. */
  toBigIntExact(): bigint | undefined {
    if (this.scale <= 0) return this.unscaled * pow10(-this.scale)
    const d = pow10(this.scale)
    return this.unscaled % d === 0n ? this.unscaled / d : undefined
  }
}

function clampScale(scale: number): number {
  return Math.min(INT_MAX, Math.max(INT_MIN, scale))
}

function shiftedQuotient(a: bigint, b: bigint, shift: number): { q: bigint; r: bigint; d: bigint } {
  const n = shift >= 0 ? a * pow10(shift) : a
  const d = shift >= 0 ? b : b * pow10(-shift)
  return { q: n / d, r: n % d, d }
}
