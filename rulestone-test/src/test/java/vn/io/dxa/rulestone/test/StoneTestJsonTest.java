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
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

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
                        + " (allowed: error, exactOutput, matchedRules, output, path, reasonCodes, ruleVersion)");
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
                        + " a failed evaluation has no output, reason codes, matched rules or path",
                "$.cases[3].expect: required object (use {} to check only that the evaluation succeeds)",
                "$.cases[4].expect.exactOutput: requires \"output\"",
                "$.cases[4].expect.error: required non-empty text that the error message must contain",
                "$.cases[4].expect: \"error\" cannot be combined with [exactOutput]:"
                        + " a failed evaluation has no output, reason codes, matched rules or path",
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

    // --- Reading with the tables' types ---------------------------------------------------------

    private static final String FEES = """
            { "id": "fees", "version": "%s", %s "hitPolicy": "COLLECT",
              "inputs": { "transfer": { "amount": "money", "valueDate": "date", "rate": "decimal" } },
              "outputs": { "fee": "money", "settles": "date" },
              "rules": [
                { "id": "BASE", "when": "true", "then": {
                  "fee": "(transfer.amount * transfer.rate).round()", "settles": "transfer.valueDate.plusDays(1)" } } ] }""";

    private static DecisionTable fees(String version, String dates) {
        return DecisionTableJson.read(FEES.formatted(version, dates));
    }

    private static final List<DecisionTable> TYPED = List.of(fees("1", ""));

    private static List<StoneTestCase> typed(List<DecisionTable> tables, String casesJson) {
        return StoneTestJson.read("{ \"decision\": \"fees\", \"cases\": [" + casesJson + "] }", "fees.json", tables);
    }

    private static List<String> typedProblems(List<DecisionTable> tables, String casesJson) {
        try {
            typed(tables, casesJson);
        } catch (InvalidStoneTestException e) {
            return e.problems();
        }
        throw new AssertionError("expected InvalidStoneTestException");
    }

    @Test
    void readsMoneyDatesAndDecimalsWithTheDeclaredTypes() {
        StoneTestCase c = typed(TYPED, """
                { "name": "n", "facts": { "transfer": { "amount": "50000000 VND", "valueDate": "2026-11-02", "rate": "0.0005" } },
                  "expect": { "output": { "fee": ["25000.00 VND"], "settles": ["2026-11-03"] }, "exactOutput": true } }""")
                .get(0);

        assertThat(c.facts().asMap().get("transfer")).isEqualTo(Map.of(
                "amount", Money.of("50000000", "VND"),
                "valueDate", LocalDate.of(2026, 11, 2),
                "rate", new BigDecimal("0.0005")));
        RuleStoneEngine fees = RuleStone.builder().decisions(TYPED).build();
        assertThat(c.check(fees)).isPresent();   // money compared by value: 25000.00 VND equals 25000 VND
    }

    @Test
    void reportsValuesThatDoNotMatchTheirTypes() {
        assertThat(typedProblems(TYPED, """
                { "name": "a", "facts": { "transfer": { "amount": 50000000, "valueDate": "2/11/2026", "rat": "0.1" } },
                  "expect": { "output": { "fee": "25000 VND", "settles": ["2026-13-01"], "total": null, "other": 1 } } },
                { "name": "b", "facts": { "transfer": { "amount": "1 VND", "rate": null } }, "expect": {} }"""))
                .containsExactly(
                        "$.cases[0].facts.transfer.amount: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
                        "$.cases[0].facts.transfer.valueDate: expected a date as \"yyyy-MM-dd\"",
                        "$.cases[0].facts.transfer.rat: unknown field (declared: amount, valueDate, rate)",
                        "$.cases[0].expect.output.total: not declared (declared: fee, settles)",
                        "$.cases[0].expect.output.fee: expected an array",
                        "$.cases[0].expect.output.settles[0]: expected an existing date as \"yyyy-MM-dd\"",
                        "$.cases[0].expect.output.other: not declared (declared: fee, settles)",
                        "$.cases[1].facts.transfer.rate: null is not a value; leave the field out instead");
    }

    @Test
    void versionsWithDifferentTypesNeedAsOfOrVersion() {
        DecisionTable v1 = fees("1", "\"effectiveTo\": \"2026-11-01\",");
        DecisionTable v2 = DecisionTableJson.read(FEES.formatted("2", "\"effectiveFrom\": \"2026-11-01\",")
                .replace("\"rate\": \"decimal\"", "\"rate\": \"decimal\", \"channel\": \"string\""));
        List<DecisionTable> tables = List.of(v1, v2);
        String facts = "\"facts\": { \"transfer\": { \"amount\": \"1 VND\", \"channel\": \"APP\" } }";

        assertThat(typedProblems(tables, "{ \"name\": \"n\", " + facts + ", \"expect\": {} }")).containsExactly(
                "$.cases[0]: set \"version\" or \"asOf\": the versions of \"fees\" declare different inputs or outputs");
        assertThat(typed(tables, "{ \"name\": \"n\", \"asOf\": \"2026-11-01\", " + facts + ", \"expect\": {} }")).hasSize(1);
        assertThat(typed(tables, "{ \"name\": \"n\", \"version\": \"2\", " + facts + ", \"expect\": {} }")).hasSize(1);
        assertThat(typedProblems(tables, "{ \"name\": \"n\", \"asOf\": \"2026-10-31\", " + facts + ", \"expect\": {} }"))
                .containsExactly("$.cases[0].facts.transfer.channel: unknown field (declared: amount, valueDate, rate)");
    }

    @Test
    void decisionsThatAreNotAmongTheTablesAreReadWithoutTypes() {
        List<StoneTestCase> cases = StoneTestJson.read("""
                { "decision": "deposit_rate", "cases": [ { "name": "n",
                  "facts": { "termMonths": 9, "balance": "100000000" }, "expect": {} } ] }""", "t.json", TYPED);

        assertThat(cases.get(0).facts().asMap()).containsEntry("balance", "100000000");
    }

    @Test
    void readsFilesFromDiskWithTypes() {
        List<StoneTestCase> cases = StoneTestJson.read(
                ExamplesStoneTest.EXAMPLES.resolve("aml_transaction_monitoring.stone-test.json"),
                ExamplesStoneTest.exampleTables());

        assertThat(cases.get(0).facts().get("transaction")).get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("amount", Money.of("10000000", "VND"))
                .containsEntry("valueDate", LocalDate.of(2026, 11, 2));
        assertThat(cases).filteredOn(c -> !c.expectsError()).allSatisfy(c -> assertThat(c.check(engine)).isPresent());
    }
}
