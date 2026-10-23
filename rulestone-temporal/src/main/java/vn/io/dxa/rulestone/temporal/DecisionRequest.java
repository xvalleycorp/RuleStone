//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;

/**
 * Input of {@link RuleStoneActivities#evaluate}: which decision to evaluate and the facts, as a
 * Temporal payload.
 *
 * <p>Facts are held in RuleStone's JSON forms, so the payload is exact and readable by workers in
 * any language: decimals as strings in plain notation ({@code "25750000.50"}), money as
 * {@code "<amount> <CCY>"} ({@code "1500000.50 VND"}), dates as {@code "yyyy-MM-dd"}, integers as JSON
 * integers, structs as objects and lists as arrays. The factories and the constructor convert Java
 * values ({@code BigDecimal}, {@link vn.io.dxa.rulestone.api.Money}, {@code LocalDate}, integers,
 * enums, records, maps and lists) into these forms and reject {@code Double} and {@code Float}.
 * The activity reads them back with the types the decision declares.
 *
 * <p>Payloads are stored in workflow history. Treat the facts as the personal data they usually
 * are: use a payload codec that encrypts them if your history must not hold them in clear.
 *
 * @param decisionId the decision to evaluate
 * @param version    an exact version label, or {@code null} to select the version effective on the
 *                   business date
 * @param asOf       the business date, or {@code null} for today according to the activity's clock
 * @param facts      the inputs in JSON forms; never {@code null} (an absent value becomes empty)
 */
public record DecisionRequest(
        String decisionId,
        String version,
        LocalDate asOf,
        @JsonDeserialize(contentUsing = PayloadJson.ExactValueDeserializer.class)
        Map<String, Object> facts) {

    /**
     * Validates the request and converts the facts into JSON forms.
     *
     * @throws IllegalArgumentException if {@code decisionId} or {@code version} is blank, or a fact
     *                                  is a floating-point number or of an unsupported type; the
     *                                  message names the fact, never its value
     */
    public DecisionRequest {
        Objects.requireNonNull(decisionId, "decisionId");
        if (decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (version != null && version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        // Top-level nulls pass here and are rejected by the activity as a non-retryable failure,
        // rather than failing payload deserialization, which Temporal would retry.
        facts = PayloadJson.write(facts == null ? Map.of() : facts, "fact", true);
    }

    /**
     * Creates a request for the version effective on today's date according to the activity's clock.
     *
     * @param decisionId the decision to evaluate
     * @param facts      the inputs
     * @return the request
     */
    public static DecisionRequest of(String decisionId, Facts facts) {
        return new DecisionRequest(decisionId, null, null, facts.asMap());
    }

    /**
     * Creates a request from named inputs, such as JSON-like values received from another system.
     *
     * @param decisionId the decision to evaluate
     * @param facts      the inputs
     * @return the request
     */
    public static DecisionRequest of(String decisionId, Map<String, ?> facts) {
        return new DecisionRequest(decisionId, null, null, new LinkedHashMap<>(facts));
    }

    /**
     * Returns a copy that evaluates exactly version {@code version}.
     *
     * @param version a version label, e.g. {@code "3"}
     * @return the new request
     */
    public DecisionRequest withVersion(String version) {
        return new DecisionRequest(decisionId, Objects.requireNonNull(version, "version"), asOf, facts);
    }

    /**
     * Returns a copy that evaluates on business date {@code date}. Recommended: the decision then
     * does not depend on when the activity runs.
     *
     * @param date the business date, e.g. the application date of a loan
     * @return the new request
     */
    public DecisionRequest withAsOf(LocalDate date) {
        return new DecisionRequest(decisionId, version, Objects.requireNonNull(date, "date"), facts);
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
