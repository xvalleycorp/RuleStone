//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class DecisionCheckTest {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                     String segment, boolean blacklisted) {}

    private static final LocalDate AFTER_V3 = LocalDate.of(2026, 12, 1);

    private final RuleStoneEngine engine = ExamplesStoneTest.exampleEngine();

    private DecisionCheck loan(int age, String income, String debt, String amount) {
        return DecisionCheck.of(engine)
                .decision("loan_eligibility").asOf(AFTER_V3)
                .given("applicant", new Applicant(age, new BigDecimal(income), new BigDecimal(debt), "RETAIL", false))
                .given("amount", new BigDecimal(amount));
    }

    private DecisionCheck approved() {
        return loan(30, "25750000.50", "5000000", "300000000");
    }

    private DecisionCheck fees(boolean interbank, boolean express, String channel) {
        return DecisionCheck.of(engine).decision("transfer_fees")
                .given("transfer", Map.of("amount", new BigDecimal("50000000"), "channel", channel,
                        "interbank", interbank, "express", express));
    }

    @Test
    void passingExpectationsChain() {
        approved()
                .expectOutput("decision", "APPROVE")
                .expectOutput("limit", new BigDecimal("309000000"))
                .expectReasonCodes()
                .expectMatchedRules("R4")
                .expectRuleVersion("3");
    }

    @Test
    void comparesNumbersByValue() {
        approved()
                .expectOutput("limit", new BigDecimal("309000000.00"))
                .expectOutput("limit", 309_000_000L)
                .expectOutput("limit", 309_000_000)
                .expectOutput("limit", new java.math.BigInteger("309000000"));
        fees(true, false, "COUNTER")
                .expectOutput("fee", List.of(25000, new BigDecimal("10000.00")))
                .expectOutput("feeCode", List.of("IBT", "CTR"));
    }

    @Test
    void reportsAWrongOutputWithExpectedAndActualValues() {
        assertThatThrownBy(() -> approved().expectOutput("decision", "REVIEW"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): output \"decision\": expected \"REVIEW\" but was \"APPROVE\"");
        assertThatThrownBy(() -> fees(true, false, "COUNTER").expectOutput("fee", List.of(25000)))
                .hasMessage("transfer_fees@1: output \"fee\": expected [25000] but was [25000, 10000]");
    }

    @Test
    void neverShowsInputFacts() {
        assertThatThrownBy(() -> approved().expectOutput("decision", "REVIEW").expectMatchedRules())
                .isInstanceOf(AssertionError.class)
                .message().doesNotContain("25750000").doesNotContain("5000000");
    }

    @Test
    void absentOutputs() {
        loan(17, "30000000", "0", "100000000").expectNoOutput("limit").expectOutput("decision", "REJECT");

        assertThatThrownBy(() -> approved().expectNoOutput("limit"))
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): output \"limit\": expected no value but was 309000000");
        assertThatThrownBy(() -> loan(17, "30000000", "0", "100000000").expectOutput("limit", 0))
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): output \"limit\": expected 0"
                        + " but the decision produced no such output");
    }

    @Test
    void exactOutput() {
        approved().expectExactOutput(Map.of("decision", "APPROVE", "limit", 309_000_000));

        assertThatThrownBy(() -> approved().expectExactOutput(Map.of("decision", "APPROVE")))
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): output \"limit\": not expected but was 309000000");
    }

    @Test
    void reasonCodesAndMatchedRulesMustMatchExactly() {
        assertThatThrownBy(() -> loan(30, "20000000", "10000001", "100000000").expectReasonCodes())
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): reason codes: expected [] but was [DTI_HIGH]");
        assertThatThrownBy(() -> fees(true, false, "COUNTER").expectMatchedRules("COUNTER", "INTERBANK"))
                .hasMessage("transfer_fees@1: matched rules: expected [COUNTER, INTERBANK] but was [INTERBANK, COUNTER]");
        fees(false, false, "APP").expectMatchedRules().expectExactOutput(Map.of("feeCode", List.of(), "fee", List.of()));
    }

    @Test
    void ruleVersionByLabelOrExactContent() {
        String full = approved().result().ruleVersion();

        approved().expectRuleVersion("3").expectRuleVersion(full);
        assertThatThrownBy(() -> approved().expectRuleVersion("2"))
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): rule version: expected 2 but was " + full);
        assertThatThrownBy(() -> approved().expectRuleVersion("3@sha256:0000"))
                .hasMessageContaining("rule version: expected 3@sha256:0000 but was 3@sha256:");
        DecisionCheck.of(engine).decision("loan_eligibility").version("2").asOf(AFTER_V3)
                .given("applicant", new Applicant(19, new BigDecimal("30000000"), BigDecimal.ZERO, "RETAIL", false))
                .given("amount", BigDecimal.ONE)
                .expectRuleVersion("2").expectReasonCodes("UNDERAGE");
    }

    @Test
    void expectedFailures() {
        loan(30, "0", "1000", "1000")
                .expectFailure(DecisionEvaluationException.class)
                .expectFailure(RuntimeException.class)
                .expectFailureMessage("division by zero")
                .expectFailureMessage("rule R3");
        DecisionCheck.of(engine).decision("nope").expectFailure(DecisionNotFoundException.class);
    }

    @Test
    void reportsFailuresThatDidNotHappenAsExpected() {
        assertThatThrownBy(() -> approved().expectFailure(DecisionEvaluationException.class))
                .hasMessage("loan_eligibility@3 (asOf 2026-12-01): expected DecisionEvaluationException but the evaluation"
                        + " succeeded with output {decision=\"APPROVE\", limit=309000000}, matched rules [R4]");
        assertThatThrownBy(() -> approved().expectFailureMessage("division"))
                .hasMessageContaining("expected the evaluation to fail with a message containing \"division\" but it succeeded");
        assertThatThrownBy(() -> loan(30, "0", "1000", "1000").expectFailure(DecisionNotFoundException.class))
                .hasMessageStartingWith("loan_eligibility (asOf 2026-12-01): expected DecisionNotFoundException"
                        + " but the evaluation threw DecisionEvaluationException: loan_eligibility@3@sha256:")
                .hasCauseInstanceOf(DecisionEvaluationException.class);
        assertThatThrownBy(() -> loan(30, "0", "1000", "1000").expectFailureMessage("overflow"))
                .hasMessageContaining("expected a failure message containing \"overflow\" but the message was: ")
                .hasMessageContaining("division by zero");
    }

    @Test
    void anExpectationOfSuccessFailsWhenTheEvaluationFails() {
        assertThatThrownBy(() -> loan(30, "0", "1000", "1000").expectOutput("decision", "REJECT"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("loan_eligibility (asOf 2026-12-01): evaluation failed: ")
                .hasMessageContaining("division by zero")
                .hasCauseInstanceOf(DecisionEvaluationException.class);
    }

    @Test
    void rejectsFloatingPointAndNullExpectations() {
        assertThatThrownBy(() -> approved().expectOutput("limit", 309000000.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floating-point");
        assertThatThrownBy(() -> approved().expectOutput("fee", List.of(new BigDecimal("1"), 0.5f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output \"fee\"[1] is a floating-point number");
        assertThatThrownBy(() -> approved().expectOutput("limit", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectNoOutput");
    }

    @Test
    void requestIsFixedOnceEvaluated() {
        DecisionCheck check = approved().expectMatchedRules("R4");

        assertThatThrownBy(() -> check.given("extra", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> check.asOf(AFTER_V3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DecisionCheck.of(engine).expectReasonCodes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision(id)");
    }

    @Test
    void factsCanBeGivenTogether() {
        DecisionCheck.of(engine).decision("deposit_rate")
                .given(Facts.of("termMonths", 9, "balance", new BigDecimal("100000000")))
                .expectOutput("rate", new BigDecimal("0.048"));
        assertThatThrownBy(() -> DecisionCheck.of(engine).decision("deposit_rate")
                .given("termMonths", 9).given(Facts.of("termMonths", 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate fact");
    }
}
