//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution.Direction;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Explanation;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class ExplanationsTest {

    private static final String CATALOG = """
            {
              "version": "2",
              "locales": ["en", "vi"],
              "codes": {
                "DTI_HIGH": {
                  "en": "Your monthly debt payments are high compared with your income.",
                  "vi": "Tổng số tiền trả nợ hằng tháng của quý khách cao so với thu nhập."
                },
                "UNDERAGE": { "vi": "Quý khách chưa đủ tuổi tối thiểu.", "en": "You are below the minimum age." }
              }
            }
            """;

    private static final String TABLE = """
            { "id": "loan", "version": "1", "hitPolicy": "FIRST",
              "inputs": { "age": "int", "dti": "decimal" },
              "outputs": { "decision": "string", "limit": "money" },
              "rules": [
                { "id": "R1", "when": "age < 18", "then": { "decision": "'REJECT'", "limit": "money(0, 'VND')" },
                  "reasonCode": "UNDERAGE" },
                { "id": "R2", "when": "dti > decimal('0.5')", "then": { "decision": "'REJECT'", "limit": "money(0, 'VND')" },
                  "reasonCode": "DTI_HIGH" },
                { "id": "R3", "when": "true", "then": { "decision": "'APPROVE'", "limit": "money(50000000, 'VND')" } } ] }
            """;

    private static ReasonCodeCatalog catalog() {
        return ReasonCodeCatalogJson.read(CATALOG);
    }

    private static RuleStoneEngine engine() {
        return RuleStone.builder().decision(DecisionTableJson.read(TABLE)).reasonCodeCatalog(catalog())
                .requireReasonCodeCatalog().recordIds(() -> "rec-1").build();
    }

    private static DecisionResult rejected() {
        return engine().evaluate(DecisionRef.of("loan").asOf(LocalDate.of(2026, 12, 1)).correlationId("APP-7"),
                Facts.of("age", 30L, "dti", new BigDecimal("0.62")));
    }

    @Test
    void readsAndWritesTheCanonicalForm() {
        ReasonCodeCatalog catalog = catalog();
        assertThat(catalog.locales()).containsExactly("en", "vi");
        assertThat(catalog.codes().keySet()).containsExactly("DTI_HIGH", "UNDERAGE");
        assertThat(catalog.message("UNDERAGE", "vi")).contains("Quý khách chưa đủ tuổi tối thiểu.");
        assertThat(catalog.message("UNKNOWN", "vi")).isEmpty();
        String canonical = ReasonCodeCatalogJson.write(catalog);
        assertThat(canonical).isEqualTo("{\"codes\":{\"DTI_HIGH\":{\"en\":\"Your monthly debt payments are high "
                + "compared with your income.\",\"vi\":\"Tổng số tiền trả nợ hằng tháng của quý khách cao so với thu "
                + "nhập.\"},\"UNDERAGE\":{\"en\":\"You are below the minimum age.\",\"vi\":\"Quý khách chưa đủ tuổi tối "
                + "thiểu.\"}},\"locales\":[\"en\",\"vi\"],\"version\":\"2\"}");
        assertThat(ReasonCodeCatalogJson.read(canonical)).isEqualTo(catalog);
        assertThat(ReasonCodeCatalogJson.exactVersion(catalog))
                .isEqualTo("2@sha256:" + CompiledDecision.sha256(canonical));
    }

    @Test
    void reportsEveryProblemTogether() {
        assertThatThrownBy(() -> ReasonCodeCatalogJson.read("""
                { "version": "1 2", "locales": ["en", "EN", "en"], "extra": 1,
                  "codes": {
                    "dti_high": { "en": "x" },
                    "A": { "en": " padded", "fr": "y" },
                    "B": { "en": "{amount} is too high" },
                    "C": { "en": "two\\nlines" },
                    "D": "text",
                    "E": { "en": 3 },
                    "F": { "en": "   " } } }
                """))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.extra: unknown field (allowed: codes, description, locales, version)",
                        "$.version: must be letters, digits, '.', '_' or '-'",
                        "$.locales[1]: must be a language tag such as en, vi or en-GB",
                        "$.locales[2]: duplicate locale 'en'",
                        "$.codes.dti_high: must be UPPER_SNAKE_CASE, e.g. DTI_HIGH",
                        "$.codes.A.en: must not start or end with spaces",
                        "$.codes.A.fr: locale not declared in $.locales",
                        "$.codes.B.en: must not contain '{' or '}', which are reserved for placeholders",
                        "$.codes.C.en: must be one line of text, without control characters",
                        "$.codes.D: expected an object of locale to message",
                        "$.codes.E.en: expected a string",
                        "$.codes.F.en: must not be blank"));
        assertThatThrownBy(() -> ReasonCodeCatalogJson.read("{ \"version\": \"1\", \"codes\": { \"A\": { \"en\": \"a\" } } }"))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.locales: required non-empty array of language tags, e.g. [\"en\", \"vi\"]",
                        "$.codes.A.en: locale not declared in $.locales"));
        assertThatThrownBy(() -> ReasonCodeCatalogJson.read(
                "{ \"version\": \"1\", \"locales\": [\"en\", \"vi\"], \"codes\": { \"A\": { \"en\": \"a\" } } }"))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.codes.A.vi: required: the message in vi"));
        assertThatThrownBy(() -> ReasonCodeCatalogJson.read("{ \"version\": \"1\", \"version\": \"2\" }"))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("Duplicate field 'version'");
    }

    @Test
    void theEngineChecksEveryReasonCodeOfTablesAndFlows() {
        ReasonCodeCatalog empty = ReasonCodeCatalogJson.read("{ \"version\": \"1\", \"locales\": [\"en\"], \"codes\": {} }");
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE)).reasonCodeCatalog(empty).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "loan@1 rules[R1].reasonCode: reason code 'UNDERAGE' is not in the reason-code catalog",
                        "loan@1 rules[R2].reasonCode: reason code 'DTI_HIGH' is not in the reason-code catalog"));
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE)).requireReasonCodeCatalog().build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "reason-code catalog: required, but none is registered"));
        // Optional by default.
        assertThat(RuleStone.builder().decision(DecisionTableJson.read(TABLE)).build()).isNotNull();
        assertThatThrownBy(() -> RuleStone.builder().reasonCodeCatalog(empty).reasonCodeCatalog(empty))
                .isInstanceOf(IllegalArgumentException.class);

        DecisionFlow flow = DecisionFlowJson.read("""
                { "id": "scored", "version": "1", "inputs": { "x": "int" }, "outputs": { "decision": "string" },
                  "start": "pd",
                  "nodes": {
                    "pd": { "type": "model", "mode": "supplied", "model": { "id": "pd_model", "version": "1.0.0" },
                            "outputs": { "pd": "decimal" },
                            "reasonCodes": { "utilisation": "HIGH_UTILISATION" },
                            "onFailure": { "to": "refer", "reasonCode": "MODEL_UNAVAILABLE" }, "next": "ok" },
                    "ok": { "type": "end", "set": { "decision": "'APPROVE'" } },
                    "refer": { "type": "end", "set": { "decision": "'REFER'" } } } }
                """);
        assertThatThrownBy(() -> RuleStone.builder().flow(flow).reasonCodeCatalog(empty).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "scored@1 nodes.pd.reasonCodes.utilisation: reason code 'HIGH_UTILISATION' is not in the "
                                + "reason-code catalog",
                        "scored@1 nodes.pd.onFailure.reasonCode: reason code 'MODEL_UNAVAILABLE' is not in the "
                                + "reason-code catalog"));
    }

    @Test
    void explainsATableDecision() {
        DecisionResult result = rejected();
        String catalog = ReasonCodeCatalogJson.exactVersion(catalog());
        Explanation en = engine().explain(result.record(), "en");
        assertThat(en.reasonCodes()).containsExactly("DTI_HIGH");
        assertThat(en.catalog()).isEqualTo(catalog);
        assertThat(en).isEqualTo(Explanations.explain(result.record(), catalog(), "en"));
        assertThat(Explanations.write(en)).isEqualTo("{\"recordId\":\"rec-1\",\"correlationId\":\"APP-7\","
                + "\"decision\":\"loan\",\"version\":\"" + result.ruleVersion() + "\",\"businessDate\":\"2026-12-01\","
                + "\"status\":\"OK\",\"locale\":\"en\",\"catalog\":\"" + catalog + "\","
                + "\"outcome\":{\"decision\":\"REJECT\",\"limit\":\"0 VND\"},"
                + "\"reasons\":[{\"code\":\"DTI_HIGH\",\"source\":\"loan\","
                + "\"message\":\"Your monthly debt payments are high compared with your income.\"}],"
                + "\"steps\":[{\"node\":\"loan\",\"type\":\"table\",\"version\":\"" + result.ruleVersion() + "\","
                + "\"matchedRules\":[\"R2\"]}]}");
        assertThat(Explanations.text(en)).isEqualTo("""
                Decision: loan %1$s
                Business date: 2026-12-01
                Outcome: decision = "REJECT", limit = "0 VND"
                Reasons:
                  1. Your monthly debt payments are high compared with your income. (DTI_HIGH, loan)
                Decided by:
                  - loan: table %1$s; matched R2
                Reason-code catalog: %2$s
                """.formatted(result.ruleVersion(), catalog));
        assertThat(Explanations.text(engine().explain(result.record(), "vi"))).isEqualTo("""
                Quyết định: loan %1$s
                Ngày nghiệp vụ: 2026-12-01
                Kết quả: decision = "REJECT", limit = "0 VND"
                Lý do:
                  1. Tổng số tiền trả nợ hằng tháng của quý khách cao so với thu nhập. (DTI_HIGH, loan)
                Căn cứ quyết định:
                  - loan: table %1$s; khớp R2
                Danh mục mã lý do: %2$s
                """.formatted(result.ruleVersion(), catalog));
    }

    @Test
    void explanationsHoldNoInputValues() {
        DecisionResult result = rejected();
        for (String locale : List.of("en", "vi")) {
            Explanation explanation = engine().explain(result.record(), locale);
            assertThat(Explanations.write(explanation)).doesNotContain("0.62", "30");
            assertThat(Explanations.text(explanation)).doesNotContain("0.62", "30");
        }
    }

    @Test
    void isDeterministic() {
        DecisionRecord record = rejected().record();
        assertThat(Explanations.write(engine().explain(record, "vi")))
                .isEqualTo(Explanations.write(engine().explain(record, "vi")));
        assertThat(Explanations.text(Explanations.explain(record, catalog(), "vi")))
                .isEqualTo(Explanations.text(engine().explain(record, "vi")));
    }

    @Test
    void rejectsLocalesTheCatalogDoesNotHave() {
        DecisionRecord record = rejected().record();
        assertThatThrownBy(() -> engine().explain(record, "fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locale 'fr' is not in the reason-code catalog, which has en, vi");
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE)).build().explain(record, "en"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explainsFlowsWithModelsAndFailures() {
        ModelStep model = new ModelStep("retail_pd_behaviour", "2.4.0", ModelMode.INVOKE, ModelStatus.OK,
                Optional.empty(), OptionalLong.of(23), Map.of("pd", new BigDecimal("0.0310")),
                Optional.of(new BigDecimal("0.97")), Optional.of("SHAP"),
                List.of(new RankedAttribution("utilisation", 1, Direction.INCREASES_RISK, Optional.of("DTI_HIGH")),
                        new RankedAttribution("monthsOnBook", 2, Direction.DECREASES_RISK, Optional.empty()),
                        new RankedAttribution("region", 3, Direction.NONE, Optional.empty())));
        DecisionRecord record = new DecisionRecord("r", Optional.empty(), "card", Optional.of("1@sha256:aa"),
                LocalDate.of(2026, 11, 1), Instant.parse("2026-11-01T02:00:00Z"), "rulestone dev",
                DecisionRecord.Status.OK, Optional.of("sha256:ff"), Map.of("decision", "APPROVE"),
                List.of(new ReasonCode("DTI_HIGH", "pd"), new ReasonCode("NOT_IN_CATALOG", "grade")),
                List.of(PathStep.branch("segment", "switch", "pd"), PathStep.model("pd", model),
                        PathStep.table("grade", "card_grade", "2@sha256:bb", List.of()),
                        PathStep.visit("approved", "end")),
                Optional.empty(), Optional.empty(),
                List.of(new DecisionRecord.ParamSetVersion("retail_credit", "1@sha256:cc")));
        Explanation explanation = Explanations.explain(record, catalog(), "en");
        String json = Explanations.write(explanation);
        assertThat(json).contains("{\"node\":\"pd\",\"type\":\"model\",\"model\":{\"id\":\"retail_pd_behaviour\","
                        + "\"version\":\"2.4.0\",\"status\":\"OK\",\"attributions\":[{\"feature\":\"utilisation\",\"rank\":1,"
                        + "\"direction\":\"INCREASES_RISK\",\"reasonCode\":\"DTI_HIGH\"},")
                .contains("{\"code\":\"NOT_IN_CATALOG\",\"source\":\"grade\"}")
                .contains("\"params\":[{\"set\":\"retail_credit\",\"version\":\"1@sha256:cc\"}]")
                .doesNotContain("0.0310", "0.97", "SHAP", "latency", "sha256:ff");
        assertThat(Explanations.text(explanation)).isEqualTo("""
                Decision: card 1@sha256:aa
                Business date: 2026-11-01
                Outcome: decision = "APPROVE"
                Reasons:
                  1. Your monthly debt payments are high compared with your income. (DTI_HIGH, pd)
                  2. (no message in the catalog) (NOT_IN_CATALOG, grade)
                Decided by:
                  - segment: switch; took pd
                  - pd: model retail_pd_behaviour 2.4.0; OK
                      1. utilisation: increases risk (DTI_HIGH)
                      2. monthsOnBook: decreases risk
                      3. region: no effect
                  - grade: table card_grade 2@sha256:bb; no rule matched
                  - approved: end
                Parameter sets: retail_credit 1@sha256:cc
                Reason-code catalog: %s
                """.formatted(explanation.catalog()));

        DecisionRecord failed = new DecisionRecord("r", Optional.empty(), "card", Optional.empty(),
                LocalDate.of(2026, 11, 1), Instant.parse("2026-11-01T02:00:00Z"), "rulestone dev",
                DecisionRecord.Status.ERROR, Optional.empty(), Map.of(), List.of(), List.of(),
                Optional.of("no version"));
        assertThat(Explanations.text(Explanations.explain(failed, catalog(), "vi"))).isEqualTo("""
                Quyết định: card
                Ngày nghiệp vụ: 2026-11-01
                Kết quả: chưa có quyết định (ERROR)
                Lý do: không có
                Căn cứ quyết định:
                Danh mục mã lý do: %s
                """.formatted(ReasonCodeCatalogJson.exactVersion(catalog())));
    }

    @Test
    void otherLocalesGetEnglishHeadings() {
        ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read("""
                { "version": "1", "locales": ["vi-VN", "ko"],
                  "codes": { "DTI_HIGH": { "vi-VN": "Nợ cao.", "ko": "부채가 많습니다." } } }
                """);
        DecisionRecord record = rejected().record();
        assertThat(Explanations.text(Explanations.explain(record, catalog, "vi-VN"))).startsWith("Quyết định: loan ");
        assertThat(Explanations.text(Explanations.explain(record, catalog, "ko")))
                .startsWith("Decision: loan ").contains("  1. 부채가 많습니다. (DTI_HIGH, loan)");
    }
}
