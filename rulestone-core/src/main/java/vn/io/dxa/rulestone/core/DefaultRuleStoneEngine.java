//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.api.AuditException;
import vn.io.dxa.rulestone.api.AuditSink;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.Bindings;

/** Embedded engine over an immutable set of compiled decisions. */
final class DefaultRuleStoneEngine implements RuleStoneEngine {

    /** Names the engine in every record, e.g. {@code rulestone 0.1.0}; {@code dev} when not run from a jar. */
    static final String ENGINE = "rulestone " + Optional.ofNullable(
            DefaultRuleStoneEngine.class.getPackage().getImplementationVersion()).orElse("dev");

    private static final String TABLE = "table";

    private final Map<String, List<CompiledDecision>> decisions;
    private final Clock clock;
    private final List<AuditSink> sinks;
    private final FactsHasher hasher;
    private final Supplier<String> recordIds;

    DefaultRuleStoneEngine(Map<String, List<CompiledDecision>> decisions, Clock clock, List<AuditSink> sinks,
                           FactsHasher hasher, Supplier<String> recordIds) {
        this.decisions = Map.copyOf(decisions);
        this.clock = clock;
        this.sinks = List.copyOf(sinks);
        this.hasher = hasher;
        this.recordIds = recordIds;
    }

    @Override
    public DecisionResult evaluate(DecisionRef ref, Facts facts) {
        // Read the clock once: the same instant dates the record, and its date in the clock's
        // zone selects the version and is the businessDate input.
        Instant evaluatedAt = clock.instant();
        LocalDate businessDate = ref.asOf().orElseGet(() -> LocalDate.ofInstant(evaluatedAt, clock.getZone()));
        Attempt attempt = new Attempt(recordIds.get(), ref, businessDate, evaluatedAt);
        DecisionResult result;
        try {
            result = attempt.run(facts);
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

    private CompiledDecision resolve(DecisionRef ref, LocalDate date) {
        List<CompiledDecision> versions = decisions.get(ref.id());
        if (versions == null) {
            throw new DecisionNotFoundException("unknown decision '" + ref.id() + "'");
        }
        if (ref.version().isPresent()) {
            String version = ref.version().get();
            return versions.stream()
                    .filter(d -> d.table().version().equals(version))
                    .findFirst()
                    .orElseThrow(() -> new DecisionNotFoundException(
                            "decision '" + ref.id() + "' has no version '" + version + "'"));
        }
        return versions.stream()
                .filter(d -> d.table().isEffectiveOn(date))
                .findFirst()
                .orElseThrow(() -> new DecisionNotFoundException(
                        "no version of decision '" + ref.id() + "' is effective on " + date));
    }

    /** One evaluation, keeping what is known so far so that a failure can still be recorded. */
    private final class Attempt {

        private final String recordId;
        private final DecisionRef ref;
        private final LocalDate businessDate;
        private final Instant evaluatedAt;
        private final List<TraceStep> trace = new ArrayList<>();
        private CompiledDecision decision;
        private Optional<String> factsHash = Optional.empty();

        Attempt(String recordId, DecisionRef ref, LocalDate businessDate, Instant evaluatedAt) {
            this.recordId = recordId;
            this.ref = ref;
            this.businessDate = businessDate;
            this.evaluatedAt = evaluatedAt;
        }

        DecisionResult run(Facts facts) {
            decision = resolve(ref, businessDate);
            Bindings bindings = decision.bind(facts, businessDate);
            factsHash = Optional.of(hasher.hash(bindings));
            CompiledDecision.Outcome outcome = decision.evaluate(bindings, trace);
            String source = decision.table().id();
            DecisionRecord record = record(Status.OK, outcome.output(),
                    outcome.reasonCodes().stream().map(code -> new ReasonCode(code, source)).toList(),
                    Optional.empty());
            return new DecisionResult(decision.table().id(), decision.ruleVersion(), outcome.output(),
                    outcome.reasonCodes(), trace, record);
        }

        DecisionRecord failed(RuntimeException e) {
            // RuleStone messages never contain input values; other exceptions might, so only
            // their type is recorded.
            String error = e instanceof RuleStoneException
                    ? e.getMessage()
                    : "internal error: " + e.getClass().getName();
            return record(Status.ERROR, Map.of(), List.of(), Optional.of(error));
        }

        private DecisionRecord record(Status status, Map<String, Object> output, List<ReasonCode> reasonCodes,
                                      Optional<String> error) {
            List<PathStep> path = decision == null ? List.of() : List.of(new PathStep(
                    decision.table().id(), TABLE, decision.ruleVersion(),
                    trace.stream().filter(TraceStep::matched).map(TraceStep::ruleId).toList()));
            return new DecisionRecord(recordId, ref.correlationId(), ref.id(),
                    Optional.ofNullable(decision).map(CompiledDecision::ruleVersion), businessDate, evaluatedAt,
                    ENGINE, status, factsHash, output, reasonCodes, path, error);
        }
    }
}
