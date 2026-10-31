//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One call of a model by a {@code model} node in {@code invoke} mode.
 *
 * <p>{@link #toString()} names the features but never shows their values, which are usually
 * personal data.
 *
 * @param modelId       the model to call, as the node pins it
 * @param modelVersion  the exact version to call, as the node pins it
 * @param features      the node's declared features by name, in declared order: typed values as the
 *                      flow computed them, never {@code double} ({@code BigDecimal}, {@code Long},
 *                      {@code String}, {@code Boolean}, {@code Money}, {@code LocalDate} and lists
 *                      of them). An adapter converts them to the model's own types at its boundary.
 * @param timeout       how long the invoker has: the node's {@code timeoutMs}, or less when the
 *                      evaluation's own timeout leaves less
 * @param correlationId the caller's id of the business transaction, from {@link DecisionRef#correlationId()}
 */
public record ModelCall(String modelId, String modelVersion, Map<String, Object> features, Duration timeout,
                        Optional<String> correlationId) {

    public ModelCall {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(correlationId, "correlationId");
        features.forEach((name, value) -> {
            Objects.requireNonNull(name, "feature name");
            Objects.requireNonNull(value, () -> "value of feature '" + name + "'");
        });
        features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    @Override
    public String toString() {
        return "ModelCall[" + modelId + "@" + modelVersion + ", features " + features.keySet()
                + ", timeout " + timeout + "]";
    }
}
