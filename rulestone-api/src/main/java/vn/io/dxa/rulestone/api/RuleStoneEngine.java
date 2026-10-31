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

    /**
     * Explains a decision record in one language, with the messages of the reason-code catalog
     * the engine was built with. The explanation depends only on the record, the catalog and the
     * locale, so the same arguments always give the same explanation, also for a record of an
     * earlier evaluation or of another engine.
     *
     * @param record the record to explain, e.g. {@link DecisionResult#record()}
     * @param locale one of the catalog's locales, e.g. {@code en} or {@code vi}
     * @throws IllegalArgumentException if the catalog has no such locale
     * @throws UnsupportedOperationException if the engine was built without a reason-code catalog
     */
    default Explanation explain(DecisionRecord record, String locale) {
        throw new UnsupportedOperationException("this engine has no reason-code catalog");
    }
}
