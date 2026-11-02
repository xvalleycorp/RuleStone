//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionComparison;
import vn.io.dxa.rulestone.api.DecisionComparison.Difference;
import vn.io.dxa.rulestone.api.DecisionComparison.Kind;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelCall;
import vn.io.dxa.rulestone.api.ModelInvoker;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.api.ModelResult;
import vn.io.dxa.rulestone.api.ModelResult.Attribution;
import vn.io.dxa.rulestone.api.ModelSource;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** Replay and re-score of recorded decisions, of tables with parameters and of flows with models. */
class ReplayTest {

    private static final Path EXAMPLES = Path.of("..", "examples");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-09-01T03:00:00Z"), ZoneOffset.UTC);

    private final List<DecisionRecord> audited = new ArrayList<>();

    // --- Decision tables with parameters --------------------------------------------------------

    private RuleStoneEngine loans(Consumer<RuleStone.Builder> configure) {
        RuleStone.Builder builder = RuleStone.builder().clock(CLOCK)
                .decision(DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v4.stone.json")))
                .paramSet(ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v1.params.json")))
                .paramSet(ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v2.params.json")))
                .auditSink(audited::add);
        configure.accept(builder);
        return builder.build();
    }

    private RuleStoneEngine loans() {
        return loans(b -> { });
    }

    /** Debt-to-income 0.48: within the 0.50 of retail_credit v1, above the 0.45 of v2. */
    private static Facts applicant(String monthlyDebt) {
        return Facts.of("applicant", Map.of("age", 35, "monthlyIncome", new BigDecimal("25000000"),
                        "monthlyDebt", new BigDecimal(monthlyDebt), "segment", "MASS", "blacklisted", false),
                "amount", new BigDecimal("200000000"));
    }

    private static final DecisionRef JUNE = DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2027, 6, 30))
            .correlationId("LOAN-2027-000042");

    @Test
    void aStoredRecordReplaysExactly() {
        RuleStoneEngine engine = loans();
        DecisionResult result = engine.evaluate(JUNE, applicant("12000000"));
        String stored = DecisionRecordJson.write(result.record());
        audited.clear();

        DecisionRecord record = DecisionRecordJson.read(stored);
        DecisionComparison replay = engine.replay(record, applicant("12000000"));

        assertThat(replay.differences()).isEmpty();
        assertThat(replay.matches()).isTrue();
        assertThat(replay.result()).isPresent();
        assertThat(replay.evaluated().recordId()).isNotEqualTo(record.recordId());
        assertThat(replay.evaluated().correlationId()).contains("LOAN-2027-000042");
        assertThat(replay.evaluated().params()).isEqualTo(record.params());
        assertThat(replay.evaluated().output()).isEqualTo(result.output());
        assertThat(audited).as("a replay writes nothing to the audit sinks").isEmpty();
    }

    @Test
    void aRecordReadBackWritesTheSameJson() {
        DecisionResult result = loans().evaluate(JUNE, applicant("12000000"));
        String stored = DecisionRecordJson.write(result.record());

        assertThat(DecisionRecordJson.write(DecisionRecordJson.read(stored))).isEqualTo(stored);
    }

    @Test
    void otherFactsDoNotMatchTheFactsHash() {
        RuleStoneEngine engine = loans();
        DecisionRecord record = engine.evaluate(JUNE, applicant("12000000")).record();

        DecisionComparison replay = engine.replay(record, applicant("12000001"));

        assertThat(replay.matches()).isFalse();
        assertThat(replay.differences(Kind.FACTS)).extracting(Difference::description)
                .containsExactly("factsHash: the facts are not those the record was made on");
    }

    @Test
    void theFactsHashIsCheckedWithTheEngineKey() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes();
        DecisionRecord record = loans(b -> b.factsHashKey(key)).evaluate(JUNE, applicant("12000000")).record();

        assertThat(loans(b -> b.factsHashKey(key)).replay(record, applicant("12000000")).matches()).isTrue();
        assertThat(loans().replay(record, applicant("12000000")).differences()).extracting(Difference::description)
                .containsExactly("factsHash: the record's hash is hmac-sha256 but this engine computes sha256; "
                        + "verify with the factsHashKey the record was made with");
        byte[] other = "fedcba9876543210fedcba9876543210".getBytes();
        assertThat(loans(b -> b.factsHashKey(other)).replay(record, applicant("12000000")).differences())
                .extracting(Difference::description)
                .containsExactly("factsHash: the facts are not those the record was made on, or the factsHashKey "
                        + "is not the one it was made with");
    }

