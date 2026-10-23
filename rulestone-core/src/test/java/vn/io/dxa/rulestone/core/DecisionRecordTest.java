//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.AuditException;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;

class DecisionRecordTest {

    /** Values planted in inputs; they must never appear in a record. */
    private static final String SECRET_ID = "079201000123";
    private static final String SECRET_AMOUNT = "987654321.99";

    private static final String TABLE = """
            {
              "id": "transfer_check",
              "version": "2",
              "hitPolicy": "COLLECT",
              "inputs": {
                "customer": { "idNumber": "string", "age": "int" },
                "amount": "money",
                "divisor": "decimal"
              },
              "outputs": { "flag": "string" },
              "rules": [
                { "id": "LARGE", "when": "amount > money(500000000, 'VND')",
                  "then": { "flag": "'LARGE'" }, "reasonCode": "LARGE_AMOUNT" },
                { "id": "YOUNG", "when": "customer.age < 25",
                  "then": { "flag": "'YOUNG'" }, "reasonCode": "YOUNG_CUSTOMER" },
                { "id": "RATIO", "when": "amount.amount() / divisor > decimal(0)",
                  "then": { "flag": "'RATIO'" } }
              ]
            }
            """;

    record Customer(String idNumber, int age) {}

    private final List<DecisionRecord> audited = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private RuleStone.Builder builder() {
        return RuleStone.builder()
                .decision(DecisionTableJson.read(TABLE))
                .clock(Clock.fixed(Instant.parse("2026-10-31T18:30:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")))
                .recordIds(() -> "rec-" + ids.incrementAndGet())
                .auditSink(audited::add);
    }

    private static Facts facts(Object amount, Object divisor) {
        return Facts.of("customer", Map.of("idNumber", SECRET_ID, "age", 22), "amount", amount, "divisor", divisor);
    }

    private static Facts valid() {
        return facts(Money.of(SECRET_AMOUNT, "VND"), new BigDecimal("2"));
    }

    @Test
    void everyResultCarriesItsRecordAndSinksReceiveTheSameRecord() {
        DecisionResult result = builder().build()
                .evaluate(DecisionRef.of("transfer_check").correlationId("TXN-2026-000042"), valid());

        DecisionRecord record = result.record();
        assertThat(audited).containsExactly(record);
        assertThat(record.recordId()).isEqualTo("rec-1");
        assertThat(record.correlationId()).contains("TXN-2026-000042");
        assertThat(record.decision()).isEqualTo("transfer_check");
        assertThat(record.version()).contains(result.ruleVersion());
        // 18:30 UTC on 31 October is already 1 November in Ho Chi Minh City.
        assertThat(record.businessDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(record.evaluatedAt()).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(record.engine()).startsWith("rulestone ");
        assertThat(record.status()).isEqualTo(Status.OK);
        assertThat(record.factsHash()).hasValueSatisfying(h -> assertThat(h).matches("sha256:[0-9a-f]{64}"));
        assertThat(record.output()).isEqualTo(result.output());
        assertThat(record.reasonCodes()).containsExactly(
                new ReasonCode("LARGE_AMOUNT", "transfer_check"), new ReasonCode("YOUNG_CUSTOMER", "transfer_check"));
        assertThat(record.path()).containsExactly(
                new PathStep("transfer_check", "table", result.ruleVersion(), List.of("LARGE", "YOUNG", "RATIO")));
        assertThat(record.error()).isEmpty();
    }

    @Test
    void aDecisionWithoutAMatchStillHasACompleteRecord() {
        DecisionResult result = builder().build().evaluate(DecisionRef.of("transfer_check"),
                Facts.of("customer", Map.of("idNumber", SECRET_ID, "age", 40),
                        "amount", Money.of("1000", "VND"), "divisor", new BigDecimal("-1")));

        assertThat(result.record().status()).isEqualTo(Status.OK);
        assertThat(result.record().output()).isEqualTo(Map.of("flag", List.of()));
        assertThat(result.record().reasonCodes()).isEmpty();
        assertThat(result.record().path().get(0).matchedRules()).isEmpty();
    }

    @Test
    void aFailingRuleIsRecordedBeforeTheExceptionReachesTheCaller() {
        var engine = builder().build();

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("transfer_check").asOf(LocalDate.of(2026, 11, 2)),
                facts(Money.of(SECRET_AMOUNT, "VND"), BigDecimal.ZERO)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("rule RATIO condition failed");

        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.ERROR);
        assertThat(record.businessDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(record.error()).hasValueSatisfying(e -> assertThat(e).contains("rule RATIO condition failed"));
        assertThat(record.output()).isEmpty();
        assertThat(record.reasonCodes()).isEmpty();
        // The rules that matched before the failure are kept: they show how far evaluation got.
        assertThat(record.path()).singleElement()
                .satisfies(step -> assertThat(step.matchedRules()).containsExactly("LARGE", "YOUNG"));
        assertThat(record.factsHash()).isPresent();
    }

    @Test
    void invalidInputsAreRecordedWithoutAFactsHash() {
        assertThatThrownBy(() -> builder().build().evaluate(DecisionRef.of("transfer_check"),
                facts(Money.of(SECRET_AMOUNT, "VND"), 2.5)))
                .isInstanceOf(DecisionEvaluationException.class);

        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.ERROR);
        assertThat(record.version()).isPresent();
        assertThat(record.factsHash()).isEmpty();
        assertThat(record.path().get(0).matchedRules()).isEmpty();
        assertThat(record.error()).hasValueSatisfying(e -> assertThat(e).contains("divisor").contains("floating-point"));
    }

