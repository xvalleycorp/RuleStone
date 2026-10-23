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

/**
 * The explanation of one evaluation: what was decided, with which exact rules, and why.
 *
 * <p>The engine produces a record for <em>every</em> call to
 * {@link RuleStoneEngine#evaluate}, whether it succeeds or fails, and hands it to every
 * configured {@link AuditSink}. A successful result also carries it as
 * {@link DecisionResult#record()}.
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
 * @param output        the decision's outputs; empty when the status is {@link Status#ERROR}
 * @param reasonCodes   the reasons for the outcome, each with the node it came from
 * @param path          every node visited, in order; when the status is {@link Status#ERROR} and
 *                      the failure happened inside a node, that node is the last step
 * @param error         why the evaluation failed; present exactly when the status is
 *                      {@link Status#ERROR}. Like every RuleStone message, it contains no input values.
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
        Optional<String> error) {

    /** Whether the evaluation completed. */
    public enum Status {
        /** The evaluation completed. The decision may still have matched no rule. */
        OK,
        /** The evaluation failed and the caller received an exception instead of a result. */
        ERROR
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
        if (error.isPresent() != (status == Status.ERROR)) {
            throw new IllegalArgumentException("error must be present exactly when the status is ERROR");
        }
        if (status == Status.ERROR && !output.isEmpty()) {
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
}
