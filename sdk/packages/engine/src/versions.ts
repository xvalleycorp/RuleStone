// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Which version of a decision is in force on a date, as DecisionVersions in Java.
//
// Versions of one decision are ordered by effectiveFrom; a version without one starts at the
// beginning of time. A version stays in force until the earlier of its own effectiveTo, if it
// has one, and the effectiveFrom of the next version. Adding a version therefore never
// requires editing, and re-hashing, the previous one.

import type { LocalDate } from './date.js'
import { DecisionNotFoundError } from './errors.js'
import { tableRef, type DecisionTable } from './table.js'

/** Sorted by effective start, a version without effectiveFrom first; stable, as Java's List.sort. */
function sorted(versions: readonly DecisionTable[]): DecisionTable[] {
  return [...versions].sort((a, b) => {
    if (a.effectiveFrom === undefined) return b.effectiveFrom === undefined ? 0 : -1
    if (b.effectiveFrom === undefined) return 1
    return a.effectiveFrom.compareTo(b.effectiveFrom)
  })
}

/**
 * Problems with the versions of one decision, with the same messages and order as Java:
 * duplicate versions, two versions taking effect on the same day (or both without
 * effectiveFrom), and an effectiveTo after the next version's effectiveFrom.
 */
export function versionProblems(versions: readonly DecisionTable[]): string[] {
  const all = sorted(versions)
  const problems: string[] = []
  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      if (all[i]!.version === all[j]!.version) problems.push(`${tableRef(all[i]!)}: version registered more than once`)
    }
  }
  for (let i = 0; i + 1 < all.length; i++) {
    const current = all[i]!
    const next = all[i + 1]!
    if (current.version === next.version) continue // already reported
    const sameStart =
      current.effectiveFrom === undefined
        ? next.effectiveFrom === undefined
        : next.effectiveFrom !== undefined && current.effectiveFrom.compareTo(next.effectiveFrom) === 0
    if (sameStart) {
      const when = current.effectiveFrom === undefined ? 'from the beginning (no effectiveFrom)' : `on ${current.effectiveFrom.toString()}`
      problems.push(
        `${tableRef(current)} and ${tableRef(next)}: both take effect ${when}, so neither supersedes the other; ` +
          'give the later version a later effectiveFrom',
      )
    } else if (current.effectiveTo !== undefined && next.effectiveFrom!.compareTo(current.effectiveTo) < 0) {
      const to = current.effectiveTo.toString()
      const from = next.effectiveFrom!.toString()
      problems.push(
        `${tableRef(current)} and ${tableRef(next)}: ${tableRef(current)} is in force until ${to} (effectiveTo), ` +
          `after ${tableRef(next)} takes effect on ${from}; remove effectiveTo from ${tableRef(current)} or set it to ${from}`,
      )
    }
  }
  return problems
}

/**
 * The version in force on date: the one with the latest effectiveFrom on or before date,
 * unless its own effectiveTo has passed.
 */
export function versionInForce<T extends DecisionTable>(versions: readonly T[], date: LocalDate): T | undefined {
  let candidate: T | undefined
  for (const table of sorted(versions) as T[]) {
    if (table.effectiveFrom !== undefined && table.effectiveFrom.compareTo(date) > 0) break
    candidate = table
  }
  if (candidate === undefined || (candidate.effectiveTo !== undefined && date.compareTo(candidate.effectiveTo) >= 0)) {
    return undefined
  }
  return candidate
}

/**
 * The version an evaluation uses: `version` if given, else the version in force on date.
 * Throws DecisionNotFoundError with Java's messages.
 */
export function selectVersion<T extends DecisionTable>(
  id: string,
  versions: readonly T[],
  version: string | undefined,
  date: LocalDate,
): T {
  if (version !== undefined) {
    const found = versions.find((t) => t.version === version)
    if (!found) throw new DecisionNotFoundError(`decision '${id}' has no version '${version}'`)
    return found
  }
  const found = versionInForce(versions, date)
  if (!found) throw new DecisionNotFoundError(`no version of decision '${id}' is effective on ${date.toString()}`)
  return found
}
