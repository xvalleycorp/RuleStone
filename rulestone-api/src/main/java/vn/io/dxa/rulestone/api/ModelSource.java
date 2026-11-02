//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * Where the {@code model} nodes of a flow get their results when a recorded decision is re-scored
 * with {@link RuleStoneEngine#rescore}.
 */
public enum ModelSource {
    /**
     * The results the decision record holds, as for a replay: no model is called. A model node gets
     * the result supplied with the facts for it, if any; otherwise the result its step in the record
     * shows, if the step names the same model id and version; otherwise none, so that it fails with
     * {@link ModelStatus#MISSING} and takes its {@code onFailure} path. Use it to measure the impact
     * of a change of rules or parameters alone.
     */
    RECORDED,
    /**
     * The models as the engine runs them for {@link RuleStoneEngine#evaluate}: {@code invoke} nodes
     * call their invokers, and {@code supplied} nodes read the results supplied with the facts. Use it
     * to measure the impact of today's models. The models are called, but nothing is written to the
     * audit sinks.
     */
    LIVE
}
