//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A recorded decision compared with a new evaluation of the same facts: the result of
 * {@link RuleStoneEngine#replay} and {@link RuleStoneEngine#rescore}.
 *
 * <p>The descriptions of the differences contain no input or output values, only names, versions,
 * dates, statuses, rule ids, node ids and reason codes, so that they can be logged like a record.
 * Compare the values of {@link #recorded()} and {@link #evaluated()} where they are needed.
 *
 * @param recorded    the record of the original decision
 * @param evaluated   the record of the new evaluation. It was not handed to any audit sink and must
 *                    not be stored as a decision: it has a new record id and the instant of the new
 *                    evaluation, and otherwise the fields of an ordinary record
 * @param result      the result of the new evaluation; empty if it failed
 * @param differences what differs, in the order of the record's fields; empty if nothing does
 */
public record DecisionComparison(DecisionRecord recorded, DecisionRecord evaluated, Optional<DecisionResult> result,
                                 List<Difference> differences) {

    /** What part of a decision differs. */
    public enum Kind {
        /** The facts do not give the record's {@code factsHash}: they are not the facts it was made on. */
        FACTS,
        /** The status, or the error of a failed evaluation. */
        STATUS,
        /** The exact version of the decision. */
        VERSION,
        /** The business date. */
        BUSINESS_DATE,
        /** The parameter set versions used. */
        PARAMS,
        /** An output. */
        OUTPUT,
        /** The reason codes, the nodes they came from, or their order. */
        REASON_CODES,
        /** A step of the path: a node, a table version, the matched rules, a branch or a model's result. */
        PATH
    }

    /**
     * One difference.
     *
     * @param kind        what differs
     * @param description what differs, without input or output values, e.g. {@code output 'limit' differs}
     */
    public record Difference(Kind kind, String description) {

        public Difference {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(description, "description");
        }

        @Override
        public String toString() {
            return kind + ": " + description;
        }
    }

    public DecisionComparison {
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(evaluated, "evaluated");
        Objects.requireNonNull(result, "result");
        differences = List.copyOf(differences);
    }

    /** Whether the new evaluation gave exactly the recorded decision: there is no difference. */
    public boolean matches() {
        return differences.isEmpty();
    }

    /** The differences of {@code kind}, in order. */
    public List<Difference> differences(Kind kind) {
        return differences.stream().filter(d -> d.kind() == kind).toList();
    }
}
