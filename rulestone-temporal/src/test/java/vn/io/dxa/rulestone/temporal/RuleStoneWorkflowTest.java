//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static vn.io.dxa.rulestone.temporal.TestSupport.HCM;
import static vn.io.dxa.rulestone.temporal.TestSupport.SECRET_DEBT;
import static vn.io.dxa.rulestone.temporal.TestSupport.adult;
import static vn.io.dxa.rulestone.temporal.TestSupport.allTables;
import static vn.io.dxa.rulestone.temporal.TestSupport.application;
import static vn.io.dxa.rulestone.temporal.TestSupport.causeOfType;
import static vn.io.dxa.rulestone.temporal.TestSupport.engine;
import static vn.io.dxa.rulestone.temporal.TestSupport.failureText;
import static vn.io.dxa.rulestone.temporal.TestSupport.loanV2;
import static vn.io.dxa.rulestone.temporal.TestSupport.loanV3;
import static vn.io.dxa.rulestone.temporal.TestSupport.loanV3Edited;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributes;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.temporal.TestWorkflows.CardFee;
import vn.io.dxa.rulestone.temporal.TestWorkflows.CardFeeImpl;
import vn.io.dxa.rulestone.temporal.TestWorkflows.CardFeeInput;
import vn.io.dxa.rulestone.temporal.TestWorkflows.TwoDates;
import vn.io.dxa.rulestone.temporal.TestWorkflows.TwoDatesImpl;
import vn.io.dxa.rulestone.temporal.samples.LoanApplication;
import vn.io.dxa.rulestone.temporal.samples.LoanWorker;
import vn.io.dxa.rulestone.temporal.samples.LoanWorkflow;
import vn.io.dxa.rulestone.temporal.samples.LoanWorkflowImpl;

class RuleStoneWorkflowTest {

    /** 18:00 UTC on 31 October is 01:00 on 1 November in Ho Chi Minh City: v2 in UTC, v3 locally. */
    private static final String NEAR_MIDNIGHT = "2026-10-31T18:00:00Z";

    private static final LoanApplication APPROVABLE =
            application(adult("25750000.50", "1000000"), "100000000", "2026-11-01");

    private TestWorkflowEnvironment env;

    @AfterEach
    void close() {
        if (env != null) {
            env.close();
        }
    }

