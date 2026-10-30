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

    // --- Model nodes ----------------------------------------------------------------------------

    private static final Path SCORED = DecisionFlowTest.FLOWS.resolve("card_origination_scored.flow.json");

    private static Facts existingCustomer() {
        return Facts.of("applicant", Map.of("id", "C-2003", "age", 35, "income", Money.of("20000000", "VND"),
                        "existingCustomer", true),
                "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.40")));
    }

    @Test
    void theModelNodesInThePageAreThoseOfTheExampleFile() throws IOException {
        String block = blocks("json").stream().filter(b -> b.contains("\"type\": \"model\", \"mode\": \"supplied\""))
                .findFirst().orElseThrow();
        assertThat(Files.readString(SCORED)).contains(block);
    }

    @Test
    void theModelsObjectInThePageIsReadAndUsed() throws IOException {
        String models = blocks("json").stream().filter(b -> b.contains("\"pd_behaviour\": { \"failure\": \"TIMEOUT\" }"))
                .findFirst().orElseThrow();
        RuleStoneEngine engine = RuleStone.builder().decisions(ModelNodeTest.tables())
                .flow(DecisionFlowJson.read(SCORED)).suppliedModelsOnly().build();

        DecisionResult result = engine.evaluate(DecisionRef.of("card_origination_scored").asOf(LocalDate.of(2026, 12, 1)),
                existingCustomer().withModels(FactsJson.readModels(new ObjectMapper().readTree(models))));

        assertThat(result.output()).containsEntry("decision", "REFER");
        assertThat(result.reasonCodes()).containsExactly("NEW_DEVICE", "ADDRESS_MISMATCH", "MODEL_UNAVAILABLE");
    }

    @Test
    void theModelStepsInThePageAreWhatTheEngineRecords() throws IOException {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        vn.io.dxa.rulestone.api.ModelInvoker behaviour = call -> {
            now.addAndGet(java.time.Duration.ofMillis(23).toNanos());
            return vn.io.dxa.rulestone.api.ModelResult.of(call.modelId(), call.modelVersion(),
                            Map.of("pd", new BigDecimal("0.0150")))
                    .withConfidence(new BigDecimal("0.95"))
                    .withAttributions("SHAP", List.of(
                            new vn.io.dxa.rulestone.api.ModelResult.Attribution("dpd30Count12m", new BigDecimal("-0.0010")),
                            new vn.io.dxa.rulestone.api.ModelResult.Attribution("utilisation", new BigDecimal("0.0040"))));
        };
        RuleStoneEngine engine = RuleStone.builder().decisions(ModelNodeTest.tables())
                .flow(DecisionFlowJson.read(SCORED)).nanoTime(now::get)
                .modelInvoker("retail_pd_behaviour", behaviour).modelInvoker("retail_pd_application", call -> null)
                .auditSink(record -> audit.add(DecisionRecordJson.write(record))).build();
        String models = blocks("json").stream().filter(b -> b.contains("\"pd_behaviour\": { \"failure\": \"TIMEOUT\" }"))
                .findFirst().orElseThrow();
        ObjectNode supplied = (ObjectNode) new ObjectMapper().readTree(models);
        supplied.remove("pd_behaviour");

        engine.evaluate(DecisionRef.of("card_origination_scored").asOf(LocalDate.of(2026, 12, 1)),
                existingCustomer().withModels(FactsJson.readModels(supplied)));

        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode actual = mapper.createArrayNode();
        mapper.readTree(audit.get(0)).get("path").forEach(step -> {
            if (step.get("type").asText().equals("model")) {
                actual.add(step);
            }
        });
        String documented = blocks("json").stream().filter(b -> b.startsWith("[") && b.contains("\"latencyMs\""))
                .findFirst().orElseThrow();
        assertThat(actual).isEqualTo(mapper.readTree(documented));
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
