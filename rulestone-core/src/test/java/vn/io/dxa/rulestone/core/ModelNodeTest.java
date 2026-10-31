//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution.Direction;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelCall;
import vn.io.dxa.rulestone.api.ModelException;
import vn.io.dxa.rulestone.api.ModelInvoker;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.api.ModelResult;
import vn.io.dxa.rulestone.api.ModelResult.Attribution;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** Model nodes: both modes, every kind of failure, reason codes from attributions, records and load-time checks. */
class ModelNodeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
    private static final DecisionRef REF = DecisionRef.of("card_origination_scored").asOf(DATE).correlationId("APP-1");

    static List<DecisionTable> tables() {
        return Stream.of("card_policy", "card_pd_grade", "card_limit")
                .map(name -> DecisionTableJson.read(DecisionFlowTest.FLOWS.resolve(name + ".stone.json")))
                .toList();
    }

    static DecisionFlow flow() {
        return DecisionFlowJson.read(DecisionFlowTest.FLOWS.resolve("card_origination_scored.flow.json"));
    }

    private final List<DecisionRecord> audited = new ArrayList<>();
    private final List<ModelCall> calls = new ArrayList<>();

    /** Returns {@code pd} for every call of either PD model, with a confidence and attributions. */
    private ModelInvoker pd(String pd) {
        return call -> {
            calls.add(call);
            return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", new BigDecimal(pd)))
                    .withConfidence(new BigDecimal("0.95"))
                    .withAttributions("SHAP", List.of(new Attribution("utilisation", new BigDecimal("0.0040")),
                            new Attribution("dpd30Count12m", new BigDecimal("-0.0010")),
                            new Attribution("age", new BigDecimal("0.0005"))));
        };
    }

    private RuleStoneEngine engine(ModelInvoker invoker) {
        return configured(b -> b.modelInvoker("retail_pd_behaviour", invoker).modelInvoker("retail_pd_application", invoker));
    }

    private RuleStoneEngine configured(Consumer<RuleStone.Builder> configure) {
        RuleStone.Builder builder = RuleStone.builder().decisions(tables()).flow(flow()).auditSink(audited::add);
        configure.accept(builder);
        return builder.build();
    }

    private static Facts facts(boolean existing, String utilisation) {
        return Facts.of(
                "applicant", Map.of("id", "C-1", "age", 35, "income", Money.of("20000000", "VND"),
                        "existingCustomer", existing),
                "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal(utilisation)));
    }

    /** The fraud model's result, supplied by the caller. */
    private static ModelResult fraud(String score) {
        return ModelResult.of("card_application_fraud", "1.2.0", Map.of("score", score))
                .withAttributions("SHAP", List.of(new Attribution("addressMismatch", new BigDecimal("0.10")),
                        new Attribution("deviceAgeDays", new BigDecimal("0.30"))));
    }

    private static Facts scored(boolean existing) {
        return facts(existing, "0.40").withModelResult("fraud", fraud("0.05"));
    }

    private static ModelStep model(DecisionResult result, String node) {
        return result.record().path().stream().filter(s -> s.node().equals(node)).findFirst()
                .flatMap(PathStep::model).orElseThrow();
    }

    // --- Both modes -----------------------------------------------------------------------------

    @Test
    void aSuppliedScoreAndAnInvokedModelDecideTogether() {
        DecisionResult result = engine(pd("0.0150")).evaluate(REF, scored(true));

        assertThat(result.output()).containsEntry("decision", "APPROVE").containsEntry("riskGrade", "A")
                .containsEntry("limit", Money.of("120000000", "VND"));
        assertThat(result.record().path()).extracting(PathStep::node).containsExactly(
                "policy", "fraud", "segment", "pd_behaviour", "grade", "limit", "approved");
        assertThat(result.record().reasonCodes()).containsExactly(
                new ReasonCode("NEW_DEVICE", "fraud"), new ReasonCode("ADDRESS_MISMATCH", "fraud"),
                new ReasonCode("HIGH_UTILISATION", "pd_behaviour"));
        assertThat(result.matchedRules()).containsExactly("PASS", "A", "A");

        ModelStep fraud = model(result, "fraud");
        assertThat(fraud).isEqualTo(new ModelStep("card_application_fraud", "1.2.0", ModelMode.SUPPLIED,
                ModelStatus.OK, Optional.empty(), OptionalLong.empty(), Map.of("score", new BigDecimal("0.05")),
                Optional.empty(), Optional.of("SHAP"), List.of(
                        new RankedAttribution("deviceAgeDays", 1, Direction.INCREASES_RISK, Optional.of("NEW_DEVICE")),
                        new RankedAttribution("addressMismatch", 2, Direction.INCREASES_RISK,
                                Optional.of("ADDRESS_MISMATCH")))));

        ModelStep pd = model(result, "pd_behaviour");
        assertThat(pd.mode()).isEqualTo(ModelMode.INVOKE);
        assertThat(pd.status()).isEqualTo(ModelStatus.OK);
        assertThat(pd.latencyMs()).isPresent();
        assertThat(pd.outputs()).containsExactly(Map.entry("pd", new BigDecimal("0.0150")));
        assertThat(pd.confidence()).contains(new BigDecimal("0.95"));
        assertThat(pd.attributions()).containsExactly(
                new RankedAttribution("utilisation", 1, Direction.INCREASES_RISK, Optional.of("HIGH_UTILISATION")),
                new RankedAttribution("dpd30Count12m", 2, Direction.DECREASES_RISK, Optional.empty()),
                new RankedAttribution("age", 3, Direction.INCREASES_RISK, Optional.empty()));
        assertThat(audited).containsExactly(result.record());
    }

    @Test
    void theInvokerReceivesOnlyTheDeclaredFeaturesThePinnedVersionAndTheTimeLeft() {
        engine(pd("0.0150")).evaluate(REF.timeout(Duration.ofSeconds(5)), scored(false));

        assertThat(calls).hasSize(1);
        ModelCall call = calls.get(0);
        assertThat(call.modelId()).isEqualTo("retail_pd_application");
        assertThat(call.modelVersion()).isEqualTo("3.1.0");
        assertThat(call.features()).containsExactly(
                Map.entry("age", 35L), Map.entry("utilisation", new BigDecimal("0.40")));
        assertThat(call.timeout()).isEqualTo(Duration.ofMillis(80));
        assertThat(call.correlationId()).contains("APP-1");
        assertThat(call.toString()).doesNotContain("0.40").contains("[age, utilisation]");
    }

    @Test
    void theTimeoutPassedToTheInvokerIsNoMoreThanTheEvaluationHasLeft() {
        AtomicLong now = new AtomicLong();
        AtomicReference<Duration> given = new AtomicReference<>();
        RuleStoneEngine engine = configured(b -> b.nanoTime(now::get).timeout(Duration.ofMillis(50))
                .modelInvoker("retail_pd_behaviour", call -> {
                    given.set(call.timeout());
                    return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"));
                })
                .modelInvoker("retail_pd_application", call -> null));
        now.addAndGet(Duration.ofMillis(20).toNanos());   // spent before the node, e.g. waiting for a thread
        engine.evaluate(REF, scored(true));
        // 50 ms timeout, counted from evaluate(); the policy and fraud nodes take no time on this clock.
        assertThat(given.get()).isLessThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void reasonCodesComeFromTheTopAttributionsThatIncreaseRiskUpToTheNodesMaximum() {
        // pd_application emits at most one code; its top risk-increasing feature is utilisation.
        DecisionResult result = engine(pd("0.0400")).evaluate(REF, scored(false));

        assertThat(result.reasonCodes()).containsExactly("NEW_DEVICE", "ADDRESS_MISMATCH", "HIGH_UTILISATION");
        assertThat(model(result, "pd_application").attributions()).extracting(RankedAttribution::reasonCode)
                .containsExactly(Optional.of("HIGH_UTILISATION"), Optional.empty(), Optional.empty());
    }

    @Test
    void aModelWithoutAttributionsGivesNoReasonCodesAndTheRecordSaysSo() {
        RuleStoneEngine engine = configured(b -> b.suppliedModelsOnly());
        DecisionResult result = engine.evaluate(REF, facts(true, "0.40")
                .withModelResult("fraud", ModelResult.of("card_application_fraud", "1.2.0", Map.of("score", "0.10")))
                .withModelResult("pd_behaviour", ModelResult.of("retail_pd_behaviour", "2.4.0", Map.of("pd", 0))));

        assertThat(result.reasonCodes()).isEmpty();
        assertThat(model(result, "fraud").attributions()).isEmpty();
        assertThat(DecisionRecordJson.write(result.record()))
                .contains("\"node\":\"fraud\",\"type\":\"model\",\"mode\":\"supplied\","
                        + "\"model\":{\"id\":\"card_application_fraud\",\"version\":\"1.2.0\"},\"status\":\"OK\","
                        + "\"outputs\":{\"score\":\"0.10\"},\"attributions\":[]}");
    }

    @Test
    void suppliedModelsOnlyRunsInvokeNodesOnTheSuppliedResultsAndCallsNoModel() {
        RuleStoneEngine engine = configured(b -> b.suppliedModelsOnly());
        DecisionResult result = engine.evaluate(REF, scored(true).withModelResult("pd_behaviour",
                ModelResult.of("retail_pd_behaviour", "2.4.0", Map.of("pd", "0.07"))));

        assertThat(result.output()).containsEntry("riskGrade", "C");
        ModelStep pd = model(result, "pd_behaviour");
        assertThat(pd.mode()).isEqualTo(ModelMode.SUPPLIED);
        assertThat(pd.latencyMs()).isEmpty();
        // The same facts give the same record, step for step.
        assertThat(engine.evaluate(REF, scored(true).withModelResult("pd_behaviour",
                ModelResult.of("retail_pd_behaviour", "2.4.0", Map.of("pd", "0.07")))).record().path())
                .isEqualTo(result.record().path());
    }

    // --- Failures -------------------------------------------------------------------------------

    private DecisionResult failing(Facts facts, ModelInvoker invoker) {
        return engine(invoker).evaluate(REF, facts);
    }

    private static void referredBecause(DecisionResult result, String node, ModelStatus status, String detail,
                                        String reasonCode) {
        assertThat(result.output()).containsEntry("decision", "REFER");
        assertThat(result.record().path().get(result.record().path().size() - 1).node()).isEqualTo("referred");
        ModelStep step = model(result, node);
        assertThat(step.status()).isEqualTo(status);
        assertThat(step.detail()).contains(detail);
        assertThat(step.outputs()).isEmpty();
        assertThat(step.attributions()).isEmpty();
        assertThat(result.record().reasonCodes()).contains(new ReasonCode(reasonCode, node));
    }

    @Test
    void aMissingSuppliedResultTakesTheFailurePath() {
        DecisionResult result = failing(facts(true, "0.4"), pd("0.01"));
        referredBecause(result, "fraud", ModelStatus.MISSING, "no result was supplied for this node",
                "FRAUD_SCORE_UNAVAILABLE");
        assertThat(result.record().path()).extracting(PathStep::node).containsExactly("policy", "fraud", "referred");
        assertThat(result.reasonCodes()).containsExactly("FRAUD_SCORE_UNAVAILABLE");
        assertThat(calls).isEmpty();
    }

    @Test
    void aFailureReportedByTheCallerTakesTheFailurePath() {
        referredBecause(failing(facts(true, "0.4").withModelFailure("fraud", ModelStatus.TIMEOUT), pd("0.01")),
                "fraud", ModelStatus.TIMEOUT, "reported by the caller", "FRAUD_SCORE_UNAVAILABLE");
    }

    @Test
    void anOutputOutsideTheValidConditionIsInvalid() {
        referredBecause(failing(facts(true, "0.4").withModelResult("fraud", fraud("1.5")), pd("0.01")),
                "fraud", ModelStatus.INVALID_OUTPUT, "the valid condition is false", "FRAUD_SCORE_UNAVAILABLE");
        referredBecause(failing(scored(true), pd("-0.01")),
                "pd_behaviour", ModelStatus.INVALID_OUTPUT, "the valid condition is false", "MODEL_UNAVAILABLE");
    }

    @Test
    void anotherModelVersionBreaksTheContract() {
        referredBecause(failing(facts(true, "0.4").withModelResult("fraud",
                        ModelResult.of("card_application_fraud", "1.3.0", Map.of("score", "0.1"))), pd("0.01")),
                "fraud", ModelStatus.CONTRACT_VIOLATION,
                "answered by card_application_fraud@1.3.0, not the pinned card_application_fraud@1.2.0",
                "FRAUD_SCORE_UNAVAILABLE");
    }

    @Test
    void missingUndeclaredAndMistypedOutputsBreakTheContract() {
        ModelInvoker missing = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of());
        referredBecause(failing(scored(true), missing), "pd_behaviour", ModelStatus.CONTRACT_VIOLATION,
                "output 'pd' is missing", "MODEL_UNAVAILABLE");
        ModelInvoker extra = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01", "lgd", "0.4"));
        referredBecause(failing(scored(true), extra), "pd_behaviour", ModelStatus.CONTRACT_VIOLATION,
                "undeclared output 'lgd'", "MODEL_UNAVAILABLE");
        ModelInvoker text = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "low"));
        referredBecause(failing(scored(true), text), "pd_behaviour", ModelStatus.CONTRACT_VIOLATION,
                "output 'pd' is not decimal", "MODEL_UNAVAILABLE");
        ModelInvoker floating = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", 0.01));
        referredBecause(failing(scored(true), floating), "pd_behaviour", ModelStatus.CONTRACT_VIOLATION,
                "output 'pd' is not decimal", "MODEL_UNAVAILABLE");
    }

    @Test
    void confidenceBelowTheMinimumOrMissingFails() {
        ModelInvoker unsure = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"))
                .withConfidence(new BigDecimal("0.69"));
        referredBecause(failing(scored(false), unsure), "pd_application", ModelStatus.LOW_CONFIDENCE,
                "confidence below minConfidence", "MODEL_UNAVAILABLE");
        ModelInvoker silent = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"));
        referredBecause(failing(scored(false), silent), "pd_application", ModelStatus.CONTRACT_VIOLATION,
                "no confidence reported, and the node sets minConfidence", "MODEL_UNAVAILABLE");
        ModelInvoker sure = call -> ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"))
                .withConfidence(new BigDecimal("0.70"));
        assertThat(failing(scored(false), sure).output()).containsEntry("decision", "APPROVE");
    }

    @Test
    void anInvokerExceptionIsRecordedByTypeOnly() {
        ModelInvoker broken = call -> {
            throw new ModelException("server said: applicant 0.40 utilisation rejected");
        };
        DecisionResult result = failing(scored(true), broken);
        referredBecause(result, "pd_behaviour", ModelStatus.ERROR,
                "the invoker threw vn.io.dxa.rulestone.api.ModelException", "MODEL_UNAVAILABLE");
        assertThat(DecisionRecordJson.write(result.record())).doesNotContain("server said").doesNotContain("0.40");

        ModelInvoker buggy = call -> {
            throw new IllegalStateException("bug with 0.40");
        };
        referredBecause(failing(scored(true), buggy), "pd_behaviour", ModelStatus.ERROR,
                "the invoker threw java.lang.IllegalStateException", "MODEL_UNAVAILABLE");
        referredBecause(failing(scored(true), call -> null), "pd_behaviour", ModelStatus.ERROR,
                "the invoker returned no result", "MODEL_UNAVAILABLE");
    }

    @Test
    void aResultLaterThanTheNodesTimeoutIsATimeout() {
        AtomicLong now = new AtomicLong();
        ModelInvoker slow = call -> {
            now.addAndGet(Duration.ofMillis(81).toNanos());
            return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"));
        };
        DecisionResult result = configured(b -> b.nanoTime(now::get).modelInvoker("retail_pd_behaviour", slow)
                .modelInvoker("retail_pd_application", slow)).evaluate(REF, scored(true));
        referredBecause(result, "pd_behaviour", ModelStatus.TIMEOUT, "no result within 80 ms", "MODEL_UNAVAILABLE");
        assertThat(model(result, "pd_behaviour").latencyMs()).hasValue(81);
    }

    @Test
    void whenTheEvaluationsOwnTimeoutPassesDuringTheCallTheEvaluationTimesOut() {
        AtomicLong now = new AtomicLong();
        ModelInvoker slow = call -> {
            now.addAndGet(Duration.ofMillis(60).toNanos());
            return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", "0.01"));
        };
        RuleStoneEngine engine = configured(b -> b.nanoTime(now::get).timeout(Duration.ofMillis(50))
                .modelInvoker("retail_pd_behaviour", slow).modelInvoker("retail_pd_application", slow));

        assertThatThrownBy(() -> engine.evaluate(REF, scored(true))).isInstanceOf(DecisionTimeoutException.class);
        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.TIMEOUT);
        PathStep last = record.path().get(record.path().size() - 1);
        assertThat(last.node()).isEqualTo("pd_behaviour");
        assertThat(last.model()).hasValueSatisfying(m -> assertThat(m.status()).isEqualTo(ModelStatus.TIMEOUT));
    }

    // --- Supplied results that do not fit ------------------------------------------------------

    @Test
    void aResultForANodeThatTakesNoneFailsTheEvaluation() {
        RuleStoneEngine engine = engine(pd("0.01"));
        DecisionEvaluationException unknown = catchThrowableOfType(DecisionEvaluationException.class,
                () -> engine.evaluate(REF, scored(true).withModelResult("grade",
                        ModelResult.of("x", "1", Map.of()))));
        assertThat(unknown.getMessage()).endsWith(
                "model result supplied for 'grade', which is not a model node of this flow");
        DecisionEvaluationException invoked = catchThrowableOfType(DecisionEvaluationException.class,
                () -> engine.evaluate(REF, scored(true).withModelResult("pd_behaviour",
                        ModelResult.of("retail_pd_behaviour", "2.4.0", Map.of("pd", "0.01")))));
        assertThat(invoked.getMessage()).contains("model result supplied for node 'pd_behaviour', which calls its "
                + "model (mode invoke)");
        assertThat(audited).extracting(DecisionRecord::status).containsExactly(Status.ERROR, Status.ERROR);
    }

    @Test
    void aDecisionTableTakesNoModelResults() {
        RuleStoneEngine engine = engine(pd("0.01"));
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("card_pd_grade"),
                Facts.of("pd", new BigDecimal("0.01")).withModelResult("pd", ModelResult.of("m", "1", Map.of()))))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageEndingWith("model results supplied for [pd], but a decision table has no model nodes");
    }

    // --- File format ----------------------------------------------------------------------------

    @Test
    void theCanonicalFormKeepsEveryFieldAndReadsBackToTheSameFlow() {
        DecisionFlow flow = flow();
        String canonical = DecisionFlowJson.write(flow);
        assertThat(DecisionFlowJson.read(canonical)).isEqualTo(flow);
        assertThat(canonical).contains("\"pd_application\":{\"as\":\"score\",\"features\":{\"age\":\"applicant.age\","
                + "\"utilisation\":\"bureau.utilisation\"},\"maxReasonCodes\":1,\"minConfidence\":\"0.70\","
                + "\"mode\":\"invoke\",\"model\":{\"id\":\"retail_pd_application\",\"version\":\"3.1.0\"},"
                + "\"next\":\"grade\",\"onFailure\":{\"reasonCode\":\"MODEL_UNAVAILABLE\",\"to\":\"referred\"},"
                + "\"outputs\":{\"pd\":\"decimal\"},\"reasonCodes\":{\"age\":\"THIN_CREDIT_FILE\","
                + "\"utilisation\":\"HIGH_UTILISATION\"},\"timeoutMs\":80,\"type\":\"model\","
                + "\"valid\":\"pd >= decimal(0) && pd <= decimal(1)\"}");
    }

    @Test
    void readingAModelNodeIsStrict() {
        String json = flowWith("""
                { "m": { "type": "model", "mode": "remote", "model": { "id": "pd", "release": "1" },
                         "outputs": { "pd": "double" }, "minConfidence": 0.5e0, "timeoutMs": "80",
                         "reasonCodes": { "x": 1 }, "shadow": [], "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """);
        assertThatThrownBy(() -> DecisionFlowJson.read(json))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.nodes.m.shadow: not supported yet: shadow models and LLM nodes are planned for a later "
                                + "release (see Hybrid AI decisioning)",
                        "$.nodes.m.mode: expected \"invoke\" or \"supplied\"",
                        "$.nodes.m.model.release: unknown field (allowed: id, version)",
                        "$.nodes.m.model.version: required non-empty string",
                        "$.nodes.m.outputs.pd: unknown type 'double' (floating point is not supported; use decimal)",
                        "$.nodes.m.minConfidence: expected a decimal from 0 to 1, e.g. \"0.80\"",
                        "$.nodes.m.timeoutMs: expected a whole number",
                        "$.nodes.m.reasonCodes.x: expected a reason code string",
                        "$.nodes.m.onFailure: required object { \"to\", \"reasonCode\" }: where to go when the model "
                                + "fails, and the reason code to give"));
    }

    // --- Load-time checks -----------------------------------------------------------------------

    private static String flowWith(String nodes) {
        return """
                { "id": "f", "version": "1",
                  "inputs": { "score": "int", "flag": "bool" },
                  "outputs": { "out": "string" }, "start": "m", "nodes": %s }
                """.formatted(nodes);
    }

    private static List<String> problems(String json, Consumer<RuleStone.Builder> configure) {
        RuleStone.Builder builder = RuleStone.builder().flow(DecisionFlowJson.read(json));
        configure.accept(builder);
        try {
            builder.build();
            return List.of();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
    }

    private static final ModelInvoker NONE = call -> null;

    @Test
    void aValidModelNodeHasNoProblems() {
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "invoke", "model": { "id": "pd", "version": "1.0.0" },
                         "features": { "s": "score" }, "outputs": { "p": "decimal" }, "timeoutMs": 50,
                         "onFailure": { "to": "down", "reasonCode": "MODEL_UNAVAILABLE" },
                         "next": [ { "when": "m.p > decimal('0.5')", "to": "high" }, { "to": "low" } ] },
                  "high": { "type": "end", "set": { "out": "'HIGH'" } },
                  "low": { "type": "end", "set": { "out": "'LOW'" } },
                  "down": { "type": "end", "set": { "out": "'DOWN'" } } }
                """), b -> b.modelInvoker("pd", NONE))).isEmpty();
    }

    @Test
    void theContractOfAModelNodeIsChecked() {
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "invoke", "model": { "id": "pd", "version": "2.x" },
                         "outputs": { "if": "decimal", "s": { "a": "int" } }, "minConfidence": "1.5",
                         "reasonCodes": { "u": "high utilisation" }, "maxReasonCodes": 11,
                         "onFailure": { "to": "e", "reasonCode": "down" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """), b -> { })).containsExactly(
                "f@1 nodes.m.model.version: must pin one exact model version, e.g. \"2.4.0\"; ranges, wildcards "
                        + "and \"latest\" are not allowed",
                "f@1 nodes.m.outputs.if: invalid output name: the valid condition reads outputs by name, so it must "
                        + "be an identifier (letters, digits and '_', not starting with a digit) and not a CEL keyword",
                "f@1 nodes.m.outputs.s: outputs must be bool, int, string, decimal, money, date or lists of them",
                "f@1 nodes.m.features: required in invoke mode: the values sent to the model, as feature name to "
                        + "expression",
                "f@1 nodes.m.timeoutMs: required in invoke mode: how long the model may take, in milliseconds",
                "f@1 nodes.m.model.id: no ModelInvoker registered for model 'pd'; register one with "
                        + "RuleStone.builder().modelInvoker(\"pd\", invoker), or build with suppliedModelsOnly() for tests",
                "f@1 nodes.m.minConfidence: must be from 0 to 1",
                "f@1 nodes.m.reasonCodes.u: must be UPPER_SNAKE_CASE, e.g. HIGH_UTILISATION",
                "f@1 nodes.m.maxReasonCodes: must be from 1 to 10",
                "f@1 nodes.m.onFailure.reasonCode: must be UPPER_SNAKE_CASE, e.g. MODEL_UNAVAILABLE");
    }

    @Test
    void aSuppliedNodeTakesNoFeaturesOrTimeoutAndNeedsNoInvoker() {
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "supplied", "model": { "id": "pd", "version": "latest" },
                         "features": { "s": "score" }, "outputs": { "p": "decimal" }, "timeoutMs": 50,
                         "onFailure": { "to": "e", "reasonCode": "DOWN" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """), b -> { })).containsExactly(
                "f@1 nodes.m.model.version: must pin one exact model version, e.g. \"2.4.0\"; ranges, wildcards "
                        + "and \"latest\" are not allowed",
                "f@1 nodes.m.features: not allowed in supplied mode: the caller runs the model, so the engine sends "
                        + "it nothing",
                "f@1 nodes.m.timeoutMs: not allowed in supplied mode: the engine calls no model");
    }

    @Test
    void theFailurePathCannotReadTheModelsOutputs() {
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "supplied", "model": { "id": "pd", "version": "1" },
                         "outputs": { "p": "decimal" },
                         "onFailure": { "to": "e", "reasonCode": "DOWN" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "string(m.p)" } } }
                """), b -> { })).containsExactly(
                "f@1 nodes.e.set.out: reads 'm', which is not set on every path that reaches this node; a node may "
                        + "read only values that every path to it sets");
    }

    @Test
    void featuresAreTypeCheckedAndTheValidConditionReadsOnlyOutputs() {
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "invoke", "model": { "id": "pd", "version": "1" },
                         "features": { "a": "score + 'x'", "b": "{'k': score}", "c": "missing" },
                         "outputs": { "p": "decimal" }, "valid": "p > decimal(0) && flag", "timeoutMs": 10,
                         "onFailure": { "to": "nowhere", "reasonCode": "DOWN" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """), b -> b.modelInvoker("pd", NONE))).containsExactly(
                "f@1 nodes.m.onFailure.to: no node 'nowhere'");
        assertThat(problems(flowWith("""
                { "m": { "type": "model", "mode": "invoke", "model": { "id": "pd", "version": "1" },
                         "features": { "a": "score + 'x'", "b": "{'k': score}" },
                         "outputs": { "p": "decimal" }, "valid": "p > decimal(0) && flag", "timeoutMs": 10,
                         "onFailure": { "to": "e", "reasonCode": "DOWN" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """), b -> b.modelInvoker("pd", NONE)))
                .hasSize(3)
                .anySatisfy(p -> assertThat(p).startsWith("f@1 nodes.m.features.a: "))
                .anySatisfy(p -> assertThat(p).startsWith("f@1 nodes.m.features.b: expression must produce bool, "
                        + "int, string, decimal, money, date or a list of them"))
                .anySatisfy(p -> assertThat(p).startsWith("f@1 nodes.m.valid: "));
    }

    @Test
    void modelNodesSharingANameMustDeclareTheSameOutputs() {
        assertThat(problems(flowWith("""
                { "m": { "type": "switch", "cases": [ { "when": "flag", "to": "a" }, { "to": "b" } ] },
                  "a": { "type": "model", "mode": "supplied", "as": "s", "model": { "id": "pa", "version": "1" },
                         "outputs": { "p": "decimal" }, "onFailure": { "to": "e", "reasonCode": "DOWN" }, "next": "e" },
                  "b": { "type": "model", "mode": "supplied", "as": "s", "model": { "id": "pb", "version": "1" },
                         "outputs": { "p": "int" }, "onFailure": { "to": "e", "reasonCode": "DOWN" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """), b -> { })).containsExactly(
                "f@1 nodes.b.as: nodes 'a' and 'b' both publish 's' but with different values: {p: decimal} and {p: int}");
    }
}
