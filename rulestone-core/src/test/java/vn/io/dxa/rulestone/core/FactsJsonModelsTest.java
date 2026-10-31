//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelResult;
import vn.io.dxa.rulestone.api.ModelResult.Attribution;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.SuppliedModel;
import vn.io.dxa.rulestone.cel.ValueType;

/** The JSON form of supplied model results: {@link FactsJson#readModels}. */
class FactsJsonModelsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void readsResultsAndFailuresExactly() {
        Map<String, SuppliedModel> models = FactsJson.readModels(json("""
                { "pd": { "modelId": "retail_pd_application", "modelVersion": "3.1.0",
                          "outputs": { "pd": "0.0310", "band": 3, "flags": [true, false], "rate": 0.0450 },
                          "confidence": "0.92", "attributionMethod": "SHAP",
                          "attributions": [ { "feature": "utilisation", "contribution": "0.0120" },
                                            { "feature": "monthsOnBook", "contribution": -0.0045 } ] },
                  "fraud": { "failure": "TIMEOUT" } }
                """));

        assertThat(models).containsOnlyKeys("pd", "fraud");
        ModelResult pd = models.get("pd").result().orElseThrow();
        assertThat(pd.modelId()).isEqualTo("retail_pd_application");
        assertThat(pd.modelVersion()).isEqualTo("3.1.0");
        assertThat(pd.outputs()).containsExactly(Map.entry("pd", "0.0310"), Map.entry("band", 3L),
                Map.entry("flags", List.of(true, false)), Map.entry("rate", new BigDecimal("0.0450")));
        assertThat(pd.confidence()).contains(new BigDecimal("0.92"));
        assertThat(pd.attributionMethod()).contains("SHAP");
        assertThat(pd.attributions()).containsExactly(new Attribution("utilisation", new BigDecimal("0.0120")),
                new Attribution("monthsOnBook", new BigDecimal("-0.0045")));
        assertThat(models.get("fraud")).isEqualTo(SuppliedModel.failed(ModelStatus.TIMEOUT));
    }

    @Test
    void reportsEveryProblemWithoutValues() {
        assertThatThrownBy(() -> FactsJson.readModels(json("""
                { "a": { "modelId": "m", "outputs": { "x": null, "y": { "secret": "4111" } }, "colour": "red",
                         "confidence": "high", "attributions": [ { "feature": "f" }, 5 ] },
                  "b": { "failure": "LOW_CONFIDENCE", "modelId": "m" },
                  "c": [] }
                """)))
                .isInstanceOfSatisfying(InvalidFactsException.class, e -> {
                    assertThat(e.problems()).containsExactly(
                            "a.colour: unknown field (allowed: attributionMethod, attributions, confidence, failure, "
                                    + "modelId, modelVersion, outputs)",
                            "a.modelVersion: required non-empty string",
                            "a.outputs.x: null is not a value",
                            "a.outputs.y: expected a bool, number, string or array: model outputs have no fields",
                            "a.confidence: expected a decimal as a number or a string in plain notation, e.g. \"0.0980\"",
                            "a.attributions[0].contribution: required decimal",
                            "a.attributions[1]: expected an object { \"feature\", \"contribution\" }",
                            "b.modelId: not allowed with \"failure\"",
                            "b.failure: expected \"TIMEOUT\" or \"ERROR\"",
                            "c: expected an object with \"modelId\", \"modelVersion\" and \"outputs\", or with \"failure\"");
                    assertThat(e.getMessage()).doesNotContain("4111");
                });
    }

    @Test
    void readsFactsAndModelsTogether() {
        Facts facts = FactsJson.readFacts(json("{ \"income\": \"30000000 VND\" }"), Map.of("income", ValueType.MONEY),
                json("{ \"pd\": { \"failure\": \"ERROR\" } }"));
        assertThat(facts.asMap()).containsExactly(Map.entry("income", Money.of("30000000", "VND")));
        assertThat(facts.models()).containsExactly(Map.entry("pd", SuppliedModel.failed(ModelStatus.ERROR)));
        assertThat(FactsJson.readFacts(json("{}"), Map.of(), null).models()).isEmpty();

        assertThatThrownBy(() -> FactsJson.readFacts(json("{ \"income\": 5 }"), Map.of("income", ValueType.MONEY),
                json("{ \"pd\": { \"failure\": \"SLOW\" } }")))
                .isInstanceOfSatisfying(InvalidFactsException.class, e -> assertThat(e.problems()).containsExactly(
                        "income: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
                        "models.pd.failure: expected \"TIMEOUT\" or \"ERROR\""));
        assertThatThrownBy(() -> FactsJson.readFacts(json("{}"), Map.of(), json("[]")))
                .isInstanceOfSatisfying(InvalidFactsException.class, e -> assertThat(e.problems()).containsExactly(
                        "models: expected an object of model node ids to results"));
    }

    @Test
    void factsCarryModelResultsApartFromInputsAndNeverShowThem() {
        Facts facts = Facts.of("income", Money.of("1", "VND"))
                .withModelResult("pd", ModelResult.of("m", "1", Map.of("pd", new BigDecimal("0.031"))))
                .withModelFailure("fraud", ModelStatus.ERROR);
        assertThat(facts.asMap()).containsOnlyKeys("income");
        assertThat(facts.models()).containsOnlyKeys("pd", "fraud");
        assertThat(facts.toString()).isEqualTo("Facts[income], models [pd, fraud]");
        assertThat(facts.models().get("pd").result().orElseThrow().toString())
                .isEqualTo("ModelResult[m@1, outputs [pd], 0 attributions]");
        assertThat(facts).isNotEqualTo(Facts.of("income", Money.of("1", "VND")));
        assertThatThrownBy(() -> facts.withModelFailure("pd", ModelStatus.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate model result: pd");
        assertThatThrownBy(() -> facts.withModelFailure("x", ModelStatus.INVALID_OUTPUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a supplied failure is TIMEOUT or ERROR, not INVALID_OUTPUT");
        assertThat(new SuppliedModel(Optional.empty(), Optional.of(ModelStatus.ERROR)).failure()).isPresent();
    }
}
