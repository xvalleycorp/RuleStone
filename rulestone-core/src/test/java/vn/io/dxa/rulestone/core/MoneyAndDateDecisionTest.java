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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class MoneyAndDateDecisionTest {

    /** 2026-12-01 in Vietnam, while it is still 2026-11-30 in UTC. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-30T20:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final String DATES = """
            {
              "id": "dates", "version": "1", "hitPolicy": "FIRST",
              "inputs": { "applicant": { "birthDate": "date" } },
              "outputs": { "today": "date", "age": "int", "adult": "bool" },
              "rules": [ { "id": "ALL", "when": "true", "then": {
                "today": "businessDate",
                "age": "yearsBetween(applicant.birthDate, businessDate)",
                "adult": "yearsBetween(applicant.birthDate, businessDate) >= 18" } } ]
            }""";

    private static final String FEES = """
            {
              "id": "fees", "version": "1", "hitPolicy": "COLLECT",
              "inputs": { "transfer": { "amount": "money", "valueDate": "date" } },
              "outputs": { "fee": "money", "settles": "date" },
              "rules": [
                { "id": "BASE", "when": "true", "then": {
                  "fee": "max(money(2000, transfer.amount.currency()), (transfer.amount * decimal('0.0005')).round())",
                  "settles": "transfer.valueDate.plusDays(1)" } },
                { "id": "LARGE", "when": "transfer.amount > money(1000000000, 'VND')",
                  "then": { "fee": "money('10000', 'VND')" }, "reasonCode": "LARGE_TRANSFER" }
              ]
            }""";

    private final RuleStoneEngine engine = RuleStone.builder()
            .clock(CLOCK)
            .decision(DecisionTableJson.read(DATES))
            .decision(DecisionTableJson.read(FEES))
            .build();

    private static Facts born(LocalDate birthDate) {
        return Facts.of("applicant", Map.of("birthDate", birthDate));
    }

    @Test
    void businessDateIsTheAsOfDate() {
        DecisionResult result = engine.evaluate(DecisionRef.of("dates").asOf(LocalDate.of(2026, 11, 1)),
                born(LocalDate.of(2008, 11, 2)));

        assertThat(result.output()).hasToString("{today=2026-11-01, age=17, adult=false}");
        assertThat(result.output().get("today")).isInstanceOf(LocalDate.class);
    }

    @Test
    void businessDateDefaultsToTheEngineClock() {
        assertThat(engine.evaluate(DecisionRef.of("dates"), born(LocalDate.of(2008, 12, 1))).output())
                .hasToString("{today=2026-12-01, age=18, adult=true}");
        // An explicit version without asOf still takes the date from the clock...
        assertThat(engine.evaluate(DecisionRef.of("dates").version("1"), born(LocalDate.of(2008, 12, 1))).output())
                .containsEntry("today", LocalDate.of(2026, 12, 1));
        // ...and with asOf, from asOf.
        assertThat(engine.evaluate(DecisionRef.of("dates").version("1").asOf(LocalDate.of(2020, 1, 1)),
                born(LocalDate.of(2008, 12, 1))).output()).containsEntry("today", LocalDate.of(2020, 1, 1));
    }

    @Test
    void businessDateIsTheDateThatSelectsTheVersion() {
        RuleStoneEngine versions = RuleStone.builder()
                .clock(CLOCK)
                .decision(DecisionTableJson.read(DATES.replace("\"version\": \"1\"",
                        "\"version\": \"1\", \"effectiveTo\": \"2026-12-01\"")))
                .decision(DecisionTableJson.read(DATES.replace("\"version\": \"1\"",
                        "\"version\": \"2\", \"effectiveFrom\": \"2026-12-01\"")))
                .build();

        DecisionResult result = versions.evaluate(DecisionRef.of("dates"), born(LocalDate.of(2000, 1, 1)));

        assertThat(result.ruleVersion()).startsWith("2@");
        assertThat(result.output()).containsEntry("today", LocalDate.of(2026, 12, 1));
    }

    @Test
    void factsCannotOverrideBusinessDate() {
        Facts facts = born(LocalDate.of(2000, 1, 1)).with("businessDate", LocalDate.of(1999, 9, 9));

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("dates"), facts))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("invalid input businessDate: reserved name")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("1999"));
    }

    @Test
    void tablesCannotDeclareBusinessDate() {
        // Other problems in the same table are still reported.
        String json = DATES.replace("\"inputs\": {", "\"inputs\": { \"businessDate\": \"date\",")
                .replace("\"age\": \"yearsBetween", "\"age\": \"yearBetween");

        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(json)).build())
                .isInstanceOf(InvalidDecisionException.class)
                .satisfies(e -> assertThat(((InvalidDecisionException) e).problems()).satisfiesExactly(
                        p -> assertThat(p).isEqualTo("dates@1 inputs.businessDate: reserved name; every decision "
                                + "receives businessDate implicitly (DecisionRef.asOf, else the engine clock)"),
                        p -> assertThat(p).startsWith("dates@1 rules[ALL].then.age")));
    }

    @Test
    void businessDateIsNotPartOfTheContentHash() throws Exception {
        DecisionTable table = DecisionTableJson.read(DATES);
        String canonical = DecisionTableJson.write(table);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(canonical).doesNotContain("\"businessDate\":");
        assertThat(engine.evaluate(DecisionRef.of("dates"), born(LocalDate.of(2000, 1, 1))).ruleVersion())
                .isEqualTo("1@sha256:" + hash);
    }

    @Test
    void moneyAndDateOutputsAreCollected() {
        DecisionResult result = engine.evaluate(DecisionRef.of("fees"), Facts.of("transfer", Map.of(
                "amount", Money.of("2000000001", "VND"), "valueDate", LocalDate.of(2026, 12, 31))));

        assertThat(result.output()).hasToString("{fee=[1000000 VND, 10000 VND], settles=[2027-01-01]}");
        assertThat(result.reasonCodes()).containsExactly("LARGE_TRANSFER");
        assertThat(((List<?>) result.output().get("fee")).get(0)).isEqualTo(Money.of("1000000", "VND"));
    }

    @Test
    void mixingCurrenciesFailsTheDecisionWithoutAmounts() {
        Facts usd = Facts.of("transfer", Map.of(
                "amount", Money.of("98765.43", "USD"), "valueDate", LocalDate.of(2026, 12, 31)));

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("fees"), usd))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("rule LARGE condition failed: money currencies differ: USD and VND")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("98765"));
    }

    @Test
    void rejectsWrongJavaTypesForMoneyAndDates() {
        Facts wrong = Facts.of("transfer", Map.of(
                "amount", new BigDecimal("5000000"), "valueDate", LocalDate.of(2026, 12, 31)));

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("fees"), wrong))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("invalid input transfer.amount: expected money but got java.math.BigDecimal")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("5000000"));
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("dates"),
                Facts.of("applicant", Map.of("birthDate", "1990-01-01"))))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("invalid input applicant.birthDate: expected date but got java.lang.String")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("1990"));
    }

    @Test
    void currencyScaleIsPassedToEveryRule() {
        String gold = """
                {
                  "id": "gold", "version": "1", "hitPolicy": "FIRST",
                  "inputs": { "holding": "money" }, "outputs": { "value": "money" },
                  "rules": [ { "id": "ALL", "when": "true", "then": { "value": "holding.round()" } } ]
                }""";
        Facts facts = Facts.of("holding", Money.of("1.23456", "XAU"));

        RuleStoneEngine withScale = RuleStone.builder().currencyScale("XAU", 4)
                .decision(DecisionTableJson.read(gold)).build();
        RuleStoneEngine withoutScale = RuleStone.builder().decision(DecisionTableJson.read(gold)).build();

        assertThat(withScale.evaluate(DecisionRef.of("gold"), facts).output()).hasToString("{value=1.2346 XAU}");
        assertThatThrownBy(() -> withoutScale.evaluate(DecisionRef.of("gold"), facts))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("currency XAU has no minor unit in ISO 4217");
        assertThatThrownBy(() -> RuleStone.builder().currencyScale("GOLD", 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyAndDateTypesRoundTripThroughTheCanonicalForm() {
        DecisionTable table = DecisionTableJson.read(FEES);

        assertThat(DecisionTableJson.write(table)).contains("\"amount\":\"money\"", "\"valueDate\":\"date\"");
        assertThat(DecisionTableJson.read(DecisionTableJson.write(table))).isEqualTo(table);
    }

    @Test
    void reportsMoneyAndDateTypeErrorsWhenLoading() {
        String json = FEES.replace("transfer.valueDate.plusDays(1)", "transfer.valueDate + 1")
                .replace("transfer.amount > money(1000000000, 'VND')", "transfer.amount > 1000000000");

        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(json)).build())
                .isInstanceOf(InvalidDecisionException.class)
                .satisfies(e -> assertThat(((InvalidDecisionException) e).problems()).satisfiesExactlyInAnyOrder(
                        p -> assertThat(p).startsWith("fees@1 rules[BASE].then.settles").contains("no matching overload"),
                        p -> assertThat(p).startsWith("fees@1 rules[LARGE].when").contains("no matching overload")));
    }

    private static String stackTraceOf(Throwable t) {
        var out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
