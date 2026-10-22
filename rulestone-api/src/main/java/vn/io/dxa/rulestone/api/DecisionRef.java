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
 */
public record DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf) {

    public DecisionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asOf, "asOf");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public static DecisionRef of(String id) {
        return new DecisionRef(id, Optional.empty(), Optional.empty());
    }

    public DecisionRef version(String version) {
        return new DecisionRef(id, Optional.of(version), asOf);
    }

    public DecisionRef asOf(LocalDate date) {
        return new DecisionRef(id, version, Optional.of(date));
    }
}
