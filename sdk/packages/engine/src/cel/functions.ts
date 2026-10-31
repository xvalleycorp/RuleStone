// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Function and operator overloads: their signatures for the type checker and their
// implementations for the interpreter. Covers the CEL standard functions RuleStone documents
// and the decimal, money, date and fuzzy libraries, with the Java engine's semantics and
// error messages.

import { LocalDate } from '../date.js'
import { Decimal, ROUNDING_MODES, digitValue, javaStrip, type RoundingMode } from '../decimal.js'
import { Money, isCurrencyCode, minorUnits } from '../money.js'
import { INT64_MAX, INT64_MIN, UInt, valuesEqual, type Value } from '../values.js'
import * as fuzzy from './fuzzy.js'
import { BOOL, DATE, DECIMAL, DOUBLE, DYN, INT, MONEY, STRING, UINT, list, type CelType } from './types.js'

/** An evaluation error. Native errors are CEL's own and carry the position of the failing node. */
export class EvalError extends Error {
  constructor(
    message: string,
    readonly native: boolean,
    public pos = -1,
  ) {
    super(message)
    this.name = 'EvalError'
  }

  /** The message as cel-java reports it. */
  display(): string {
    return this.native ? `evaluation error at <input>:${this.pos}: ${this.message}` : this.message
  }
}

function nativeError(message: string): EvalError {
  return new EvalError(message, true)
}

function libraryError(message: string): EvalError {
  return new EvalError(message, false)
}

export interface EvalContext {
  /** Minor units overriding ISO 4217, by currency code. */
  readonly currencyScales: ReadonlyMap<string, number>
}

export interface Overload {
  readonly id: string
  /** Called as target.fn(args); params[0] is the target. */
  readonly member: boolean
  /** Parameter types; type parameters are vars with negative ids. */
  readonly params: readonly CelType[]
  readonly result: CelType
  readonly impl: (args: readonly Value[], ctx: EvalContext) => Value
  /** Declared so that the type checker can say the browser engine does not provide it. */
  readonly unsupported?: boolean
}

const A: CelType = { k: 'var', id: -1 }

const functions = new Map<string, Overload[]>()

function def(fn: string, id: string, member: boolean, params: CelType[], result: CelType, impl: Overload['impl']): void {
  let list = functions.get(fn)
  if (!list) functions.set(fn, (list = []))
  list.push({ id, member, params, result, impl })
}

function global(fn: string, id: string, params: CelType[], result: CelType, impl: Overload['impl']): void {
  def(fn, id, false, params, result, impl)
}

function member(fn: string, id: string, params: CelType[], result: CelType, impl: Overload['impl']): void {
  def(fn, id, true, params, result, impl)
}

/** The overloads of a function or operator, or undefined if it is not defined. */
export function overloadsOf(fn: string): readonly Overload[] | undefined {
  return functions.get(fn)
}

/** Every function and operator with its overloads, for the function catalog. */
export function allOverloads(): ReadonlyMap<string, readonly Overload[]> {
  return functions
}

/** Functions of standard CEL that the browser engine does not provide. */
export const UNSUPPORTED_FUNCTIONS = new Set([
  'bytes', 'timestamp', 'duration', 'type', 'getFullYear', 'getMonth', 'getDate', 'getDayOfMonth', 'getDayOfWeek',
  'getDayOfYear', 'getHours', 'getMinutes', 'getSeconds', 'getMilliseconds',
])

// --- integers ----------------------------------------------------------------------------------

const UINT64_MAX = 2n ** 64n - 1n

function int64(v: bigint): bigint {
  if (v < INT64_MIN || v > INT64_MAX) throw nativeError('long overflow')
  return v
}

function uint64(v: bigint): UInt {
  if (v < 0n || v > UINT64_MAX) throw nativeError('unsigned long overflow')
  return new UInt(v)
}

const i = (v: Value) => v as bigint
const u = (v: Value) => (v as UInt).value
const d = (v: Value) => v as number
const s = (v: Value) => v as string
const dec = (v: Value) => v as Decimal
const mon = (v: Value) => v as Money
const date = (v: Value) => v as LocalDate
const lst = (v: Value) => v as readonly Value[]

