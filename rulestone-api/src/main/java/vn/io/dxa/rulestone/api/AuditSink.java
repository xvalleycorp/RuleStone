//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * Receives the {@link DecisionRecord} of every evaluation, successful or not.
 *
 * <p>The engine calls each sink synchronously, on the evaluating thread, before it returns
 * the result or throws. A sink that needs to be fast can hand records to a queue of its
 * own. Implementations must be thread-safe.
 *
 * <p>If a sink throws, the evaluation fails with an {@link AuditException} and the caller
 * gets no result: a decision is never returned without its record having been accepted.
 */
@FunctionalInterface
public interface AuditSink {

    /** Accepts one record. */
    void write(DecisionRecord record);
}
