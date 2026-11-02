//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.nio.file.Path;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionComparison;
import vn.io.dxa.rulestone.api.DecisionComparison.Difference;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionRecordJson;

/**
 * Asserts that recorded decisions still replay exactly, e.g. golden records kept with the rules, so
 * that a build fails when a release would no longer reproduce them. Works with any test framework: a
 * failed check throws {@link AssertionError}.
 *
 * <pre>{@code
 * ReplayCheck.assertReplays(engine, Path.of("golden/loan-2027-000042.record.json"), facts);
 * }</pre>
 *
 * <p>Failure messages list the differences, which name outputs, nodes, rules and versions, never
 * input or output values.
 */
public final class ReplayCheck {

    private ReplayCheck() {}

    /**
     * Replays {@code record} on {@code facts} and fails unless it matches exactly.
     *
     * @return the comparison, for further assertions
     * @throws AssertionError listing the differences, if there are any
     */
    public static DecisionComparison assertReplays(RuleStoneEngine engine, DecisionRecord record, Facts facts) {
        Objects.requireNonNull(engine, "engine");
        DecisionComparison replay = engine.replay(record, facts);
        if (!replay.matches()) {
            StringBuilder message = new StringBuilder("decision record ").append(record.recordId())
                    .append(" of ").append(record.decision()).append(" does not replay exactly:");
            for (Difference difference : replay.differences()) {
                message.append("\n  - ").append(difference);
            }
            throw new AssertionError(message.toString());
        }
        return replay;
    }

    /**
     * Replays the record in {@code recordFile}, as {@code DecisionRecordJson.write} wrote it, on
     * {@code facts}; see {@link #assertReplays(RuleStoneEngine, DecisionRecord, Facts)}.
     */
    public static DecisionComparison assertReplays(RuleStoneEngine engine, Path recordFile, Facts facts) {
        return assertReplays(engine, DecisionRecordJson.read(recordFile), facts);
    }
}