    @Test
    void anUnknownDecisionIsRecordedWithoutVersionOrPath() {
        assertThatThrownBy(() -> builder().build().evaluate(DecisionRef.of("no_such_decision"), valid()))
                .isInstanceOf(DecisionNotFoundException.class);

        DecisionRecord record = audited.get(0);
        assertThat(record.decision()).isEqualTo("no_such_decision");
        assertThat(record.status()).isEqualTo(Status.ERROR);
        assertThat(record.version()).isEmpty();
        assertThat(record.path()).isEmpty();
        assertThat(record.error()).contains("unknown decision 'no_such_decision'");
    }

    @Test
    void unexpectedExceptionsAreRecordedByTypeOnly() {
        // A map that fails when read, with a message that quotes an input value.
        Map<String, Object> failing = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of();
            }

            @Override
            public Object get(Object key) {
                throw new IllegalStateException("cannot read " + SECRET_ID);
            }
        };

        assertThatThrownBy(() -> builder().build().evaluate(DecisionRef.of("transfer_check"),
                Facts.of("customer", failing, "amount", Money.of(SECRET_AMOUNT, "VND"), "divisor", BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(audited.get(0).error()).contains("internal error: java.lang.IllegalStateException");
        assertThat(DecisionRecordJson.write(audited.get(0))).doesNotContain(SECRET_ID);
    }

    @Test
    void recordsNeverContainInputValues() {
        var engine = builder().build();
        engine.evaluate(DecisionRef.of("transfer_check"), valid());
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("transfer_check"),
                facts(Money.of(SECRET_AMOUNT, "VND"), BigDecimal.ZERO)));
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("transfer_check"),
                facts(Money.of(SECRET_AMOUNT, "VND"), SECRET_ID)));

        assertThat(audited).hasSize(3).allSatisfy(record -> assertThat(record + DecisionRecordJson.write(record))
                .doesNotContain(SECRET_ID)
                .doesNotContain(SECRET_AMOUNT)
                .doesNotContain("98765432199"));
    }

    @Test
    void theFactsHashIsTheSha256OfTheCanonicalDeclaredInputs() throws Exception {
        DecisionRecord record = builder().build().evaluate(DecisionRef.of("transfer_check"), Facts.of(
                "customer", Map.of("idNumber", "A1", "age", 30),
                "amount", Money.of("1500000.50", "VND"),
                "divisor", new BigDecimal("2.500"))).record();

        // Keys sorted, decimals without trailing zeros, businessDate left out.
        String canonical = "{\"amount\":\"1500000.5 VND\",\"customer\":{\"age\":30,\"idNumber\":\"A1\"},\"divisor\":\"2.5\"}";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(record.factsHash()).contains("sha256:" + expected);
    }

    @Test
    void equivalentInputsHaveTheSameFactsHash() {
        var engine = builder().build();
        String fromMap = hash(engine, Facts.of(
                "customer", Map.of("idNumber", "A1", "age", 30),
                "amount", Money.of("1500000.50", "VND"),
                "divisor", new BigDecimal("2.0")));
        String fromRecord = hash(engine, Facts.of(
                "customer", new Customer("A1", 30),
                "amount", Money.of("1500000.5000", "VND"),
                "divisor", 2L).with("ignoredBecauseUndeclared", "anything"));
        String changed = hash(engine, Facts.of(
                "customer", new Customer("A1", 31),
                "amount", Money.of("1500000.50", "VND"),
                "divisor", new BigDecimal("2")));

        assertThat(fromRecord).isEqualTo(fromMap);
        assertThat(changed).isNotEqualTo(fromMap);
    }

    @Test
    void aKeyMakesTheFactsHashAnHmac() {
        byte[] key = "a bank-held key of 32 bytes!!!!!".getBytes(StandardCharsets.UTF_8);
        String plain = hash(builder().build(), valid());
        String keyed = hash(builder().factsHashKey(key).build(), valid());
        String keyedAgain = hash(builder().factsHashKey(key).build(), valid());
        String otherKey = hash(builder().factsHashKey("another key of at least 16 bytes".getBytes(StandardCharsets.UTF_8))
                .build(), valid());

        assertThat(keyed).matches("hmac-sha256:[0-9a-f]{64}").isEqualTo(keyedAgain).isNotEqualTo(otherKey);
        assertThat(keyed.substring("hmac-".length())).isNotEqualTo(plain);
        assertThatThrownBy(() -> RuleStone.builder().factsHashKey(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSinkThatFailsStopsTheResultFromBeingReturned() {
        List<String> order = new ArrayList<>();
        var engine = builder()
                .auditSink(record -> order.add("second"))
                .auditSink(record -> {
                    throw new IllegalStateException("disk full");
                })
                .build();

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("transfer_check"), valid()))
                .isInstanceOf(AuditException.class)
                .hasMessage("audit sink rejected decision record rec-1 (java.lang.IllegalStateException); "
                        + "the decision was not returned")
                .satisfies(e -> assertThat(((AuditException) e).recordId()).isEqualTo("rec-1"));
        assertThat(audited).hasSize(1);
        assertThat(order).containsExactly("second");
    }

    @Test
    void aSinkFailureDuringAFailedEvaluationIsAttachedToTheOriginalException() {
        var engine = builder().auditSink(record -> {
            throw new IllegalStateException("disk full");
        }).build();

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("no_such_decision"), valid()))
                .isInstanceOf(DecisionNotFoundException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement().isInstanceOf(AuditException.class));
    }

    @Test
    void recordsAreWrittenAsJsonInAFixedShape() {
        DecisionResult result = builder().build()
                .evaluate(DecisionRef.of("transfer_check").correlationId("TXN-1"), valid());

        assertThat(DecisionRecordJson.write(result.record())).isEqualTo("{"
                + "\"recordId\":\"rec-1\",\"correlationId\":\"TXN-1\",\"decision\":\"transfer_check\","
                + "\"version\":\"" + result.ruleVersion() + "\",\"businessDate\":\"2026-11-01\","
                + "\"evaluatedAt\":\"2026-10-31T18:30:00Z\",\"engine\":\"" + result.record().engine() + "\","
                + "\"status\":\"OK\",\"factsHash\":\"" + result.record().factsHash().orElseThrow() + "\","
                + "\"output\":{\"flag\":[\"LARGE\",\"YOUNG\",\"RATIO\"]},"
                + "\"reasonCodes\":[{\"code\":\"LARGE_AMOUNT\",\"source\":\"transfer_check\"},"
                + "{\"code\":\"YOUNG_CUSTOMER\",\"source\":\"transfer_check\"}],"
                + "\"path\":[{\"node\":\"transfer_check\",\"type\":\"table\",\"version\":\"" + result.ruleVersion()
                + "\",\"matchedRules\":[\"LARGE\",\"YOUNG\",\"RATIO\"]}]}");
    }

    @Test
    void recordsRejectInconsistentStatus() {
        assertThatThrownBy(() -> new DecisionRecord("r", Optional.empty(), "d", Optional.empty(), LocalDate.EPOCH,
                Instant.EPOCH, "e", Status.ERROR, Optional.empty(), Map.of(), List.of(), List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionRef.of("d").correlationId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String hash(vn.io.dxa.rulestone.api.RuleStoneEngine engine, Facts facts) {
        return engine.evaluate(DecisionRef.of("transfer_check"), facts).record().factsHash().orElseThrow();
    }
}
