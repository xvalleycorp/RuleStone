//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * The public API for evaluating decisions.
 *
 * <p>Application code depends on these types only: obtain a
 * {@link vn.io.dxa.rulestone.api.RuleStoneEngine} (for an embedded engine, from
 * {@code vn.io.dxa.rulestone.core.RuleStone}), name a decision with a
 * {@link vn.io.dxa.rulestone.api.DecisionRef}, pass inputs as
 * {@link vn.io.dxa.rulestone.api.Facts}, and read the
 * {@link vn.io.dxa.rulestone.api.DecisionResult}.
 *
 * <p>All exceptions extend {@link vn.io.dxa.rulestone.api.RuleStoneException}. An evaluation
 * either returns a complete result or throws; it never returns a partial or guessed outcome.
 *
 * <p>This package depends on the JDK only.
 */
package vn.io.dxa.rulestone.api;
