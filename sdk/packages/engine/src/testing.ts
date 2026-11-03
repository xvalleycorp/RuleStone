// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * Test cases for decision tables and flows: reads `.stone-test.json` files and checks an
 * engine's results against them. The same file format as `rulestone-test` in Java. Kept apart
 * from the main entry point, which applications load in production.
 *
 * @packageDocumentation
 */

export { parseStoneTests, checkStoneTestCase, StoneTestFailure, InvalidStoneTestError } from './stone-test.js'
export type { StoneTestCase, StoneTestOutcome } from './stone-test.js'
export { ApprovalIssuer, approvalSubjects } from './approval-issuer.js'
