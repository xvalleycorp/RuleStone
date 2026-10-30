//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;

/**
 * Input of {@link RuleStoneActivities#evaluate}: which decision to evaluate and the facts, as a
 * Temporal payload.
 *
 * <p>Facts are a JSON object in RuleStone's JSON forms ({@code vn.io.dxa.rulestone.core.FactsJson}),
 * so the payload is exact and readable by workers and clients in any language: decimals as strings
 * in plain notation ({@code "25750000.50"}; JSON numbers are accepted too and read exactly), money as
 * {@code "<amount> <CCY>"} ({@code "1500000.50 VND"}), dates as {@code "yyyy-MM-dd"}, integers as
 * JSON integers, structs as objects and lists as arrays. The factories convert Java values, including
 * records, and reject {@code Double} and {@code Float}. The activity reads the facts with the types
 * the decision declares.
 *
 * <p>{@code models} carries the results of models that the caller ran itself, for the {@code model}
 * nodes of a decision flow in {@code supplied} mode, in the form that
 * {@code FactsJson.readModels} reads: node id to {@code { "modelId", "modelVersion", "outputs", … }},
 * or to {@code { "failure": "TIMEOUT" }}. The activity evaluates decision tables only for now, so a
 * request with model results fails until flows can be evaluated through it.
 *
 * <p>Payloads are stored in workflow history. Treat facts as the personal data they usually are:
 * use a payload codec that encrypts them if your history must not hold them in clear.
 *
 * @param decisionId the decision to evaluate
 * @param version    an exact version label, or {@code null} to select the version effective on the
 *                   business date
 * @param asOf       the business date, or {@code null} for today according to the activity's clock
 * @param facts      the inputs in JSON forms; {@code null} means none
 * @param models     supplied model results by model node id; {@code null} means none
 */
public record DecisionRequest(
        String decisionId,
        String version,
        LocalDate asOf,
        @JsonDeserialize(using = PayloadJson.ExactObjectDeserializer.class)
        ObjectNode facts,
        @JsonDeserialize(using = PayloadJson.ExactObjectDeserializer.class)
        ObjectNode models) {

    /**
     * Validates the request and keeps a copy of the facts.
     *
     * @throws IllegalArgumentException if {@code decisionId} or {@code version} is blank
     */
    public DecisionRequest {
        Objects.requireNonNull(decisionId, "decisionId");
        if (decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (version != null && version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        facts = facts == null ? JsonNodeFactory.instance.objectNode() : facts.deepCopy();
        models = models == null ? null : models.deepCopy();
    }

    /**
     * A request without model results.
     *
     * @param decisionId the decision to evaluate
     * @param version    an exact version label, or {@code null}
     * @param asOf       the business date, or {@code null}
     * @param facts      the inputs in JSON forms; {@code null} means none
     */
    public DecisionRequest(String decisionId, String version, LocalDate asOf, ObjectNode facts) {
        this(decisionId, version, asOf, facts, null);
    }

    /**
     * Creates a request for the version effective today according to the activity's clock.
     *
     * @param decisionId the decision to evaluate
     * @param facts      the inputs
     * @return the request
     * @throws IllegalArgumentException if a fact is floating point or of an unsupported type; the
     *                                  message names the fact, never its value
     */
    public static DecisionRequest of(String decisionId, Facts facts) {
        return of(decisionId, facts.asMap());
    }

    /**
     * Creates a request from named inputs.
     *
     * @param decisionId the decision to evaluate
     * @param facts      the inputs, as Java values accepted by {@link Facts}
     * @return the request
     * @throws IllegalArgumentException if a fact is floating point or of an unsupported type; the
     *                                  message names the fact, never its value
     */
    public static DecisionRequest of(String decisionId, Map<String, ?> facts) {
        return new DecisionRequest(decisionId, null, null, PayloadJson.write(facts), null);
    }

    /**
     * The facts in JSON forms.
     *
     * @return a copy of the facts
     */
    @Override
    public ObjectNode facts() {
        return facts.deepCopy();
    }

    /**
     * The supplied model results, by model node id.
     *
     * @return a copy, or {@code null} if there are none
     */
    @Override
    public ObjectNode models() {
        return models == null ? null : models.deepCopy();
    }

    /**
     * Returns a copy with supplied model results, in the JSON form described above.
     *
     * @param models model node id to result, e.g.
     *               {@code {"pd": {"modelId": "retail_pd", "modelVersion": "3.1.0", "outputs": {"pd": "0.031"}}}}
     * @return the new request
     */
    public DecisionRequest withModels(ObjectNode models) {
        return new DecisionRequest(decisionId, version, asOf, facts, Objects.requireNonNull(models, "models"));
    }

    /**
     * Returns a copy that evaluates exactly version {@code version}.
     *
     * @param version a version label, e.g. {@code "3"}
     * @return the new request
     */
    public DecisionRequest withVersion(String version) {
        return new DecisionRequest(decisionId, Objects.requireNonNull(version, "version"), asOf, facts, models);
    }

    /**
     * Returns a copy that evaluates on business date {@code date}. Recommended: the decision then does
     * not depend on when the activity runs.
     *
     * @param date the business date, e.g. the application date of a loan
     * @return the new request
     */
    public DecisionRequest withAsOf(LocalDate date) {
        return new DecisionRequest(decisionId, version, Objects.requireNonNull(date, "date"), facts, models);
    }

    /**
     * The decision reference this request names, without a business date when {@link #asOf()} is
     * {@code null}.
     *
     * @return the reference
     */
    public DecisionRef toDecisionRef() {
        DecisionRef ref = DecisionRef.of(decisionId);
        if (version != null) {
            ref = ref.version(version);
        }
        return asOf == null ? ref : ref.asOf(asOf);
    }
}
