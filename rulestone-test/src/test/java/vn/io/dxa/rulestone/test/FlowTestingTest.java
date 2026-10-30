//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionTable;

/** Test support for decision flows: expected paths, typed facts, and node and branch coverage. */
class FlowTestingTest {

    private static final List<DecisionTable> TABLES = ExamplesStoneTest.allExampleTables();
    private static final List<DecisionFlow> FLOWS = ExamplesStoneTest.exampleFlows();
    /** The example flow without model nodes, which the coverage examples of docs/testing.md use. */
    private static final List<DecisionFlow> ORIGINATION = FLOWS.stream().filter(f -> f.id().equals("card_origination")).toList();

    private final RuleStoneEngine engine = ExamplesStoneTest.exampleEngine();

    @TempDir
    Path dir;

    private static String flowCase(String name, String id, int age, boolean existing, String utilisation,
                                   String expect) {
        return """
                { "name": "%s",
                  "facts": { "applicant": { "id": "%s", "age": %d, "income": "20000000 VND", "existingCustomer": %b },
                             "bureau": { "dpd30Count12m": 0, "utilisation": "%s" } },
                  "expect": %s }""".formatted(name, id, age, existing, utilisation, expect);
    }

    private Path write(String content) throws IOException {
        return Files.writeString(dir.resolve("flow.stone-test.json"),
                "{ \"decision\": \"card_origination\", \"cases\": [" + content + "] }");
    }

    @Test
    void flowCasesAreReadWithTheFlowsTypes() {
        List<StoneTestCase> cases = StoneTestJson.read("{ \"decision\": \"card_origination\", \"cases\": ["
                + flowCase("a", "C-1", 30, true, "0.1", "{ \"output\": { \"limit\": \"0 VND\" } }") + "] }",
                "flow.stone-test.json", TABLES, FLOWS);

        @SuppressWarnings("unchecked")
        Map<String, Object> applicant = (Map<String, Object>) cases.get(0).facts().asMap().get("applicant");
        assertThat(applicant.get("income")).isEqualTo(Money.of("20000000", "VND"));

        assertThatThrownBy(() -> StoneTestJson.read("{ \"decision\": \"card_origination\", \"cases\": ["
                + "{ \"name\": \"a\", \"facts\": { \"applicant\": { \"agee\": 1 } }, \"expect\": { \"output\": { \"limitt\": 1 } } }"
                + "] }", "flow.stone-test.json", TABLES, FLOWS))
                .isInstanceOfSatisfying(InvalidStoneTestException.class, e -> assertThat(e.problems()).contains(
                        "$.cases[0].facts.applicant.agee: unknown field (declared: id, age, income, existingCustomer)",
                        "$.cases[0].expect.output.limitt: not declared (declared: decision, limit, riskGrade)"));
    }

    @Test
    void aWrongPathFailsTheCaseShowingBothPaths() throws IOException {
        Path file = write(flowCase("rejected", "C-1", 17, true, "0.1",
                "{ \"path\": [\"policy\", \"affordability\", \"rejected\"] }"));
        StoneTestCase testCase = StoneTestJson.read(file, TABLES, FLOWS).get(0);

        assertThatThrownBy(() -> testCase.check(engine))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("evaluated with card_origination@1:")
                .hasMessageContaining("path: expected [policy, affordability, rejected] but was [policy, rejected]");
    }

