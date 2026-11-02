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

    /**
     * Re-runs a recorded decision on its original facts and compares the outcome with the record.
     *
     * <p>The evaluation uses exactly what the record names: the decision version, the business
     * date, the parameter set versions and, in a flow, the version of every table step. Every
     * {@code model} node takes the result its step in the record shows, so no model is called, even
     * one that has been retrained or retired. The facts are checked against the record's
     * {@code factsHash}, with the engine's {@code factsHashKey}. Nothing is written to the audit
     * sinks, no canary or shadow version is used, and no timeout applies.
     *
     * @param record the record of the decision, e.g. read back with {@code DecisionRecordJson.read}
     * @param facts  the facts the decision was made on; the record holds none. They must not carry
     *               model results: those come from the record
     * @return the comparison; {@link DecisionComparison#matches()} when the decision was reproduced exactly
     * @throws DecisionNotFoundException if a version the record names, of the decision, of a table it
     *         used or of a parameter set, is not registered with exactly the recorded content hash
     * @throws IllegalArgumentException if the record names no decision version, because none was found
     *         when it was made, or the facts carry model results
     * @throws UnsupportedOperationException if the engine cannot replay decisions
     */
    default DecisionComparison replay(DecisionRecord record, Facts facts) {
        throw new UnsupportedOperationException("this engine cannot replay decisions");
    }

    /**
     * Evaluates the facts of a recorded decision again, with the version {@code target} selects, and
     * compares the outcome with the record: for complaint investigations ("what would we decide
     * today?") and for measuring the impact of a change of rules, parameters or models.
     *
     * <p>{@code target} selects the version, business date and parameter set versions as for
     * {@link #evaluate}: e.g. {@code DecisionRef.of(id).asOf(record.businessDate())} for the version in
     * force on the original business date, {@code DecisionRef.of(id)} for today's, or
     * {@code DecisionRef.of(id).version("5")}. Without a correlation id of its own, it takes the
     * record's. No canary or shadow version is used, and nothing is written to the audit sinks. The
     * facts are first checked against the record's {@code factsHash}, with the recorded version, which
     * must therefore still be registered; facts that do not match give a {@link DecisionComparison.Kind#FACTS}
     * difference.
     *
     * @param record the record of the original decision
     * @param facts  the facts the decision was made on, and optionally model results for
     *               {@link ModelSource#RECORDED} or for {@code supplied} nodes
     * @param target the decision to evaluate instead; its id must be the record's decision
     * @param models where model nodes get their results
     * @return the comparison of the new evaluation with the record
     * @throws DecisionNotFoundException if the recorded version, or the version {@code target} selects,
     *         is not registered
     * @throws IllegalArgumentException if {@code target} names another decision, or the record names no version
     * @throws UnsupportedOperationException if the engine cannot re-score decisions
     */
    default DecisionComparison rescore(DecisionRecord record, Facts facts, DecisionRef target, ModelSource models) {
        throw new UnsupportedOperationException("this engine cannot re-score decisions");
    }
}
