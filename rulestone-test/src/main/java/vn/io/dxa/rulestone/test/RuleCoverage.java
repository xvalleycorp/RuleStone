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
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.FlowNode;
import vn.io.dxa.rulestone.core.Rule;

/**
 * Which rules of a set of decision tables, and which nodes and branches of a set of decision
 * flows, were exercised by tests, per decision and version.
 *
 * <pre>{@code
 * RuleCoverage coverage = RuleCoverage.of(tables, flows);
 * coverage.record(result);          // for each result of a passing test
 * coverage.assertComplete();        // fails, listing every rule, node and branch never exercised
 * }</pre>
 *
 * <p>A rule counts as covered once a recorded result lists it among the matched rules of a step
 * of its table, whether the table was evaluated on its own or as a node of a flow. A node counts
 * as covered once a recorded flow result passes through it. A branch is a way out of a node that
 * has several: each distinct target of a {@code switch} and of a conditional {@code next}, written
 * {@code segment -> grade_new}, and each bucket of a {@code split}, written
 * {@code limit_strategy[challenger]}. Record only results of tests that passed: what was exercised
 * only in a failing test, or in an evaluation that failed, is not covered. Results of decisions or
 * versions that are not tracked are ignored.
 *
 * <p>Instances are thread-safe.
 */
public final class RuleCoverage {

    /** Keyed by {@code id@version}; values are the rule ids still never matched, in table order. */
    private final Map<String, Set<String>> uncovered = new LinkedHashMap<>();
    private final Map<String, Integer> ruleCounts = new LinkedHashMap<>();
    /** Keyed by flow {@code id@version}; values are the nodes, and branches, never exercised, in file order. */
    private final Map<String, Set<String>> uncoveredNodes = new LinkedHashMap<>();
    private final Map<String, Set<String>> uncoveredBranches = new LinkedHashMap<>();
    private final Map<String, Integer> nodeCounts = new LinkedHashMap<>();
    private final Map<String, Integer> branchCounts = new LinkedHashMap<>();
    /** Flow {@code id@version} to the node ids that have several ways out. */
    private final Map<String, Map<String, FlowNode>> branching = new LinkedHashMap<>();
    private final List<DecisionTable> tables;
    private final List<DecisionFlow> flows;

    private RuleCoverage(Collection<DecisionTable> tables, Collection<DecisionFlow> flows) {
        this.tables = List.copyOf(tables);
        this.flows = List.copyOf(flows);
        for (DecisionTable table : tables) {
            Set<String> ids = new LinkedHashSet<>();
            table.rules().stream().map(Rule::id).forEach(ids::add);
            if (uncovered.putIfAbsent(table.ref(), ids) != null) {
                throw new IllegalArgumentException(table.ref() + " is listed more than once");
            }
            ruleCounts.put(table.ref(), ids.size());
        }
        for (DecisionFlow flow : flows) {
            if (uncoveredNodes.putIfAbsent(flow.ref(), new LinkedHashSet<>(flow.nodes().keySet())) != null) {
                throw new IllegalArgumentException(flow.ref() + " is listed more than once");
            }
            nodeCounts.put(flow.ref(), flow.nodes().size());
            Set<String> branches = new LinkedHashSet<>();
            Map<String, FlowNode> nodes = new LinkedHashMap<>();
            flow.nodes().forEach((id, node) -> {
                Set<String> own = branches(id, node);
                if (!own.isEmpty()) {
                    nodes.put(id, node);
                    branches.addAll(own);
                }
            });
            uncoveredBranches.put(flow.ref(), branches);
            branchCounts.put(flow.ref(), branches.size());
            branching.put(flow.ref(), nodes);
        }
    }

    /** The branches of a node with several ways out; empty for a node with one or none. */
    private static Set<String> branches(String id, FlowNode node) {
        Set<String> branches = new LinkedHashSet<>();
        if (node instanceof FlowNode.Split split) {
            split.buckets().forEach(bucket -> branches.add(id + "[" + bucket.name() + "]"));
            return branches;
        }
        List<FlowNode.Branch> next = List.of();
        if (node instanceof FlowNode.Switch sw) {
            next = sw.cases();
        } else if (node instanceof FlowNode.Table table) {
            next = table.next();
        } else if (node instanceof FlowNode.Derive derive) {
            next = derive.next();
        }
        next.forEach(branch -> branches.add(id + " -> " + branch.to()));
        return branches.size() > 1 ? branches : Set.of();
    }

    /**
     * Starts tracking coverage of every rule of every version in {@code tables}.
     *
     * @param tables the decision tables under test, usually the same ones the engine was built from
     * @return a coverage tracker with no rule covered yet
     * @throws IllegalArgumentException if a decision version is listed twice
     */
    public static RuleCoverage of(Collection<DecisionTable> tables) {
        return new RuleCoverage(tables, List.of());
    }

    /**
     * Starts tracking coverage of every rule of every version in {@code tables}, and of every
     * node and branch of every version in {@code flows}.
     *
     * @param tables the decision tables under test, including those the flows use
     * @param flows  the decision flows under test
     * @return a coverage tracker with nothing covered yet
     * @throws IllegalArgumentException if a decision or flow version is listed twice
     */
    public static RuleCoverage of(Collection<DecisionTable> tables, Collection<DecisionFlow> flows) {
        return new RuleCoverage(tables, flows);
    }

    /**
     * Starts tracking coverage of every rule of every version in {@code tables}.
     *
     * @param tables the decision tables under test
     * @return a coverage tracker with no rule covered yet
     * @throws IllegalArgumentException if a decision version is listed twice
     */
    public static RuleCoverage of(DecisionTable... tables) {
        return new RuleCoverage(List.of(tables), List.of());
    }

