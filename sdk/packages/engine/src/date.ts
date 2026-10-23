// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Calendar dates without time or time zone, with the arithmetic of java.time.LocalDate
// (proleptic ISO calendar, years -999999999 to 999999999).

const MIN_YEAR = -999_999_999
const MAX_YEAR = 999_999_999
const MIN_EPOCH_DAY = -365243219162
const MAX_EPOCH_DAY = 365241780471
const DAYS_0000_TO_1970 = 146097 * 5 - (30 * 365 + 7)

function isLeapYear(year: number): boolean {
  return (year & 3) === 0 && (year % 100 !== 0 || year % 400 === 0)
}

function lengthOfMonth(year: number, month: number): number {
  switch (month) {
    case 2:
      return isLeapYear(year) ? 29 : 28
    case 4:
    case 6:
    case 9:
    case 11:
      return 30
    default:
      return 31
  }
}

/** A date is outside the supported range, as java.time.DateTimeException. */
export class DateRangeError extends RangeError {
  constructor() {
    super('date outside the supported range')
    this.name = 'DateRangeError'
  }
}

/** A date in the ISO calendar, like java.time.LocalDate. Immutable. */
export class LocalDate {
  private constructor(
    readonly year: number,
    /** 1 to 12 */
    readonly month: number,
    /** 1 to 31 */
    readonly day: number,
  ) {}

  /** The date, or undefined if it does not exist (e.g. 2026-02-30) or is out of range. */
  static ofValid(year: number, month: number, day: number): LocalDate | undefined {
    if (!Number.isInteger(year) || year < MIN_YEAR || year > MAX_YEAR) return undefined
    if (!Number.isInteger(month) || month < 1 || month > 12) return undefined
    if (!Number.isInteger(day) || day < 1 || day > lengthOfMonth(year, month)) return undefined
    return new LocalDate(year, month, day)
  }

  /** @throws DateRangeError if the date does not exist */
  static of(year: number, month: number, day: number): LocalDate {
    const date = LocalDate.ofValid(year, month, day)
    if (!date) throw new DateRangeError()
    return date
  }

  /**
   * Parses ISO yyyy-MM-dd as LocalDate.parse does: four-digit years, or a sign and up to ten
   * digits ('+' only for more than four). Undefined for invalid text or an impossible date.
   */
  static parse(text: string): LocalDate | undefined {
    const m = /^(?:([+-])([0-9]{4,10})|([0-9]{4}))-([0-9]{2})-([0-9]{2})$/.exec(text)
    if (!m) return undefined
    let year: number
    if (m[3] !== undefined) {
      year = Number(m[3])
    } else {
      const digits = m[2]!
      if (m[1] === '+' && digits.length <= 4) return undefined
      year = Number(digits)
      if (m[1] === '-') {
        if (year === 0) return undefined
        year = -year
      }
    }
    return LocalDate.ofValid(year, Number(m[4]), Number(m[5]))
  }

  /** @throws DateRangeError if out of range */
  static ofEpochDay(epochDay: number): LocalDate {
    if (!Number.isSafeInteger(epochDay) || epochDay < MIN_EPOCH_DAY || epochDay > MAX_EPOCH_DAY) throw new DateRangeError()
    // Algorithm of java.time.LocalDate.ofEpochDay.
    let zeroDay = epochDay + DAYS_0000_TO_1970 - 60
    let adjust = 0
    if (zeroDay < 0) {
      const adjustCycles = Math.trunc((zeroDay + 1) / 146097) - 1
      adjust = adjustCycles * 400
      zeroDay += -adjustCycles * 146097
    }
    const doyOf = (y: number) => zeroDay - (365 * y + Math.trunc(y / 4) - Math.trunc(y / 100) + Math.trunc(y / 400))
    let yearEst = Math.trunc((400 * zeroDay + 591) / 146097)
    let doyEst = doyOf(yearEst)
    if (doyEst < 0) {
      yearEst--
      doyEst = doyOf(yearEst)
    }
    yearEst += adjust
    const marchMonth0 = Math.trunc((doyEst * 5 + 2) / 153)
    const month = ((marchMonth0 + 2) % 12) + 1
    const dom = doyEst - Math.trunc((marchMonth0 * 306 + 5) / 10) + 1
    yearEst += Math.trunc(marchMonth0 / 10)
    return LocalDate.of(yearEst, month, dom)
  }