global('_+_', 'add_int64', [INT, INT], INT, ([a, b]) => int64(i(a!) + i(b!)))
global('_+_', 'add_uint64', [UINT, UINT], UINT, ([a, b]) => uint64(u(a!) + u(b!)))
global('_+_', 'add_double', [DOUBLE, DOUBLE], DOUBLE, ([a, b]) => d(a!) + d(b!))
global('_+_', 'add_string', [STRING, STRING], STRING, ([a, b]) => s(a!) + s(b!))
global('_+_', 'add_list', [list(A), list(A)], list(A), ([a, b]) => Object.freeze([...lst(a!), ...lst(b!)]))
global('_-_', 'subtract_int64', [INT, INT], INT, ([a, b]) => int64(i(a!) - i(b!)))
global('_-_', 'subtract_uint64', [UINT, UINT], UINT, ([a, b]) => uint64(u(a!) - u(b!)))
global('_-_', 'subtract_double', [DOUBLE, DOUBLE], DOUBLE, ([a, b]) => d(a!) - d(b!))
global('_*_', 'multiply_int64', [INT, INT], INT, ([a, b]) => int64(i(a!) * i(b!)))
global('_*_', 'multiply_uint64', [UINT, UINT], UINT, ([a, b]) => uint64(u(a!) * u(b!)))
global('_*_', 'multiply_double', [DOUBLE, DOUBLE], DOUBLE, ([a, b]) => d(a!) * d(b!))
global('_/_', 'divide_int64', [INT, INT], INT, ([a, b]) => {
  if (i(b!) === 0n) throw nativeError('/ by zero')
  if (i(a!) === INT64_MIN && i(b!) === -1n) throw nativeError('most negative number wraps')
  return i(a!) / i(b!)
})
global('_/_', 'divide_uint64', [UINT, UINT], UINT, ([a, b]) => {
  if (u(b!) === 0n) throw nativeError('/ by zero')
  return new UInt(u(a!) / u(b!))
})
global('_/_', 'divide_double', [DOUBLE, DOUBLE], DOUBLE, ([a, b]) => d(a!) / d(b!))
global('_%_', 'modulo_int64', [INT, INT], INT, ([a, b]) => {
  if (i(b!) === 0n) throw nativeError('/ by zero')
  return i(a!) % i(b!)
})
global('_%_', 'modulo_uint64', [UINT, UINT], UINT, ([a, b]) => {
  if (u(b!) === 0n) throw nativeError('/ by zero')
  return new UInt(u(a!) % u(b!))
})
global('-_', 'negate_int64', [INT], INT, ([a]) => int64(-i(a!)))
global('-_', 'negate_double', [DOUBLE], DOUBLE, ([a]) => -d(a!))
global('!_', 'logical_not', [BOOL], BOOL, ([a]) => !(a as boolean))

// --- equality, membership, comparison ----------------------------------------------------------

global('_==_', 'equals', [A, A], BOOL, ([a, b]) => valuesEqual(a!, b!))
global('_!=_', 'not_equals', [A, A], BOOL, ([a, b]) => !valuesEqual(a!, b!))
global('@in', 'in_list', [A, list(A)], BOOL, ([a, b]) => lst(b!).some((item) => valuesEqual(a!, item)))
global('_[_]', 'index_list', [list(A), INT], A, ([a, b]) => {
  const items = lst(a!)
  const index = b instanceof UInt ? b.value : typeof b === 'number' ? (Number.isInteger(b) ? BigInt(b) : -1n) : i(b!)
  if (index < 0n || index >= BigInt(items.length)) throw libraryError('list index out of bounds')
  return items[Number(index)]!
})

const COMPARISONS: [string, string, (c: number) => boolean][] = [
  ['_<_', 'less', (c) => c < 0],
  ['_<=_', 'less_equals', (c) => c <= 0],
  ['_>_', 'greater', (c) => c > 0],
  ['_>=_', 'greater_equals', (c) => c >= 0],
]

function cmp<T>(a: T, b: T): number {
  return a < b ? -1 : a > b ? 1 : 0
}