    /**
     * Marks the matched rules, and for a flow the nodes visited and branches taken, of
     * {@code result} as covered.
     *
     * @param result the result of a passing test
     */
    public synchronized void record(DecisionResult result) {
        // A table evaluated on its own.
        Set<String> own = uncovered.get(result.decisionId() + "@" + Expectations.versionLabel(result.ruleVersion()));
        if (own != null) {
            result.matchedRules().forEach(own::remove);
        }
        // The table nodes of a flow.
        List<PathStep> path = result.record().path();
        for (PathStep step : path) {
            if (step.decision().isPresent() && step.version().isPresent()) {
                Set<String> rules = uncovered.get(step.decision().get() + "@"
                        + Expectations.versionLabel(step.version().get()));
                if (rules != null) {
                    step.matchedRules().forEach(rules::remove);
                }
            }
        }
        String flow = result.decisionId() + "@" + Expectations.versionLabel(result.ruleVersion());
        Set<String> nodes = uncoveredNodes.get(flow);
        if (nodes == null) {
            return;
        }
        Set<String> branches = uncoveredBranches.get(flow);
        Map<String, FlowNode> withBranches = branching.get(flow);
        for (int i = 0; i < path.size(); i++) {
            PathStep step = path.get(i);
            nodes.remove(step.node());
            FlowNode node = withBranches.get(step.node());
            if (node instanceof FlowNode.Split) {
                step.taken().ifPresent(bucket -> branches.remove(step.node() + "[" + bucket + "]"));
            } else if (node != null && i + 1 < path.size()) {
                branches.remove(step.node() + " -> " + path.get(i + 1).node());
            }
        }
    }

    /**
     * Whether every rule has been matched, and every flow node and branch exercised, at least once.
     *
     * @return {@code true} if nothing is uncovered
     */
    public synchronized boolean isComplete() {
        return uncovered.values().stream().allMatch(Set::isEmpty)
                && uncoveredNodes.values().stream().allMatch(Set::isEmpty)
                && uncoveredBranches.values().stream().allMatch(Set::isEmpty);
    }

    /**
     * The rules never matched, by decision version.
     *
     * @return {@code id@version} to the uncovered rule ids in table order; versions with full
     *         coverage are left out
     */
    public synchronized Map<String, List<String>> uncoveredRules() {
        return nonEmpty(uncovered);
    }

    /**
     * The flow nodes never visited, by flow version.
     *
     * @return {@code id@version} to the uncovered node ids in file order; versions with full
     *         coverage are left out
     */
    public synchronized Map<String, List<String>> uncoveredNodes() {
        return nonEmpty(uncoveredNodes);
    }

    /**
     * The flow branches never taken, by flow version, written as described in the class
     * documentation, e.g. {@code segment -> grade_new}.
     *
     * @return {@code id@version} to the uncovered branches in file order; versions with full
     *         coverage are left out
     */
    public synchronized Map<String, List<String>> uncoveredBranches() {
        return nonEmpty(uncoveredBranches);
    }

    private static Map<String, List<String>> nonEmpty(Map<String, Set<String>> uncovered) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        uncovered.forEach((ref, items) -> {
            if (!items.isEmpty()) {
                result.put(ref, List.copyOf(items));
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
     * node coverage: 10 of 10 nodes and 7 of 8 branches exercised by a passing test
     *   card_origination@1: 10 of 10 nodes, 7 of 8 branches, never taken: limit_strategy[challenger]
     * </pre>
     *
     * The node coverage lines appear only when flows are tracked.
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
        if (flows.isEmpty()) {
            return report.toString();
        }
        int nodes = nodeCounts.values().stream().mapToInt(Integer::intValue).sum();
        int missingNodes = uncoveredNodes.values().stream().mapToInt(Set::size).sum();
        int branches = branchCounts.values().stream().mapToInt(Integer::intValue).sum();
        int missingBranches = uncoveredBranches.values().stream().mapToInt(Set::size).sum();
        report.append("\nnode coverage: ").append(nodes - missingNodes).append(" of ").append(nodes)
                .append(" nodes and ").append(branches - missingBranches).append(" of ").append(branches)
                .append(" branches exercised by a passing test");
        uncoveredNodes.forEach((ref, unvisited) -> {
            int nodeCount = nodeCounts.get(ref);
            int branchCount = branchCounts.get(ref);
            Set<String> untaken = uncoveredBranches.get(ref);
            report.append("\n  ").append(ref).append(": ")
                    .append(nodeCount - unvisited.size()).append(" of ").append(nodeCount).append(" nodes, ")
                    .append(branchCount - untaken.size()).append(" of ").append(branchCount).append(" branches");
            if (!unvisited.isEmpty()) {
                report.append(", never visited: ").append(String.join(", ", unvisited));
            }
            if (!untaken.isEmpty()) {
                report.append(", never taken: ").append(String.join(", ", untaken));
            }
        });
        return report.toString();
    }

    /**
     * Fails unless every rule has been matched, and every flow node and branch exercised, at least once.
     *
     * @throws AssertionError with the {@link #report()} if anything was never exercised
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

    /** The tables whose rules are tracked, used by {@link StoneTests} to read test files with their types. */
    List<DecisionTable> tables() {
        return tables;
    }

    /** The flows whose nodes are tracked, used by {@link StoneTests} to read test files with their types. */
    List<DecisionFlow> flows() {
        return flows;
    }
}
