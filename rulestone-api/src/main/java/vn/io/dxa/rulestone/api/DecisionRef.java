//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Identifies which decision to evaluate, and which version of it.
 *
 * <p>When {@code version} is empty the engine selects the version effective on {@code asOf}.
 * When {@code asOf} is empty the engine uses the current business date from its clock.
 * Passing {@code asOf} explicitly makes a decision reproducible: replaying it later
 * selects exactly the rule version that was effective on that date.
 *
 * <p>The same date, whether given or taken from the clock, is passed to the rules as the
 * implicit {@code businessDate} input, also when {@code version} is given.
 *
 * <p>{@code correlationId} is the caller's id for the business transaction, such as a loan
 * application number. It is copied into the {@link DecisionRecord}, so that the record can
 * be joined with the bank's own data. It must not itself contain personal data.
 */
public record DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf,
                          Optional<String> correlationId) {

    public DecisionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(correlationId, "correlationId");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (correlationId.isPresent() && correlationId.get().isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }

    /** A reference without a correlation id. */
    public DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf) {
        this(id, version, asOf, Optional.empty());
    }

    public static DecisionRef of(String id) {
        return new DecisionRef(id, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public DecisionRef version(String version) {
        return new DecisionRef(id, Optional.of(version), asOf, correlationId);
    }

    public DecisionRef asOf(LocalDate date) {
        return new DecisionRef(id, version, Optional.of(date), correlationId);
    }

    public DecisionRef correlationId(String correlationId) {
        return new DecisionRef(id, version, asOf, Optional.of(correlationId));
    }
}