function sameCurrency(a: Money, b: Money): Money {
  if (a.currency !== b.currency) throw libraryError(`money currencies differ: ${a.currency} and ${b.currency}`)
  return b
}

for (const [fn, name, test] of COMPARISONS) {
  global(fn, `${name}_int64`, [INT, INT], BOOL, ([a, b]) => test(cmp(i(a!), i(b!))))
  global(fn, `${name}_uint64`, [UINT, UINT], BOOL, ([a, b]) => test(cmp(u(a!), u(b!))))
  global(fn, `${name}_double`, [DOUBLE, DOUBLE], BOOL, ([a, b]) => test(cmp(d(a!), d(b!))))
  global(fn, `${name}_string`, [STRING, STRING], BOOL, ([a, b]) => test(cmp(s(a!), s(b!))))
  global(fn, `${name}_bool`, [BOOL, BOOL], BOOL, ([a, b]) => test(cmp(a as boolean, b as boolean)))
  global(fn, `rulestone_${name}_decimal_decimal`, [DECIMAL, DECIMAL], BOOL, ([a, b]) => test(dec(a!).compareTo(dec(b!))))
  global(fn, `rulestone_${name}_decimal_int`, [DECIMAL, INT], BOOL, ([a, b]) => test(dec(a!).compareTo(Decimal.fromBigInt(i(b!)))))
  global(fn, `rulestone_${name}_int_decimal`, [INT, DECIMAL], BOOL, ([a, b]) => test(Decimal.fromBigInt(i(a!)).compareTo(dec(b!))))
  global(fn, `rulestone_${name}_money_money`, [MONEY, MONEY], BOOL, ([a, b]) =>
    test(mon(a!).amount.compareTo(sameCurrency(mon(a!), mon(b!)).amount)),
  )
  global(fn, `rulestone_${name}_date_date`, [DATE, DATE], BOOL, ([a, b]) => test(date(a!).compareTo(date(b!))))
}

// --- strings, lists and conversions ------------------------------------------------------------

function codePointLength(text: string): number {
  let n = 0
  for (const _ of text) n++
  return n
}

global('size', 'size_string', [STRING], INT, ([a]) => BigInt(codePointLength(s(a!))))
global('size', 'size_list', [list(A)], INT, ([a]) => BigInt(lst(a!).length))
member('size', 'string_size', [STRING], INT, ([a]) => BigInt(codePointLength(s(a!))))
member('size', 'list_size', [list(A)], INT, ([a]) => BigInt(lst(a!).length))
member('contains', 'contains_string', [STRING, STRING], BOOL, ([a, b]) => s(a!).includes(s(b!)))
member('startsWith', 'starts_with_string', [STRING, STRING], BOOL, ([a, b]) => s(a!).startsWith(s(b!)))
member('endsWith', 'ends_with_string', [STRING, STRING], BOOL, ([a, b]) => s(a!).endsWith(s(b!)))
global('matches', 'matches', [STRING, STRING], BOOL, ([a, b]) => regex(s(b!)).test(s(a!)))
member('matches', 'matches_string', [STRING, STRING], BOOL, ([a, b]) => regex(s(b!)).test(s(a!)))

const regexCache = new Map<string, RegExp>()

