//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * An evaluation took longer than its timeout ({@link DecisionRef#timeout(Duration)}, else the
 * engine's default) and was cut short. Like any {@link DecisionEvaluationException}, the
 * decision produced no result; the caller decides what happens instead, e.g. a fallback
 * policy that does not depend on this decision.
 *
 * <p>The engine checks the time before each rule, so a single rule is never interrupted;
 * the evaluation can overrun the timeout by the time one rule takes.
 */
public final class DecisionTimeoutException extends DecisionEvaluationException {

    private static final long serialVersionUID = 1L;

    private final Duration timeout;
    /** Not serialized: a deserialized exception has an empty trace. */
    private final transient List<TraceStep> trace;

    /**
     * @param trace the rules examined before the time ran out, in evaluation order
     */
    public DecisionTimeoutException(String decisionId, String ruleVersion, Duration timeout, List<TraceStep> trace) {
        super(decisionId, ruleVersion, "evaluation timed out after " + format(timeout) + " with "
                + Objects.requireNonNull(trace, "trace").size() + " rule(s) examined", null);
        this.timeout = timeout;
        this.trace = List.copyOf(trace);
    }

    /** The timeout that was exceeded. */
    public Duration timeout() {
        return timeout;
    }

    /** The rules examined before the time ran out, in evaluation order. */
    public List<TraceStep> trace() {
        return trace == null ? List.of() : trace;
    }

    private static String format(Duration timeout) {
        long nanos = timeout.toNanos();
        if (nanos % 1_000_000 == 0) {
            return nanos / 1_000_000 + " ms";
        }
        return nanos % 1_000 == 0 ? nanos / 1_000 + " µs" : nanos + " ns";
    }
}
