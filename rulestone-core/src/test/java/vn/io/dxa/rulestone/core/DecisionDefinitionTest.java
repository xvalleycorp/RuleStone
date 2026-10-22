//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class DecisionDefinitionTest {

    private static List<String> problemsOf(Runnable action) {
        try {
            action.run();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
        throw new AssertionError("expected InvalidDecisionException");
    }

    private static void build(String json) {
        RuleStone.builder().decision(DecisionTableJson.read(json)).build();
    }

    @Test
    void reportsEveryCompileProblemAtOnce() {
        List<String> problems = problemsOf(() -> build("""
                {
                  "id": "limits", "version": "1", "hitPolicy": "FIRST",
                  "inputs": { "customer": { "age": "int", "income": "decimal" } },
                  "outputs": { "limit": "decimal" },
                  "rules": [
                    { "id": "A", "when": "customer.agee > 18", "then": { "limit": "decimal(1)" } },
                    { "id": "B", "when": "customer.income > 1.5", "then": { "limit": "decimal(1)" } },
                    { "id": "C", "when": "customer.age", "then": { "limit": "decimal(1)" } },
                    { "id": "D", "when": "true", "then": { "limit": "customer.age" } },
                    { "id": "E", "when": "true", "then": { "amount": "decimal(1)" } },
                    { "id": "E", "when": "true", "then": {}, "reasonCode": "too-low" }
                  ]
                }"""));

        assertThat(problems).hasSize(7);
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[A].when").contains("undefined field 'agee'"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[B].when").contains("no matching overload"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[C].when").contains("must produce bool"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[D].then.limit").contains("must produce decimal"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[E].then.amount").contains("undeclared output"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("limits@1 rules[E]").contains("duplicate rule id"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("reasonCode").contains("UPPER_SNAKE_CASE"));
    }

    @Test
    void readingIsStrict() {
        List<String> problems = problemsOf(() -> DecisionTableJson.read("""
                {
                  "id": "x", "version": "1", "hitPolicy": "FRIST", "efectiveFrom": "2026-01-01",
                  "inputs": { "rate": "double" },
                  "outputs": {},
                  "rules": [ { "id": "A", "when": "true", "then": { "out": 1 } } ]
                }"""));

        assertThat(problems).containsExactlyInAnyOrder(
                "$.efectiveFrom: unknown field (allowed: description, effectiveFrom, effectiveTo, hitPolicy, id, inputs, outputs, rules, version)",
                "$.hitPolicy: unknown hit policy 'FRIST' (expected UNIQUE, FIRST or COLLECT)",
                "$.inputs.rate: unknown type 'double' (floating point is not supported; use decimal)",
                "$.rules[0].then.out: expected a CEL expression string (string literals need inner quotes, e.g. \"'APPROVE'\")");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> DecisionTableJson.read("""
                { "id": "x", "id": "y" }"""))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("Duplicate field 'id'");
    }

    @Test
    void ruleVersionHashIgnoresFormattingButTracksContent() {
        String compact = """
                {"id":"t","version":"1","hitPolicy":"FIRST","inputs":{"x":"int","y":"int"},"outputs":{},
                 "rules":[{"id":"A","when":"x > y","then":{}}]}""";
        String reordered = """
                {
                  "rules": [ { "then": {}, "when": "x > y", "id": "A" } ],
                  "outputs": {},
                  "inputs": { "y": "int", "x": "int" },
                  "hitPolicy": "FIRST", "version": "1", "id": "t"
                }""";
        String changed = compact.replace("x > y", "x >= y");

        assertThat(versionOf(reordered)).isEqualTo(versionOf(compact));
        assertThat(versionOf(changed)).isNotEqualTo(versionOf(compact));
        assertThat(versionOf(compact)).matches("1@sha256:[0-9a-f]{64}");
    }

    @Test
    void canonicalFormRoundTrips() {
        DecisionTable table = RuleStoneEngineTest.load("loan_eligibility.v3.stone.json");

        DecisionTable reread = DecisionTableJson.read(DecisionTableJson.write(table));

        assertThat(reread).isEqualTo(table);
    }

    private static String versionOf(String json) {
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(json)).build();
        return engine.evaluate(DecisionRef.of("t"), vn.io.dxa.rulestone.api.Facts.of("x", 1, "y", 2)).ruleVersion();
    }
}
