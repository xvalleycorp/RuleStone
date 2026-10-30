//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * The outcome of a {@code model} node: {@link #OK}, or the kind of failure. Every failure leads to
 * the node's {@code onFailure} path; a model failure never fails the evaluation.
 */
public enum ModelStatus {
    /** The model's result met the node's contract, and later nodes read its outputs. */
    OK,
    /** The model did not answer within the node's {@code timeoutMs}, or the caller reports a timeout. */
    TIMEOUT,
    /** The invoker threw an exception, or the caller reports that the model failed. */
    ERROR,
    /**
     * The result broke the node's contract: another model id or version answered, an output was
     * missing, undeclared or of the wrong type, or the node sets {@code minConfidence} and the
     * result reports no confidence.
     */
    CONTRACT_VIOLATION,
    /** The node's {@code valid} condition is false for the outputs, e.g. a probability above 1. */
    INVALID_OUTPUT,
    /** The model reported a confidence below the node's {@code minConfidence}. */
    LOW_CONFIDENCE,
    /** The model's result should have come with the facts, and did not. */
    MISSING;

    /** Whether this is a failure: anything but {@link #OK}. */
    public boolean failed() {
        return this != OK;
    }
}
