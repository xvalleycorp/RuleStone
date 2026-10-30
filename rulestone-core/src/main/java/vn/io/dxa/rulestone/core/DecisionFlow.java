//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * A versioned decision flow: typed inputs and outputs plus a directed acyclic graph of
 * {@link FlowNode nodes}, evaluated from {@code start} along exactly one path to an
 * {@link FlowNode.End end} node.
 *
 * <p>Flows are versioned and effective-dated exactly like decision tables, share their id
 * namespace, and are evaluated through the same {@code RuleStoneEngine.evaluate} call; see
 * {@link DecisionVersions}. This is only the definition: structural and type errors, including
 * the rule that a node may read a name only if every path to it sets that name, are reported
 * when the flow is registered with an engine.
 *
 * @param id            the decision id, shared by all versions and unique among tables and flows
 * @param version       the version label, e.g. {@code 1}
 * @param description   free text for authors and reviewers
 * @param effectiveFrom first day this version is in force (inclusive)
 * @param effectiveTo   first day this version is no longer in force (exclusive)
 * @param inputs        the facts the flow reads, by name, as for a table
 * @param outputs       the values every {@code end} node sets, by name, as for a table
 * @param start         the id of the first node
 * @param nodes         the nodes by id, in file order
 */
public record DecisionFlow(
        String id,
        String version,
        Optional<String> description,
        Optional<LocalDate> effectiveFrom,
        Optional<LocalDate> effectiveTo,
        Map<String, ValueType> inputs,
        Map<String, ValueType> outputs,
        String start,
        Map<String, FlowNode> nodes) implements VersionedDefinition {

    public DecisionFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        Objects.requireNonNull(start, "start");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        nodes.forEach((name, node) -> Objects.requireNonNull(node, () -> "node " + name));
    }
}
