//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * Runs the code shown in README.md and docs/getting-started.md and checks the
 * printed values quoted there. Update both together.
 */
class GettingStartedTest {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                     String segment, boolean blacklisted) {}

    @Test
    void documentedExampleProducesDocumentedOutput() {
        RuleStoneEngine engine = RuleStone.builder()
                .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
                .decision(DecisionTableJson.read(Path.of("../examples/loan_eligibility.v3.stone.json")))
                .build();

        Applicant applicant = new Applicant(30, new BigDecimal("25750000.50"),
                new BigDecimal("5000000"), "RETAIL", false);

        DecisionResult result = engine.evaluate(
                DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 12, 1)),
                Facts.of("applicant", applicant, "amount", new BigDecimal("300000000")));

        assertThat(result.output()).hasToString("{decision=APPROVE, limit=309000000}");
        assertThat(result.reasonCodes()).hasToString("[]");
        assertThat(result.matchedRules()).hasToString("[R4]");
        assertThat(result.ruleVersion()).startsWith("3@sha256:");
    }

    @Test
    void documentedRejectionProducesDocumentedOutput() {
        RuleStoneEngine engine = RuleStone.builder()
                .decision(DecisionTableJson.read(Path.of("../examples/loan_eligibility.v3.stone.json")))
                .build();

        DecisionResult result = engine.evaluate(
                DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 12, 1)),
                Facts.of("applicant", new Applicant(30, new BigDecimal("20000000"),
                                new BigDecimal("12000000"), "RETAIL", false),
                        "amount", new BigDecimal("100000000")));

        assertThat(result.output()).hasToString("{decision=REJECT}");
        assertThat(result.reasonCodes()).hasToString("[DTI_HIGH]");
        assertThat(result.trace()).hasToString(
                "[TraceStep[ruleId=R1, matched=false], TraceStep[ruleId=R2, matched=false], "
                        + "TraceStep[ruleId=R3, matched=true]]");
    }
}