    /** Starts the sample worker. Its engine clock is years off, so only workflow time can pick a version. */
    private WorkflowClient startSampleWorker(String initialTime) {
        env = TestSupport.environment(initialTime);
        Clock misleading = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), HCM);
        LoanWorker.register(env.getWorkerFactory(), allTables(), misleading);
        env.start();
        return env.getWorkflowClient();
    }

    private WorkflowClient startWorker(String initialTime, Class<?> type, Object implementation) {
        env = TestSupport.environment(initialTime);
        Worker worker = env.newWorker(LoanWorker.TASK_QUEUE);
        registerFactory(worker, type, implementation);
        env.start();
        return env.getWorkflowClient();
    }

    @SuppressWarnings("unchecked")
    private static <T> void registerFactory(Worker worker, Class<T> type, Object implementation) {
        worker.registerWorkflowImplementationFactory(type, () -> (T) implementation);
    }

    private static <T> T stub(WorkflowClient client, Class<T> type, String workflowId) {
        return client.newWorkflowStub(type, WorkflowOptions.newBuilder()
                .setTaskQueue(LoanWorker.TASK_QUEUE)
                .setWorkflowId(workflowId)
                .build());
    }

    private static void replay(WorkflowExecutionHistory history, RuleStoneEngine engine, ZoneId zone)
            throws Exception {
        try (TestWorkflowEnvironment replayEnv = TestWorkflowEnvironment.newInstance()) {
            Worker worker = replayEnv.newWorker(LoanWorker.TASK_QUEUE);
            worker.registerWorkflowImplementationFactory(LoanWorkflow.class, () -> new LoanWorkflowImpl(engine, zone));
            WorkflowReplayer.replayWorkflowExecution(history, worker);
        }
    }

    private WorkflowExecutionHistory decideAndRecord(String workflowId) {
        WorkflowClient client = startSampleWorker(NEAR_MIDNIGHT);
        DecisionOutcome outcome = stub(client, LoanWorkflow.class, workflowId).decide(APPROVABLE);
        assertThat(outcome.ruleVersion()).startsWith("3@sha256:");
        return client.fetchHistory(workflowId);
    }

    @Test
    void businessDateComesFromWorkflowTimeInTheBusinessZone() {
        WorkflowClient client = startSampleWorker(NEAR_MIDNIGHT);

        DecisionOutcome outcome = stub(client, LoanWorkflow.class, "loan-hcm").decide(APPROVABLE);

        assertThat(outcome.businessDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(outcome.ruleVersion()).isEqualTo(engine(allTables())
                .evaluate(vn.io.dxa.rulestone.api.DecisionRef.of("loan_eligibility").version("3"),
                        vn.io.dxa.rulestone.api.Facts.of("applicant", APPROVABLE.applicant(),
                                "amount", APPROVABLE.amount()))
                .ruleVersion());
        assertThat(outcome.text("decision")).contains("APPROVE");
        assertThat(outcome.decimal("limit")).hasValueSatisfying(l -> assertThat(l).isEqualByComparingTo("309000000"));
    }

    @Test
    void theZoneDecidesTheBusinessDate() {
        WorkflowClient client = startWorker(NEAR_MIDNIGHT, LoanWorkflow.class,
                new LoanWorkflowImpl(engine(allTables()), ZoneOffset.UTC));

        DecisionOutcome outcome = stub(client, LoanWorkflow.class, "loan-utc").decide(APPROVABLE);

        assertThat(outcome.businessDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(outcome.ruleVersion()).startsWith("2@sha256:");
    }

    @Test
    void sleepingAcrossAnEffectiveDateSwitchesVersion() {
        WorkflowClient client = startWorker("2026-10-20T02:00:00Z", TwoDates.class,
                new TwoDatesImpl(engine(allTables()), HCM));

        List<DecisionOutcome> outcomes = stub(client, TwoDates.class, "two-dates").decideTwice(APPROVABLE);

        assertThat(outcomes).extracting(DecisionOutcome::businessDate)
                .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 4));
        assertThat(outcomes.get(0).ruleVersion()).startsWith("2@sha256:");
        assertThat(outcomes.get(1).ruleVersion()).startsWith("3@sha256:");
    }

    @Test
    void upsertsSearchAttributes() {
        WorkflowClient client = startSampleWorker(NEAR_MIDNIGHT);
        stub(client, LoanWorkflow.class, "loan-sa").decide(APPROVABLE);

        SearchAttributes attributes = client.newUntypedWorkflowStub("loan-sa").describe().getTypedSearchAttributes();

        assertThat(attributes.get(RuleStoneSearchAttributes.DECISION)).isEqualTo("loan_eligibility");
        assertThat(attributes.get(RuleStoneSearchAttributes.RULE_VERSION)).startsWith("3@sha256:");
        assertThat(attributes.get(RuleStoneSearchAttributes.OUTCOME)).isEqualTo("APPROVE");
        assertThat(attributes.containsKey(RuleStoneSearchAttributes.REASON_CODES)).isFalse();
    }

    @Test
    void replayReproducesTheRecordedVersionAndDate() throws Exception {
        WorkflowExecutionHistory history = decideAndRecord("loan-replay");

        replay(history, engine(allTables()), HCM);
        // Recorded values win: in UTC the date would be 31 October and select v2.
        replay(history, engine(allTables()), ZoneOffset.UTC);
        // Version 2 is not needed to replay a v3 decision.
        replay(history, engine(List.of(loanV3())), HCM);
    }

    @Test
    void replayFailsWhenTheRuleTextChangedUnderTheSameVersion() {
        WorkflowExecutionHistory history = decideAndRecord("loan-edited");
        DecisionTable edited = loanV3Edited();

        Throwable thrown = catchThrowable(() -> replay(history, engine(List.of(loanV2(), edited)), HCM));

        // WorkflowReplayer reports the workflow task failure as text.
        String recorded = engine(List.of(loanV3())).evaluate(
                vn.io.dxa.rulestone.api.DecisionRef.of("loan_eligibility").version("3"),
                vn.io.dxa.rulestone.api.Facts.of("applicant", APPROVABLE.applicant(), "amount", APPROVABLE.amount()))
                .ruleVersion();
        String registered = engine(List.of(edited)).evaluate(
                vn.io.dxa.rulestone.api.DecisionRef.of("loan_eligibility").version("3"),
                vn.io.dxa.rulestone.api.Facts.of("applicant", APPROVABLE.applicant(), "amount", APPROVABLE.amount()))
                .ruleVersion();
        assertThat(registered).isNotEqualTo(recorded);
        assertThat(thrown).hasMessageContaining(RuleVersionMismatchException.class.getName())
                .hasMessageContaining("RuleStone replay mismatch for decision 'loan_eligibility': workflow history"
                        + " recorded rule version " + recorded + ", but version '3' now registered in the engine is "
                        + registered + ". The rule content changed without a new version label.");
    }

    @Test
    void replayFailsWhenTheRecordedVersionIsNoLongerRegistered() {
        WorkflowExecutionHistory history = decideAndRecord("loan-removed");

        Throwable thrown = catchThrowable(() -> replay(history, engine(List.of(loanV2())), HCM));

        assertThat(thrown).hasMessageContaining(RuleVersionMismatchException.class.getName())
                .hasMessageContaining("but the engine has no version '3'. Keep every rule version registered"
                        + " while workflows that used it can still replay.");
    }

    @Test
    void noEffectiveVersionFailsTheWorkflowDeterministically() throws Exception {
        WorkflowClient client = startSampleWorker("2025-06-01T03:00:00Z");

        Throwable thrown = catchThrowable(() -> stub(client, LoanWorkflow.class, "loan-early").decide(APPROVABLE));

        assertThat(thrown).isInstanceOf(WorkflowFailedException.class);
        ApplicationFailure failure = causeOfType(thrown, ApplicationFailure.class);
        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.DECISION_NOT_FOUND);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failure.getOriginalMessage())
                .isEqualTo("no version of decision 'loan_eligibility' is effective on 2025-06-01");
        // The not-found result is recorded, so the failed run replays even with other rules.
        replay(client.fetchHistory("loan-early"), engine(allTables()), HCM);
    }

    @Test
    void evaluationFailureIsNonRetryableAndRevealsNoInputValues() {
        WorkflowClient client = startSampleWorker(NEAR_MIDNIGHT);
        LoanApplication bad = application(adult("0", SECRET_DEBT), "1000", "2026-11-01");

        Throwable thrown = catchThrowable(() -> stub(client, LoanWorkflow.class, "loan-bad").decide(bad));

        ApplicationFailure failure = causeOfType(thrown, ApplicationFailure.class);
        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.DECISION_EVALUATION_FAILED);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failureText(thrown)).doesNotContain(SECRET_DEBT).doesNotContain("98765432");
    }

    @Test
    void moneyAndDatesFromWorkflowDataAndTheBusinessDate() {
        WorkflowClient client = startWorker(NEAR_MIDNIGHT, CardFee.class, new CardFeeImpl(engine(allTables()), HCM));
        CardFeeInput input = new CardFeeInput(LocalDate.of(1980, 5, 20),
                Money.of("123456789012.345", "VND"), new BigDecimal("0.0010"));

        DecisionOutcome outcome = stub(client, CardFee.class, "card-fee").fee(input);

        assertThat(outcome.businessDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(outcome.money("fee")).contains(Money.of(input.spend().amount().multiply(input.rate()), "VND"));
        assertThat(outcome.date("nextReview")).contains(LocalDate.of(2027, 5, 1));
        assertThat(outcome.output().get("fee").asText()).isEqualTo("123456789.0123450 VND");
    }
}
