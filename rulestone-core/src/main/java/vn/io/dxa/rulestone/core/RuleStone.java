//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.api.AuditSink;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.ModelInvoker;
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
        private final List<DecisionFlow> flows = new ArrayList<>();
        private Clock clock = Clock.systemDefaultZone();
        private int maxIterations = ExpressionEnvironment.DEFAULT_MAX_ITERATIONS;
        private final Map<String, Integer> currencyScales = new HashMap<>();
        private final List<AuditSink> auditSinks = new ArrayList<>();
        private byte[] factsHashKey;
        private Supplier<String> recordIds = () -> UUID.randomUUID().toString();
        private Optional<Duration> timeout = Optional.empty();
        private LongSupplier nanoTime = System::nanoTime;
        private final Map<String, String> canaryVersions = new LinkedHashMap<>();
        private final Map<String, Double> canaryShares = new HashMap<>();
        private final Map<String, String> shadowVersions = new LinkedHashMap<>();
        private Executor shadowExecutor = Runnable::run;
        private final Map<String, ModelInvoker> modelInvokers = new LinkedHashMap<>();
        private boolean suppliedModelsOnly;

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

        /**
         * Registers a version of a decision flow. Flows are evaluated by id like decision tables,
         * share their id namespace, and are versioned and effective-dated the same way. Every
         * decision table a flow uses must be registered with the same builder.
         */
        public Builder flow(DecisionFlow flow) {
            flows.add(Objects.requireNonNull(flow, "flow"));
            return this;
        }

        public Builder flows(Collection<DecisionFlow> flows) {
            flows.forEach(this::flow);
            return this;
        }

        /**
         * Registers the invoker that calls model {@code modelId} for the {@code model} nodes of
         * flows in {@code invoke} mode. Every such node needs one, unless the engine is built with
         * {@link #suppliedModelsOnly()}. The invoker receives the node's pinned version in each
         * {@link vn.io.dxa.rulestone.api.ModelCall}, so one invoker can serve every version of a model.
         *
         * @throws IllegalArgumentException if {@code modelId} already has an invoker
         */
        public Builder modelInvoker(String modelId, ModelInvoker invoker) {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(invoker, "invoker");
            if (modelInvokers.putIfAbsent(modelId, invoker) != null) {
                throw new IllegalArgumentException("model '" + modelId + "' already has an invoker");
            }
            return this;
        }

        /**
         * Runs every {@code model} node in {@code supplied} mode: the engine calls no model, and
         * reads each node's result from the facts ({@link vn.io.dxa.rulestone.api.Facts#withModelResult}).
         * A node whose result is not supplied fails with {@code MISSING} and takes its
         * {@code onFailure} path. Meant for tests, which then need no model server and are
         * deterministic, and for replaying recorded decisions; not for production.
         */
        public Builder suppliedModelsOnly() {
            this.suppliedModelsOnly = true;
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
         * Default timeout of every evaluation, for requests without
         * {@link DecisionRef#timeout(Duration)}. An evaluation that takes longer fails with a
         * {@link DecisionTimeoutException}. The engine checks the time before each rule, so it
         * never interrupts a rule and needs no thread of its own. By default there is no timeout.
         *
         * @param timeout a positive duration
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = Optional.of(timeout);
            return this;
        }

        /**
         * Routes a share of the traffic of decision {@code decisionId} to {@code version} instead
         * of the version in force (a canary release). Which requests are routed depends only on
         * their {@link DecisionRef#correlationId correlation id}, so a retried transaction always
         * gets the same version. Requests without a correlation id, and requests that name a
         * version, are never routed. The record of a routed evaluation says so in
         * {@link DecisionRecord#routing()}.
         *
         * <p>A request is routed when the first 64 bits of the SHA-256 of
         * {@code decisionId + "
" + correlationId}, read as a fraction of 2<sup>64</sup>, are
         * below {@code share}.
         *
         * @param share the share of correlation ids to route, above 0 and at most 1, e.g. {@code 0.05}
         * @throws IllegalArgumentException if {@code share} is out of range, or the decision already has a canary
         */
        public Builder canary(String decisionId, String version, double share) {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(version, "version");
            if (!(share > 0 && share <= 1)) {
                throw new IllegalArgumentException("canary share must be above 0 and at most 1");
            }
            if (canaryVersions.putIfAbsent(decisionId, version) != null) {
                throw new IllegalArgumentException("decision '" + decisionId + "' already has a canary");
            }
            canaryShares.put(decisionId, share);
            return this;
        }

        /**
         * Evaluates {@code version} of decision {@code decisionId} on the same facts as every
         * request for that decision that does not name a version (shadow mode), after the
         * request's own evaluation. The shadow's record goes to the audit sinks, with
         * {@link DecisionRecord#routing()} linking it to the record of the request; its result
         * is never returned. A shadow that fails, or whose record a sink rejects, never affects
         * the request. By default shadows run on the caller's thread; see {@link #shadowExecutor}.
         *
         * @throws IllegalArgumentException if the decision already has a shadow
         */
        public Builder shadow(String decisionId, String version) {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(version, "version");
            if (shadowVersions.putIfAbsent(decisionId, version) != null) {
                throw new IllegalArgumentException("decision '" + decisionId + "' already has a shadow");
            }
            return this;
        }

        /**
         * Runs shadow evaluations on {@code executor} instead of the caller's thread, so that
         * they add no latency to requests. A shadow the executor rejects is skipped. The facts
         * of a request must then not change after {@code evaluate} returns.
         */
        public Builder shadowExecutor(Executor executor) {
            this.shadowExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Source of the monotonic time that timeouts are measured with; for tests. */
        Builder nanoTime(LongSupplier nanoTime) {
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            return this;
        }

        /**
         * @throws InvalidDecisionException listing every problem in every registered table and
         *         flow, including inconsistent versions (see {@link DecisionVersions#problems}) and
         *         canaries or shadows of unknown versions
         */
        public RuleStoneEngine build() {
            List<String> problems = new ArrayList<>();
            Map<String, List<CompiledDecision>> compiledTables = new LinkedHashMap<>();
            Map<String, List<DecisionTable>> registered = new LinkedHashMap<>();
            for (DecisionTable table : tables) {
                registered.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(table);
                CompiledDecision.compile(table, maxIterations, currencyScales, problems)
                        .ifPresent(d -> compiledTables.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(d));
            }
            compiledTables.values().forEach(versions -> problems.addAll(
                    DecisionVersions.problems(versions.stream().map(CompiledDecision::table).toList())));
            Set<String> flowIds = new HashSet<>();
            flows.forEach(f -> flowIds.add(f.id()));
            Map<String, List<Executable>> byId = new LinkedHashMap<>();
            compiledTables.forEach((id, versions) -> byId.put(id, new ArrayList<>(versions)));
            Map<String, List<DecisionFlow>> flowVersions = new LinkedHashMap<>();
            CompiledFlow.Models models = new CompiledFlow.Models(modelInvokers, suppliedModelsOnly, nanoTime);
            for (DecisionFlow flow : flows) {
                flowVersions.computeIfAbsent(flow.id(), id -> new ArrayList<>()).add(flow);
                CompiledFlow.compile(flow, compiledTables, registered, flowIds, maxIterations, currencyScales, models,
                                problems)
                        .ifPresent(f -> byId.computeIfAbsent(flow.id(), id -> new ArrayList<>()).add(f));
            }
            flowVersions.values().forEach(versions -> problems.addAll(DecisionVersions.problems(versions)));
            Map<String, DefaultRuleStoneEngine.Canary> canaries = new HashMap<>();
            canaryVersions.forEach((id, version) -> route("canary", id, version, byId, problems)
                    .ifPresent(d -> canaries.put(id, new DefaultRuleStoneEngine.Canary(d, canaryShares.get(id)))));
            Map<String, Executable> shadows = new HashMap<>();
            shadowVersions.forEach((id, version) -> route("shadow", id, version, byId, problems)
                    .ifPresent(d -> shadows.put(id, d)));
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
            return new DefaultRuleStoneEngine(byId, clock, auditSinks, new FactsHasher(factsHashKey), checkedIds,
                    timeout, nanoTime, new DefaultRuleStoneEngine.Routes(canaries, shadows, shadowExecutor));
        }

        /** The compiled {@code version} of decision {@code id}, or a problem if it was never registered. */
        private Optional<Executable> route(String kind, String id, String version,
                                           Map<String, List<Executable>> byId, List<String> problems) {
            Optional<Executable> decision = byId.getOrDefault(id, List.of()).stream()
                    .filter(d -> d.definition().version().equals(version)).findFirst();
            List<VersionedDefinition> definitions = new ArrayList<>(tables);
            definitions.addAll(flows);
            boolean registered = definitions.stream().anyMatch(t -> t.id().equals(id) && t.version().equals(version));
            if (!registered) {
                problems.add(kind + " " + id + "@" + version + ": "
                        + (definitions.stream().anyMatch(t -> t.id().equals(id))
                                ? "decision '" + id + "' has no version '" + version + "'"
                                : "unknown decision '" + id + "'"));
            }
            // A registered version that did not compile is already reported.
            return decision;
        }
    }
}
