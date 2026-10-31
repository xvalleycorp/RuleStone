//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;

/**
 * A {@link DecisionRecord} explained in one language: the outcome, the reasons for it with the
 * reviewed message of each reason code from a reason-code catalog, and what decided it.
 *
 * <p>An explanation is built from the record and the catalog only, by a pure function: the same
 * record, catalog and locale always give the same explanation. It holds nothing the record does not
 * hold apart from the catalog's messages, so, like the record, it never contains input values. It
 * also leaves out what the person affected does not need: the facts hash, model outputs,
 * confidence, latencies and failure details.
 *
 * @param recordId      the record explained, {@link DecisionRecord#recordId()}
 * @param correlationId the caller's id for the business transaction, as in the record
 * @param decision      the requested decision id
 * @param version       exact version of the decision used; empty if none was found
 * @param businessDate  the business date the decision was made for
 * @param status        whether the evaluation completed
 * @param locale        the language of the messages, one of the catalog's locales, e.g. {@code vi}
 * @param catalog       the exact version of the catalog the messages come from, e.g. {@code 3@sha256:…}
 * @param outcome       the decision's outputs, as in the record; empty unless the status is {@link Status#OK}
 * @param reasons       the reasons for the outcome, in the record's order
 * @param steps         what decided: every node visited, in order
 * @param params        every parameter set version the evaluation used, as in the record
 */
public record Explanation(
        String recordId,
        Optional<String> correlationId,
        String decision,
        Optional<String> version,
        LocalDate businessDate,
        Status status,
        String locale,
        String catalog,
        Map<String, Object> outcome,
        List<Reason> reasons,
        List<Step> steps,
        List<ParamSetVersion> params) {

    /**
     * One reason for the outcome.
     *
     * @param code    the reason code, e.g. {@code DTI_HIGH}
     * @param source  the node that produced it, e.g. the decision table id
     * @param message the catalog's message for the code in the explanation's locale; empty only when
     *                the catalog has no entry for the code, which an engine built with the catalog
     *                rules out for every code its tables and flows can give
     */
    public record Reason(String code, String source, Optional<String> message) {

        public Reason {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * One node visited, without values.
     *
     * @param node         the node id; for a decision table evaluated on its own, the decision id
     * @param type         the kind of node: {@code table}, {@code switch}, {@code derive},
     *                     {@code model}, {@code split} or {@code end}
     * @param decision     for a {@code table} node of a flow, the decision table it evaluated
     * @param version      for a {@code table} step, the exact version of the table used
     * @param matchedRules for a {@code table} step, the rules that matched, in evaluation order
     * @param taken        for a {@code switch} node, the node chosen; for a {@code split} node, the bucket
     * @param model        for a {@code model} node, the model and how its features moved the result
     */
    public record Step(String node, String type, Optional<String> decision, Optional<String> version,
                       List<String> matchedRules, Optional<String> taken, Optional<Model> model) {

        public Step {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(taken, "taken");
            Objects.requireNonNull(model, "model");
            matchedRules = List.copyOf(matchedRules);
        }
    }

    /**
     * What a model contributed: its pinned version, its status and, when it succeeded, the
     * features that moved its result by rank and direction. Contribution values, feature values
     * and the model's outputs are not part of an explanation.
     *
     * @param modelId      the model, as the node pins it
     * @param modelVersion the exact version, as the node pins it
     * @param status       {@link ModelStatus#OK}, or the kind of failure, after which the flow took
     *                     the node's {@code onFailure} path
     * @param attributions the model's attributions by rank, each with its direction and the reason
     *                     code the flow emitted for it, if any; empty if it failed or returned none
     */
    public record Model(String modelId, String modelVersion, ModelStatus status,
                        List<RankedAttribution> attributions) {

        public Model {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelVersion, "modelVersion");
            Objects.requireNonNull(status, "status");
            attributions = List.copyOf(attributions);
        }
    }

    public Explanation {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(catalog, "catalog");
        outcome.forEach((name, value) -> {
            Objects.requireNonNull(name, "output name");
            Objects.requireNonNull(value, () -> "value of output '" + name + "'");
        });
        outcome = Collections.unmodifiableMap(new LinkedHashMap<>(outcome));
        reasons = List.copyOf(reasons);
        steps = List.copyOf(steps);
        params = List.copyOf(params);
    }

    /** The reason codes, in order. */
    public List<String> reasonCodes() {
        return reasons.stream().map(Reason::code).toList();
    }
}
