//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a model returned: from a {@link ModelInvoker}, or supplied by the caller with
 * {@link Facts#withModelResult}. The engine checks it against the node's contract before any later
 * node reads it.
 *
 * <p>Outputs use the Java types of decision values: {@code BigDecimal} for {@code decimal},
 * {@code Long} (or {@code Integer}) for {@code int}, {@code String}, {@code Boolean},
 * {@code Money}, {@code LocalDate}, and lists of them. Their JSON text forms are accepted too, such
 * as {@code "0.0310"} for a decimal or {@code "1500000 VND"} for money. {@code Double} and
 * {@code Float} break the contract: they cannot hold exact decimals.
 *
 * <p>{@link #toString()} shows the model, the output names and the number of attributions, never
 * values.
 *
 * @param modelId           the model that answered
 * @param modelVersion      the version that actually answered; it must be the version the node pins
 * @param outputs           output name to value: exactly the node's declared outputs
 * @param confidence        the model's confidence in this result, if it reports one; required when
 *                          the node sets {@code minConfidence}
 * @param attributions      how much each feature contributed to the result, in any order. A positive
 *                          contribution increases risk: for a score where higher means less risk, the
 *                          adapter negates the contributions.
 * @param attributionMethod how the attributions were computed, e.g. {@code SHAP} or
 *                          {@code scorecard points}; recorded in the decision record
 */
public record ModelResult(String modelId, String modelVersion, Map<String, Object> outputs,
                          Optional<BigDecimal> confidence, List<Attribution> attributions,
                          Optional<String> attributionMethod) {

    /**
     * The contribution of one feature to a model's result.
     *
     * @param feature      the feature name, as the node's {@code reasonCodes} names it
     * @param contribution how much the feature moved the result; positive values increase risk
     */
    public record Attribution(String feature, BigDecimal contribution) {

        public Attribution {
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(contribution, "contribution");
        }

        @Override
        public String toString() {
            return "Attribution[" + feature + "]";
        }
    }

    public ModelResult {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(attributionMethod, "attributionMethod");
        outputs.forEach((name, value) -> {
            Objects.requireNonNull(name, "output name");
            Objects.requireNonNull(value, () -> "value of output '" + name + "'");
        });
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        attributions = List.copyOf(attributions);
    }

    /** A result without confidence or attributions. */
    public static ModelResult of(String modelId, String modelVersion, Map<String, ?> outputs) {
        return new ModelResult(modelId, modelVersion, new LinkedHashMap<>(outputs), Optional.empty(), List.of(),
                Optional.empty());
    }

    /** A copy with {@code confidence}. */
    public ModelResult withConfidence(BigDecimal confidence) {
        return new ModelResult(modelId, modelVersion, outputs, Optional.of(confidence), attributions,
                attributionMethod);
    }

    /** A copy with {@code attributions}, computed by {@code method}, e.g. {@code SHAP}. */
    public ModelResult withAttributions(String method, List<Attribution> attributions) {
        return new ModelResult(modelId, modelVersion, outputs, confidence, attributions,
                Optional.of(Objects.requireNonNull(method, "method")));
    }

    @Override
    public String toString() {
        return "ModelResult[" + modelId + "@" + modelVersion + ", outputs " + outputs.keySet() + ", "
                + attributions.size() + " attributions]";
    }
}
