//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.List;

/**
 * The extension point for anchoring. A chain proves that entries were not altered only to someone who
 * holds a later hash; an anchor puts the head hash out of the reach of whoever can rewrite the whole
 * log. A later step adds signed checkpoints, RFC 3161 timestamps and {@code ANCHORED} events; each
 * implements this interface to compare the head of the log with the latest anchor.
 */
@FunctionalInterface
public interface AnchorCheck {

    /** Checks nothing: no anchor is configured. */
    AnchorCheck NONE = log -> List.of();

    /**
     * Compares the log with the latest anchor. Called after the chain verified.
     *
     * @return the problems found, such as a head that differs from the anchored one or a log shorter than
     *         the anchored sequence; empty if the log matches
     */
    List<String> check(List<LogEntry> log);
}
