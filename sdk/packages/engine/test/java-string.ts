// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { Decimal, LocalDate, Money, type Value } from '../src/index.js'

/** A result value as Java's String.valueOf prints it: BigDecimal.toString, List.toString. */
export function javaString(value: Value): string {
  if (value instanceof Decimal) return value.toString()
  if (value instanceof Money || value instanceof LocalDate) return value.toString()
  if (Array.isArray(value)) return '[' + (value as readonly Value[]).map(javaString).join(', ') + ']'
  return String(value)
}