    @Test
    void theRecordedParameterSetVersionIsUsedAlthoughAnotherIsInForce() {
        // A record of 30 June used retail_credit v1. Replayed with an engine that has only v1 and v2,
        // it still uses v1, although the record's business date is what selects it.
        RuleStoneEngine engine = loans();
        DecisionRecord record = engine.evaluate(JUNE, applicant("12000000")).record();
        assertThat(record.params()).extracting(DecisionRecord.ParamSetVersion::version).allMatch(v -> v.startsWith("1@"));
        assertThat(record.output()).containsEntry("decision", "APPROVE");

        assertThat(engine.replay(record, applicant("12000000")).matches()).isTrue();
    }

    @Test
    void aVersionThatIsNotRegisteredIsNamed() {
        DecisionRecord record = loans().evaluate(JUNE, applicant("12000000")).record();
        String version = record.version().orElseThrow();

        RuleStoneEngine without = RuleStone.builder()
                .decision(DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v3.stone.json"))).build();
        assertThatThrownBy(() -> without.replay(record, applicant("12000000")))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("decision 'loan_eligibility' has no version " + version);

        DecisionRecord tampered = withVersion(record, "4@sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> loans().replay(tampered, applicant("12000000")))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("decision 'loan_eligibility' has no version 4@sha256:" + "0".repeat(64)
                        + ": version '4' is registered with other content, " + version
                        + "; register the content the record names");
    }

    @Test
    void aParameterSetVersionThatIsNotRegisteredIsNamed() {
        DecisionRecord record = loans().evaluate(JUNE, applicant("12000000")).record();
        String params = record.params().get(0).version();

        RuleStoneEngine onlyV2 = RuleStone.builder()
                .decision(DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v4.stone.json")))
                .paramSet(ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v2.params.json")))
                .build();
        assertThatThrownBy(() -> onlyV2.replay(record, applicant("12000000")))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("parameter set 'retail_credit' has no version " + params);
    }

    @Test
    void aRecordWithoutVersionOrFactsWithModelsCannotBeReplayed() {
        RuleStoneEngine engine = loans();
        DecisionRecord record = engine.evaluate(JUNE, applicant("12000000")).record();

        assertThatThrownBy(() -> engine.replay(withVersion(record, null), applicant("12000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no version of decision 'loan_eligibility'");
        assertThatThrownBy(() -> engine.replay(record, applicant("12000000")
                .withModelResult("pd", ModelResult.of("m", "1", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a replay takes the model results from the record; the facts must not carry any, "
                        + "but carry [pd]");
    }

    @Test
    void aFailedDecisionReplaysItsFailure() {
        RuleStoneEngine engine = loans();
        Facts invalid = Facts.of("applicant", Map.of("age", 35), "amount", new BigDecimal("1"));
        DecisionRecord record;
        try {
            engine.evaluate(JUNE, invalid);
            throw new AssertionError("expected a failure");
        } catch (RuntimeException e) {
            record = audited.get(audited.size() - 1);
        }
        assertThat(record.status()).isEqualTo(Status.ERROR);

        DecisionComparison replay = engine.replay(record, invalid);

        assertThat(replay.differences()).isEmpty();
        assertThat(replay.result()).isEmpty();
        assertThat(replay.evaluated().status()).isEqualTo(Status.ERROR);
    }

    @Test
    void reScoringWithTheParametersInForceLaterShowsTheChange() {
        RuleStoneEngine engine = loans();
        DecisionRecord record = engine.evaluate(JUNE, applicant("12000000")).record();

        // The same business date, with the parameter set version 2 instead.
        DecisionComparison v2 = engine.rescore(record, applicant("12000000"),
                DecisionRef.of("loan_eligibility").asOf(record.businessDate()).params("retail_credit", "2"),
                ModelSource.RECORDED);

        assertThat(v2.matches()).isFalse();
        assertThat(v2.differences(Kind.FACTS)).isEmpty();
        assertThat(v2.differences()).extracting(Difference::kind)
                .containsExactly(Kind.PARAMS, Kind.OUTPUT, Kind.OUTPUT, Kind.REASON_CODES, Kind.PATH);
        assertThat(v2.differences()).extracting(Difference::description).contains(
                "output 'decision' differs",
                "output 'limit': recorded, not set now",
                "reasonCodes: recorded [], now [DTI_HIGH from loan_eligibility]",
                "path step 1 (loan_eligibility): recorded matched rules [R5], now [R4]");
        assertThat(v2.evaluated().output()).containsEntry("decision", "REJECT");
        assertThat(v2.evaluated().correlationId()).contains("LOAN-2027-000042");

        // Today's version: the engine clock says 1 September 2027, when v2 is in force.
        DecisionComparison today = engine.rescore(record, applicant("12000000"), DecisionRef.of("loan_eligibility"),
                ModelSource.RECORDED);
        assertThat(today.differences()).extracting(Difference::kind).startsWith(Kind.BUSINESS_DATE, Kind.PARAMS);
        assertThat(today.evaluated().businessDate()).isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(audited).hasSize(1);
    }

    @Test
    void reScoringChecksTheFactsWithTheRecordedVersion() {
        RuleStoneEngine engine = loans();
        DecisionRecord record = engine.evaluate(JUNE, applicant("12000000")).record();

        DecisionComparison same = engine.rescore(record, applicant("12000000"),
                DecisionRef.of("loan_eligibility").asOf(record.businessDate()), ModelSource.RECORDED);
        assertThat(same.matches()).isTrue();

        DecisionComparison other = engine.rescore(record, applicant("1000000"),
                DecisionRef.of("loan_eligibility").asOf(record.businessDate()), ModelSource.RECORDED);
        assertThat(other.differences(Kind.FACTS)).hasSize(1);

        assertThatThrownBy(() -> engine.rescore(record, applicant("12000000"), DecisionRef.of("other"),
                ModelSource.RECORDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a re-score evaluates the recorded decision 'loan_eligibility', not 'other'");
    }

    // --- Flows with models ----------------------------------------------------------------------

    private static final DecisionRef CARD = DecisionRef.of("card_origination_scored")
            .asOf(LocalDate.of(2026, 11, 15)).correlationId("APP-1");

    private final List<ModelCall> calls = new ArrayList<>();

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

    private RuleStoneEngine cards(ModelInvoker invoker) {
        return RuleStone.builder().clock(CLOCK).decisions(ModelNodeTest.tables()).flow(ModelNodeTest.flow())
                .modelInvoker("retail_pd_behaviour", invoker).modelInvoker("retail_pd_application", invoker)
                .auditSink(audited::add).build();
    }

    private static Facts card(boolean existing) {
        return Facts.of(
                "applicant", Map.of("id", "C-1", "age", 35, "income", Money.of("20000000", "VND"),
                        "existingCustomer", existing),
                "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.40")));
    }

    private static ModelResult fraud(String score) {
        return ModelResult.of("card_application_fraud", "1.2.0", Map.of("score", score))
                .withAttributions("SHAP", List.of(new Attribution("addressMismatch", new BigDecimal("0.10")),
                        new Attribution("deviceAgeDays", new BigDecimal("0.30"))));
    }

    @Test
    void aFlowReplaysWithTheRecordedModelResultsWithoutCallingAModel() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionResult result = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05")));
        DecisionRecord record = DecisionRecordJson.read(DecisionRecordJson.write(result.record()));
        calls.clear();
        audited.clear();

        // The model has been retrained since: it would answer differently now.
        RuleStoneEngine retrained = cards(pd("0.5000"));
        DecisionComparison replay = retrained.replay(record, card(true));

        assertThat(replay.differences()).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(audited).isEmpty();
        assertThat(replay.evaluated().reasonCodes()).isEqualTo(record.reasonCodes());
        ModelStep pd = step(replay.evaluated(), "pd_behaviour");
        assertThat(pd.mode()).isEqualTo(ModelMode.SUPPLIED);
        assertThat(pd.latencyMs()).isEmpty();
        assertThat(step(record, "pd_behaviour").mode()).isEqualTo(ModelMode.INVOKE);
    }

    @Test
    void aRecordedModelFailureReplaysAsTheSameFailure() {
        ModelInvoker invalid = call -> ModelResult.of(call.modelId(), call.modelVersion(),
                Map.of("pd", new BigDecimal("1.5")));
        RuleStoneEngine engine = cards(invalid);
        DecisionRecord record = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05"))).record();
        assertThat(step(record, "pd_behaviour").status()).isEqualTo(ModelStatus.INVALID_OUTPUT);

        DecisionComparison replay = engine.replay(record, card(true));

        assertThat(replay.differences()).isEmpty();
        assertThat(replay.evaluated().output()).containsEntry("decision", "REFER");
    }

    @Test
    void aMissingSuppliedResultReplaysAsMissing() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionRecord record = engine.evaluate(CARD, card(true)).record();
        assertThat(step(record, "fraud").status()).isEqualTo(ModelStatus.MISSING);

        assertThat(engine.replay(record, card(true)).differences()).isEmpty();
    }

    @Test
    void aTamperedModelOutputIsReported() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionRecord record = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05"))).record();
        // The record says the model returned 0.30: the decision does not follow from it.
        String json = DecisionRecordJson.write(record).replace("\"pd\":\"0.0150\"", "\"pd\":\"0.30\"");

        DecisionComparison replay = engine.replay(DecisionRecordJson.read(json), card(true));

        assertThat(replay.matches()).isFalse();
        assertThat(replay.differences()).extracting(Difference::description)
                .contains("output 'riskGrade' differs",
                        "path step 5 (grade): recorded matched rules [A], now [D]",
                        "path step 6: recorded table node 'limit', now end node 'referred'",
                        "path: recorded 7 steps (policy, fraud, segment, pd_behaviour, grade, limit, approved), "
                                + "now 6 (policy, fraud, segment, pd_behaviour, grade, referred)");
    }

    @Test
    void reScoringWithLiveModelsCallsThemAndShowsTheChange() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionRecord record = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05"))).record();
        calls.clear();
        audited.clear();
        RuleStoneEngine retrained = cards(pd("0.0900"));

        DecisionComparison recorded = retrained.rescore(record, card(true), CARD, ModelSource.RECORDED);
        assertThat(recorded.matches()).isTrue();
        assertThat(calls).isEmpty();

        DecisionComparison live = retrained.rescore(record, card(true).withModelResult("fraud", fraud("0.05")),
                CARD, ModelSource.LIVE);
        assertThat(calls).hasSize(1);
        assertThat(live.differences()).extracting(Difference::description)
                .contains("path step 4 (pd_behaviour): model output 'pd' differs", "output 'riskGrade' differs");
        assertThat(audited).isEmpty();
    }

    @Test
    void reScoringWithRecordedModelsTakesSuppliedResultsFirst() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionRecord record = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05"))).record();
        calls.clear();

        // A challenger model's score, computed offline, for the invoke node.
        ModelResult challenger = ModelResult.of("retail_pd_behaviour", "2.4.0", Map.of("pd", "0.0900"));
        DecisionComparison rescored = engine.rescore(record, card(true).withModelResult("pd_behaviour", challenger),
                CARD, ModelSource.RECORDED);

        assertThat(calls).isEmpty();
        assertThat(step(rescored.evaluated(), "pd_behaviour").outputs()).containsEntry("pd", new BigDecimal("0.0900"));
        assertThat(step(rescored.evaluated(), "fraud").status()).isEqualTo(ModelStatus.OK);
        assertThat(rescored.differences(Kind.FACTS)).isEmpty();
        assertThat(rescored.differences(Kind.OUTPUT)).isNotEmpty();
    }

    @Test
    void aTableVersionOfAFlowStepMustBeRegistered() {
        RuleStoneEngine engine = cards(pd("0.0150"));
        DecisionRecord record = engine.evaluate(CARD, card(true).withModelResult("fraud", fraud("0.05"))).record();
        List<PathStep> path = new ArrayList<>(record.path());
        PathStep grade = path.get(4);
        String fake = "1@sha256:" + "f".repeat(64);
        path.set(4, PathStep.table(grade.node(), grade.decision().orElseThrow(), fake, grade.matchedRules()));
        DecisionRecord tampered = new DecisionRecord(record.recordId(), record.correlationId(), record.decision(),
                record.version(), record.businessDate(), record.evaluatedAt(), record.engine(), record.status(),
                record.factsHash(), record.output(), record.reasonCodes(), path, record.error(), record.routing(),
                record.params());

        assertThatThrownBy(() -> engine.replay(tampered, card(true)))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessageStartingWith("decision 'card_pd_grade' has no version " + fake
                        + ": version '1' is registered with other content, 1@sha256:");
    }

    // --- Reading records ------------------------------------------------------------------------

    @Test
    void readingIsStrict() {
        assertThatThrownBy(() -> DecisionRecordJson.read("""
                { "recordId": "r", "decision": "d", "businessDate": "2026-13-01", "evaluatedAt": "now",
                  "engine": "rulestone dev", "status": "FINE", "output": {}, "reasonCodes": [],
                  "path": [ { "node": "d", "type": "table", "outputs": {} } ], "extra": 1 }
                """))
                .isInstanceOf(InvalidRecordException.class)
                .extracting(e -> ((InvalidRecordException) e).problems()).asList()
                .containsExactly(
                        "$.extra: unknown field (allowed: businessDate, correlationId, decision, engine, error, "
                                + "evaluatedAt, factsHash, output, params, path, reasonCodes, recordId, routing, "
                                + "status, version)",
                        "$.businessDate: expected a date as \"yyyy-MM-dd\"",
                        "$.evaluatedAt: expected an instant such as \"2026-11-01T02:15:04.218Z\"",
                        "$.status: expected one of OK, ERROR, TIMEOUT",
                        "$.path[0].outputs: only a model step has this field");
        assertThatThrownBy(() -> DecisionRecordJson.read("{ \"recordId\": \"secret"))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageNotContaining("secret");
    }

    private static ModelStep step(DecisionRecord record, String node) {
        return record.path().stream().filter(s -> s.node().equals(node)).findFirst()
                .flatMap(PathStep::model).orElseThrow();
    }

    private static DecisionRecord withVersion(DecisionRecord r, String version) {
        return new DecisionRecord(r.recordId(), r.correlationId(), r.decision(), Optional.ofNullable(version),
                r.businessDate(), r.evaluatedAt(), r.engine(), r.status(), r.factsHash(), r.output(), r.reasonCodes(),
                r.path(), r.error(), r.routing(), r.params());
    }
}
