//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.Rule;

/**
 * Which rules of a set of decision tables were matched by tests, per decision and version.
 *
 * <pre>{@code
 * RuleCoverage coverage = RuleCoverage.of(tables);
 * coverage.record(result);          // for each result of a passing test
 * coverage.assertComplete();        // fails, listing every rule never matched
 * }</pre>
 *
 * <p>A rule counts as covered once a recorded result lists it among its matched rules. Record
 * only results of tests that passed: a rule that matched only in a failing test, or in an
 * evaluation that failed, is not covered. Results of decisions or versions that are not among the
 * tables are ignored.
 *
 * <p>Instances are thread-safe.
 */
public final class RuleCoverage {

    /** Keyed by {@code id@version}; values are the rule ids still never matched, in table order. */
    private final Map<String, Set<String>> uncovered = new LinkedHashMap<>();
    private final Map<String, Integer> ruleCounts = new LinkedHashMap<>();

    private RuleCoverage(Collection<DecisionTable> tables) {
        for (DecisionTable table : tables) {
            Set<String> ids = new LinkedHashSet<>();
            table.rules().stream().map(Rule::id).forEach(ids::add);
            if (uncovered.putIfAbsent(table.ref(), ids) != null) {
                throw new IllegalArgumentException(table.ref() + " is listed more than once");
            }
            ruleCounts.put(table.ref(), ids.size());
        }
    }

    /**
     * Starts tracking coverage of every rule of every version in {@code tables}.
     *
     * @param tables the decision tables under test, usually the same ones the engine was built from
     * @return a coverage tracker with no rule covered yet
     * @throws IllegalArgumentException if a decision version is listed twice
     */
    public static RuleCoverage of(Collection<DecisionTable> tables) {
        return new RuleCoverage(tables);
    }

    /**
     * Starts tracking coverage of every rule of every version in {@code tables}.
     *
     * @param tables the decision tables under test
     * @return a coverage tracker with no rule covered yet
     * @throws IllegalArgumentException if a decision version is listed twice
     */
    public static RuleCoverage of(DecisionTable... tables) {
        return new RuleCoverage(List.of(tables));
    }

    /**
     * Marks the matched rules of {@code result} as covered.
     *
     * @param result the result of a passing test
     */
    public synchronized void record(DecisionResult result) {
        Set<String> rules = uncovered.get(result.decisionId() + "@" + Expectations.versionLabel(result.ruleVersion()));
        if (rules != null) {
            result.matchedRules().forEach(rules::remove);
        }
    }

    /**
     * Whether every rule has been matched at least once.
     *
     * @return {@code true} if no rule is uncovered
     */
    public synchronized boolean isComplete() {
        return uncovered.values().stream().allMatch(Set::isEmpty);
    }

    /**
     * The rules never matched, by decision version.
     *
     * @return {@code id@version} to the uncovered rule ids in table order; versions with full
     *         coverage are left out
     */
    public synchronized Map<String, List<String>> uncoveredRules() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        uncovered.forEach((ref, rules) -> {
            if (!rules.isEmpty()) {
                result.put(ref, List.copyOf(rules));
            }
        });
        return result;
    }

    /**
     * A human-readable summary, one line per decision version, e.g.
     *
     * <pre>
     * rule coverage: 7 of 8 rules matched by a passing test
     *   loan_eligibility@2: 2 of 3 rules, never matched: R3
     *   loan_eligibility@3: 5 of 5 rules
     * </pre>
     *
     * @return the report
     */
    public synchronized String report() {
        int total = ruleCounts.values().stream().mapToInt(Integer::intValue).sum();
        int missing = uncovered.values().stream().mapToInt(Set::size).sum();
        StringBuilder report = new StringBuilder("rule coverage: ")
                .append(total - missing).append(" of ").append(total).append(" rules matched by a passing test");
        uncovered.forEach((ref, rules) -> {
            int count = ruleCounts.get(ref);
            report.append("\n  ").append(ref).append(": ")
                    .append(count - rules.size()).append(" of ").append(count).append(" rules");
            if (!rules.isEmpty()) {
                report.append(", never matched: ").append(String.join(", ", rules));
            }
        });
        return report.toString();
    }

    /**
     * Fails unless every rule has been matched at least once.
     *
     * @throws AssertionError with the {@link #report()} if a rule was never matched
     */
    public void assertComplete() {
        String report;
        synchronized (this) {
            if (isComplete()) {
                return;
            }
            report = report();
        }
        throw new AssertionError(report);
    }
}
