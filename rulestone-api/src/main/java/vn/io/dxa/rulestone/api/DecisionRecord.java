//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

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
     * @param node         the node id; for a decision evaluated on its own, the decision id
     * @param type         the kind of node, e.g. {@code table}
     * @param version      the exact version of the node's definition, e.g. {@code 3@sha256:…}
     * @param matchedRules ids of the rules that matched, in evaluation order
     */
    public record PathStep(String node, String type, String version, List<String> matchedRules) {

        public PathStep {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(version, "version");
            matchedRules = List.copyOf(matchedRules);
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
