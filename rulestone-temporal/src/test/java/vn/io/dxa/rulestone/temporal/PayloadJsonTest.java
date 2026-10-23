//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.FactsJson;

/** Payloads through Temporal's default data converter keep decimals, money and dates exact. */
class PayloadJsonTest {

    private static final DataConverter CONVERTER = DefaultDataConverter.newDefaultInstance();

    record Customer(LocalDate birthDate, String nickname) {
    }

    private static <T> T roundTrip(T value, Class<T> type) {
        Payload payload = CONVERTER.toPayload(value).orElseThrow();
        return CONVERTER.fromPayload(payload, type, type);
    }

    private static Payload json(String text) {
        return Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFrom(text, StandardCharsets.UTF_8))
                .build();
    }

    @Test
    void requestRoundTripsWithoutPrecisionLoss() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("amount", new BigDecimal("12345678901234567890.123456789000"));
        facts.put("whole", new BigDecimal("120000000"));
        facts.put("spend", Money.of("1500000.50", "VND"));
        facts.put("due", LocalDate.of(2026, 11, 1));
        facts.put("count", 7);
        facts.put("customer", new Customer(LocalDate.of(1980, 5, 20), null));
        facts.put("fees", List.of(Money.of("0.10", "USD"), Money.of("2", "USD")));
        DecisionRequest request = DecisionRequest.of("any", facts).withAsOf(LocalDate.of(2026, 11, 15));

        DecisionRequest back = roundTrip(request, DecisionRequest.class);

        assertThat(back).isEqualTo(request);
        Map<String, ValueType> types = new LinkedHashMap<>();
        types.put("amount", ValueType.DECIMAL);
        types.put("whole", ValueType.DECIMAL);
        types.put("spend", ValueType.MONEY);
        types.put("due", ValueType.DATE);
        types.put("count", ValueType.INT);
        types.put("customer", ValueType.struct(Map.of("birthDate", ValueType.DATE, "nickname", ValueType.STRING)));
        types.put("fees", ValueType.listOf(ValueType.MONEY));
        Facts read = FactsJson.readFacts(back.facts(), types);
        assertThat(read.get("amount")).contains(new BigDecimal("12345678901234567890.123456789000"));
        assertThat(read.get("whole")).contains(new BigDecimal("120000000"));
        assertThat(read.get("spend")).contains(Money.of("1500000.50", "VND"));
        assertThat(read.get("due")).contains(LocalDate.of(2026, 11, 1));
        assertThat(read.get("count")).contains(7L);
        // The null record component is left out, so rules see the field as unset.
        assertThat(read.get("customer")).contains(Map.of("birthDate", LocalDate.of(1980, 5, 20)));
        assertThat(read.get("fees")).contains(List.of(Money.of("0.10", "USD"), Money.of("2", "USD")));
    }

    @Test
    void outcomeRoundTripsWithoutPrecisionLoss() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("fee", Money.of("123456789.0123450", "VND"));
        output.put("rate", new BigDecimal("0.0980"));
        output.put("review", LocalDate.of(2027, 5, 1));
        output.put("tier", "STANDARD");
        output.put("fees", List.of(new BigDecimal("1.50"), new BigDecimal("2")));
        DecisionOutcome outcome = DecisionOutcome.from(new DecisionResult("card_fee", "1@sha256:00", output,
                List.of("CODE"), List.of(new TraceStep("R1", true))), LocalDate.of(2026, 11, 1));

        DecisionOutcome back = roundTrip(outcome, DecisionOutcome.class);

        assertThat(back).isEqualTo(outcome);
        assertThat(back.money("fee")).contains(Money.of("123456789.0123450", "VND"));
        assertThat(back.decimal("rate")).contains(new BigDecimal("0.0980"));
        assertThat(back.date("review")).contains(LocalDate.of(2027, 5, 1));
        assertThat(back.text("tier")).contains("STANDARD");
        assertThat(back.outputValue("fees", ValueType.listOf(ValueType.DECIMAL)))
                .contains(List.of(new BigDecimal("1.50"), new BigDecimal("2")));
        assertThat(back.decimal("absent")).isEmpty();
        assertThat(back.matchedRules()).containsExactly("R1");
    }

    @Test
    void decimalsSentAsJsonNumbersAreReadExactly() {
        DecisionRequest request = CONVERTER.fromPayload(json("""
                {"decisionId":"card_fee","asOf":"2026-11-15",
                 "facts":{"rate":0.0010,"big":12345678901234567890.123456789,"n":3}}"""),
                DecisionRequest.class, DecisionRequest.class);

        Facts facts = FactsJson.readFacts(request.facts(),
                Map.of("rate", ValueType.DECIMAL, "big", ValueType.DECIMAL, "n", ValueType.INT));

        assertThat(facts.get("rate")).contains(new BigDecimal("0.0010"));
        assertThat(facts.get("big")).contains(new BigDecimal("12345678901234567890.123456789"));
        assertThat(facts.get("n")).contains(3L);
        assertThat(request.asOf()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void floatingPointFactsAreRejectedWithoutTheirValue() {
        assertThatThrownBy(() -> DecisionRequest.of("any", Map.of("rate", 0.123456789d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate")
                .hasMessageNotContaining("0.123456789");
    }

    @Test
    void requestsAndOutcomesAreImmutable() {
        DecisionRequest request = DecisionRequest.of("any", Map.of("n", 1));
        request.facts().put("n", 2);
        assertThat(request.facts().get("n").asLong()).isEqualTo(1);
    }
}
