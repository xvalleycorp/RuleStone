//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * The governance service core: {@link vn.io.dxa.rulestone.governance.GovernanceService} runs the
 * lifecycle of rule artifacts, change sets and maker-checker with approval policies, issues signed
 * approval statements and releases that engines built with {@code requireApproved} and
 * {@code requireRelease} accept, and writes every event to a hash-chained log that
 * {@link vn.io.dxa.rulestone.governance.GovernanceVerifier} checks.
 */
package vn.io.dxa.rulestone.governance;
