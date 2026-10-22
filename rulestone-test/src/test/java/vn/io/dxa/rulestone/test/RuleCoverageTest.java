//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class RuleCoverageTest {

    private static DecisionTable table(String name) {
        return DecisionTableJson.read(ExamplesStoneTest.EXAMPLES.resolve(name));
    }

    private static DecisionResult result(String decision, String ruleVersion, String... matched) {
        return new DecisionResult(decision, ruleVersion, java.util.Map.of(), List.of(),
                java.util.Arrays.stream(matched).map(id -> new TraceStep(id, true)).toList());
    }

    @Test
    void tracksMatchedRulesPerDecisionVersion() {
        RuleCoverage coverage = RuleCoverage.of(table("loan_eligibility.v2.stone.json"), table("loan_eligibility.v3.stone.json"));
        assertThat(coverage.isComplete()).isFalse();

        coverage.record(result("loan_eligibility", "3@sha256:abc", "R1"));
        coverage.record(result("loan_eligibility", "3@sha256:abc", "R4"));
        coverage.record(new DecisionResult("loan_eligibility", "2@sha256:def", java.util.Map.of(), List.of(),
                List.of(new TraceStep("R1", false), new TraceStep("R2", true))));

        assertThat(coverage.uncoveredRules()).hasToString("{loan_eligibility@2=[R1, R3], loan_eligibility@3=[R2, R3, R5]}");
        assertThat(coverage.report()).isEqualTo("""
                rule coverage: 3 of 8 rules matched by a passing test
                  loan_eligibility@2: 1 of 3 rules, never matched: R1, R3
                  loan_eligibility@3: 2 of 5 rules, never matched: R2, R3, R5""");
        assertThatThrownBy(coverage::assertComplete).isInstanceOf(AssertionError.class).hasMessage(coverage.report());
    }

    @Test
    void completeCoverage() {
        RuleCoverage coverage = RuleCoverage.of(List.of(table("transfer_fees.stone.json")));
        coverage.record(result("transfer_fees", "1@sha256:x", "INTERBANK", "EXPRESS", "COUNTER"));

        assertThat(coverage.isComplete()).isTrue();
        assertThat(coverage.uncoveredRules()).isEmpty();
        assertThat(coverage.report()).isEqualTo("rule coverage: 3 of 3 rules matched by a passing test\n"
                + "  transfer_fees@1: 3 of 3 rules");
        assertThatCode(coverage::assertComplete).doesNotThrowAnyException();
    }

    @Test
    void ignoresDecisionsAndVersionsOutsideItsTables() {
        RuleCoverage coverage = RuleCoverage.of(table("deposit_rate.stone.json"));
        coverage.record(result("deposit_rate", "2@sha256:x", "SHORT"));
        coverage.record(result("other", "1@sha256:x", "SHORT"));

        assertThat(coverage.uncoveredRules()).hasToString("{deposit_rate@1=[SHORT, MID, LONG, VIP_LONG]}");
    }

    @Test
    void rejectsATableListedTwice() {
        DecisionTable table = table("deposit_rate.stone.json");

        assertThatThrownBy(() -> RuleCoverage.of(table, table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deposit_rate@1 is listed more than once");
    }
}
