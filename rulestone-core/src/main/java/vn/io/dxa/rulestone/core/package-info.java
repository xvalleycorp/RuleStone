//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Decision tables and the embedded engine.
 *
 * <ul>
 *   <li>{@link vn.io.dxa.rulestone.core.DecisionTable} and {@link vn.io.dxa.rulestone.core.Rule}
 *       model a versioned, effective-dated decision table.</li>
 *   <li>{@link vn.io.dxa.rulestone.core.DecisionTableJson} reads and writes the
 *       {@code .stone.json} format.</li>
 *   <li>{@link vn.io.dxa.rulestone.core.RuleStone#builder()} compiles tables into an immutable,
 *       thread-safe {@link vn.io.dxa.rulestone.api.RuleStoneEngine}.</li>
 * </ul>
 *
 * <p>Every problem in a definition is reported when the engine is built, through
 * {@link vn.io.dxa.rulestone.core.InvalidDecisionException}, never while evaluating.
 */
package vn.io.dxa.rulestone.core;
