//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * How long one evaluation may take. Measured with a monotonic clock ({@link System#nanoTime()}
 * by default), never the engine clock: the timeout bounds the time an evaluation takes, it never
 * influences its result.
 */
final class Deadline {

    /** No timeout. */
    static final Deadline NONE = new Deadline(null, 0, 0, null);

    private final LongSupplier nanoTime;
    private final long start;
    private final long nanos;
    private final Duration timeout;

    private Deadline(LongSupplier nanoTime, long start, long nanos, Duration timeout) {
        this.nanoTime = nanoTime;
        this.start = start;
        this.nanos = nanos;
        this.timeout = timeout;
    }

    /** A deadline {@code timeout} from now. */
    static Deadline after(Duration timeout, LongSupplier nanoTime) {
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            return NONE;   // more than 292 years
        }
        return new Deadline(nanoTime, nanoTime.getAsLong(), nanos, timeout);
    }

    /** Whether the time is up. */
    boolean passed() {
        // Compare elapsed time, not instants, so that the check is correct when nanoTime wraps.
        return this != NONE && nanoTime.getAsLong() - start > nanos;
    }

    Duration timeout() {
        return timeout;
    }

    /** The time left, never negative; {@code max} when there is no timeout or more than {@code max} is left. */
    Duration remaining(Duration max) {
        if (this == NONE) {
            return max;
        }
        long left = nanos - (nanoTime.getAsLong() - start);
        return left <= 0 ? Duration.ZERO : left < max.toNanos() ? Duration.ofNanos(left) : max;
    }
}
