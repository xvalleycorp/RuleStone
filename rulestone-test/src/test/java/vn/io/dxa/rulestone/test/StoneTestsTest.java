//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class StoneTestsTest {

    private static final String RATE_CASES = """
            { "decision": "deposit_rate", "cases": [
              { "name": "short", "facts": { "termMonths": 3, "balance": 1 }, "expect": { "matchedRules": ["SHORT"] } },
              { "name": "mid", "facts": { "termMonths": 9, "balance": 1 }, "expect": { "matchedRules": ["MID"] } }
            ] }""";

    private final RuleStoneEngine engine = ExamplesStoneTest.exampleEngine();

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static List<String> names(List<org.junit.jupiter.api.DynamicTest> tests) {
        return tests.stream().map(org.junit.jupiter.api.DynamicTest::getDisplayName).toList();
    }

    private static void run(Executable executable) throws Throwable {
        executable.execute();
    }

    @Test
    void oneTestPerCaseNamedByFileAndCase() throws Throwable {
        write("rates.stone-test.json", RATE_CASES);
        write("nested/more.stone-test.json", RATE_CASES.replace("\"short\"", "\"short 2\""));
        write("ignored.json", "not a test file");

        var tests = StoneTests.dynamicTests(engine, dir).toList();

        assertThat(names(tests)).containsExactly(
                "nested/more.stone-test.json: short 2", "nested/more.stone-test.json: mid",
                "rates.stone-test.json: short", "rates.stone-test.json: mid");
        assertThat(tests.get(2).getTestSourceUri()).contains(dir.resolve("rates.stone-test.json").toUri());
        for (var test : tests) {
            run(test.getExecutable());
        }
    }

    @Test
    void acceptsASingleFile() throws IOException {
        Path file = write("anything.json", RATE_CASES);

        assertThat(names(StoneTests.dynamicTests(engine, file).toList()))
                .containsExactly("anything.json: short", "anything.json: mid");
    }

    @Test
    void aFailingCaseFailsItsOwnTestOnly() throws IOException {
        write("rates.stone-test.json", RATE_CASES.replace("[\"MID\"]", "[\"LONG\"]"));
        var tests = StoneTests.dynamicTests(engine, dir).toList();

        assertThatCode(() -> run(tests.get(0).getExecutable())).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(tests.get(1).getExecutable()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("rates.stone-test.json, case \"mid\", evaluated with deposit_rate@1:\n"
                        + "  - matched rules: expected [LONG] but was [MID]");
    }

    @Test
    void anInvalidFileBecomesOneFailingTest() throws IOException {
        write("a.stone-test.json", RATE_CASES);
        write("b.stone-test.json", "{ \"decision\": \"deposit_rate\", \"cases\": [ { \"name\": \"x\" } ] }");
        write("c.stone-test.json", RATE_CASES);
        var tests = StoneTests.dynamicTests(engine, dir).toList();

        assertThat(names(tests)).containsExactly("a.stone-test.json: short", "a.stone-test.json: mid",
                "b.stone-test.json", "c.stone-test.json: short", "c.stone-test.json: mid");
        assertThatThrownBy(() -> run(tests.get(2).getExecutable()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("invalid test file b.stone-test.json (2 problems):\n"
                        + "  - $.cases[0].facts: required object of fact names to values\n"
                        + "  - $.cases[0].expect: required object (use {} to check only that the evaluation succeeds)")
                .hasCauseInstanceOf(InvalidStoneTestException.class);
    }

    @Test
    void aWrongPathFailsTheFactory() {
        assertThatThrownBy(() -> StoneTests.dynamicTests(engine, dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("no *.stone-test.json files in ");
        assertThatThrownBy(() -> StoneTests.dynamicTests(engine, dir.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("no such file or directory: ");
    }

    @Test
    void coverageTestPassesWhenEveryRuleNodeAndBranchIsCovered() throws Throwable {
        var tests = StoneTests.dynamicTests(engine, ExamplesStoneTest.EXAMPLES,
                RuleCoverage.of(ExamplesStoneTest.allExampleTables(), ExamplesStoneTest.exampleFlows())).toList();

        assertThat(tests.get(tests.size() - 1).getDisplayName()).isEqualTo("rule and node coverage");
        run(tests.get(tests.size() - 1).getExecutable());
    }

    @Test
    void failingAndErrorCasesCoverNothing() throws IOException {
        write("rates.stone-test.json", """
                { "decision": "deposit_rate", "cases": [
                  { "name": "short", "facts": { "termMonths": 3, "balance": 1 }, "expect": {} },
                  { "name": "mid, wrongly expected", "facts": { "termMonths": 9, "balance": 1 },
                    "expect": { "output": { "rate": 1 } } },
                  { "name": "long, but error expected", "facts": { "termMonths": 12, "balance": 1 },
                    "expect": { "error": "x" } }
                ] }""");
        RuleCoverage coverage = RuleCoverage.of(DecisionTableJson.read(ExamplesStoneTest.EXAMPLES.resolve("deposit_rate.stone.json")));
        var tests = StoneTests.dynamicTests(engine, dir, coverage).toList();

        // The coverage test does not depend on the case tests having run.
        assertThatThrownBy(() -> run(tests.get(tests.size() - 1).getExecutable()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("rule coverage: 1 of 4 rules matched by a passing test\n"
                        + "  deposit_rate@1: 1 of 4 rules, never matched: MID, LONG, VIP_LONG");
        assertThat(coverage.uncoveredRules()).hasToString("{deposit_rate@1=[MID, LONG, VIP_LONG]}");
    }
}
