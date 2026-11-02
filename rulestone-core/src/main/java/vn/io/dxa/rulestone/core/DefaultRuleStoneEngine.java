//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.api.AuditException;
import vn.io.dxa.rulestone.api.AuditSink;
import vn.io.dxa.rulestone.api.DecisionComparison;
import vn.io.dxa.rulestone.api.DecisionComparison.Difference;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Routing;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Explanation;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelSource;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;

/** Embedded engine over an immutable set of compiled decision tables and flows. */
final class DefaultRuleStoneEngine implements RuleStoneEngine {

    /** Names the engine in every record, e.g. {@code rulestone 0.1.0}; {@code dev} when not run from a jar. */
    static final String ENGINE = "rulestone " + Optional.ofNullable(
            DefaultRuleStoneEngine.class.getPackage().getImplementationVersion()).orElse("dev");

    /** A version that receives {@code share} of the correlation ids of a decision instead of the version in force. */
    record Canary(Executable decision, double share) {}

    /** How the engine routes evaluations to versions other than the one in force; see {@link RuleStone.Builder#canary}. */
    record Routes(Map<String, Canary> canaries, Map<String, Executable> shadows, Executor shadowExecutor) {

        Routes {
            canaries = Map.copyOf(canaries);
            shadows = Map.copyOf(shadows);
        }
    }

    private final Map<String, List<Executable>> decisions;
    private final Clock clock;
    private final List<AuditSink> sinks;
    private final FactsHasher hasher;
    private final Supplier<String> recordIds;
    private final Optional<Duration> timeout;
    private final LongSupplier nanoTime;
    private final Routes routes;
    private final Optional<ReasonCodeCatalog> catalog;
    private final String catalogVersion;
    private final Parameters.Registry parameterSets;

    DefaultRuleStoneEngine(Map<String, List<Executable>> decisions, Clock clock, List<AuditSink> sinks,
                           FactsHasher hasher, Supplier<String> recordIds, Optional<Duration> timeout,
                           LongSupplier nanoTime, Routes routes, Optional<ReasonCodeCatalog> catalog,
                           Parameters.Registry parameterSets) {
        this.parameterSets = parameterSets;
        this.decisions = Map.copyOf(decisions);
        this.clock = clock;
        this.sinks = List.copyOf(sinks);
        this.hasher = hasher;
        this.recordIds = recordIds;
        this.timeout = timeout;
        this.nanoTime = nanoTime;
        this.routes = routes;
        this.catalog = catalog;
        this.catalogVersion = catalog.map(ReasonCodeCatalogJson::exactVersion).orElse(null);
    }

    @Override
    public Explanation explain(DecisionRecord record, String locale) {
        ReasonCodeCatalog reasons = catalog.orElseThrow(() -> new UnsupportedOperationException(
                "this engine has no reason-code catalog; build it with RuleStone.builder().reasonCodeCatalog(...)"));
        return Explanations.explain(record, reasons, catalogVersion, locale);
    }

    @Override
    public DecisionResult evaluate(DecisionRef ref, Facts facts) {
        // The timeout counts from here, so that it covers everything the caller waits for.
        Deadline deadline = deadline(ref);
        // Read the clock once: the same instant dates the record, and its date in the clock's
        // zone selects the version and is the businessDate input.
        Instant evaluatedAt = clock.instant();
        LocalDate businessDate = ref.asOf().orElseGet(() -> LocalDate.ofInstant(evaluatedAt, clock.getZone()));
        Attempt attempt = new Attempt(recordIds.get(), ref, businessDate, evaluatedAt);
        try {
            DecisionResult result;
            try {
                result = attempt.run(facts, deadline);
            } catch (RuntimeException e) {
                DecisionRecord failed = attempt.failed(e);
                try {
                    audit(failed);
                } catch (AuditException audit) {
                    e.addSuppressed(audit);
                }
                throw e;
            }
            audit(result.record());
            return result;
        } finally {
            shadow(attempt, facts);
        }
    }