/** Compiles an RE2 pattern as a JavaScript regular expression with the same meaning for common syntax. */
function regex(pattern: string): RegExp {
  let compiled = regexCache.get(pattern)
  if (compiled) return compiled
  let flags = 'u'
  let source = pattern
  const inline = /^\(\?([imsU]+)\)/.exec(source)
  if (inline) {
    if (inline[1]!.includes('U')) throw libraryError('matches() pattern is not a valid regular expression')
    flags += inline[1]!
    source = source.slice(inline[0].length)
  }
  source = source
    .replace(/\\p([A-Z])/g, '\\p{$1}')
    .replace(/\\P([A-Z])/g, '\\P{$1}')
    .replace(/\(\?P</g, '(?<')
    .replace(/\\A/g, '^')
    .replace(/\\z/g, '$')
  try {
    compiled = new RegExp(source, flags)
  } catch {
    throw libraryError('matches() pattern is not a valid regular expression')
  }
  if (regexCache.size > 256) regexCache.clear()
  regexCache.set(pattern, compiled)
  return compiled
}

/** Long.parseLong: an optional sign and decimal digits of any script. */
function parseJavaLong(text: string, allowSign: boolean): bigint | undefined {
  const cps = Array.from(text)
  let negative = false
  let k = 0
  if (allowSign && (cps[0] === '+' || cps[0] === '-')) {
    negative = cps[0] === '-'
    k = 1
  }
  if (k >= cps.length) return undefined
  let digits = ''
  for (; k < cps.length; k++) {
    const v = digitValue(cps[k]!)
    if (v < 0) return undefined
    digits += String(v)
  }
  const value = BigInt(digits)
  return negative ? -value : value
}

const BOOL_TEXT = new Map([
  ['1', true], ['t', true], ['true', true], ['TRUE', true], ['True', true],
  ['0', false], ['f', false], ['false', false], ['FALSE', false], ['False', false],
])

/** Double.parseDouble for decimal notation, NaN and Infinity. */
function parseJavaDouble(text: string): number | undefined {
  const t = text.replace(/^[\u0000- ]+|[\u0000- ]+$/g, '')
  const m = /^([+-]?)(?:(NaN|Infinity)|((?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[fFdD]?)$/.exec(t)
  if (!m) return undefined
  const v = m[2] === 'NaN' ? NaN : m[2] === 'Infinity' ? Infinity : Number(m[3])
  return m[1] === '-' ? -v : v
}

global('int', 'int64_to_int64', [INT], INT, ([a]) => a!)
global('int', 'uint64_to_int64', [UINT], INT, ([a]) => {
  if (u(a!) > INT64_MAX) throw nativeError('unsigned long overflow')
  return u(a!)
})
global('int', 'double_to_int64', [DOUBLE], INT, ([a]) => {
  const v = d(a!)
  if (!Number.isFinite(v) || v <= -9223372036854775809 || v >= 9223372036854775807) throw nativeError('double is out of range for int')
  return BigInt(Math.trunc(v))
})
global('int', 'string_to_int64', [STRING], INT, ([a]) => {
  const v = parseJavaLong(s(a!), true)
  if (v === undefined || v < INT64_MIN || v > INT64_MAX) throw libraryError('int() argument is not a valid integer')
  return v
})
global('uint', 'uint64_to_uint64', [UINT], UINT, ([a]) => a!)
global('uint', 'int64_to_uint64', [INT], UINT, ([a]) => {
  if (i(a!) < 0n) throw nativeError('int out of uint range')
  return new UInt(i(a!))
})
global('uint', 'double_to_uint64', [DOUBLE], UINT, ([a]) => {
  const v = d(a!)
  if (!Number.isFinite(v) || v <= -1 || v >= 18446744073709551616) throw nativeError('double out of uint range')
  return new UInt(BigInt(Math.trunc(v)))
})
global('uint', 'string_to_uint64', [STRING], UINT, ([a]) => {
  const v = parseJavaLong(s(a!), false)
  if (v === undefined || v > UINT64_MAX) throw libraryError('uint() argument is not a valid unsigned integer')
  return new UInt(v)
})
global('double', 'double_to_double', [DOUBLE], DOUBLE, ([a]) => a!)
global('double', 'int64_to_double', [INT], DOUBLE, ([a]) => Number(i(a!)))
global('double', 'uint64_to_double', [UINT], DOUBLE, ([a]) => Number(u(a!)))
global('double', 'string_to_double', [STRING], DOUBLE, ([a]) => {
  const v = parseJavaDouble(s(a!))
  if (v === undefined) throw libraryError('double() argument is not a valid number')
  return v
})
global('string', 'string_to_string', [STRING], STRING, ([a]) => a!)
global('string', 'int64_to_string', [INT], STRING, ([a]) => i(a!).toString())
global('string', 'uint64_to_string', [UINT], STRING, ([a]) => u(a!).toString())
global('string', 'bool_to_string', [BOOL], STRING, ([a]) => String(a))
// Java formats doubles differently by JDK version; RuleStone rules never use them.
functions.get('string')!.push({ id: 'double_to_string', member: false, params: [DOUBLE], result: STRING, impl: () => '', unsupported: true })
global('bool', 'bool_to_bool', [BOOL], BOOL, ([a]) => a!)
global('bool', 'string_to_bool', [STRING], BOOL, ([a]) => {
  const v = BOOL_TEXT.get(s(a!))
  if (v === undefined) throw libraryError('bool() argument is not a valid boolean')
  return v
})
global('dyn', 'to_dyn', [A], DYN, ([a]) => a!)

// --- decimal -----------------------------------------------------------------------------------

function parseDecimal(text: string, message: string): Decimal {
  const value = Decimal.parse(javaStrip(text))
  if (!value) throw libraryError(message)
  return value
}

function divide(a: Decimal, b: Decimal, message: string): Decimal {
  if (b.isZero()) throw libraryError(message)
  return a.divide(b)
}

/** RoundingMode.valueOf(name.toUpperCase(Locale.ROOT)), without UNNECESSARY. */
export function roundingMode(name: string): RoundingMode {
  const upper = name.toUpperCase()
  if (!(ROUNDING_MODES as readonly string[]).includes(upper)) {
    throw libraryError('unknown rounding mode (expected HALF_EVEN, HALF_UP, HALF_DOWN, UP, DOWN, CEILING or FLOOR)')
  }
  return upper as RoundingMode
}

function roundDecimal(value: Decimal, scale: bigint, mode: RoundingMode): Decimal {
  if (scale < -1000n || scale > 1000n) throw libraryError('round() scale must be between -1000 and 1000')
  return value.setScale(Number(scale), mode)
}

global('decimal', 'rulestone_decimal_string', [STRING], DECIMAL, ([a]) => parseDecimal(s(a!), 'decimal() argument is not a valid number'))
global('decimal', 'rulestone_decimal_int', [INT], DECIMAL, ([a]) => Decimal.fromBigInt(i(a!)))
const DECIMAL_OPS: [string, string, (a: Decimal, b: Decimal) => Decimal][] = [
  ['_+_', 'add', (a, b) => a.add(b)],
  ['_-_', 'subtract', (a, b) => a.subtract(b)],
  ['_*_', 'multiply', (a, b) => a.multiply(b)],
  ['_/_', 'divide', (a, b) => divide(a, b, 'decimal division by zero')],
]
for (const [fn, name, op] of DECIMAL_OPS) {
  global(fn, `rulestone_${name}_decimal_decimal`, [DECIMAL, DECIMAL], DECIMAL, ([a, b]) => op(dec(a!), dec(b!)))
  global(fn, `rulestone_${name}_decimal_int`, [DECIMAL, INT], DECIMAL, ([a, b]) => op(dec(a!), Decimal.fromBigInt(i(b!))))
  global(fn, `rulestone_${name}_int_decimal`, [INT, DECIMAL], DECIMAL, ([a, b]) => op(Decimal.fromBigInt(i(a!)), dec(b!)))
}
global('-_', 'rulestone_negate_decimal', [DECIMAL], DECIMAL, ([a]) => dec(a!).negate())
global('string', 'rulestone_string_decimal', [DECIMAL], STRING, ([a]) => dec(a!).toPlainString())
member('round', 'rulestone_round_decimal_int', [DECIMAL, INT], DECIMAL, ([a, b]) => roundDecimal(dec(a!), i(b!), 'HALF_EVEN'))
member('round', 'rulestone_round_decimal_int_string', [DECIMAL, INT, STRING], DECIMAL, ([a, b, c]) => {
  const mode = roundingMode(s(c!))
  return roundDecimal(dec(a!), i(b!), mode)
})
member('abs', 'rulestone_abs_decimal', [DECIMAL], DECIMAL, ([a]) => dec(a!).abs())
global('min', 'rulestone_min_decimal_decimal', [DECIMAL, DECIMAL], DECIMAL, ([a, b]) => (dec(a!).compareTo(dec(b!)) <= 0 ? a! : b!))
global('max', 'rulestone_max_decimal_decimal', [DECIMAL, DECIMAL], DECIMAL, ([a, b]) => (dec(a!).compareTo(dec(b!)) >= 0 ? a! : b!))

// --- money -------------------------------------------------------------------------------------

function createMoney(amount: Decimal, currency: string): Money {
  if (!isCurrencyCode(currency)) throw libraryError('money() currency is not an ISO 4217 code in upper case')
  return Money.of(amount, currency)
}

function times(m: Money, factor: Decimal): Money {
  return Money.of(m.amount.multiply(factor), m.currency)
}

function divideMoney(m: Money, divisor: Decimal): Money {
  return Money.of(divide(m.amount, divisor, 'money division by zero'), m.currency)
}

function roundMoney(m: Money, mode: RoundingMode, ctx: EvalContext): Money {
  const scale = ctx.currencyScales.get(m.currency) ?? minorUnits(m.currency)
  if (scale === undefined) {
    throw libraryError(
      `currency ${m.currency} has no minor unit in ISO 4217; configure one with currencyScale("${m.currency}", digits)`,
    )
  }
  return Money.of(m.amount.setScale(scale, mode), m.currency)
}

global('money', 'rulestone_money_string_string', [STRING, STRING], MONEY, ([a, b]) =>
  createMoney(parseDecimal(s(a!), 'money() amount is not a valid number'), s(b!)),
)
global('money', 'rulestone_money_decimal_string', [DECIMAL, STRING], MONEY, ([a, b]) => createMoney(dec(a!), s(b!)))
global('money', 'rulestone_money_int_string', [INT, STRING], MONEY, ([a, b]) => createMoney(Decimal.fromBigInt(i(a!)), s(b!)))
member('amount', 'rulestone_amount_money', [MONEY], DECIMAL, ([a]) => mon(a!).amount)
member('currency', 'rulestone_currency_money', [MONEY], STRING, ([a]) => mon(a!).currency)
global('_+_', 'rulestone_add_money_money', [MONEY, MONEY], MONEY, ([a, b]) =>
  Money.of(mon(a!).amount.add(sameCurrency(mon(a!), mon(b!)).amount), mon(a!).currency),
)
global('_-_', 'rulestone_subtract_money_money', [MONEY, MONEY], MONEY, ([a, b]) =>
  Money.of(mon(a!).amount.subtract(sameCurrency(mon(a!), mon(b!)).amount), mon(a!).currency),
)
global('_*_', 'rulestone_multiply_money_decimal', [MONEY, DECIMAL], MONEY, ([a, b]) => times(mon(a!), dec(b!)))
global('_*_', 'rulestone_multiply_money_int', [MONEY, INT], MONEY, ([a, b]) => times(mon(a!), Decimal.fromBigInt(i(b!))))
global('_*_', 'rulestone_multiply_decimal_money', [DECIMAL, MONEY], MONEY, ([a, b]) => times(mon(b!), dec(a!)))
global('_*_', 'rulestone_multiply_int_money', [INT, MONEY], MONEY, ([a, b]) => times(mon(b!), Decimal.fromBigInt(i(a!))))
global('_/_', 'rulestone_divide_money_decimal', [MONEY, DECIMAL], MONEY, ([a, b]) => divideMoney(mon(a!), dec(b!)))
global('_/_', 'rulestone_divide_money_int', [MONEY, INT], MONEY, ([a, b]) => divideMoney(mon(a!), Decimal.fromBigInt(i(b!))))
global('-_', 'rulestone_negate_money', [MONEY], MONEY, ([a]) => Money.of(mon(a!).amount.negate(), mon(a!).currency))
member('round', 'rulestone_round_money', [MONEY], MONEY, ([a], ctx) => roundMoney(mon(a!), 'HALF_EVEN', ctx))
member('round', 'rulestone_round_money_string', [MONEY, STRING], MONEY, ([a, b], ctx) => roundMoney(mon(a!), roundingMode(s(b!)), ctx))
member('abs', 'rulestone_abs_money', [MONEY], MONEY, ([a]) => Money.of(mon(a!).amount.abs(), mon(a!).currency))
global('min', 'rulestone_min_money_money', [MONEY, MONEY], MONEY, ([a, b]) =>
  mon(a!).amount.compareTo(sameCurrency(mon(a!), mon(b!)).amount) <= 0 ? a! : b!,
)
global('max', 'rulestone_max_money_money', [MONEY, MONEY], MONEY, ([a, b]) =>
  mon(a!).amount.compareTo(sameCurrency(mon(a!), mon(b!)).amount) >= 0 ? a! : b!,
)
global('string', 'rulestone_string_money', [MONEY], STRING, ([a]) => mon(a!).toString())

// --- date --------------------------------------------------------------------------------------

global('date', 'rulestone_date_string', [STRING], DATE, ([a]) => {
  const value = LocalDate.parse(s(a!))
  if (!value) throw libraryError('date() argument is not a valid ISO date (yyyy-MM-dd)')
  return value
})
for (const [fn, shift] of [
  ['plusDays', (x: LocalDate, n: bigint) => x.plusDays(n)],
  ['plusMonths', (x: LocalDate, n: bigint) => x.plusMonths(n)],
  ['plusYears', (x: LocalDate, n: bigint) => x.plusYears(n)],
] as const) {
  member(fn, `rulestone_${fn}_date_int`, [DATE, INT], DATE, ([a, b]) => {
    try {
      return shift(date(a!), i(b!))
    } catch {
      throw libraryError(`${fn}() result is outside the supported date range`)
    }
  })
}
member('year', 'rulestone_year_date', [DATE], INT, ([a]) => BigInt(date(a!).year))
member('month', 'rulestone_month_date', [DATE], INT, ([a]) => BigInt(date(a!).month))
member('day', 'rulestone_day_date', [DATE], INT, ([a]) => BigInt(date(a!).day))
member('dayOfWeek', 'rulestone_dayOfWeek_date', [DATE], INT, ([a]) => BigInt(date(a!).dayOfWeek()))
global('daysBetween', 'rulestone_daysBetween_date_date', [DATE, DATE], INT, ([a, b]) => BigInt(date(a!).daysUntil(date(b!))))
global('monthsBetween', 'rulestone_monthsBetween_date_date', [DATE, DATE], INT, ([a, b]) => BigInt(date(a!).monthsUntil(date(b!))))
global('yearsBetween', 'rulestone_yearsBetween_date_date', [DATE, DATE], INT, ([a, b]) => BigInt(date(a!).yearsUntil(date(b!))))
global('string', 'rulestone_string_date', [DATE], STRING, ([a]) => date(a!).toString())

// --- fuzzy -------------------------------------------------------------------------------------

function limited<T>(f: () => T): T {
  try {
    return f()
  } catch (e) {
    if (e instanceof fuzzy.FuzzyLimitError) throw libraryError(e.message)
    throw e
  }
}

global('normalizeName', 'rulestone_normalize_name_string', [STRING], STRING, ([a]) => fuzzy.normalizeName(s(a!)))
global('similarity', 'rulestone_similarity_string_string', [STRING, STRING], DECIMAL, ([a, b]) =>
  limited(() => fuzzy.similarity(s(a!), s(b!))),
)
global('nameSimilarity', 'rulestone_name_similarity_string_string', [STRING, STRING], DECIMAL, ([a, b]) =>
  limited(() => fuzzy.nameSimilarity(s(a!), s(b!))),
)
global('levenshtein', 'rulestone_levenshtein_string_string', [STRING, STRING], INT, ([a, b]) =>
  limited(() => BigInt(fuzzy.levenshtein(s(a!), s(b!)))),
)
global('maxNameSimilarity', 'rulestone_max_name_similarity_string_list', [STRING, list(STRING)], DECIMAL, ([a, b]) =>
  limited(() => fuzzy.maxNameSimilarity(s(a!), lst(b!) as readonly string[])),
)

// --- aggregates --------------------------------------------------------------------------------

// list.sum(), list.sum(zero), list.min(), list.max() and list.avg(). The parser marks these calls
// as aggregates, and the interpreter counts every element towards the iteration budget first.

function nonEmpty(items: readonly Value[], fn: string): void {
  if (items.length === 0) throw libraryError(`${fn}() of an empty list`)
}

function exactSum(items: readonly Value[]): bigint {
  return items.reduce<bigint>((t, v) => t + i(v), 0n)
}

/** The exact total; fails only if the total itself is beyond 64 bits. */
function sumInt(items: readonly Value[]): bigint {
  const total = exactSum(items)
  if (total < INT64_MIN || total > INT64_MAX) throw libraryError('sum() result is out of int range')
  return total
}

function sumDecimal(items: readonly Value[]): Decimal {
  let total = dec(items[0]!)
  for (let k = 1; k < items.length; k++) total = total.add(dec(items[k]!))
  return total
}

/** Sums the amounts, requiring every element to have the currency of `reference`. */
function sumMoney(items: readonly Value[], reference: Money): Money {
  let total: Decimal | undefined
  for (const item of items) {
    sameCurrency(reference, mon(item))
    total = total === undefined ? mon(item).amount : total.add(mon(item).amount)
  }
  return Money.of(total!, reference.currency)
}

/** The smallest (min) or largest element; the first of equal ones. */
function extreme(items: readonly Value[], fn: 'min' | 'max'): Value {
  nonEmpty(items, fn)
  let best = items[0]!
  for (let k = 1; k < items.length; k++) {
    const item = items[k]!
    let c: number
    if (typeof item === 'bigint') c = cmp(item, i(best))
    else if (item instanceof Decimal) c = item.compareTo(dec(best))
    else c = sameCurrency(mon(best), mon(item)).amount.compareTo(mon(best).amount)
    if (fn === 'min' ? c < 0 : c > 0) best = item
  }
  return best
}

const count = (items: readonly Value[]): Decimal => Decimal.fromBigInt(BigInt(items.length))
const EMPTY_MONEY_SUM = "sum() of an empty money list has no currency; give the result for an empty list, e.g. sum(money(0, 'VND'))"

member('sum', 'rulestone_sum_list_int', [list(INT)], INT, ([a]) => sumInt(lst(a!)))
member('sum', 'rulestone_sum_list_decimal', [list(DECIMAL)], DECIMAL, ([a]) =>
  lst(a!).length === 0 ? Decimal.ZERO : sumDecimal(lst(a!)),
)
member('sum', 'rulestone_sum_list_money', [list(MONEY)], MONEY, ([a]) => {
  const items = lst(a!)
  if (items.length === 0) throw libraryError(EMPTY_MONEY_SUM)
  return sumMoney(items, mon(items[0]!))
})
member('sum', 'rulestone_sum_list_int_int', [list(INT), INT], INT, ([a, z]) => (lst(a!).length === 0 ? z! : sumInt(lst(a!))))
member('sum', 'rulestone_sum_list_decimal_decimal', [list(DECIMAL), DECIMAL], DECIMAL, ([a, z]) =>
  lst(a!).length === 0 ? z! : sumDecimal(lst(a!)),
)
member('sum', 'rulestone_sum_list_money_money', [list(MONEY), MONEY], MONEY, ([a, z]) =>
  lst(a!).length === 0 ? z! : sumMoney(lst(a!), mon(z!)),
)
for (const fn of ['min', 'max'] as const) {
  member(fn, `rulestone_${fn}_list_int`, [list(INT)], INT, ([a]) => extreme(lst(a!), fn))
  member(fn, `rulestone_${fn}_list_decimal`, [list(DECIMAL)], DECIMAL, ([a]) => extreme(lst(a!), fn))
  member(fn, `rulestone_${fn}_list_money`, [list(MONEY)], MONEY, ([a]) => extreme(lst(a!), fn))
}
member('avg', 'rulestone_avg_list_int', [list(INT)], DECIMAL, ([a]) => {
  const items = lst(a!)
  nonEmpty(items, 'avg')
  return Decimal.fromBigInt(exactSum(items)).divide(count(items))
})
member('avg', 'rulestone_avg_list_decimal', [list(DECIMAL)], DECIMAL, ([a]) => {
  const items = lst(a!)
  nonEmpty(items, 'avg')
  return sumDecimal(items).divide(count(items))
})
member('avg', 'rulestone_avg_list_money', [list(MONEY)], MONEY, ([a]) => {
  const items = lst(a!)
  nonEmpty(items, 'avg')
  const total = sumMoney(items, mon(items[0]!))
  return Money.of(total.amount.divide(count(items)), total.currency)
})
