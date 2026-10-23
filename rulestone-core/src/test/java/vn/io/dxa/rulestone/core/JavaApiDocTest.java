//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.Bindings;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ValueType;

/** Runs the code shown in docs/java-api.md. Update both together. */
class JavaApiDocTest {

    @Test
    void decisionTableBuiltInJava() {
        DecisionTable table = new DecisionTable(
                "risk_band", "1", Optional.empty(), Optional.empty(), Optional.empty(),
                HitPolicy.FIRST,
                Map.of("score", ValueType.INT),
                Map.of("band", ValueType.STRING),
                List.of(
                        Rule.of("HIGH", "score < 500", Map.of("band", "'HIGH'"), "LOW_SCORE"),
                        Rule.of("NORMAL", "true", Map.of("band", "'NORMAL'"))));

        DecisionResult result = RuleStone.builder().decision(table).build()
                .evaluate(DecisionRef.of("risk_band"), Facts.of("score", 420));

        assertThat(result.output()).containsEntry("band", "HIGH");
        assertThat(result.reasonCodes()).containsExactly("LOW_SCORE");
    }

    @Test
    void moneyRecord() {
        Money price = Money.of("1500000.50", "VND");

        assertThat(price).isEqualTo(Money.of(new BigDecimal("1500000.50"), "VND"));
        assertThat(price.amount()).hasToString("1500000.50");
        assertThat(price.currency()).isEqualTo("VND");
        assertThat(price.toString()).isEqualTo("1500000.50 VND");
        assertThat(Money.of("1.0", "VND")).isNotEqualTo(Money.of("1.00", "VND"));
    }

    static final String CARD_FEE = """
            {
              "id": "card_fee",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs":  { "customer": { "birthDate": "date" }, "spend": "money" },
              "outputs": { "fee": "money", "nextReview": "date" },
              "rules": [
                { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
                  "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)" },
                  "reasonCode": "YOUTH_WAIVER" },
                { "id": "STANDARD", "when": "true",
                  "then": { "fee": "(spend * decimal('0.001')).round()", "nextReview": "businessDate.plusMonths(6)" } }
              ]
            }
            """;

    @Test
    void moneyAndDateDecision() throws IOException {
        assertThat(Files.readString(Path.of("..", "docs", "java-api.md"))).contains("```json\n" + CARD_FEE + "```");
        RuleStoneEngine engine = RuleStone.builder()
                .currencyScale("XAU", 4)
                .decision(DecisionTableJson.read(CARD_FEE))
                .build();

        DecisionResult result = engine.evaluate(
                DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)),
                Facts.of("customer", Map.of("birthDate", LocalDate.of(1990, 5, 20)),
                         "spend", Money.of("123456789", "VND")));

        assertThat(result.output()).hasToString("{fee=123457 VND, nextReview=2027-05-01}");

        DecisionResult youth = engine.evaluate(
                DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)),
                Facts.of("customer", Map.of("birthDate", LocalDate.of(2002, 1, 1)),
                         "spend", Money.of("123456789", "VND")));

        assertThat(youth.output()).hasToString("{fee=0 VND, nextReview=2027-11-01}");
        assertThat(youth.reasonCodes()).containsExactly("YOUTH_WAIVER");
    }

    @Test
    void factsJson() {
        DecisionTable cardFee = DecisionTableJson.read(CARD_FEE);
        RuleStoneEngine engine = RuleStone.builder().decision(cardFee).build();

        Facts facts = FactsJson.readFacts("""
                { "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }""", cardFee.inputs());
        DecisionResult result = engine.evaluate(DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)), facts);

        String json = FactsJson.write(result.output());
        assertThat(json).isEqualTo("{\"fee\":\"123457 VND\",\"nextReview\":\"2027-05-01\"}");

        assertThatThrownBy(() -> FactsJson.readFacts("""
                { "customer": { "birthDate": "20/05/1990" }, "spend": 123456789 }""", cardFee.inputs()))
                .isInstanceOf(InvalidFactsException.class)
                .extracting(e -> ((InvalidFactsException) e).problems())
                .isEqualTo(List.of("customer.birthDate: expected a date as \"yyyy-MM-dd\"",
                        "spend: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\""));
    }

    @Test
    void decisionRecords() throws IOException {
        List<DecisionTable> tables = List.of(DecisionTableJson.read(CARD_FEE));
        byte[] factsHashKey = "example key, not for production!".getBytes(StandardCharsets.UTF_8);
        List<String> auditLog = new ArrayList<>();

        // --- as in docs/java-api.md
        RuleStoneEngine engine = RuleStone.builder()
                .decisions(tables)
                .factsHashKey(factsHashKey)   // 32 random bytes from the bank's key store
                .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
                .build();

        DecisionResult result = engine.evaluate(
                DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)).correlationId("CARD-2026-000123"),
                Facts.of("customer", Map.of("birthDate", LocalDate.of(2002, 1, 1)),
                         "spend", Money.of("123456789", "VND")));
        // ---

        assertThat(auditLog).containsExactly(DecisionRecordJson.write(result.record()));
        String doc = Files.readString(Path.of("..", "docs", "java-api.md"));
        Matcher json = Pattern.compile("```json\\n(\\{\\n  \"recordId\".*?)```", Pattern.DOTALL).matcher(doc);
        assertThat(json.find()).as("record example in docs/java-api.md").isTrue();
        ObjectNode documented = (ObjectNode) new ObjectMapper().readTree(json.group(1));
        ObjectNode actual = DecisionRecordJson.toJson(result.record());
        // Fields that differ on every run are only checked for their form.
        assertThat(actual.get("recordId").asText()).matches("[0-9a-f-]{36}");
        assertThat(actual.get("engine").asText()).startsWith("rulestone ");
        for (String varying : List.of("recordId", "evaluatedAt", "engine")) {
            actual.set(varying, documented.get(varying));
        }
        assertThat(actual).as(actual.toPrettyString()).isEqualTo(documented);
    }

    @Test
    void standaloneExpression() {
        ExpressionEnvironment env = ExpressionEnvironment.builder()
                .variable("amount", ValueType.DECIMAL)
                .variable("tenorMonths", ValueType.INT)
                .build();

        CelExpression fee = env.compile("(amount * decimal('0.01') / tenorMonths).round(0)", ValueType.DECIMAL);

        Bindings inputs = env.bind(Map.of("amount", new BigDecimal("12000000"), "tenorMonths", 12));
        BigDecimal value = (BigDecimal) fee.evaluate(inputs);

        assertThat(value).hasToString("10000");
    }
}
