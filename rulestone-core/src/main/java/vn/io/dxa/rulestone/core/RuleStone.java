//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;

/**
 * Entry point for building an embedded engine.
 *
 * <pre>{@code
 * RuleStoneEngine engine = RuleStone.builder()
 *     .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
 *     .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.stone.json")))
 *     .build();
 * }</pre>
 *
 * <p>{@link Builder#build()} compiles and type-checks every rule up front, so an invalid
 * definition fails at startup (or at reload) rather than in the middle of a transaction.
 */
public final class RuleStone {

    private RuleStone() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<DecisionTable> tables = new ArrayList<>();
        private Clock clock = Clock.systemDefaultZone();
        private int maxIterations = ExpressionEnvironment.DEFAULT_MAX_ITERATIONS;

        private Builder() {}

        /**
         * Clock used to pick the business date when a request does not specify one.
         * Set it to the bank's time zone; the default is the JVM's.
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder decision(DecisionTable table) {
            tables.add(Objects.requireNonNull(table, "table"));
            return this;
        }

        public Builder decisions(Collection<DecisionTable> tables) {
            tables.forEach(this::decision);
            return this;
        }

        /** Upper bound on iterations of any comprehension in a rule; see {@link ExpressionEnvironment}. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * @throws InvalidDecisionException listing every problem in every registered table,
         *         including duplicate versions and overlapping effective periods
         */
        public RuleStoneEngine build() {
            List<String> problems = new ArrayList<>();
            Map<String, List<CompiledDecision>> byId = new LinkedHashMap<>();
            for (DecisionTable table : tables) {
                CompiledDecision.compile(table, maxIterations, problems)
                        .ifPresent(d -> byId.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(d));
            }
            byId.values().forEach(versions -> checkVersions(versions, problems));
            if (!problems.isEmpty()) {
                throw new InvalidDecisionException(problems);
            }
            byId.replaceAll((id, versions) -> List.copyOf(versions));
            return new DefaultRuleStoneEngine(byId, clock);
        }

        /** At most one version of a decision may be in force on any date, so selection is never ambiguous. */
        private static void checkVersions(List<CompiledDecision> versions, List<String> problems) {
            versions.sort(Comparator.comparing(d -> d.table().effectiveFrom().orElse(LocalDate.MIN)));
            for (int i = 0; i < versions.size(); i++) {
                DecisionTable current = versions.get(i).table();
                for (int j = i + 1; j < versions.size(); j++) {
                    DecisionTable other = versions.get(j).table();
                    if (current.version().equals(other.version())) {
                        problems.add(current.ref() + ": version registered more than once");
                    }
                }
                if (i + 1 < versions.size()) {
                    DecisionTable next = versions.get(i + 1).table();
                    LocalDate currentEnd = current.effectiveTo().orElse(LocalDate.MAX);
                    LocalDate nextStart = next.effectiveFrom().orElse(LocalDate.MIN);
                    if (nextStart.isBefore(currentEnd)) {
                        problems.add(current.ref() + " and " + next.ref()
                                + ": effective periods overlap; set effectiveTo of the earlier version");
                    }
                }
            }
        }
    }
}
