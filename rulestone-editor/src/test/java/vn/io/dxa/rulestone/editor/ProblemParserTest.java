//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;

class ProblemParserTest {

    private static final JsonNode TABLE = Json.parse("""
            {"id": "loan_eligibility", "version": "3", "rules": [
              {"id": "R1"}, {"id": "R2"}, {"id": "R[2]"}, {"id": "R2"}]}""");

    private final ProblemParser parser = new ProblemParser(TABLE);

    @Test
    void expressionErrorWithPosition() {
        Problem p = parser.parse("loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'\n"
                + " | applicant.agee < 18\n | .........^");

        assertThat(p.message()).isEqualTo("undefined field 'agee'");
        assertThat(p.detail()).startsWith("loan_eligibility@3 rules[R1].when: ERROR:");
        assertThat(p.location()).isEqualTo(new Problem.Location("$.rules[0].when", "R1", 0, "when", null, null, null, 1, 10));
    }

    @Test
    void outputExpression() {
        Problem p = parser.parse("loan_eligibility@3 rules[R2].then.limit: expression must produce decimal but produces string: 'x'");

        assertThat(p.location()).isEqualTo(new Problem.Location("$.rules[1].then.limit", "R2", 1, "then", "limit", null, null, null, null));
        assertThat(p.message()).isEqualTo("expression must produce decimal but produces string: 'x'");
    }

    @Test
    void ruleIdsContainingBrackets() {
        Problem p = parser.parse("loan_eligibility@3 rules[R[2]].reasonCode: must be UPPER_SNAKE_CASE, e.g. DTI_HIGH");

        assertThat(p.location().rule()).isEqualTo("R[2]");
        assertThat(p.location().ruleIndex()).isEqualTo(2);
        assertThat(p.location().field()).isEqualTo("reasonCode");
    }

    @Test
    void duplicateRuleIdPointsAtTheLaterRow() {
        Problem p = parser.parse("loan_eligibility@3 rules[R2]: duplicate rule id");

        assertThat(p.location().ruleIndex()).isEqualTo(3);
        assertThat(p.location().field()).isEqualTo("id");
    }

    @Test
    void tableLevelCompileProblems() {
        assertThat(parser.parse("loan_eligibility@3 outputs.x: outputs must be bool, int, string, decimal, money, date or lists of them")
                .location()).isEqualTo(new Problem.Location("$.outputs.x", null, null, "outputs", "x", null, null, null, null));
        assertThat(parser.parse("loan_eligibility@3 inputs.businessDate: reserved name").location().input())
                .isEqualTo("businessDate");
        assertThat(parser.parse("loan_eligibility@3 effectiveTo: must be after effectiveFrom").location().field())
                .isEqualTo("effectiveTo");
        assertThat(parser.parse("loan_eligibility@3 rules: a decision table needs at least one rule").location().field())
                .isEqualTo("rules");
    }

    @Test
    void jsonPathProblems() {
        assertThat(parser.parse("$.rules[2].then.limit: expected a CEL expression string").location())
                .isEqualTo(new Problem.Location("$.rules[2].then.limit", "R[2]", 2, "then", "limit", null, null, null, null));
        assertThat(parser.parse("$.rules[1]: expected an object").location())
                .isEqualTo(new Problem.Location("$.rules[1]", "R2", 1, null, null, null, null, null, null));
        assertThat(parser.parse("$.inputs.applicant.accounts[].balance: unknown type 'double'").location())
                .isEqualTo(new Problem.Location("$.inputs.applicant.accounts[].balance", null, null, "inputs", null,
                        "applicant.accounts[].balance", null, null, null));
        assertThat(parser.parse("$.efectiveFrom: unknown field (allowed: ...)").location().field()).isEqualTo("efectiveFrom");
        assertThat(parser.parse("$: expected a JSON object").location().field()).isNull();
    }

    @Test
    void invalidJson() {
        Problem p = parser.parse("invalid JSON: Unexpected end-of-input (line 3, column 5)");
        assertThat(p.location().line()).isEqualTo(3);
        assertThat(p.location().column()).isEqualTo(5);
    }

    @Test
    void unknownShapesBecomeTableProblems() {
        Problem p = new ProblemParser(null).parse("something else\nsecond line");
        assertThat(p.message()).isEqualTo("something else");
        assertThat(p.location().field()).isNull();
    }

    @Test
    void parsesWhatTheEngineActuallyReports() {
        String json = EditorHarness.read(Path.of("../examples/loan_eligibility.v3.stone.json"))
                .replace("\"applicant.age < 18\"", "\"applicant.agee < 18\"")
                .replace("\"reasonCode\": \"DTI_HIGH\"", "\"reasonCode\": \"dti_high\"");
        List<String> raw = TableValidator.compileProblems(DecisionTableJson.read(json));

        List<Problem> problems = new ProblemParser(Json.parse(json)).parseAll(raw);

        assertThat(problems).extracting(p -> p.location().rule() + "." + p.location().field())
                .containsExactlyInAnyOrder("R1.when", "R3.reasonCode");
        assertThat(problems).allMatch(p -> !p.message().contains("\n"));
    }

    @Test
    void structuralProblemsFromTheReader() {
        String json = "{\"id\":\"t\",\"version\":\"1\",\"hitPolicy\":\"SOME\",\"inputs\":{},\"outputs\":{},\"rules\":[]}";
        try {
            DecisionTableJson.read(json);
        } catch (InvalidDecisionException e) {
            assertThat(new ProblemParser(Json.parse(json)).parse(e.problems().get(0)).location().field()).isEqualTo("hitPolicy");
            return;
        }
        throw new AssertionError("expected a problem");
    }
}
