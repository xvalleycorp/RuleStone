//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.Rule;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Runs the code and test files shown in docs/testing.md and checks that every message quoted
 * there is exactly what RuleStone produces. Update both together.
 */
class TestingDocTest {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                     String segment, boolean blacklisted) {}

    private static final Path DOC = Path.of("..", "docs", "testing.md");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private final RuleStoneEngine engine = ExamplesStoneTest.exampleEngine();

    private static String doc() throws IOException {
        return Files.readString(DOC);
    }

    /** The fenced code blocks of the page with the given language tag ("" for plain text). */
    private static List<String> blocks(String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(doc());
        while (m.find()) {
            if (m.group(1).equals(language)) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    private static String testFileInDoc() throws IOException {
        return blocks("json").stream().filter(b -> b.contains("\"cases\"")).findFirst().orElseThrow();
    }

    private static Applicant applicant(int age, String income, String debt) {
        return new Applicant(age, new BigDecimal(income), new BigDecimal(debt), "RETAIL", false);
    }

    @Test
    void everyTestFileInThePagePasses() throws IOException {
        List<String> files = blocks("json").stream().filter(b -> b.contains("\"cases\"")).toList();
        assertThat(files).isNotEmpty();
        for (String json : files) {
            List<StoneTestCase> cases = StoneTestJson.read(json, "testing.md");
            assertThat(cases).isNotEmpty();
            cases.forEach(c -> c.check(engine));
        }
    }

    @Test
    void caseWithMoneyAndDatesPassesWithTheTables() throws IOException {
        String caseJson = blocks("json").stream().filter(b -> b.contains("LARGE_CASH_TRANSACTION")).findFirst().orElseThrow();
        String file = "{ \"decision\": \"aml_transaction_monitoring\", \"cases\": [" + caseJson + "] }";

        List<StoneTestCase> cases = StoneTestJson.read(file, "testing.md", ExamplesStoneTest.exampleTables());

        assertThat(cases).singleElement().satisfies(c -> assertThat(c.check(engine)).isPresent());
        List<String> exampleNames = StoneTestJson.read(
                        ExamplesStoneTest.EXAMPLES.resolve("aml_transaction_monitoring.stone-test.json"),
                        ExamplesStoneTest.exampleTables())
                .stream().map(StoneTestCase::name).toList();
        assertThat(exampleNames).contains(cases.get(0).name());
    }

    @Test
    void casesInThePageAreCopiedFromTheExampleFile() throws IOException {
        List<String> exampleNames = StoneTestJson.read(ExamplesStoneTest.EXAMPLES.resolve("loan_eligibility.v3.stone-test.json"))
                .stream().map(StoneTestCase::name).toList();

        assertThat(StoneTestJson.read(testFileInDoc(), "testing.md"))
                .extracting(StoneTestCase::name)
                .isSubsetOf(exampleNames);
    }

    @Test
    void strictReadingMessage() throws IOException {
        String misspelt = testFileInDoc().replaceFirst("\"matchedRules\"", "\"matchedRule\"");

        assertThatThrownBy(() -> StoneTestJson.read(misspelt, "loan_eligibility.v3.stone-test.json"))
                .isInstanceOf(InvalidStoneTestException.class)
                .satisfies(e -> assertThat(blocks("")).contains(e.getMessage() + "\n"));
    }

    @Test
    void failingCaseMessage() throws IOException {
        // R4 changed to lend at most 10 monthly incomes instead of 12
        DecisionTable v3 = DecisionTableJson.read(ExamplesStoneTest.EXAMPLES.resolve("loan_eligibility.v3.stone.json"));
        List<Rule> rules = v3.rules().stream().map(r -> !r.id().equals("R4") ? r : new Rule(r.id(), r.description(),
                "amount <= applicant.monthlyIncome * 10",
                Map.of("decision", "'APPROVE'",
                        "limit", "(applicant.monthlyIncome * 10 / 1000000).round(0, 'FLOOR') * 1000000"),
                r.reasonCode())).toList();
        RuleStoneEngine changed = RuleStone.builder().decision(new DecisionTable(v3.id(), v3.version(),
                v3.description(), v3.effectiveFrom(), v3.effectiveTo(), v3.hitPolicy(), v3.inputs(), v3.outputs(),
                rules)).build();
        StoneTestCase approved = StoneTestJson.read(testFileInDoc(), "loan_eligibility.v3.stone-test.json").get(0);

        assertThatThrownBy(() -> approved.check(changed))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(blocks("")).contains(e.getMessage() + "\n"));
    }

    @Test
    void junitFactoryAndTestNames() {
        // As in the page, with the examples directory in place of "rules"
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, Path.of("../examples")).toList();

        assertThat(tests).extracting(DynamicTest::getDisplayName)
                .contains("loan_eligibility.v3.stone-test.json: adult with low debt is approved");
    }

    @Test
    void coverageReportWhenATestFileIsMissing(@TempDir Path dir) throws Throwable {
        try (Stream<Path> files = Files.list(ExamplesStoneTest.EXAMPLES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(StoneTestJson.FILE_SUFFIX)).toList()) {
                if (!file.getFileName().toString().equals("loan_eligibility.v2.stone-test.json")) {
                    Files.copy(file, dir.resolve(file.getFileName()));
                }
            }
        }
        // The page quotes the report for the four original examples; later examples must not change it.
        Set<String> documented = Set.of("loan_eligibility", "deposit_rate", "transfer_fees");
        List<DecisionTable> tables = ExamplesStoneTest.exampleTables().stream()
                .filter(t -> documented.contains(t.id()) && !t.ref().equals("loan_eligibility@4"))
                .toList();
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, dir, RuleCoverage.of(tables)).toList();
        DynamicTest coverage = tests.get(tests.size() - 1);

        assertThat(coverage.getDisplayName()).isEqualTo("rule coverage");
        assertThatThrownBy(() -> coverage.getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(blocks("")).contains(e.getMessage() + "\n"));
    }

    @Test
    void junitFactoryWithCoverage(@TempDir Path rules) throws Throwable {
        // As in the page, with copies of the example files in place of "rules"
        for (String name : List.of("loan_eligibility.v2.stone.json", "loan_eligibility.v3.stone.json",
                "loan_eligibility.v2.stone-test.json", "loan_eligibility.v3.stone-test.json")) {
            Files.copy(ExamplesStoneTest.EXAMPLES.resolve(name), rules.resolve(name));
        }

        List<DecisionTable> tables = List.of(
                DecisionTableJson.read(rules.resolve("loan_eligibility.v2.stone.json")),
                DecisionTableJson.read(rules.resolve("loan_eligibility.v3.stone.json")));
        RuleStoneEngine engine = RuleStone.builder().decisions(tables).build();
        List<DynamicTest> tests = StoneTests.dynamicTests(engine, rules, RuleCoverage.of(tables)).toList();

        assertThat(tests).last().extracting(DynamicTest::getDisplayName).isEqualTo("rule coverage");
        for (DynamicTest test : tests) {
            test.getExecutable().execute();
        }
    }

    @Test
    void coverageOutsideJunit() {
        DecisionTable loanEligibilityV3 =
                DecisionTableJson.read(ExamplesStoneTest.EXAMPLES.resolve("loan_eligibility.v3.stone.json"));
        DecisionResult result = DecisionCheck.of(engine)
                .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
                .given("applicant", applicant(30, "25750000.50", "5000000"))
                .given("amount", new BigDecimal("300000000"))
                .expectMatchedRules("R4")
                .result();

        RuleCoverage coverage = RuleCoverage.of(loanEligibilityV3);
        coverage.record(result);

        assertThat(coverage.uncoveredRules()).hasToString("{loan_eligibility@3=[R1, R2, R3, R5]}");
        assertThat(coverage.report()).startsWith("rule coverage: 1 of 5 rules matched by a passing test");
        assertThatThrownBy(coverage::assertComplete).isInstanceOf(AssertionError.class)
                .hasMessage(coverage.report());
    }

    @Test
    void fluentExample() {
        Applicant applicant = applicant(30, "25750000.50", "5000000");

        DecisionCheck.of(engine)
                .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
                .given("applicant", applicant).given("amount", new BigDecimal("300000000"))
                .expectOutput("decision", "APPROVE")
                .expectOutput("limit", new BigDecimal("309000000"))   // compared by value
                .expectReasonCodes()                                   // exactly none
                .expectMatchedRules("R4");
    }

    @Test
    void fluentFailureMessage() {
        DecisionCheck check = DecisionCheck.of(engine)
                .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
                .given("applicant", applicant(30, "25750000.50", "5000000"))
                .given("amount", new BigDecimal("300000000"));

        assertThatThrownBy(() -> check.expectOutput("limit", new BigDecimal("300000000")))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(blocks("")).contains(e.getMessage() + "\n"));
    }

    @Test
    void fluentExpectedFailure() {
        Applicant zeroIncomeApplicant = applicant(30, "0", "1000");

        DecisionCheck.of(engine)
                .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
                .given("applicant", zeroIncomeApplicant).given("amount", new BigDecimal("1000"))
                .expectFailure(DecisionEvaluationException.class)
                .expectFailureMessage("division by zero");
    }
}
