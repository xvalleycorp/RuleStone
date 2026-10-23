// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { Decimal } from './decimal.js'
import { ISO4217_AMENDMENT, ISO4217_ENTRIES } from './iso4217.js'

export { ISO4217_AMENDMENT }

let table: Map<string, number> | undefined

function minorUnitTable(): Map<string, number> {
  if (!table) {
    table = new Map()
    for (const entry of ISO4217_ENTRIES.split(' ')) {
      const digits = entry.slice(3)
      table.set(entry.slice(0, 3), digits === '-' ? -1 : Number(digits))
    }
  }
  return table
}

/** Whether code is an upper-case ISO 4217 code in RuleStone's table, e.g. "VND". */
export function isCurrencyCode(code: string): boolean {
  return minorUnitTable().has(code)
}

/**
 * The digits after the decimal point of the currency's minor unit: 0 for VND, 2 for USD;
 * undefined for a code without a minor unit, such as XAU.
 * @throws RangeError if code is not in the table
 */
export function minorUnits(code: string): number | undefined {
  const digits = minorUnitTable().get(code)
  if (digits === undefined) throw new RangeError('not an ISO 4217 currency code')
  return digits < 0 ? undefined : digits
}

/** Every code with its minor units (-1 for none), in alphabetical order. */
export function currencyTable(): ReadonlyMap<string, number> {
  return minorUnitTable()
}

/**
 * An exact amount in an ISO 4217 currency, like vn.io.dxa.rulestone.api.Money. The amount
 * keeps its scale; money is never rounded implicitly. Immutable.
 */
export class Money {
  private constructor(
    readonly amount: Decimal,
    readonly currency: string,
  ) {}

  /** @throws RangeError if currency is not an upper-case ISO 4217 code */
  static of(amount: Decimal, currency: string): Money {
    if (!isCurrencyCode(currency)) {
      // The text is not quoted: it may come from customer data.
      throw new RangeError('currency must be an ISO 4217 code in upper case, e.g. VND')
    }
    return new Money(amount, currency)
  }

  /** Numeric equality in the same currency: 1.0 VND equals 1.00 VND. */
  equals(other: Money): boolean {
    return this.currency === other.currency && this.amount.equals(other.amount)
  }

  /** The amount in plain notation and the code, e.g. "1500000.50 VND". */
  toString(): string {
    return `${this.amount.toPlainString()} ${this.currency}`
  }
}
