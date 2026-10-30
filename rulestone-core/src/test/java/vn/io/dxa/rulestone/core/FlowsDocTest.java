//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** Checks the examples in docs/flows.md against the engine. Update both together. */
class FlowsDocTest {

    private static final Path DOC = Path.of("..", "docs", "flows.md");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static List<String> blocks(String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(Files.readString(DOC));
        while (m.find()) {
            if (m.group(1).equals(language)) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    private final List<String> audit = new ArrayList<>();

    /** As in the page, with the example folder in place of "rules". */
    private RuleStoneEngine engine() {
        Path rules = DecisionFlowTest.FLOWS;
        List<DecisionTable> tables = Stream.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit")
                .map(id -> DecisionTableJson.read(rules.resolve(id + ".stone.json")))
                .toList();
        return RuleStone.builder()
                .clock(Clock.fixed(Instant.parse("2026-12-01T02:15:04.218Z"), ZoneId.of("Asia/Ho_Chi_Minh")))
                .decisions(tables)
                .flow(DecisionFlowJson.read(rules.resolve("card_origination.flow.json")))
                .auditSink(record -> audit.add(DecisionRecordJson.write(record)))
                .build();
    }

    private DecisionResult evaluate(RuleStoneEngine engine) {
        return engine.evaluate(
                DecisionRef.of("card_origination").asOf(LocalDate.of(2026, 12, 1)).correlationId("CARD-2026-000777"),
                Facts.of("applicant", Map.of("id", "C-1001", "age", 35, "income", Money.of("20000000", "VND"),
                                "existingCustomer", true),
                        "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.10"))));
    }

    @Test
    void theFlowInThePageIsTheExampleFile() throws IOException {
        assertThat(blocks("json").get(0))
                .isEqualTo(Files.readString(DecisionFlowTest.FLOWS.resolve("card_origination.flow.json")));
    }

    @Test
    void runningAFlow() {
        DecisionResult result = evaluate(engine());

        assertThat(result.output()).hasToString("{decision=APPROVE, limit=144000000 VND, riskGrade=A}");
        assertThat(result.matchedRules()).containsExactly("PASS", "A", "A_CHALLENGER");
    }

    @Test
    void theRecordInThePageIsWhatTheEngineWrites() throws IOException {
        evaluate(engine());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode documented = (ObjectNode) mapper.readTree(blocks("json").stream()
                .filter(b -> b.contains("\"recordId\"")).findFirst().orElseThrow());
        ObjectNode actual = (ObjectNode) mapper.readTree(audit.get(0));
        for (String field : List.of("recordId", "engine")) {
            documented.remove(field);
            actual.remove(field);
        }
        assertThat(actual).isEqualTo(documented);
    }

    @Test
    void theValidationMessageInThePageIsWhatTheEngineReports() throws IOException {
        String flow = Files.readString(DecisionFlowTest.FLOWS.resolve("card_origination.flow.json"))
                .replace("\"limit\": \"money(0, applicant.income.currency())\" } },\n    \"rejected\"",
                        "\"limit\": \"limit.limit\" } },\n    \"rejected\"");
        assertThat(flow).contains("\"limit\": \"limit.limit\" } },\n    \"rejected\"");

        assertThatThrownBy(() -> RuleStone.builder().decisions(DecisionFlowTest.tables())
                .flow(DecisionFlowJson.read(flow)).build())
                .isInstanceOf(InvalidDecisionException.class)
                .satisfies(e -> assertThat(blocks("")).contains(e.getMessage() + "\n"));
    }
}
