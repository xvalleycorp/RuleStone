//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.api.AuditSink;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;

/**
 * Entry point for building an embedded engine.
 *
 * <pre>{@code
 * RuleStoneEngine engine = RuleStone.builder()
 *     .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
 *     .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.stone.json")))
 *     .build();
 * }</pre>
 *
 * <p>{@link Builder#build()} compiles and type-checks every rule up front, so an invalid
 * definition fails at startup (or at reload) rather than in the middle of a transaction.
 */
public final class RuleStone {

    private RuleStone() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<DecisionTable> tables = new ArrayList<>();
        private Clock clock = Clock.systemDefaultZone();
        private int maxIterations = ExpressionEnvironment.DEFAULT_MAX_ITERATIONS;
        private final Map<String, Integer> currencyScales = new HashMap<>();
        private final List<AuditSink> auditSinks = new ArrayList<>();
        private byte[] factsHashKey;
        private Supplier<String> recordIds = () -> UUID.randomUUID().toString();

        private Builder() {}

        /**
         * Clock used to pick the business date when a request does not specify one. The business
         * date selects the decision version and is the value of every rule's implicit
         * {@code businessDate} input. Set it to the bank's time zone; the default is the JVM's.
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder decision(DecisionTable table) {
            tables.add(Objects.requireNonNull(table, "table"));
            return this;
        }

        public Builder decisions(Collection<DecisionTable> tables) {
            tables.forEach(this::decision);
            return this;
        }

        /** Upper bound on iterations of any comprehension in a rule; see {@link ExpressionEnvironment}. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Overrides the ISO 4217 minor units that {@code money.round()} uses for {@code currency}
         * in every rule; see {@link ExpressionEnvironment.Builder#currencyScale}. Needed for
         * currencies without minor units in ISO 4217, such as {@code XAU}.
         *
         * @param currency an upper-case ISO 4217 code, e.g. {@code VND}
         * @param digits   digits after the decimal point, 0 to 18
         * @throws IllegalArgumentException if the code is not ISO 4217 or {@code digits} is out of range
         */
        public Builder currencyScale(String currency, int digits) {
            ExpressionEnvironment.builder().currencyScale(currency, digits); // validates the arguments
            currencyScales.put(currency, digits);
            return this;
        }

        /**
         * Adds a sink for the {@link DecisionRecord} of every evaluation, successful or not.
         * Sinks are called in the order added, before the result is returned or the exception
         * thrown. If a sink throws, the evaluation fails with an
         * {@link vn.io.dxa.rulestone.api.AuditException}; see {@link AuditSink}.
         */
        public Builder auditSink(AuditSink sink) {
            auditSinks.add(Objects.requireNonNull(sink, "sink"));
            return this;
        }

        /**
         * Key for {@link DecisionRecord#factsHash()}. With a key the hash is HMAC-SHA256, so that
         * nobody without the key can test guesses of low-variety inputs, such as a birth date,
         * against a record. Without one it is plain SHA-256. Use at least 32 random bytes,
         * kept in the bank's key store, and the same key wherever records are verified.
         */
        public Builder factsHashKey(byte[] key) {
            Objects.requireNonNull(key, "key");
            if (key.length < 16) {
                throw new IllegalArgumentException("factsHashKey must be at least 16 bytes");
            }
            this.factsHashKey = key.clone();
            return this;
        }

        /** Source of {@link DecisionRecord#recordId()} values; the default is a random UUID. Must be thread-safe. */
        public Builder recordIds(Supplier<String> recordIds) {
            this.recordIds = Objects.requireNonNull(recordIds, "recordIds");
            return this;
        }

        /**
         * @throws InvalidDecisionException listing every problem in every registered table,
         *         including inconsistent versions (see {@link DecisionVersions#problems})
         */
        public RuleStoneEngine build() {
            List<String> problems = new ArrayList<>();
            Map<String, List<CompiledDecision>> byId = new LinkedHashMap<>();
            for (DecisionTable table : tables) {
                CompiledDecision.compile(table, maxIterations, currencyScales, problems)
                        .ifPresent(d -> byId.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(d));
            }
            byId.values().forEach(versions -> problems.addAll(
                    DecisionVersions.problems(versions.stream().map(CompiledDecision::table).toList())));
            if (!problems.isEmpty()) {
                throw new InvalidDecisionException(problems);
            }
            byId.replaceAll((id, versions) -> List.copyOf(versions));
            Supplier<String> ids = recordIds;
            Supplier<String> checkedIds = () -> {
                String id = ids.get();
                if (id == null || id.isBlank()) {
                    throw new IllegalStateException("recordIds supplied an empty record id");
                }
                return id;
            };
            return new DefaultRuleStoneEngine(byId, clock, auditSinks, new FactsHasher(factsHashKey), checkedIds);
        }
    }
}
