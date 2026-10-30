//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The explanation of one evaluation: what was decided, with which exact rules, and why.
 *
 * <p>The engine produces a record for <em>every</em> call to
 * {@link RuleStoneEngine#evaluate}, whether it succeeds or fails, and hands it to every
 * configured {@link AuditSink}. A successful result also carries it as
 * {@link DecisionResult#record()}. A shadow evaluation produces a record of its own, which
 * goes to the sinks only.
 *
 * <p>A record never contains input values. {@link #factsHash()} identifies the inputs
 * without revealing them, so the record can be written to ordinary logs; the inputs
 * themselves stay in the bank's own data stores and can be checked against the hash.
 *
 * @param recordId      unique id of this record
 * @param correlationId the caller's id for the business transaction, from {@link DecisionRef#correlationId()}
 * @param decision      the requested decision id
 * @param version       exact version of the decision used, e.g. {@code 3@sha256:…}; empty if none was found
 * @param businessDate  the business date the decision was made for
 * @param evaluatedAt   when the evaluation started, from the engine clock
 * @param engine        the engine that produced the record, e.g. {@code rulestone 0.1.0}
 * @param status        whether the evaluation completed
 * @param factsHash     hash of the declared inputs in canonical form, e.g. {@code sha256:…}; empty if
 *                      the inputs were not valid
 * @param output        the decision's outputs; empty unless the status is {@link Status#OK}
 * @param reasonCodes   the reasons for the outcome, each with the node it came from
 * @param path          every node visited, in order; when the evaluation failed or timed out
 *                      inside a node, that node is the last step
 * @param error         why the evaluation failed or was cut short; present exactly when the status
 *                      is not {@link Status#OK}. Like every RuleStone message, it contains no input values.
 * @param routing       why a version other than the one in force was evaluated: a canary or a
 *                      shadow version; empty for an ordinary evaluation
 */
public record DecisionRecord(
        String recordId,
        Optional<String> correlationId,
        String decision,
        Optional<String> version,
        LocalDate businessDate,
        Instant evaluatedAt,
        String engine,
        Status status,
        Optional<String> factsHash,
        Map<String, Object> output,
        List<ReasonCode> reasonCodes,
        List<PathStep> path,
        Optional<String> error,
        Optional<Routing> routing) {

    /** Whether the evaluation completed. */
    public enum Status {
        /** The evaluation completed. The decision may still have matched no rule. */
        OK,
        /** The evaluation failed and the caller received an exception instead of a result. */
        ERROR,
        /**
         * The evaluation took longer than its timeout and was cut short; the caller received a
         * {@link DecisionTimeoutException}. The path shows the rules that matched until then.
         */
        TIMEOUT
    }

    /**
     * Why an evaluation used a version other than the one in force on the business date.
     *
     * @param kind             a canary or a shadow evaluation
     * @param champion         the exact version the decision would otherwise have used, e.g.
     *                         {@code 1@sha256:…}; for a shadow, the version whose result the caller received
     * @param share            for a canary, the share of correlation ids routed to it, above 0 and at
     *                         most 1; empty for a shadow
     * @param shadowOf         for a shadow, the {@link DecisionRecord#recordId()} of the record of the evaluation it
     *                         shadows, made on the same facts; empty for a canary
     */
    public record Routing(Kind kind, String champion, OptionalDouble share, Optional<String> shadowOf) {

        /** How the version came to be evaluated. */
        public enum Kind {
            /**
             * The request fell into the canary's share of the traffic, so the canary version was
             * evaluated, and its result returned, instead of the version in force.
             */
            CANARY,
            /**
             * The version was evaluated on the same facts as the version in force, only to be
             * recorded. Its result was never returned to the caller.
             */
            SHADOW
        }

        public Routing {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(champion, "champion");
            Objects.requireNonNull(share, "share");
            Objects.requireNonNull(shadowOf, "shadowOf");
            if (share.isPresent() != (kind == Kind.CANARY)) {
                throw new IllegalArgumentException("share must be present exactly for a canary");
            }
            if (share.isPresent() && !(share.getAsDouble() > 0 && share.getAsDouble() <= 1)) {
                throw new IllegalArgumentException("share must be above 0 and at most 1");
            }
            if (shadowOf.isPresent() != (kind == Kind.SHADOW)) {
                throw new IllegalArgumentException("shadowOf must be present exactly for a shadow");
            }
        }

        /** A canary evaluation, instead of {@code champion}, for {@code share} of the correlation ids. */
        public static Routing canary(String champion, double share) {
            return new Routing(Kind.CANARY, champion, OptionalDouble.of(share), Optional.empty());
        }

        /** A shadow evaluation of the evaluation recorded as {@code shadowOf}, which used {@code champion}. */
        public static Routing shadow(String champion, String shadowOf) {
            return new Routing(Kind.SHADOW, champion, OptionalDouble.empty(), Optional.of(shadowOf));
        }
    }

    /**
     * One reason for the outcome.
     *
     * @param code   a stable, machine-readable code such as {@code DTI_HIGH}
     * @param source the node that produced it, e.g. the decision table id
     */
    public record ReasonCode(String code, String source) {

        public ReasonCode {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * One node visited during the evaluation.
     *
     * <p>A decision table evaluated on its own has one step, of type {@code table}, whose node is
     * the decision id. A decision flow has one step per node it visits, in order, ending with an
     * {@code end} node unless the evaluation failed.
     *
     * @param node         the node id; for a decision table evaluated on its own, the decision id
     * @param type         the kind of node: {@code table}, {@code switch}, {@code derive}, {@code model},
     *                     {@code split} or {@code end}
     * @param decision     for a {@code table} node of a flow, the id of the decision table it
     *                     evaluated; empty for other nodes, and for a table evaluated on its own,
     *                     whose node is already the decision id
     * @param version      for a {@code table} step, the exact version of the table used, e.g.
     *                     {@code 3@sha256:…}; empty for other nodes
     * @param matchedRules for a {@code table} step, ids of the rules that matched, in evaluation
     *                     order; empty for other nodes
     * @param taken        for a {@code switch} node, the node its chosen branch leads to; for a
     *                     {@code split} node, the name of the chosen bucket; empty for other nodes
     * @param model        for a {@code model} node, what the model was asked and answered; empty
     *                     for other nodes
     */
    public record PathStep(String node, String type, Optional<String> decision, Optional<String> version,
                           List<String> matchedRules, Optional<String> taken, Optional<ModelStep> model) {

        public PathStep {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(taken, "taken");
            Objects.requireNonNull(model, "model");
            matchedRules = List.copyOf(matchedRules);
        }

        /** A step of any node but a {@code model} node. */
        public PathStep(String node, String type, Optional<String> decision, Optional<String> version,
                        List<String> matchedRules, Optional<String> taken) {
            this(node, type, decision, version, matchedRules, taken, Optional.empty());
        }

        /** The step of a decision table evaluated on its own: node {@code decision}, type {@code table}. */
        public PathStep(String node, String type, String version, List<String> matchedRules) {
            this(node, type, Optional.empty(), Optional.of(version), matchedRules, Optional.empty());
        }

        /** The step of a {@code table} node of a flow, which evaluated {@code version} of {@code decision}. */
        public static PathStep table(String node, String decision, String version, List<String> matchedRules) {
            return new PathStep(node, "table", Optional.of(decision), Optional.of(version), matchedRules,
                    Optional.empty());
        }

        /** The step of a {@code switch} or {@code split} node, which took {@code taken}. */
        public static PathStep branch(String node, String type, String taken) {
            return new PathStep(node, type, Optional.empty(), Optional.empty(), List.of(), Optional.of(taken));
        }

        /** The step of a node that records nothing but its visit, such as {@code derive} or {@code end}. */
        public static PathStep visit(String node, String type) {
            return new PathStep(node, type, Optional.empty(), Optional.empty(), List.of(), Optional.empty());
        }

        /** The step of a {@code model} node. */
        public static PathStep model(String node, ModelStep model) {
            return new PathStep(node, "model", Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                    Optional.of(model));
        }
    }

    /**
     * What a {@code model} node asked of its model and what it got. It holds no feature values
     * and no raw contributions: only identifiers, the outcome, the outputs the flow read, and the
     * ranks of the attributions. The outputs are recorded so that the decision can be replayed
     * with them after the model has changed.
     *
     * @param modelId           the model, as the node pins it
     * @param modelVersion      the exact version, as the node pins it
     * @param mode              how the result was obtained: called by the engine, or supplied by
     *                          the caller (also for an {@code invoke} node of an engine built
     *                          with {@code suppliedModelsOnly()})
     * @param status            {@link ModelStatus#OK}, or the kind of failure, after which the flow
     *                          took the node's {@code onFailure} path
     * @param detail            for a failure, what went wrong, e.g. the output that broke the
     *                          contract or the type of the exception the invoker threw; never a value
     * @param latencyMs         for a model the engine called, how long the call took, in milliseconds
     * @param outputs           when the status is {@link ModelStatus#OK}, the outputs that later
     *                          nodes read; empty otherwise
     * @param confidence        when the status is {@link ModelStatus#OK}, the confidence the model reported, if any
     * @param attributionMethod how the attributions were computed, e.g. {@code SHAP}, if the model said
     * @param attributions      when the status is {@link ModelStatus#OK}, every attribution the
     *                          model returned, by rank; empty if it returned none
     */
    public record ModelStep(String modelId, String modelVersion, ModelMode mode, ModelStatus status,
                            Optional<String> detail, OptionalLong latencyMs, Map<String, Object> outputs,
                            Optional<BigDecimal> confidence, Optional<String> attributionMethod,
                            List<RankedAttribution> attributions) {

        public ModelStep {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelVersion, "modelVersion");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(latencyMs, "latencyMs");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(attributionMethod, "attributionMethod");
            if (status.failed() && (!outputs.isEmpty() || !attributions.isEmpty() || confidence.isPresent())) {
                throw new IllegalArgumentException("a failed model step records no outputs, confidence or attributions");
            }
            outputs.forEach((name, value) -> {
                Objects.requireNonNull(name, "output name");
                Objects.requireNonNull(value, () -> "value of output '" + name + "'");
            });
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
            attributions = List.copyOf(attributions);
        }

        @Override
        public String toString() {
            return "ModelStep[" + modelId + "@" + modelVersion + ", " + mode + ", " + status + "]";
        }
    }

    /**
     * One attribution of a model's result, by rank, without its contribution value.
     *
     * @param feature    the feature name
     * @param rank       1 for the feature that moved the result most, in either direction
     * @param direction  whether the feature increased or decreased risk
     * @param reasonCode the reason code the node maps the feature to, when the flow emitted it as a
     *                   reason for the decision
     */
    public record RankedAttribution(String feature, int rank, Direction direction, Optional<String> reasonCode) {

        /** The sign of a feature's contribution. */
        public enum Direction {
            /** A positive contribution. */
            INCREASES_RISK,
            /** A negative contribution. */
            DECREASES_RISK,
            /** A contribution of zero. */
            NONE
        }

        public RankedAttribution {
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (rank < 1) {
                throw new IllegalArgumentException("rank must be at least 1");
            }
        }
    }

    public DecisionRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(factsHash, "factsHash");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(routing, "routing");
        if (error.isPresent() != (status != Status.OK)) {
            throw new IllegalArgumentException("error must be present exactly when the status is not OK");
        }
        if (status != Status.OK && !output.isEmpty()) {
            throw new IllegalArgumentException("a failed evaluation has no output");
        }
        output.forEach((name, value) -> {
            Objects.requireNonNull(name, "output name");
            Objects.requireNonNull(value, () -> "value of output '" + name + "'");
        });
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        reasonCodes = List.copyOf(reasonCodes);
        path = List.copyOf(path);
    }

    /** A record of an ordinary evaluation, without {@link #routing()}. */
    public DecisionRecord(String recordId, Optional<String> correlationId, String decision, Optional<String> version,
                          LocalDate businessDate, Instant evaluatedAt, String engine, Status status,
                          Optional<String> factsHash, Map<String, Object> output, List<ReasonCode> reasonCodes,
                          List<PathStep> path, Optional<String> error) {
        this(recordId, correlationId, decision, version, businessDate, evaluatedAt, engine, status, factsHash,
                output, reasonCodes, path, error, Optional.empty());
    }
}