    @Override
    public DecisionComparison replay(DecisionRecord record, Facts facts) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(facts, "facts");
        if (!facts.models().isEmpty()) {
            throw new IllegalArgumentException("a replay takes the model results from the record; the facts must "
                    + "not carry any, but carry " + facts.models().keySet());
        }
        Attempt attempt = replayAttempt(record);
        Optional<DecisionResult> result = run(attempt, facts, Deadline.NONE);
        DecisionRecord evaluated = result.map(DecisionResult::record).orElseGet(() -> attempt.failure);
        List<Difference> differences = new ArrayList<>();
        Comparisons.factsHash(record.factsHash(), evaluated.factsHash()).ifPresent(differences::add);
        differences.addAll(Comparisons.compare(record, evaluated));
        return new DecisionComparison(record, evaluated, result, differences);
    }

    @Override
    public DecisionComparison rescore(DecisionRecord record, Facts facts, DecisionRef target, ModelSource models) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(models, "models");
        if (!target.id().equals(record.decision())) {
            throw new IllegalArgumentException("a re-score evaluates the recorded decision '" + record.decision()
                    + "', not '" + target.id() + "'");
        }
        // The facts are checked with the recorded version, whose inputs the hash covers.
        Attempt check = replayAttempt(record);
        Optional<DecisionResult> original = run(check, facts, Deadline.NONE);
        Optional<String> originalHash = original.map(r -> r.record().factsHash())
                .orElseGet(() -> check.failure.factsHash());

        DecisionRef ref = target.correlationId().isPresent() || record.correlationId().isEmpty()
                ? target : target.correlationId(record.correlationId().get());
        Instant evaluatedAt = clock.instant();
        LocalDate businessDate = ref.asOf().orElseGet(() -> LocalDate.ofInstant(evaluatedAt, clock.getZone()));
        Progress.Overrides overrides = models == ModelSource.RECORDED
                ? new Progress.Overrides(Map.of(), recordedModels(record), true)
                : Progress.Overrides.NONE;
        Attempt attempt = new Attempt(recordIds.get(), ref, businessDate, evaluatedAt,
                new Progress(ref.params(), overrides));
        attempt.decision = resolve(ref, businessDate);
        Optional<DecisionResult> result = run(attempt, facts, deadline(ref));
        DecisionRecord evaluated = result.map(DecisionResult::record).orElseGet(() -> attempt.failure);
        List<Difference> differences = new ArrayList<>();
        Comparisons.factsHash(record.factsHash(), originalHash).ifPresent(differences::add);
        differences.addAll(Comparisons.compare(record, evaluated));
        return new DecisionComparison(record, evaluated, result, differences);
    }

    /**
     * An attempt that evaluates exactly what {@code record} names: its version, business date and
     * parameter set versions, the version of each table step, and the recorded model results.
     *
     * @throws DecisionNotFoundException if one of these versions is not registered with exactly the
     *         recorded content hash
     */
    private Attempt replayAttempt(DecisionRecord record) {
        String recorded = record.version().orElseThrow(() -> new IllegalArgumentException("record "
                + record.recordId() + " names no version of decision '" + record.decision()
                + "': none was found when it was made, so there is nothing to replay"));
        Executable decision = exact(record.decision(), recorded);
        Map<String, String> tables = new LinkedHashMap<>();
        for (PathStep step : record.path()) {
            if (step.type().equals("table") && step.decision().isPresent() && step.version().isPresent()) {
                exact(step.decision().get(), step.version().get());
                tables.put(step.node(), step.version().get());
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (ParamSetVersion used : record.params()) {
            exactParams(used);
            params.put(used.set(), label(used.version()));
        }
        DecisionRef ref = new DecisionRef(record.decision(), Optional.of(label(recorded)),
                Optional.of(record.businessDate()), record.correlationId(), Optional.empty(), params);
        Attempt attempt = new Attempt(recordIds.get(), ref, record.businessDate(), clock.instant(),
                new Progress(params, new Progress.Overrides(tables, recordedModels(record), true)));
        attempt.decision = decision;
        return attempt;
    }

    /** The recorded step of every model node, by node id. */
    private static Map<String, ModelStep> recordedModels(DecisionRecord record) {
        Map<String, ModelStep> models = new LinkedHashMap<>();
        record.path().forEach(step -> step.model().ifPresent(model -> models.put(step.node(), model)));
        return models;
    }

    /**
     * Runs an attempt without writing to the audit sinks, keeping its record on failure.
     *
     * @return the result, or empty if the evaluation failed; {@code attempt.failure} then holds its record
     */
    private Optional<DecisionResult> run(Attempt attempt, Facts facts, Deadline deadline) {
        try {
            return Optional.of(attempt.run(facts, deadline));
        } catch (RuntimeException e) {
            attempt.failure = attempt.failed(e);
            return Optional.empty();
        }
    }

    /** The registered decision whose exact version is {@code exact}, e.g. {@code 4@sha256:…}. */
    private Executable exact(String id, String exact) {
        List<Executable> versions = decisions.get(id);
        if (versions == null) {
            throw new DecisionNotFoundException("unknown decision '" + id + "': the record names version " + exact);
        }
        Optional<Executable> found = versions.stream().filter(d -> d.ruleVersion().equals(exact)).findFirst();
        if (found.isPresent()) {
            return found.get();
        }
        String label = label(exact);
        Optional<String> sameLabel = versions.stream().map(Executable::ruleVersion)
                .filter(v -> label(v).equals(label)).findFirst();
        throw new DecisionNotFoundException("decision '" + id + "' has no version " + exact
                + sameLabel.map(v -> ": version '" + label + "' is registered with other content, " + v
                        + "; register the content the record names").orElse(""));
    }

    /** Checks that the parameter set version {@code used} names is registered with exactly its hash. */
    private void exactParams(ParamSetVersion used) {
        List<Parameters.CompiledSet> versions = parameterSets.compiled().getOrDefault(used.set(), List.of());
        if (versions.stream().anyMatch(c -> c.version().equals(used.version()))) {
            return;
        }
        String label = label(used.version());
        Optional<String> sameLabel = versions.stream().map(Parameters.CompiledSet::version)
                .filter(v -> label(v).equals(label)).findFirst();
        throw new DecisionNotFoundException(Parameters.KIND + " '" + used.set() + "' has no version " + used.version()
                + sameLabel.map(v -> ": version '" + label + "' is registered with other content, " + v
                        + "; register the content the record names").orElse(""));
    }

    /** The label of an exact version: {@code 4} for {@code 4@sha256:…}. */
    private static String label(String exact) {
        int at = exact.indexOf('@');
        return at < 0 ? exact : exact.substring(0, at);
    }

    private Deadline deadline(DecisionRef ref) {
        return ref.timeout().or(() -> timeout).map(t -> Deadline.after(t, nanoTime)).orElse(Deadline.NONE);
    }

    private void audit(DecisionRecord record) {
        for (AuditSink sink : sinks) {
            try {
                sink.write(record);
            } catch (RuntimeException e) {
                throw new AuditException(record.recordId(), e);
            }
        }
    }

    /**
     * Evaluates the shadow version of the decision, if one is configured, on the facts of
     * {@code champion}, and hands its record to the sinks. Best effort: whatever goes wrong,
     * the caller of {@link #evaluate} is never affected.
     */
    private void shadow(Attempt champion, Facts facts) {
        Executable candidate = routes.shadows().get(champion.ref.id());
        if (candidate == null || champion.ref.version().isPresent() || champion.decision == null
                || champion.decision == candidate) {
            return;
        }
        Routing routing = Routing.shadow(champion.decision.ruleVersion(), champion.recordId);
        Runnable shadow = () -> {
            try {
                Attempt attempt = new Attempt(recordIds.get(), champion.ref, champion.businessDate,
                        champion.evaluatedAt);
                attempt.decision = candidate;
                attempt.routing = Optional.of(routing);
                DecisionRecord record;
                try {
                    record = attempt.run(facts, deadline(champion.ref)).record();
                } catch (RuntimeException e) {
                    record = attempt.failed(e);
                }
                audit(record);
            } catch (RuntimeException e) {
                // A shadow is never returned, so a failure to record it cannot affect a decision.
            }
        };
        try {
            routes.shadowExecutor().execute(shadow);
        } catch (RuntimeException e) {
            // The executor refused the shadow, e.g. because its queue is full: skip it.
        }
    }

    private Executable resolve(DecisionRef ref, LocalDate date) {
        List<Executable> versions = decisions.get(ref.id());
        if (versions == null) {
            throw new DecisionNotFoundException("unknown decision '" + ref.id() + "'");
        }
        VersionedDefinition definition = DecisionVersions.select(ref.id(),
                versions.stream().map(Executable::definition).toList(), ref.version(), date);
        return versions.stream().filter(d -> d.definition() == definition).findFirst().orElseThrow();
    }

    /**
     * Where a correlation id falls in the traffic of a decision, in [0, 1): the first 64 bits of
     * the SHA-256 of {@code decisionId + "\n" + correlationId}, as a fraction. A request is routed
     * to a canary with share {@code s} when this is below {@code s}, so the same correlation id
     * always gets the same version, and decisions split their traffic independently.
     */
    static double bucket(String decisionId, String correlationId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((decisionId + "\n" + correlationId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
        long bits = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            bits = bits << 8 | (digest[i] & 0xff);
        }
        return (bits >>> 11) * 0x1.0p-53;
    }

    /** One evaluation, keeping what is known so far so that a failure can still be recorded. */
    private final class Attempt {

        private final String recordId;
        private final DecisionRef ref;
        private final LocalDate businessDate;
        private final Instant evaluatedAt;
        private final Progress progress;
        private Executable decision;
        private Optional<Routing> routing = Optional.empty();
        /** The record of a failed replay or re-score. */
        private DecisionRecord failure;

        Attempt(String recordId, DecisionRef ref, LocalDate businessDate, Instant evaluatedAt) {
            this(recordId, ref, businessDate, evaluatedAt, new Progress(ref.params()));
        }

        Attempt(String recordId, DecisionRef ref, LocalDate businessDate, Instant evaluatedAt, Progress progress) {
            this.recordId = recordId;
            this.ref = ref;
            this.businessDate = businessDate;
            this.evaluatedAt = evaluatedAt;
            this.progress = progress;
        }

        DecisionResult run(Facts facts, Deadline deadline) {
            if (decision == null) {
                select();
            }
            Executable.Run run = decision.run(facts, businessDate, ref.correlationId(), deadline, progress, hasher);
            DecisionRecord record = record(Status.OK, run.output(), run.reasonCodes(), Optional.empty());
            return new DecisionResult(decision.definition().id(), decision.ruleVersion(), run.output(),
                    run.reasonCodes().stream().map(ReasonCode::code).toList(), progress.trace, record);
        }

        /** The version in force, or the canary if the request falls into its share. */
        private void select() {
            decision = resolve(ref, businessDate);
            Canary canary = routes.canaries().get(ref.id());
            if (canary != null && ref.version().isEmpty() && ref.correlationId().isPresent()
                    && canary.decision() != decision && bucket(ref.id(), ref.correlationId().get()) < canary.share()) {
                routing = Optional.of(Routing.canary(decision.ruleVersion(), canary.share()));
                decision = canary.decision();
            }
        }

        DecisionRecord failed(RuntimeException e) {
            // RuleStone messages never contain input values; other exceptions might, so only
            // their type is recorded.
            String error = e instanceof RuleStoneException
                    ? e.getMessage()
                    : "internal error: " + e.getClass().getName();
            Status status = e instanceof DecisionTimeoutException ? Status.TIMEOUT : Status.ERROR;
            return record(status, Map.of(), List.of(), Optional.of(error));
        }

        private DecisionRecord record(Status status, Map<String, Object> output, List<ReasonCode> reasonCodes,
                                      Optional<String> error) {
            return new DecisionRecord(recordId, ref.correlationId(), ref.id(),
                    Optional.ofNullable(decision).map(Executable::ruleVersion), businessDate, evaluatedAt,
                    ENGINE, status, progress.factsHash(), output, reasonCodes, progress.path(), error, routing,
                    progress.params());
        }
    }
}
