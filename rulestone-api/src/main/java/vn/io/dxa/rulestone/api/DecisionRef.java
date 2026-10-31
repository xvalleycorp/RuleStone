//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

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
 * be joined with the bank's own data. It must not itself contain personal data. When the
 * engine routes a share of the traffic to a canary version, the correlation id decides which
 * share a request falls into.
 *
 * <p>{@code timeout} bounds how long the evaluation may take, overriding the engine's default.
 * Pass the time left of the caller's own deadline, so that a slow evaluation fails with a
 * {@link DecisionTimeoutException} instead of overrunning it.
 *
 * <p>{@code params} forces versions of parameter sets, by set id, regardless of dates, to replay a
 * decision with the parameter set versions its record lists. A table or flow that pins a version
 * of a set in its own {@code params} declaration keeps using that version.
 */
public record DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf,
                          Optional<String> correlationId, Optional<Duration> timeout, Map<String, String> params) {

    public DecisionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(timeout, "timeout");
        params.forEach((set, v) -> {
            Objects.requireNonNull(set, "parameter set id");
            Objects.requireNonNull(v, () -> "version of parameter set " + set);
        });
        params = Collections.unmodifiableMap(new TreeMap<>(params));
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (correlationId.isPresent() && correlationId.get().isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (timeout.isPresent() && (timeout.get().isNegative() || timeout.get().isZero())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /** A reference that forces no parameter set version. */
    public DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf,
                       Optional<String> correlationId, Optional<Duration> timeout) {
        this(id, version, asOf, correlationId, timeout, Map.of());
    }

    /** A reference without a correlation id or timeout. */
    public DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf) {
        this(id, version, asOf, Optional.empty(), Optional.empty());
    }

    /** A reference without a timeout. */
    public DecisionRef(String id, Optional<String> version, Optional<LocalDate> asOf,
                       Optional<String> correlationId) {
        this(id, version, asOf, correlationId, Optional.empty());
    }

    public static DecisionRef of(String id) {
        return new DecisionRef(id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    /**
     * Exactly this version, whatever its effective dates: also one that takes effect only
     * later, or is no longer in force.
     */
    public DecisionRef version(String version) {
        return new DecisionRef(id, Optional.of(version), asOf, correlationId, timeout, params);
    }

    public DecisionRef asOf(LocalDate date) {
        return new DecisionRef(id, version, Optional.of(date), correlationId, timeout, params);
    }

    public DecisionRef correlationId(String correlationId) {
        return new DecisionRef(id, version, asOf, Optional.of(correlationId), timeout, params);
    }

    /**
     * Fails the evaluation with a {@link DecisionTimeoutException} if it takes longer than
     * {@code timeout}, measured from the call to {@link RuleStoneEngine#evaluate}.
     *
     * @param timeout a positive duration
     */
    public DecisionRef timeout(Duration timeout) {
        return new DecisionRef(id, version, asOf, correlationId, Optional.of(timeout), params);
    }

    /**
     * Uses exactly {@code version} of parameter set {@code set}, whatever its effective dates, for
     * replaying a decision with the versions its record lists in {@link DecisionRecord#params()}.
     * A table or flow that pins a version of the set keeps its pinned version. A forced version
     * that the set does not have fails the evaluation with a {@link DecisionNotFoundException}.
     *
     * @param set     the parameter set id
     * @param version its version label, e.g. {@code 7}
     */
    public DecisionRef params(String set, String version) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(version, "version");
        Map<String, String> forced = new TreeMap<>(params);
        forced.put(set, version);
        return new DecisionRef(id, this.version, asOf, correlationId, timeout, forced);
    }
}
