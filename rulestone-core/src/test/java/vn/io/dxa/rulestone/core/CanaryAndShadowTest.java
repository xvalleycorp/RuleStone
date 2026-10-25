//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.Routing;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class CanaryAndShadowTest {

    /** Version 1 is in force; version 2 lowers the threshold and takes effect only next year. */
    private static String version(String version, String effectiveFrom, String when) {
        return """
                {
                  "id": "gate_decision",
                  "version": "%s",
                  "effectiveFrom": "%s",
                  "hitPolicy": "FIRST",
                  "inputs": { "score": "int" },
                  "outputs": { "action": "string" },
                  "rules": [
                    { "id": "BLOCK", "when": "%s", "then": { "action": "'BLOCK'" }, "reasonCode": "SCORE_HIGH" },
                    { "id": "ALLOW", "when": "true", "then": { "action": "'ALLOW'" } }
                  ]
                }
                """.formatted(version, effectiveFrom, when);
    }

    private static final DecisionTable V1 = DecisionTableJson.read(version("1", "2026-01-01", "score >= 70"));
    private static final DecisionTable V2 = DecisionTableJson.read(version("2", "2027-01-01", "score >= 60"));
    /** Fails on every request: integer division by zero. */
    private static final DecisionTable V3 = DecisionTableJson.read(version("3", "2028-01-01", "score / 0 > 1"));

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final Facts FACTS = Facts.of("score", 65);

    private final List<DecisionRecord> audited = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private RuleStone.Builder builder() {
        return RuleStone.builder()
                .decisions(List.of(V1, V2, V3))
                .recordIds(() -> "rec-" + ids.incrementAndGet())
                .auditSink(audited::add);
    }

    private static DecisionRef ref(String correlationId) {
        return DecisionRef.of("gate_decision").asOf(TODAY).correlationId(correlationId);
    }

    /** The first correlation id, of the form TXN-n, whose bucket is (or is not) below {@code share}. */
    private static String correlationId(double share, boolean inside) {
        return IntStream.range(0, 10_000).mapToObj(i -> "TXN-" + i)
                .filter(id -> DefaultRuleStoneEngine.bucket("gate_decision", id) < share == inside)
                .findFirst().orElseThrow();
    }

    // Canary

    @Test
    void aRequestInTheCanaryShareGetsTheCanaryVersionAndItsRecordSaysWhy() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 0.05).build();
        String inside = correlationId(0.05, true);

        DecisionResult result = engine.evaluate(ref(inside), FACTS);

        assertThat(result.output()).containsEntry("action", "BLOCK");
        assertThat(result.ruleVersion()).startsWith("2@sha256:");
        DecisionRecord record = result.record();
        assertThat(record.version()).contains(result.ruleVersion());
        assertThat(record.routing()).contains(Routing.canary(versionOf(engine, "1"), 0.05));
        // The business date stays the request's: a canary may take effect only later.
        assertThat(record.businessDate()).isEqualTo(TODAY);
    }

    @Test
    void aRequestOutsideTheCanaryShareGetsTheVersionInForce() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 0.05).build();

        DecisionResult result = engine.evaluate(ref(correlationId(0.05, false)), FACTS);

        assertThat(result.output()).containsEntry("action", "ALLOW");
        assertThat(result.ruleVersion()).startsWith("1@sha256:");
        assertThat(result.record().routing()).isEmpty();
    }

    @Test
    void theSameCorrelationIdAlwaysGetsTheSameVersion() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 0.5).build();

        for (int i = 0; i < 50; i++) {
            String id = "TXN-" + i;
            String first = engine.evaluate(ref(id), FACTS).ruleVersion();
            assertThat(engine.evaluate(ref(id), FACTS).ruleVersion()).isEqualTo(first);
            assertThat(builder().canary("gate_decision", "2", 0.5).build().evaluate(ref(id), FACTS).ruleVersion())
                    .isEqualTo(first);
        }
    }

    @Test
    void theCanaryReceivesItsShareOfTheTraffic() {
        long routed = IntStream.range(0, 20_000)
                .filter(i -> DefaultRuleStoneEngine.bucket("gate_decision", "PAY-" + i) < 0.05).count();

        assertThat(routed / 20_000.0).isCloseTo(0.05, within(0.005));
    }

    @Test
    void theBucketIsTheFirst64BitsOfTheSha256AsAFraction() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("gate_decision\nTXN-42".getBytes(StandardCharsets.UTF_8));
        double expected = 0;
        for (int i = 0; i < 8; i++) {
            expected += (digest[i] & 0xff) * Math.pow(2, -8 * (i + 1));
        }

        assertThat(DefaultRuleStoneEngine.bucket("gate_decision", "TXN-42")).isCloseTo(expected, within(1e-15));
    }

    @Test
    void requestsWithoutCorrelationIdOrWithAVersionAreNeverRouted() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 1.0).build();

        assertThat(engine.evaluate(DecisionRef.of("gate_decision").asOf(TODAY), FACTS).ruleVersion())
                .startsWith("1@");
        DecisionResult pinned = engine.evaluate(ref("TXN-1").version("1"), FACTS);
        assertThat(pinned.ruleVersion()).startsWith("1@");
        assertThat(pinned.record().routing()).isEmpty();
        assertThat(engine.evaluate(ref("TXN-1"), FACTS).ruleVersion()).startsWith("2@");
    }

    @Test
    void aCanaryThatIsAlreadyInForceIsNotARouting() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 1.0).build();

        DecisionResult result = engine.evaluate(ref("TXN-1").asOf(LocalDate.of(2027, 6, 1)), FACTS);

        assertThat(result.ruleVersion()).startsWith("2@");
        assertThat(result.record().routing()).isEmpty();
    }

    @Test
    void canariesOfUnknownDecisionsOrVersionsAreRejectedAtBuild() {
        assertThatThrownBy(() -> builder().canary("gate_decision", "9", 0.1).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("canary gate_decision@9: decision 'gate_decision' has no version '9'");
        assertThatThrownBy(() -> builder().canary("nope", "1", 0.1).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("canary nope@1: unknown decision 'nope'");
        assertThatThrownBy(() -> builder().canary("gate_decision", "2", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("canary share must be above 0 and at most 1");
        assertThatThrownBy(() -> builder().canary("gate_decision", "2", 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().canary("gate_decision", "2", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().canary("gate_decision", "2", 0.1).canary("gate_decision", "3", 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision 'gate_decision' already has a canary");
    }

    @Test
    void aCanaryRecordIsWrittenAsJson() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 0.05).build();

        ObjectNode json = DecisionRecordJson.toJson(engine.evaluate(ref(correlationId(0.05, true)), FACTS).record());

        assertThat(json.get("routing").toString()).isEqualTo(
                "{\"kind\":\"CANARY\",\"champion\":\"" + versionOf(engine, "1") + "\",\"share\":0.05}");
    }

    // Shadow

    @Test
    void aShadowIsEvaluatedOnTheSameFactsAndOnlyRecorded() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "2").build();

        DecisionResult result = engine.evaluate(ref("TXN-7"), FACTS);

        assertThat(result.output()).containsEntry("action", "ALLOW");
        assertThat(result.record().routing()).isEmpty();
        assertThat(audited).hasSize(2);
        assertThat(audited.get(0)).isEqualTo(result.record());
        DecisionRecord shadow = audited.get(1);
        assertThat(shadow.recordId()).isEqualTo("rec-2");
        assertThat(shadow.correlationId()).contains("TXN-7");
        assertThat(shadow.version()).hasValueSatisfying(v -> assertThat(v).startsWith("2@sha256:"));
        assertThat(shadow.status()).isEqualTo(Status.OK);
        assertThat(shadow.output()).containsEntry("action", "BLOCK");
        assertThat(shadow.businessDate()).isEqualTo(result.record().businessDate());
        assertThat(shadow.evaluatedAt()).isEqualTo(result.record().evaluatedAt());
        assertThat(shadow.factsHash()).isEqualTo(result.record().factsHash());
        assertThat(shadow.routing()).contains(Routing.shadow(result.ruleVersion(), "rec-1"));
        assertThat(DecisionRecordJson.toJson(shadow).get("routing").toString()).isEqualTo(
                "{\"kind\":\"SHADOW\",\"champion\":\"" + result.ruleVersion() + "\",\"shadowOf\":\"rec-1\"}");
    }

    @Test
    void aFailingShadowIsRecordedButNeverReachesTheCaller() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "3").build();

        DecisionResult result = engine.evaluate(ref("TXN-7"), FACTS);

        assertThat(result.output()).containsEntry("action", "ALLOW");
        assertThat(audited).hasSize(2);
        assertThat(audited.get(1).status()).isEqualTo(Status.ERROR);
        assertThat(audited.get(1).routing()).hasValueSatisfying(r -> assertThat(r.shadowOf()).contains("rec-1"));
    }

    @Test
    void aSinkRejectingAShadowRecordNeverReachesTheCaller() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "2")
                .auditSink(record -> {
                    if (record.routing().isPresent()) {
                        throw new IllegalStateException("shadow records not accepted");
                    }
                })
                .build();

        assertThat(engine.evaluate(ref("TXN-7"), FACTS).output()).containsEntry("action", "ALLOW");
    }

    @Test
    void theShadowAlsoRunsWhenTheRequestFails() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "2").build();

        assertThatThrownBy(() -> engine.evaluate(ref("TXN-7"), Facts.of("score", "high")))
                .isInstanceOf(DecisionEvaluationException.class);

        assertThat(audited).extracting(DecisionRecord::status).containsExactly(Status.ERROR, Status.ERROR);
        assertThat(audited.get(1).routing()).isPresent();
    }

    @Test
    void noShadowForRequestsThatNameAVersionOrAlreadyUseTheShadowVersion() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "2").build();

        engine.evaluate(ref("TXN-1").version("1"), FACTS);
        engine.evaluate(ref("TXN-2").asOf(LocalDate.of(2027, 6, 1)), FACTS);
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("gate_decision").asOf(LocalDate.of(2025, 1, 1)), FACTS))
                .isInstanceOf(RuntimeException.class);

        assertThat(audited).hasSize(3).allSatisfy(r -> assertThat(r.routing()).isEmpty());
    }

    @Test
    void aShadowOfTheCanaryVersionIsSkippedWhenTheRequestWasRoutedToIt() {
        RuleStoneEngine engine = builder().canary("gate_decision", "2", 1.0).shadow("gate_decision", "2").build();

        engine.evaluate(ref("TXN-1"), FACTS);

        assertThat(audited).hasSize(1);
    }

    @Test
    void shadowsCanRunOnAnExecutorOfTheirOwn() {
        List<Runnable> queued = new ArrayList<>();
        RuleStoneEngine engine = builder().shadow("gate_decision", "2").shadowExecutor(queued::add).build();

        engine.evaluate(ref("TXN-7"), FACTS);
        assertThat(audited).hasSize(1);
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        assertThat(audited).hasSize(2);
        assertThat(audited.get(1).routing()).hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(Routing.Kind.SHADOW));
    }

    @Test
    void aShadowTheExecutorRejectsIsSkipped() {
        RuleStoneEngine engine = builder().shadow("gate_decision", "2")
                .shadowExecutor(task -> {
                    throw new RejectedExecutionException("queue full");
                })
                .build();

        assertThat(engine.evaluate(ref("TXN-7"), FACTS).output()).containsEntry("action", "ALLOW");
        assertThat(audited).hasSize(1);
    }

    @Test
    void shadowsOfUnknownVersionsAreRejectedAtBuild() {
        assertThatThrownBy(() -> builder().shadow("gate_decision", "9").build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("shadow gate_decision@9: decision 'gate_decision' has no version '9'");
        assertThatThrownBy(() -> builder().shadow("gate_decision", "2").shadow("gate_decision", "3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision 'gate_decision' already has a shadow");
    }

    @Test
    void routingIsValidated() {
        assertThatThrownBy(() -> Routing.canary("1@sha256:x", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Routing.shadow("1@sha256:x", "rec-1").share()).isEmpty();
        assertThat(Map.of("k", Routing.canary("1@sha256:x", 1)).get("k").share().getAsDouble()).isEqualTo(1);
    }

    private static String versionOf(RuleStoneEngine engine, String version) {
        return engine.evaluate(DecisionRef.of("gate_decision").version(version), FACTS).ruleVersion();
    }
}
