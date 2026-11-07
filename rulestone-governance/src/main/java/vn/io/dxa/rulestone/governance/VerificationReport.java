//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The result of {@link GovernanceVerifier}: what was checked and every problem found, a report an
 * auditor can keep. It names log positions, change sets, statements, releases, versions and
 * principals, never rule content.
 *
 * @param entries    how many log entries were read
 * @param statements how many approval statements were read
 * @param releases   how many releases were read
 * @param problems   the problems, in the order found; the chain of the log comes first
 */
public record VerificationReport(long entries, int statements, int releases, List<Problem> problems) {

    public VerificationReport {
        problems = List.copyOf(problems);
    }

    /**
     * One problem.
     *
     * @param seq     the log entry where the check failed, if it failed at one
     * @param message what is wrong
     */
    public record Problem(OptionalLong seq, String message) {

        public Problem {
            Objects.requireNonNull(seq, "seq");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return seq.isPresent() ? "entry " + seq.getAsLong() + ": " + message : message;
        }
    }

    /** Whether nothing is wrong. */
    public boolean ok() {
        return problems.isEmpty();
    }

    /** The report as text: the counts, then {@code OK} or each problem on its own line. */
    public String format() {
        StringBuilder text = new StringBuilder();
        text.append("log entries: ").append(entries).append('\n');
        text.append("approval statements: ").append(statements).append('\n');
        text.append("releases: ").append(releases).append('\n');
        if (ok()) {
            text.append("OK: the log, the statements and the releases verify\n");
        } else {
            text.append("FAILED: ").append(problems.size()).append(problems.size() == 1 ? " problem\n" : " problems\n");
            problems.forEach(p -> text.append("  - ").append(p).append('\n'));
        }
        return text.toString();
    }
}
