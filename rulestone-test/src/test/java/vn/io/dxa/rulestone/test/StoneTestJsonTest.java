//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class StoneTestJsonTest {

    private final RuleStoneEngine engine = ExamplesStoneTest.exampleEngine();

    private static StoneTestCase single(String caseJson) {
        List<StoneTestCase> cases = StoneTestJson.read("{ \"decision\": \"deposit_rate\", \"cases\": [" + caseJson + "] }", "t.json");
        assertThat(cases).hasSize(1);
        return cases.get(0);
    }

    private static List<String> problems(String json) {
        try {
            StoneTestJson.read(json, "bad.stone-test.json");
        } catch (InvalidStoneTestException e) {
            assertThat(e.source()).isEqualTo("bad.stone-test.json");
            assertThat(e.getMessage()).startsWith("invalid test file bad.stone-test.json (");
            return e.problems();
        }
        throw new AssertionError("expected InvalidStoneTestException");
    }

    @Test
    void readsCaseFields() {
        StoneTestCase c = single("""
                { "name": "n", "description": "d", "version": "1", "asOf": "2026-12-01",
                  "facts": { "termMonths": 9, "balance": 100000000 }, "expect": {} }""");

        assertThat(c.source()).isEqualTo("t.json");
        assertThat(c.decision()).isEqualTo("deposit_rate");
        assertThat(c.name()).isEqualTo("n");
        assertThat(c.description()).contains("d");
        assertThat(c.version()).contains("1");
        assertThat(c.asOf()).contains(LocalDate.of(2026, 12, 1));
        assertThat(c.expectsError()).isFalse();
        assertThat(c.ref()).isEqualTo(DecisionRef.of("deposit_rate").version("1").asOf(LocalDate.of(2026, 12, 1)));
        assertThat(c).hasToString("t.json: n");
        assertThat(c.check(engine)).isPresent();
    }

    @Test
    void readsNumbersExactlyWithoutFloatingPoint() {
        StoneTestCase c = single("""
                { "name": "n", "facts": {
                    "int": 30, "negative": -7, "huge": 123456789012345678901234567890,
                    "decimal": 25750000.50, "exponent": 1.5e3, "small": 0.035,
                    "list": [1, 2.50], "struct": { "a": { "b": 0.10 } }, "flag": true, "text": "x" },
                  "expect": {} }""");
        Map<String, Object> facts = c.facts().asMap();

        assertThat(facts.get("int")).isEqualTo(30L);
        assertThat(facts.get("negative")).isEqualTo(-7L);
        assertThat(facts.get("huge")).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(facts.get("decimal")).isEqualTo(new BigDecimal("25750000.50"));   // scale kept
        assertThat(facts.get("exponent")).isEqualTo(new BigDecimal("1.5e3"));
        assertThat(facts.get("small")).isEqualTo(new BigDecimal("0.035"));
        assertThat(facts.get("list")).isEqualTo(List.of(1L, new BigDecimal("2.50")));
        assertThat(facts.get("struct")).isEqualTo(Map.of("a", Map.of("b", new BigDecimal("0.10"))));
        assertThat(facts.get("flag")).isEqualTo(true);
        assertThat(facts.get("text")).isEqualTo("x");
    }

    @Test
    void expectationsAreComparedByValue() {
        single("""
                { "name": "n", "facts": { "termMonths": 9, "balance": 100000000 },
                  "expect": { "output": { "rate": 0.0480 }, "exactOutput": true, "reasonCodes": [],
                              "matchedRules": ["MID"], "ruleVersion": "1" } }""").check(engine);
    }

    @Test
    void reportsEveryMismatchOfACase() {
        StoneTestCase c = single("""
                { "name": "wrong", "facts": { "termMonths": 9, "balance": 100000000 },
                  "expect": { "output": { "rate": 0.05, "other": null }, "reasonCodes": ["X"],
                              "matchedRules": ["LONG"], "ruleVersion": "2" } }""");

        assertThatThrownBy(() -> c.check(engine))
                .isInstanceOf(AssertionError.class)
                .hasMessage("""
                        t.json, case "wrong", evaluated with deposit_rate@1:
                          - output "rate": expected 0.05 but was 0.048
                          - reason codes: expected [X] but was []
                          - matched rules: expected [LONG] but was [MID]
                          - rule version: expected 2 but was\s""" + engine.evaluate(DecisionRef.of("deposit_rate"),
                        c.facts()).ruleVersion());
    }

    @Test
    void exactOutputReportsUnexpectedOutputs() {
        StoneTestCase c = single("""
                { "name": "n", "facts": { "termMonths": 9, "balance": 1 },
                  "expect": { "output": { }, "exactOutput": true } }""");

        assertThatThrownBy(() -> c.check(engine))
                .hasMessageEndingWith("- output \"rate\": not expected but was 0.048");
    }

    @Test
    void errorExpectations() {
        StoneTestCase error = single("""
                { "name": "n", "facts": { "termMonths": 9 }, "expect": { "error": "balance: missing input" } }""");
        assertThat(error.expectsError()).isTrue();
        assertThat(error.check(engine)).isEmpty();

        StoneTestCase wrongMessage = single("""
                { "name": "n", "facts": { "termMonths": 9 }, "expect": { "error": "termMonths" } }""");
        assertThatThrownBy(() -> wrongMessage.check(engine))
                .hasMessageStartingWith("t.json, case \"n\":\n  - expected a failure message containing \"termMonths\""
                        + " but the message was: deposit_rate@1@sha256:");

        StoneTestCase noError = single("""
                { "name": "n", "facts": { "termMonths": 9, "balance": 1 }, "expect": { "error": "x" } }""");
        assertThatThrownBy(() -> noError.check(engine))
                .hasMessage("t.json, case \"n\":\n  - expected the evaluation to fail with a message containing \"x\""
                        + " but it succeeded with output {rate=0.048}, matched rules [MID]");

        StoneTestCase unexpected = single("""
                { "name": "n", "facts": { "termMonths": 9 }, "expect": { } }""");
        assertThatThrownBy(() -> unexpected.check(engine))
                .hasMessageStartingWith("t.json, case \"n\":\n  - evaluation failed: deposit_rate@1@sha256:")
                .hasMessageEndingWith("balance: missing input");
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevel() {
        assertThat(problems("""
                { "decision": "d", "decisions": 1, "cases": [
                  { "name": "n", "fact": {}, "facts": {}, "expect": { "matchedRule": [] } } ] }""")).containsExactly(
                "$.decisions: unknown field (allowed: cases, decision, description)",
                "$.cases[0].fact: unknown field (allowed: asOf, description, expect, facts, name, version)",
                "$.cases[0].expect.matchedRule: unknown field"
                        + " (allowed: error, exactOutput, matchedRules, output, reasonCodes, ruleVersion)");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThat(problems("""
                { "decision": "d", "cases": [ { "name": "n", "facts": { "a": 1, "a": 2 }, "expect": {} } ] }"""))
                .singleElement().asString()
                .startsWith("invalid JSON: Duplicate field 'a'")
                .contains("line 1");
    }

    @Test
    void reportsEveryProblemWithItsPath() {
        assertThat(problems("""
                {
                  "cases": [
                    { "name": "a", "asOf": "01/12/2026", "facts": { "x": null, "l": [1, null] }, "expect": {} },
                    { "name": "a", "facts": {}, "expect": { "exactOutput": "yes", "reasonCodes": "R", "matchedRules": [1] } },
                    { "facts": [], "expect": { "error": "x", "output": {}, "matchedRules": [] } },
                    { "name": "b", "facts": {} },
                    { "name": "c", "facts": {}, "expect": { "exactOutput": true, "error": " " } },
                    42
                  ]
                }""")).containsExactly(
                "$.decision: required non-empty string",
                "$.cases[0].asOf: expected an ISO date (yyyy-MM-dd) but got '01/12/2026'",
                "$.cases[0].facts.x: null is not a value; leave the field out instead",
                "$.cases[0].facts.l[1]: null is not a value; leave the field out instead",
                "$.cases[1].expect.exactOutput: expected true or false",
                "$.cases[1].expect.reasonCodes: expected an array of strings ([] for none)",
                "$.cases[1].expect.matchedRules[0]: expected a string",
                "$.cases[2].name: required non-empty string",
                "$.cases[2].facts: required object of fact names to values",
                "$.cases[2].expect: \"error\" cannot be combined with [output, matchedRules]:"
                        + " a failed evaluation has no output, reason codes or matched rules",
                "$.cases[3].expect: required object (use {} to check only that the evaluation succeeds)",
                "$.cases[4].expect.exactOutput: requires \"output\"",
                "$.cases[4].expect.error: required non-empty text that the error message must contain",
                "$.cases[4].expect: \"error\" cannot be combined with [exactOutput]:"
                        + " a failed evaluation has no output, reason codes or matched rules",
                "$.cases[5]: expected an object");
    }

    @Test
    void rejectsDuplicateCaseNames() {
        assertThat(problems("""
                { "decision": "d", "cases": [
                  { "name": "same", "facts": {}, "expect": {} },
                  { "name": "same", "facts": {}, "expect": {} } ] }"""))
                .containsExactly("$.cases[1].name: duplicate case name \"same\"");
    }

    @Test
    void rejectsMalformedFiles() {
        assertThat(problems("[]")).containsExactly("$: expected a JSON object");
        assertThat(problems("")).containsExactly("$: expected a JSON object");
        assertThat(problems("{ \"decision\": \"d\", \"cases\": [] }"))
                .containsExactly("$.cases: required non-empty array of test cases");
        assertThat(problems("{ \"decision\": \"d\", \"cases\": [ }")).singleElement().asString()
                .startsWith("invalid JSON: ");
        assertThat(problems("{ \"decision\": \"d\" } {}")).singleElement().asString()
                .startsWith("invalid JSON: ").containsIgnoringCase("trailing token");
    }

    @Test
    void nullInExpectedOutputMeansAbsent() {
        StoneTestCase c = single("""
                { "name": "n", "facts": { "termMonths": 9, "balance": 1 }, "expect": { "output": { "rate": null } } }""");

        assertThatThrownBy(() -> c.check(engine)).hasMessageEndingWith("- output \"rate\": expected no value but was 0.048");
    }

    @Test
    void readsFilesFromDisk() {
        List<StoneTestCase> cases = StoneTestJson.read(ExamplesStoneTest.EXAMPLES.resolve("transfer_fees.stone-test.json"));

        assertThat(cases).first().extracting(StoneTestCase::source).isEqualTo("transfer_fees.stone-test.json");
        assertThat(cases.get(0).facts().get("transfer")).get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("amount", 50_000_000L);
        assertThat(cases).allSatisfy(c -> assertThat(c.check(engine)).isPresent());
    }
}
