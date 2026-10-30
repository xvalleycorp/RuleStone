//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A model result that the caller passes with the facts for one {@code model} node: either the
 * {@link ModelResult}, or the failure the caller met when it called the model itself. See
 * {@link Facts#withModelResult} and {@link Facts#withModelFailure}.
 *
 * @param result  the model's result; present exactly when {@code failure} is empty
 * @param failure {@link ModelStatus#TIMEOUT} or {@link ModelStatus#ERROR}; the engine finds the other
 *                kinds of failure itself when it checks a result
 */
public record SuppliedModel(Optional<ModelResult> result, Optional<ModelStatus> failure) {

    public SuppliedModel {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(failure, "failure");
        if (result.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one of result and failure must be present");
        }
        failure.ifPresent(kind -> {
            if (kind != ModelStatus.TIMEOUT && kind != ModelStatus.ERROR) {
                throw new IllegalArgumentException("a supplied failure is TIMEOUT or ERROR, not " + kind);
            }
        });
    }

    /** The model's result. */
    public static SuppliedModel of(ModelResult result) {
        return new SuppliedModel(Optional.of(result), Optional.empty());
    }

    /** The model failed when the caller called it: {@link ModelStatus#TIMEOUT} or {@link ModelStatus#ERROR}. */
    public static SuppliedModel failed(ModelStatus failure) {
        return new SuppliedModel(Optional.empty(), Optional.of(failure));
    }
}
