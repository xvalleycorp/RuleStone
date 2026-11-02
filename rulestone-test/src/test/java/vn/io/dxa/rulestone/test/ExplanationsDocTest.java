//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Explanation;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.Explanations;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Checks the examples in docs/explanations.md and its Vietnamese translation against the engine. */
class ExplanationsDocTest {

    private static final Path DOC = Path.of("..", "docs", "explanations.md");
    private static final Path VI_DOC = Path.of("..", "docs", "vi", "explanations.md");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static List<String> blocks(Path doc, String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(Files.readString(doc));
        while (m.find()) {
            if (m.group(1).equals(language)) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    /** The case of the page: a credit limit increase rejected for its debt-to-income ratio. */
    private static DecisionResult rejected(RuleStoneEngine engine) {
        StoneTestCase testCase = StoneTestJson.read(ExamplesStoneTest.EXAMPLES.resolve("credit_limit_increase.stone-test.json"),
                        ExamplesStoneTest.exampleTables()).stream()
                .filter(c -> c.name().equals("monthly payments above half of the income reject"))
                .findFirst().orElseThrow();
        return engine.evaluate(DecisionRef.of("credit_limit_increase").asOf(LocalDate.of(2026, 12, 1))
                .correlationId("CLI-2026-000318"), testCase.facts());
    }

    private static RuleStoneEngine engine() {
        ReasonCodeCatalog catalog = ExamplesStoneTest.exampleCatalog();
        return RuleStone.builder()
                .decisions(ExamplesStoneTest.exampleTables())
                .paramSets(ExamplesStoneTest.exampleParamSets())
                .reasonCodeCatalog(catalog)
                .requireReasonCodeCatalog()
                .recordIds(() -> "6f1c0f3e-5b1d-4f0e-9a52-1d3a3f9c2b7e")
                .build();
    }

    @Test
    void theCatalogExcerptIsInTheExamplesCatalog() throws IOException {
        ReasonCodeCatalog excerpt = ReasonCodeCatalogJson.read(blocks(DOC, "json").get(0));
        ReasonCodeCatalog examples = ExamplesStoneTest.exampleCatalog();
        assertThat(excerpt.locales()).isEqualTo(examples.locales());
        excerpt.codes().forEach((code, messages) -> assertThat(examples.codes().get(code)).isEqualTo(messages));
        assertThat(blocks(VI_DOC, "json").get(0)).isEqualTo(blocks(DOC, "json").get(0));
    }

    @Test
    void theTextAndJsonInThePageAreWhatTheEngineWrites() throws IOException {
        RuleStoneEngine engine = engine();
        DecisionResult result = rejected(engine);
        Explanation vi = engine.explain(result.record(), "vi");
        Explanation en = engine.explain(result.record(), "en");
        assertThat(vi.reasonCodes()).containsExactly("DTI_HIGH");
        List<String> text = blocks(DOC, "text");
        assertThat(text.get(1)).isEqualTo(Explanations.text(vi));
        assertThat(text.get(2)).isEqualTo(Explanations.text(en));
        assertThat(new ObjectMapper().readTree(blocks(DOC, "json").get(1))).isEqualTo(Explanations.toJson(en));
    }

    @Test
    void theVietnamesePageHasTheSameCodeBlocks() throws IOException {
        for (String language : List.of("text", "json", "java", "js")) {
            assertThat(blocks(VI_DOC, language)).isEqualTo(blocks(DOC, language));
        }
    }

    @Test
    void theLoadTimeProblemsInThePageAreWhatTheEngineReports() throws IOException {
        ReasonCodeCatalog withoutDti = ReasonCodeCatalogJson.read(
                "{ \"version\": \"1\", \"locales\": [\"en\"], \"codes\": { \"MISSED_PAYMENTS\": { \"en\": \"m\" },"
                        + " \"UNSECURED_DEBT_HIGH\": { \"en\": \"u\" }, \"LIMIT_REDUCED\": { \"en\": \"l\" } } }");
        String problems = blocks(DOC, "text").get(0);
        assertThatThrownBy(() -> RuleStone.builder()
                        .decision(DecisionTableJson.read(ExamplesStoneTest.EXAMPLES.resolve("credit_limit_increase.stone.json")))
                        .reasonCodeCatalog(withoutDti)
                        .build())
                .isInstanceOfSatisfying(InvalidDecisionException.class,
                        e -> assertThat(e.getMessage() + "\n").isEqualTo(problems));
    }
}
