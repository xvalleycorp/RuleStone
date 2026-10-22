//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * Evaluates decisions. Implementations are thread-safe and deterministic: the same
 * {@link DecisionRef} (with a resolved version) and the same {@link Facts} always
 * produce the same {@link DecisionResult}.
 */
public interface RuleStoneEngine {

    DecisionResult evaluate(DecisionRef ref, Facts facts);
}
