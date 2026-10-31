//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * One node of a {@link DecisionFlow}. Expressions are CEL source text, compiled and type-checked
 * when the flow is registered with an engine.
 *
 * <p>A node publishes its values to later nodes under its id, or under {@code as} where the
 * node has one: {@code policy.decision} for a {@code table} node, {@code ratios.dti} for a
 * {@code derive} node, {@code pd.score} for a {@code model} node, {@code segment.taken} for a
 * {@code switch} or {@code split} node.
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
     * Obtains a machine-learning model's result and checks it against a typed contract: the model
     * is called through a {@code ModelInvoker} ({@code invoke} mode), or its result comes with the
     * facts ({@code supplied} mode). On success the node publishes the model's outputs; on any
     * failure the flow goes to {@code onFailure} instead, and nothing is published.
     *
     * @param mode           how the result is obtained
     * @param modelId        the model's id
     * @param modelVersion   the exact model version; a range or {@code latest} is not allowed
     * @param features       in {@code invoke} mode, feature name to a CEL expression over the flow
     *                       values: the only values the model receives. Empty in {@code supplied} mode.
     * @param outputs        output name to type, as for table outputs; the result must have exactly these
     * @param valid          a CEL condition over the outputs, by name; when it is false the output is invalid
     * @param minConfidence  the lowest confidence accepted; the result must then report one
     * @param timeoutMs      in {@code invoke} mode, how long the model may take, in milliseconds
     * @param reasonCodes    feature name to the reason code emitted when the feature is among the
     *                       top attributions that increase risk
     * @param maxReasonCodes how many reason codes the node emits at most; three when empty
     * @param onFailure      where to go when the model fails, and the reason code to emit
     * @param as             a name to publish the outputs under instead of the node id
     * @param next           where to go when the result meets the contract; conditions may read the outputs
     */
    record Model(Optional<String> description, ModelMode mode, String modelId, String modelVersion,
                 Map<String, String> features, Map<String, ValueType> outputs, Optional<String> valid,
                 Optional<BigDecimal> minConfidence, Optional<Integer> timeoutMs, Map<String, String> reasonCodes,
                 Optional<Integer> maxReasonCodes, OnFailure onFailure, Optional<String> as,
                 List<Branch> next) implements FlowNode {

        /** How many reason codes a model node emits when it does not say. */
        public static final int DEFAULT_MAX_REASON_CODES = 3;

        public Model {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelVersion, "modelVersion");
            Objects.requireNonNull(valid, "valid");
            Objects.requireNonNull(minConfidence, "minConfidence");
            Objects.requireNonNull(timeoutMs, "timeoutMs");
            Objects.requireNonNull(maxReasonCodes, "maxReasonCodes");
            Objects.requireNonNull(onFailure, "onFailure");
            Objects.requireNonNull(as, "as");
            features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
            reasonCodes = Collections.unmodifiableMap(new LinkedHashMap<>(reasonCodes));
            next = List.copyOf(next);
        }

        @Override
        public String type() {
            return "model";
        }
    }

    /**
     * Where a {@link Model} node goes when the model fails.
     *
     * @param to         the id of the next node
     * @param reasonCode the reason code emitted, e.g. {@code MODEL_UNAVAILABLE}
     */
    record OnFailure(String to, String reasonCode) {

        public OnFailure {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(reasonCode, "reasonCode");
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
