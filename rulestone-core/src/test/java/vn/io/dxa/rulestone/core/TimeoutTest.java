//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.TraceStep;

class TimeoutTest {

    private static final String TABLE = """
            {
              "id": "gate_decision",
              "version": "1",
              "hitPolicy": "COLLECT",
              "inputs": { "score": "int" },
              "outputs": { "flag": "string" },
              "rules": [
                { "id": "HIGH", "when": "score >= 70", "then": { "flag": "'HIGH'" } },
                { "id": "MEDIUM", "when": "score >= 40", "then": { "flag": "'MEDIUM'" } },
                { "id": "ANY", "when": "true", "then": { "flag": "'ANY'" } }
              ]
            }
            """;

    private static final DecisionRef REF = DecisionRef.of("gate_decision").asOf(LocalDate.of(2026, 11, 1));
    private static final Facts FACTS = Facts.of("score", 55);

    /** Advances one millisecond every time it is read: once when evaluation starts, then before each rule. */
    private final AtomicLong nanos = new AtomicLong();
    private final List<DecisionRecord> audited = new ArrayList<>();

    private RuleStone.Builder builder() {
        return RuleStone.builder()
                .decision(DecisionTableJson.read(TABLE))
                .nanoTime(() -> nanos.getAndAdd(1_000_000))
                .auditSink(audited::add);
    }

    @Test
    void anEvaluationThatOverrunsItsTimeoutFailsWithTheRulesExaminedSoFar() {
        RuleStoneEngine engine = builder().build();

        DecisionTimeoutException e = catchThrowableOfType(DecisionTimeoutException.class,
                () -> engine.evaluate(REF.timeout(Duration.ofMillis(2)), FACTS));

        assertThat(e).isInstanceOf(DecisionEvaluationException.class);
        assertThat(e.decisionId()).isEqualTo("gate_decision");
        assertThat(e.ruleVersion()).startsWith("1@sha256:");
        assertThat(e.timeout()).isEqualTo(Duration.ofMillis(2));
        assertThat(e.trace()).containsExactly(new TraceStep("HIGH", false), new TraceStep("MEDIUM", true));
        assertThat(e).hasMessageEndingWith(": evaluation timed out after 2 ms with 2 rule(s) examined");
    }

    @Test
    void aTimedOutEvaluationIsRecordedAsCutShort() {
        RuleStoneEngine engine = builder().build();

        assertThatThrownBy(() -> engine.evaluate(REF.timeout(Duration.ofMillis(2)), FACTS))
                .isInstanceOf(DecisionTimeoutException.class);

        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.TIMEOUT);
        assertThat(record.output()).isEmpty();
        assertThat(record.error()).hasValueSatisfying(error -> assertThat(error).contains("timed out after 2 ms"));
        assertThat(record.factsHash()).isPresent();
        assertThat(record.path()).containsExactly(
                new PathStep("gate_decision", "table", record.version().orElseThrow(), List.of("MEDIUM")));
        assertThat(DecisionRecordJson.toJson(record).get("status").asText()).isEqualTo("TIMEOUT");
    }

    @Test
    void anEvaluationWithinItsTimeoutReturnsItsResult() {
        DecisionResult result = builder().build().evaluate(REF.timeout(Duration.ofMillis(3)), FACTS);

        assertThat(result.matchedRules()).containsExactly("MEDIUM", "ANY");
        assertThat(result.record().status()).isEqualTo(Status.OK);
    }

    @Test
    void theEngineTimeoutAppliesToRequestsWithoutTheirOwn() {
        RuleStoneEngine engine = builder().timeout(Duration.ofMillis(1)).build();

        DecisionTimeoutException e = catchThrowableOfType(DecisionTimeoutException.class,
                () -> engine.evaluate(REF, FACTS));
        assertThat(e.timeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(e.trace()).containsExactly(new TraceStep("HIGH", false));

        // The request's own timeout replaces the engine's, also when it is longer.
        assertThat(engine.evaluate(REF.timeout(Duration.ofSeconds(1)), FACTS).matched()).isTrue();
    }

    @Test
    void withoutATimeoutTheTimeIsNeverRead() {
        builder().build().evaluate(REF, FACTS);

        assertThat(nanos.get()).isZero();
    }

    @Test
    void timeoutsMustBePositive() {
        assertThatThrownBy(() -> REF.timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout must be positive");
        assertThatThrownBy(() -> RuleStone.builder().timeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout must be positive");
    }

    @Test
    void aTimeoutIsMeasuredWithTheMonotonicClockByDefault() {
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(TABLE)).build();

        assertThat(engine.evaluate(REF.timeout(Duration.ofSeconds(10)), FACTS).matched()).isTrue();
        assertThatThrownBy(() -> engine.evaluate(REF.timeout(Duration.ofNanos(1)), FACTS))
                .isInstanceOf(DecisionTimeoutException.class);
    }

    @Test
    void aTimeoutTooLongToMeasureMeansNoTimeout() {
        RuleStoneEngine engine = builder().build();

        assertThat(engine.evaluate(REF.timeout(Duration.ofDays(365L * 1_000)), FACTS).matched()).isTrue();
    }

    @Test
    void theRefKeepsItsTimeoutThroughEveryWither() {
        DecisionRef ref = DecisionRef.of("d").timeout(Duration.ofMillis(5)).version("2")
                .asOf(LocalDate.of(2026, 1, 1)).correlationId("TXN-1");

        assertThat(ref.timeout()).contains(Duration.ofMillis(5));
        assertThat(new DecisionRef("d", ref.version(), ref.asOf(), ref.correlationId()).timeout()).isEmpty();
    }
}
