//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class LocalValuesTest {

    private static final String TABLE = """
            {
              "id": "loan", "version": "1", "hitPolicy": "FIRST",
              "inputs": { "applicant": { "monthlyIncome": "decimal", "monthlyDebt": "decimal" }, "amount": "decimal",
                          "vip": "bool" },
              "outputs": { "decision": "string", "dti": "decimal" },
              "let": {
                "highDti": "dti > decimal('0.5')",
                "dti": "applicant.monthlyDebt / applicant.monthlyIncome",
                "annualIncome": "applicant.monthlyIncome * 12"
              },
              "rules": [
                { "id": "VIP", "when": "vip", "then": { "decision": "'REFER'" } },
                { "id": "R1", "when": "highDti", "then": { "decision": "'REJECT'", "dti": "dti" } },
                { "id": "R2", "when": "amount <= annualIncome", "then": { "decision": "'APPROVE'", "dti": "dti" } }
              ]
            }""";

    private static RuleStoneEngine engine(String json) {
        return RuleStone.builder().decision(DecisionTableJson.read(json)).build();
    }

    private static DecisionResult evaluate(Map<String, Object> facts) {
        return engine(TABLE).evaluate(DecisionRef.of("loan"), Facts.from(facts));
    }

    private static Map<String, Object> facts(String income, String debt, String amount, boolean vip) {
        return Map.of("applicant", Map.of("monthlyIncome", new BigDecimal(income), "monthlyDebt", new BigDecimal(debt)),
                "amount", new BigDecimal(amount), "vip", vip);
    }

    private static List<String> problems(String json) {
        try {
            engine(json);
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
        throw new AssertionError("expected InvalidDecisionException");
    }

    private static String table(String let, String when) {
        return """
                { "id": "t", "version": "1", "hitPolicy": "FIRST", "inputs": { "x": "int", "xs": ["int"] },
                  "outputs": {}, "let": %s, "rules": [ { "id": "R", "when": "%s" } ] }""".formatted(let, when);
    }

    @Test
    void rulesReadLocalValuesThatReadEachOther() {
        DecisionResult result = evaluate(facts("10000000", "6000000", "1", false));
        assertThat(result.output()).containsEntry("decision", "REJECT").containsEntry("dti", new BigDecimal("0.6"));

        result = evaluate(facts("10000000", "1000000", "50000000", false));
        assertThat(result.output()).containsEntry("decision", "APPROVE").containsEntry("dti", new BigDecimal("0.1"));
    }

    @Test
    void aLocalValueNoRuleReachesNeverFails() {
        // VIP matches before highDti, whose division by zero would fail, is read.
        assertThat(evaluate(facts("0", "1", "1", true)).output()).containsEntry("decision", "REFER");
    }

    @Test
    void aFailingLocalValueFailsTheRuleThatReadsItAndNamesItself() {
        assertThatThrownBy(() -> evaluate(facts("0", "1", "1", false)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("rule R1 condition failed: local value dti failed: decimal division by zero"
                        + " in: applicant.monthlyDebt / applicant.monthlyIncome");
    }

    @Test
    void errorsAreAbsorbedAsIfTheExpressionWereWrittenInPlace() {
        String json = table("{ \"bad\": \"1 / (x - x) > 0\" }", "bad || x == 1");
        DecisionResult result = engine(json).evaluate(DecisionRef.of("t"), Facts.of("x", 1, "xs", List.of()));
        assertThat(result.matched()).isTrue();
    }

    @Test
    void reportsNameClashesCyclesAndTypeErrors() {
        assertThat(problems(table("{ \"x\": \"1\", \"params\": \"1\", \"businessDate\": \"1\", \"in\": \"1\", \"2a\": \"1\" }",
                "true"))).containsExactly(
                "t@1 let.2a: must be an identifier (a letter or '_', then letters, digits or '_') and not a reserved word",
                "t@1 let.businessDate: reserved name; every decision receives businessDate implicitly",
                "t@1 let.in: must be an identifier (a letter or '_', then letters, digits or '_') and not a reserved word",
                "t@1 let.params: reserved name for parameter sets",
                "t@1 let.x: an input has the same name");
        assertThat(problems(table("{ \"a\": \"b + 1\", \"b\": \"c + 1\", \"c\": \"a + 1\", \"d\": \"d\", \"e\": \"a\" }",
                "e > 0"))).containsExactly("t@1 let.a: cycle: a -> b -> c -> a", "t@1 let.d: cycle: d -> d");
        assertThat(problems(table("{ \"a\": \"x + 's'\", \"b\": \"a * 2\", \"c\": \"y\", \"d\": \"0.5\" }", "b > 0")))
                .satisfiesExactly(
                        p -> assertThat(p).startsWith("t@1 let.a: ERROR: <input>:1:3: found no matching overload for '_+_'"),
                        p -> assertThat(p).startsWith("t@1 let.c: ERROR: <input>:1:1: undeclared reference to 'y'"),
                        p -> assertThat(p).isEqualTo("t@1 let.d: a local value must produce bool, int, string, decimal, "
                                + "money, date, a list or a struct but produces double: 0.5"));
    }

    @Test
    void typesAreInferred() {
        assertThat(problems(table("{ \"n\": \"xs.size()\" }", "n == 'a'")))
                .singleElement().asString().contains("found no matching overload for '_==_' applied to '(int, string)'");
        String structs = """
                { "id": "t", "version": "1", "hitPolicy": "FIRST",
                  "inputs": { "loans": [ { "amount": "decimal" } ] }, "outputs": { "total": "decimal" },
                  "let": { "first": "loans[0]", "total": "loans.map(l, l.amount).sum()" },
                  "rules": [ { "id": "R", "when": "first.amount > decimal(0)", "then": { "total": "total" } } ] }""";
        DecisionResult result = engine(structs).evaluate(DecisionRef.of("t"),
                Facts.of("loans", List.of(Map.of("amount", 2), Map.of("amount", 3))));
        assertThat(result.output()).containsEntry("total", new BigDecimal("5"));
    }

    @Test
    void theHashCoversLocalValuesAndIsUnchangedWithoutThem() {
        String without = """
                { "id": "t", "version": "1", "hitPolicy": "FIRST", "inputs": {}, "outputs": {},
                  "rules": [ { "id": "R", "when": "true" } ] }""";
        String empty = without.replace("\"outputs\": {},", "\"outputs\": {}, \"let\": {},");
        String with = without.replace("\"outputs\": {},", "\"outputs\": {}, \"let\": { \"a\": \"1\" },");
        assertThat(DecisionTableJson.write(DecisionTableJson.read(without))).isEqualTo(
                "{\"hitPolicy\":\"FIRST\",\"id\":\"t\",\"inputs\":{},\"outputs\":{},"
                        + "\"rules\":[{\"id\":\"R\",\"then\":{},\"when\":\"true\"}],"
                        + "\"version\":\"1\"}");
        assertThat(DecisionTableJson.write(DecisionTableJson.read(empty)))
                .isEqualTo(DecisionTableJson.write(DecisionTableJson.read(without)));
        assertThat(DecisionTableJson.write(DecisionTableJson.read(with))).contains("\"let\":{\"a\":\"1\"}");
    }

    @Test
    void readingIsStrict() {
        assertThatThrownBy(() -> DecisionTableJson.read(table("{ \"a\": 1 }", "true")))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("$.let.a: expected a CEL expression string");
        assertThatThrownBy(() -> DecisionTableJson.read(table("[]", "true")))
                .hasMessageContaining("$.let: expected an object of name to CEL expression");
    }
}