  toEpochDay(): number {
    // Algorithm of java.time.LocalDate.toEpochDay.
    const y = this.year
    const m = this.month
    let total = 365 * y
    if (y >= 0) total += Math.trunc((y + 3) / 4) - Math.trunc((y + 99) / 100) + Math.trunc((y + 399) / 400)
    else total -= Math.trunc(y / -4) - Math.trunc(y / -100) + Math.trunc(y / -400)
    total += Math.trunc((367 * m - 362) / 12)
    total += this.day - 1
    if (m > 2) {
      total--
      if (!isLeapYear(y)) total--
    }
    return total - DAYS_0000_TO_1970
  }

  /** 1 = Monday to 7 = Sunday. */
  dayOfWeek(): number {
    const d = this.toEpochDay() + 3
    return d - Math.floor(d / 7) * 7 + 1
  }

  /** @throws DateRangeError if the result is out of range */
  plusDays(days: bigint): LocalDate {
    if (days === 0n) return this
    const result = BigInt(this.toEpochDay()) + days
    if (result < BigInt(MIN_EPOCH_DAY) || result > BigInt(MAX_EPOCH_DAY)) throw new DateRangeError()
    return LocalDate.ofEpochDay(Number(result))
  }

  /** Adds months, clamping the day to the end of the month. @throws DateRangeError */
  plusMonths(months: bigint): LocalDate {
    if (months === 0n) return this
    const calc = BigInt(this.year) * 12n + BigInt(this.month - 1) + months
    const year = floorDivBig(calc, 12n)
    if (year < BigInt(MIN_YEAR) || year > BigInt(MAX_YEAR)) throw new DateRangeError()
    return LocalDate.clamped(Number(year), Number(calc - year * 12n) + 1, this.day)
  }

  /** Adds years, clamping 29 February. @throws DateRangeError */
  plusYears(years: bigint): LocalDate {
    if (years === 0n) return this
    const year = BigInt(this.year) + years
    if (year < BigInt(MIN_YEAR) || year > BigInt(MAX_YEAR)) throw new DateRangeError()
    return LocalDate.clamped(Number(year), this.month, this.day)
  }

  private static clamped(year: number, month: number, day: number): LocalDate {
    return LocalDate.of(year, month, Math.min(day, lengthOfMonth(year, month)))
  }

  private prolepticMonth(): number {
    return this.year * 12 + this.month - 1
  }

  /** Whole days from this date to end (ChronoUnit.DAYS.between). */
  daysUntil(end: LocalDate): number {
    return end.toEpochDay() - this.toEpochDay()
  }

  /** Complete months from this date to end (ChronoUnit.MONTHS.between). */
  monthsUntil(end: LocalDate): number {
    const packed1 = this.prolepticMonth() * 32 + this.day
    const packed2 = end.prolepticMonth() * 32 + end.day
    return Math.trunc((packed2 - packed1) / 32)
  }

  /** Complete years from this date to end (ChronoUnit.YEARS.between). */
  yearsUntil(end: LocalDate): number {
    return Math.trunc(this.monthsUntil(end) / 12)
  }

  compareTo(other: LocalDate): number {
    return this.year - other.year || this.month - other.month || this.day - other.day
  }

  equals(other: LocalDate): boolean {
    return this.compareTo(other) === 0
  }

  /** ISO text as LocalDate.toString(): 2026-11-01, -0001-01-01, +10000-01-01. */
  toString(): string {
    const absYear = Math.abs(this.year)
    const year =
      absYear < 1000 ? (this.year < 0 ? '-' : '') + String(absYear).padStart(4, '0') : (this.year > 9999 ? '+' : '') + String(this.year)
    return `${year}-${String(this.month).padStart(2, '0')}-${String(this.day).padStart(2, '0')}`
  }
}

function floorDivBig(a: bigint, b: bigint): bigint {
  const q = a / b
  return a % b !== 0n && a < 0n !== b < 0n ? q - 1n : q
}

/** The date of an instant in a time zone (IANA name), or in the runtime's default zone. */
export function dateInZone(instant: Date, timeZone?: string): LocalDate {
  const parts = new Intl.DateTimeFormat('en-US', {
    ...(timeZone !== undefined ? { timeZone } : {}),
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    era: 'short',
  }).formatToParts(instant)
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  let year = Number(get('year').replace(/[^0-9]/g, ''))
  if (/^B/i.test(get('era'))) year = 1 - year
  return LocalDate.of(year, Number(get('month')), Number(get('day')))
}

/** An instant as java.time.Instant.toString() prints it, to the millisecond. */
export function instantToString(instant: Date): string {
  const iso = instant.toISOString()
  return iso.endsWith('.000Z') ? iso.slice(0, -5) + 'Z' : iso
}
