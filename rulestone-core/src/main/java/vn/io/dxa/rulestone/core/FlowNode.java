//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * One node of a {@link DecisionFlow}. Expressions are CEL source text, compiled and type-checked
 * when the flow is registered with an engine.
 *
 * <p>A node publishes its values to later nodes under its id, or under {@code as} where the
 * node has one: {@code policy.decision} for a {@code table} node, {@code ratios.dti} for a
 * {@code derive} node, {@code segment.taken} for a {@code switch} or {@code split} node.
 */
public sealed interface FlowNode {

    /** Free text for authors and reviewers. */
    Optional<String> description();

    /** The node type as written in a flow file, e.g. {@code table}. */
    String type();

    /**
     * One way out of a node: to node {@code to} when {@code when} holds. Alternatives are tried in
     * order; the first whose condition holds is taken, and the last has no condition.
     *
     * @param when a CEL condition; empty for the final, unconditional alternative
     * @param to   the id of the next node
     */
    record Branch(Optional<String> when, String to) {

        public Branch {
            Objects.requireNonNull(when, "when");
            Objects.requireNonNull(to, "to");
        }

        /** An unconditional branch. */
        public static Branch to(String node) {
            return new Branch(Optional.empty(), node);
        }
    }

    /**
     * Evaluates a decision table. By default the table receives the flow values whose names match
     * its declared inputs; {@code facts} maps inputs to CEL expressions instead.
     *
     * @param decision the id of a registered decision table
     * @param version  a version of the table to always use; empty for the version in force on the
     *                 business date
     * @param facts    table input name to a CEL expression over the flow values
     * @param as       a name to publish the table's outputs under instead of the node id
     * @param next     where to go next; conditions may read this node's outputs
     */
    record Table(Optional<String> description, String decision, Optional<String> version,
                 Map<String, String> facts, Optional<String> as, List<Branch> next) implements FlowNode {

        public Table {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(as, "as");
            facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
            next = List.copyOf(next);
        }

        @Override
        public String type() {
            return "table";
        }
    }

    /**
     * Segments the population: the first case whose condition holds is taken, and the last case
     * has no condition. Publishes {@code taken}, the id of the node chosen.
     */
    record Switch(Optional<String> description, List<Branch> cases) implements FlowNode {

        public Switch {
            Objects.requireNonNull(description, "description");
            cases = List.copyOf(cases);
        }

        @Override
        public String type() {
            return "switch";
        }
    }

    /**
     * Computes named values once, such as a debt-to-income ratio, for later nodes to read.
     *
     * @param outputs value name to type; structs are not allowed, as for table outputs
     * @param set     value name to a CEL expression of that type
     * @param as      a name to publish the values under instead of the node id
     * @param next    where to go next; conditions may read this node's values
     */
    record Derive(Optional<String> description, Map<String, ValueType> outputs, Map<String, String> set,
                  Optional<String> as, List<Branch> next) implements FlowNode {

        public Derive {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(as, "as");
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
            set = Collections.unmodifiableMap(new LinkedHashMap<>(set));
            next = List.copyOf(next);
        }

        @Override
        public String type() {
            return "derive";
        }
    }

    /**
     * A deterministic percentage split, e.g. champion/challenger. The bucket depends only on
     * {@code salt} and the value of {@code key}, so the same key always lands in the same bucket.
     * Publishes {@code taken}, the name of the bucket chosen.
     *
     * @param key     a CEL expression producing a string, e.g. {@code applicant.id}
     * @param salt    distinguishes independent splits on the same key
     * @param buckets the buckets in order; their percentages add up to 100
     */
    record Split(Optional<String> description, String key, String salt, List<Bucket> buckets) implements FlowNode {

        public Split {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(salt, "salt");
            buckets = List.copyOf(buckets);
        }

        @Override
        public String type() {
            return "split";
        }
    }

    /**
     * One bucket of a {@link Split}.
     *
     * @param name    recorded in the decision record, e.g. {@code challenger}
     * @param percent whole percentage of keys, 1 to 100
     * @param to      the id of the next node
     */
    record Bucket(String name, int percent, String to) {

        public Bucket {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * Ends the flow, setting every declared output of the flow.
     *
     * @param set flow output name to a CEL expression of the output's type
     */
    record End(Optional<String> description, Map<String, String> set) implements FlowNode {

        public End {
            Objects.requireNonNull(description, "description");
            set = Collections.unmodifiableMap(new LinkedHashMap<>(set));
        }

        @Override
        public String type() {
            return "end";
        }
    }
}
