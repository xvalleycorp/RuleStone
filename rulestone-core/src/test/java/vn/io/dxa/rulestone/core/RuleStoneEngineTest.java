//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.TraceStep;

class RuleStoneEngineTest {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt, String segment, boolean blacklisted) {}

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate AFTER_V3 = LocalDate.of(2026, 12, 1);

    private final RuleStoneEngine engine = RuleStone.builder()
            .clock(Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), VIETNAM))
            .decision(load("loan_eligibility.v2.stone.json"))
            .decision(load("loan_eligibility.v3.stone.json"))
            .decision(load("transfer_fees.stone.json"))
            .decision(load("deposit_rate.stone.json"))
            .build();

    /** Loads a file from the repository's examples/ directory, which the documentation quotes. */
    static DecisionTable load(String name) {
        return DecisionTableJson.read(Path.of("..", "examples", name));
    }

    private static Applicant applicant(int age, String income, String debt) {
        return new Applicant(age, new BigDecimal(income), new BigDecimal(debt), "RETAIL", false);
    }

    private DecisionResult loan(Applicant applicant, String amount) {
        return engine.evaluate(DecisionRef.of("loan_eligibility").asOf(AFTER_V3),
                Facts.of("applicant", applicant, "amount", new BigDecimal(amount)));
    }

    // --- FIRST ---------------------------------------------------------------------------------

    @Test
    void firstHitPolicyStopsAtFirstMatchingRule() {
        DecisionResult result = loan(applicant(17, "30000000", "0"), "100000000");

        assertThat(result.output()).containsEntry("decision", "REJECT").doesNotContainKey("limit");
        assertThat(result.reasonCodes()).containsExactly("UNDERAGE");
        assertThat(result.trace()).containsExactly(new TraceStep("R1", true));
    }

    @Test
    void computesDebtToIncomeWithDecimals() {
        DecisionResult result = loan(applicant(30, "20000000", "10000001"), "100000000");

        assertThat(result.output()).containsEntry("decision", "REJECT");
        assertThat(result.reasonCodes()).containsExactly("DTI_HIGH");
        assertThat(result.matchedRules()).containsExactly("R3");
    }

    @Test
    void approvesWithLimitRoundedDownToMillion() {
        DecisionResult result = loan(applicant(30, "25750000.50", "5000000"), "300000000");

        assertThat(result.output()).containsEntry("decision", "APPROVE");
        assertThat((BigDecimal) result.output().get("limit")).isEqualByComparingTo("309000000");
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.trace()).extracting(TraceStep::matched).containsExactly(false, false, false, true);
    }

    @Test
    void fallsThroughToCatchAllRule() {
        DecisionResult result = loan(applicant(30, "10000000", "0"), "500000000");

        assertThat(result.output()).containsEntry("decision", "REVIEW");
        assertThat(result.reasonCodes()).containsExactly("AMOUNT_ABOVE_LIMIT");
    }

    // --- UNIQUE ------------------------------------------------------------------------------

    @Test
    void uniqueHitPolicyReturnsTheSingleMatch() {
        DecisionResult result = engine.evaluate(DecisionRef.of("deposit_rate"),
                Facts.of("termMonths", 9, "balance", new BigDecimal("100000000")));

        assertThat((BigDecimal) result.output().get("rate")).isEqualByComparingTo("0.048");
        assertThat(result.matchedRules()).containsExactly("MID");
        assertThat(result.trace()).hasSize(4);
    }

    @Test
    void uniqueHitPolicySeparatesVipTier() {
        DecisionResult result = engine.evaluate(DecisionRef.of("deposit_rate"),
                Facts.of("termMonths", 12, "balance", new BigDecimal("6000000000")));

        assertThat((BigDecimal) result.output().get("rate")).isEqualByComparingTo("0.055");
        assertThat(result.matchedRules()).containsExactly("VIP_LONG");
    }

    @Test
    void uniqueHitPolicyRejectsOverlappingMatches() {
        RuleStoneEngine overlapping = RuleStone.builder().decision(DecisionTableJson.read("""
                {
                  "id": "deposit_rate", "version": "0", "hitPolicy": "UNIQUE",
                  "inputs": { "termMonths": "int", "balance": "decimal" },
                  "outputs": { "rate": "decimal" },
                  "rules": [
                    { "id": "LONG", "when": "termMonths >= 12", "then": { "rate": "decimal('0.052')" } },
                    { "id": "VIP_LONG", "when": "termMonths >= 12 && balance >= 5000000000", "then": { "rate": "decimal('0.055')" } }
                  ]
                }""")).build();

        assertThatThrownBy(() -> overlapping.evaluate(DecisionRef.of("deposit_rate"),
                Facts.of("termMonths", 12, "balance", new BigDecimal("6000000000"))))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("UNIQUE hit policy violated")
                .hasMessageContaining("[LONG, VIP_LONG]");
    }

    // --- COLLECT -----------------------------------------------------------------------------

    @Test
    void collectHitPolicyGathersEveryMatch() {
        DecisionResult result = engine.evaluate(DecisionRef.of("transfer_fees"), Facts.of("transfer", Map.of(
                "amount", new BigDecimal("50000000"), "channel", "COUNTER", "interbank", true, "express", false)));

        assertThat(result.output()).hasToString("{feeCode=[IBT, CTR], fee=[25000, 10000]}");
        assertThat(result.matchedRules()).containsExactly("INTERBANK", "COUNTER");
        assertThat(result.output().get("feeCode")).isEqualTo(List.of("IBT", "CTR"));
        assertThat(result.output().get("fee"))
                .asInstanceOf(InstanceOfAssertFactories.list(BigDecimal.class))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("25000"), new BigDecimal("10000"));
    }

    @Test
    void collectWithNoMatchYieldsEmptyLists() {
        DecisionResult result = engine.evaluate(DecisionRef.of("transfer_fees"), Facts.of("transfer", Map.of(
                "amount", BigDecimal.ONE, "channel", "APP", "interbank", false, "express", false)));

        assertThat(result.matched()).isFalse();
        assertThat(result.output()).containsEntry("feeCode", List.of()).containsEntry("fee", List.of());
    }

    // --- Effective dating --------------------------------------------------------------------

    @Test
    void selectsVersionEffectiveOnRequestedDate() {
        Facts facts = Facts.of("applicant", applicant(19, "30000000", "0"), "amount", new BigDecimal("1000000"));

        DecisionResult before = engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 10, 31)), facts);
        DecisionResult after = engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 11, 1)), facts);

        assertThat(before.ruleVersion()).startsWith("2@sha256:");
        assertThat(before.output()).hasToString("{decision=REJECT}");
        assertThat(before.reasonCodes()).containsExactly("UNDERAGE");
        assertThat(after.ruleVersion()).startsWith("3@sha256:");
        assertThat(after.output()).hasToString("{decision=APPROVE, limit=360000000}");
    }

    @Test
    void usesEngineClockWhenNoDateGiven() {
        DecisionResult result = engine.evaluate(DecisionRef.of("loan_eligibility"),
                Facts.of("applicant", applicant(30, "30000000", "0"), "amount", new BigDecimal("1000000")));

        assertThat(result.ruleVersion()).startsWith("3@");
    }

    @Test
    void explicitVersionIgnoresEffectiveDates() {
        DecisionResult result = engine.evaluate(DecisionRef.of("loan_eligibility").version("2").asOf(AFTER_V3),
                Facts.of("applicant", applicant(19, "30000000", "0"), "amount", new BigDecimal("1000000")));

        assertThat(result.ruleVersion()).startsWith("2@");
    }

    @Test
    void reportsMissingDecisionsAndVersions() {
        Facts facts = Facts.empty();
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("nope"), facts))
                .isInstanceOf(DecisionNotFoundException.class);
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("loan_eligibility").version("9"), facts))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessageContaining("no version '9'");
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2025, 6, 1)), facts))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessageContaining("effective on 2025-06-01");
    }

    @Test
    void rejectsOverlappingEffectivePeriods() {
        DecisionTable v3 = load("loan_eligibility.v3.stone.json");
        DecisionTable overlapping = new DecisionTable(v3.id(), "4", v3.description(),
                java.util.Optional.of(LocalDate.of(2027, 1, 1)), java.util.Optional.empty(),
                v3.hitPolicy(), v3.inputs(), v3.outputs(), v3.rules());

        assertThatThrownBy(() -> RuleStone.builder().decision(v3).decision(overlapping).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("effective periods overlap");
        assertThatThrownBy(() -> RuleStone.builder().decision(v3).decision(v3).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("version registered more than once");
    }

    // --- Inputs and runtime errors -----------------------------------------------------------

    @Test
    void rejectsInvalidInputsWithTheirPath() {
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("deposit_rate"),
                Facts.of("termMonths", 12, "balance", 1.5e9)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("balance")
                .hasMessageContaining("floating-point");
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("deposit_rate"), Facts.of("termMonths", 12)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("balance: missing input");
    }

    @Test
    void failsLoudlyWhenARuleCannotBeEvaluated() {
        DecisionEvaluationException e = org.junit.jupiter.api.Assertions.assertThrows(DecisionEvaluationException.class,
                () -> loan(applicant(30, "0", "1000"), "1000"));

        assertThat(e.getMessage()).contains("rule R3").contains("division by zero");
        assertThat(e.decisionId()).isEqualTo("loan_eligibility");
        assertThat(e.ruleVersion()).startsWith("3@sha256:");
    }
}