    @Test
    void anErrorCannotBeCombinedWithAPath() {
        assertThatThrownBy(() -> StoneTestJson.read("{ \"decision\": \"card_origination\", \"cases\": ["
                + "{ \"name\": \"a\", \"facts\": {}, \"expect\": { \"error\": \"x\", \"path\": [] } }] }", "f", TABLES, FLOWS))
                .isInstanceOfSatisfying(InvalidStoneTestException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.cases[0].expect: \"error\" cannot be combined with [path]: a failed evaluation has no "
                                + "output, reason codes, matched rules or path"));
    }

    @Test
    void decisionCheckExpectsAPath() {
        DecisionResult result = DecisionCheck.of(engine)
                .decision("card_origination").asOf(LocalDate.of(2026, 12, 1))
                .given("applicant", Map.of("id", "C-1", "age", 17, "income", Money.of("1", "VND"), "existingCustomer", true))
                .given("bureau", Map.of("dpd30Count12m", 0, "utilisation", java.math.BigDecimal.ONE))
                .expectPath("policy", "rejected")
                .result();
        assertThat(result.output()).containsEntry("decision", "REJECT");

        assertThatThrownBy(() -> DecisionCheck.of(engine).decision("deposit_rate")
                .given("termMonths", 3).given("balance", 1).expectPath("other"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("path: expected [other] but was [deposit_rate]");
    }

    // --- docs/testing.md, "Testing decision flows" ---------------------------------------------

    private static List<String> docBlocks(String language) throws IOException {
        List<String> blocks = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("```(\\w*)\\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(Files.readString(Path.of("..", "docs", "testing.md")));
        while (m.find()) {
            if (m.group(1).equals(language)) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    @Test
    void theFlowCaseInThePagePassesAndIsInTheExampleFile() throws IOException {
        String caseJson = docBlocks("json").stream().filter(b -> b.contains("\"path\"")).findFirst().orElseThrow();
        List<StoneTestCase> cases = StoneTestJson.read("{ \"decision\": \"card_origination\", \"cases\": [" + caseJson
                + "] }", "testing.md", TABLES, FLOWS);

        assertThat(cases).singleElement().satisfies(c -> assertThat(c.check(engine)).isPresent());
        assertThat(StoneTestJson.read(ExamplesStoneTest.FLOWS.resolve("card_origination.stone-test.json"), TABLES, FLOWS))
                .extracting(StoneTestCase::name).contains(cases.get(0).name());
    }

    @Test
    void theCoverageReportInThePageIsForTheExampleWithoutItsChallengerCase() throws Throwable {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode file = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(ExamplesStoneTest.FLOWS.resolve("card_origination.stone-test.json").toFile());
        com.fasterxml.jackson.databind.node.ArrayNode cases = (com.fasterxml.jackson.databind.node.ArrayNode) file.get("cases");
        for (int i = cases.size() - 1; i >= 0; i--) {
            if (cases.get(i).get("name").asText().contains("challenger")) {
                cases.remove(i);
            }
        }
        Files.writeString(dir.resolve("card_origination.stone-test.json"), mapper.writeValueAsString(file));
        List<DecisionTable> flowTables = ExamplesStoneTest.allExampleTables().stream()
                .filter(t -> List.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit").contains(t.id()))
                .toList();
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, dir, RuleCoverage.of(flowTables, ORIGINATION)).toList();
        DynamicTest coverage = tests.get(tests.size() - 1);

        assertThat(coverage.getDisplayName()).isEqualTo("rule and node coverage");
        assertThatThrownBy(() -> coverage.getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(docBlocks("")).contains(e.getMessage() + "\n"));
    }

    @Test
    void coverageReportsNodesNeverVisitedAndBranchesNeverTaken() throws Throwable {
        write(String.join(",",
                flowCase("rejected", "C-1010", 17, true, "0.1", "{}"),
                flowCase("champion A", "C-1002", 35, true, "0.1", "{}"),
                flowCase("failing", "C-1006", 19, false, "0.1", "{ \"output\": { \"decision\": \"APPROVE\" } }")));
        RuleCoverage coverage = RuleCoverage.of(TABLES.stream().filter(t -> List.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit").contains(t.id())).toList(), ORIGINATION);
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, dir, coverage).toList();
        DynamicTest last = tests.get(tests.size() - 1);

        assertThat(last.getDisplayName()).isEqualTo("rule and node coverage");
        assertThatThrownBy(() -> last.getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessage("""
                        rule coverage: 4 of 15 rules matched by a passing test
                          card_grade_existing@1: 1 of 4 rules, never matched: B, C, D
                          card_grade_new@1: 0 of 4 rules, never matched: THIN_FILE, B, C, D
                          card_limit@1: 1 of 4 rules, never matched: A_CHALLENGER, B, C
                          card_policy@1: 2 of 3 rules, never matched: DELINQUENT
                        node coverage: 8 of 10 nodes and 5 of 10 branches exercised by a passing test
                          card_origination@1: 8 of 10 nodes, 5 of 10 branches, never visited: grade_new, referred, \
                        never taken: segment -> grade_new, grade_existing -> referred, grade_new -> referred, \
                        grade_new -> limit_strategy, limit_strategy[challenger]""");
        assertThat(coverage.uncoveredNodes()).containsExactly(Map.entry("card_origination@1", List.of("grade_new", "referred")));
        assertThat(coverage.uncoveredBranches().get("card_origination@1")).contains("limit_strategy[challenger]");
        assertThat(coverage.isComplete()).isFalse();
    }

    // --- Model nodes ----------------------------------------------------------------------------

    private static final String SCORED_FACTS = """
            "facts": { "applicant": { "id": "C-1", "age": 35, "income": "20000000 VND", "existingCustomer": true },
                       "bureau": { "dpd30Count12m": 0, "utilisation": "0.1" } }""";

    private static final String FRAUD = """
            "fraud": { "modelId": "card_application_fraud", "modelVersion": "1.2.0", "outputs": { "score": "0.01" } }""";

    private Path writeScored(String... cases) throws IOException {
        return Files.writeString(dir.resolve("scored.stone-test.json"),
                "{ \"decision\": \"card_origination_scored\", \"cases\": [" + String.join(",", cases) + "] }");
    }

    @Test
    void casesGiveModelResultsOrFailures() throws IOException {
        Path file = writeScored("""
                { "name": "approved", %s,
                  "models": { %s, "pd_behaviour": { "modelId": "retail_pd_behaviour", "modelVersion": "2.4.0",
                                                    "outputs": { "pd": 0.0150 } } },
                  "expect": { "output": { "decision": "APPROVE", "riskGrade": "A" } } }""".formatted(SCORED_FACTS, FRAUD),
                """
                { "name": "timeout", %s,
                  "models": { %s, "pd_behaviour": { "failure": "TIMEOUT" } },
                  "expect": { "reasonCodes": ["MODEL_UNAVAILABLE"],
                              "path": ["policy", "fraud", "segment", "pd_behaviour", "referred"] } }"""
                        .formatted(SCORED_FACTS, FRAUD));
        List<StoneTestCase> cases = StoneTestJson.read(file, TABLES, FLOWS);

        assertThat(cases.get(0).facts().models()).containsOnlyKeys("fraud", "pd_behaviour");
        assertThat(cases).allSatisfy(c -> assertThat(c.check(engine)).isPresent());
        assertThat(cases.get(1).check(engine).orElseThrow().record().path().get(3).model())
                .hasValueSatisfying(m -> assertThat(m.status()).isEqualTo(vn.io.dxa.rulestone.api.ModelStatus.TIMEOUT));
    }

    @Test
    void invalidModelResultsAreReportedWithTheirLocation() {
        assertThatThrownBy(() -> StoneTestJson.read("{ \"decision\": \"card_origination_scored\", \"cases\": [{ "
                + "\"name\": \"a\", " + SCORED_FACTS + ", \"models\": { \"fraud\": { \"failure\": \"SLOW\" }, "
                + "\"pd\": { \"modelId\": \"m\" } }, \"expect\": {} }] }", "f", TABLES, FLOWS))
                .isInstanceOfSatisfying(InvalidStoneTestException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.cases[0].models.fraud.failure: expected \"TIMEOUT\" or \"ERROR\"",
                        "$.cases[0].models.pd.modelVersion: required non-empty string",
                        "$.cases[0].models.pd.outputs: required object of output names to values"));
    }

    @Test
    void coverageCountsTheFailurePathOfEveryModelNode() throws Throwable {
        writeScored("""
                { "name": "approved", %s,
                  "models": { %s, "pd_behaviour": { "modelId": "retail_pd_behaviour", "modelVersion": "2.4.0",
                                                    "outputs": { "pd": "0.0150" } } },
                  "expect": {} }""".formatted(SCORED_FACTS, FRAUD),
                """
                { "name": "no fraud score", %s, "expect": { "path": ["policy", "fraud", "referred"] } }"""
                        .formatted(SCORED_FACTS));
        RuleCoverage coverage = RuleCoverage.of(List.of(),
                FLOWS.stream().filter(f -> f.id().equals("card_origination_scored")).toList());
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, dir, coverage).toList();
        assertThatThrownBy(() -> tests.get(tests.size() - 1).getExecutable().execute())
                .isInstanceOf(AssertionError.class);

        // The missing fraud score took fraud's failure path, not its conditional next to the same node.
        assertThat(coverage.uncoveredBranches().get("card_origination_scored@1")).containsExactly(
                "policy -> rejected", "fraud -> referred", "segment -> pd_application",
                "pd_behaviour -> referred (onFailure)", "pd_application -> grade",
                "pd_application -> referred (onFailure)", "grade -> referred");
        assertThat(coverage.uncoveredNodes().get("card_origination_scored@1"))
                .containsExactly("pd_application", "rejected");
    }
}
