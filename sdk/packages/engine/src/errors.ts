// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Errors, with the messages of the Java engine's exceptions. Messages name decisions, rules,
// inputs, fields and currency codes, never input values.

import type { DecisionRecord } from './record.js'

/** An expression cannot be compiled or evaluated, as vn.io.dxa.rulestone.cel.ExpressionException. */
export class ExpressionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ExpressionError'
  }
}

/** A value does not match its declared type, as InvalidValueException. */
export class InvalidValueError extends ExpressionError {
  constructor(
    /** Location of the offending value, e.g. applicant.accounts[2].balance */
    readonly path: string,
    problem: string,
  ) {
    super(`${path}: ${problem}`)
    this.name = 'InvalidValueError'
  }
}

/** Base of the errors the engine raises, as RuleStoneException. */
export class RuleStoneError extends Error {
  /** The decision record of the failed evaluation, when the error comes from evaluate(). */
  record: DecisionRecord | undefined

  constructor(message: string) {
    super(message)
    this.name = 'RuleStoneError'
  }
}

/** A decision definition is malformed or ill-typed, as InvalidDecisionException. Lists every problem. */
export class InvalidDecisionError extends RuleStoneError {
  readonly problems: readonly string[]

  constructor(problems: readonly string[]) {
    super(
      `invalid decision definition (${problems.length} ${problems.length === 1 ? 'problem' : 'problems'}):` +
        problems.map((p) => '\n  - ' + p.replaceAll('\n', '\n    ')).join(''),
    )
    this.name = 'InvalidDecisionError'
    this.problems = [...problems]
  }
}

/** JSON facts or values do not match the declared types, as InvalidFactsException. */
export class InvalidFactsError extends RuleStoneError {
  readonly problems: readonly string[]

  constructor(problems: readonly string[]) {
    if (problems.length === 0) throw new RangeError('problems must not be empty')
    super(
      `invalid JSON values (${problems.length} ${problems.length === 1 ? 'problem' : 'problems'}):` +
        problems.map((p) => '\n  - ' + p).join(''),
    )
    this.name = 'InvalidFactsError'
    this.problems = [...problems]
  }
}

/** No version of the requested decision matches the reference, as DecisionNotFoundException. */
export class DecisionNotFoundError extends RuleStoneError {
  constructor(message: string) {
    super(message)
    this.name = 'DecisionNotFoundError'
  }
}

/** A decision could not be evaluated, as DecisionEvaluationException. No result was produced. */
export class DecisionEvaluationError extends RuleStoneError {
  constructor(
    readonly decisionId: string,
    readonly ruleVersion: string,
    message: string,
    options?: { cause?: unknown },
  ) {
    super(`${decisionId}@${ruleVersion}: ${message}`)
    this.name = 'DecisionEvaluationError'
    if (options?.cause !== undefined) (this as { cause?: unknown }).cause = options.cause
  }
}

/** An evaluation took longer than its timeout, as DecisionTimeoutException. */
export class DecisionTimeoutError extends DecisionEvaluationError {
  constructor(
    decisionId: string,
    ruleVersion: string,
    /** The timeout that passed, in milliseconds. */
    readonly timeoutMs: number,
    /** The rules examined before the time ran out, in evaluation order. */
    readonly trace: readonly { readonly ruleId: string; readonly matched: boolean }[],
  ) {
    super(decisionId, ruleVersion, `evaluation timed out after ${formatTimeout(timeoutMs)} with ${trace.length} rule(s) examined`)
    this.name = 'DecisionTimeoutError'
  }
}

/** A timeout as Java's DecisionTimeoutException writes a Duration: ms, µs or ns. */
function formatTimeout(timeoutMs: number): string {
  const nanos = Math.round(timeoutMs * 1_000_000)
  if (nanos % 1_000_000 === 0) return `${nanos / 1_000_000} ms`
  return nanos % 1_000 === 0 ? `${nanos / 1_000} µs` : `${nanos} ns`
}

/** An audit sink failed, so the evaluation failed, as AuditException. */
export class AuditError extends RuleStoneError {
  constructor(
    readonly recordId: string,
    cause: unknown,
  ) {
    super(
      `audit sink rejected decision record ${recordId} (${cause instanceof Error ? cause.name : typeof cause}); the decision was not returned`,
    )
    this.name = 'AuditError'
    ;(this as { cause?: unknown }).cause = cause
  }
}
