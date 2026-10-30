//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.io.dxa.rulestone.temporal.TestSupport.SECRET_DEBT;
import static vn.io.dxa.rulestone.temporal.TestSupport.adult;
import static vn.io.dxa.rulestone.temporal.TestSupport.allTables;
import static vn.io.dxa.rulestone.temporal.TestSupport.application;
import static vn.io.dxa.rulestone.temporal.TestSupport.causeOfType;
import static vn.io.dxa.rulestone.temporal.TestSupport.clockAt;
import static vn.io.dxa.rulestone.temporal.TestSupport.engine;
import static vn.io.dxa.rulestone.temporal.TestSupport.failureText;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.temporal.TestWorkflows.CountingActivities;
import vn.io.dxa.rulestone.temporal.TestWorkflows.ViaActivity;
import vn.io.dxa.rulestone.temporal.TestWorkflows.ViaActivityImpl;
import vn.io.dxa.rulestone.temporal.samples.LoanActivityWorkflow;
import vn.io.dxa.rulestone.temporal.samples.LoanActivityWorkflowImpl;

class RuleStoneActivitiesTest {

    private static final String QUEUE = "rulestone-activities";

    private TestWorkflowEnvironment env;
    private CountingActivities activities;

    @AfterEach
    void close() {
        if (env != null) {
            env.close();
        }
    }

    private WorkflowClient start(RuleStoneEngine engine, Clock clock) {
        env = TestSupport.environment("2026-11-15T03:00:00Z");
        activities = new CountingActivities(new RuleStoneActivitiesImpl(engine, allTables(), clock));
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(ViaActivityImpl.class, LoanActivityWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
        return env.getWorkflowClient();
    }

    private WorkflowClient start() {
        return start(engine(allTables()), clockAt("2026-11-15"));
    }

    private static <T> T stub(WorkflowClient client, Class<T> type) {
        return client.newWorkflowStub(type, WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());
    }

    private ApplicationFailure failureOf(WorkflowClient client, DecisionRequest request) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> stub(client, ViaActivity.class).evaluate(request));
        assertThat(thrown).isInstanceOf(WorkflowFailedException.class);
        assertThat(causeOfType(thrown, ActivityFailure.class)).isNotNull();
        ApplicationFailure failure = causeOfType(thrown, ApplicationFailure.class);
        assertThat(failure).isNotNull();
        return failure;
    }

    @Test
    void decidesALoanThroughTheSampleWorkflow() {
        WorkflowClient client = start();

        DecisionOutcome outcome = stub(client, LoanActivityWorkflow.class)
                .decide(application(adult("25750000.50", "1000000"), "100000000", "2026-11-15"));

        assertThat(outcome.text("decision")).contains("APPROVE");
        assertThat(outcome.decimal("limit")).hasValueSatisfying(
                limit -> assertThat(limit).isEqualByComparingTo("309000000"));
        assertThat(outcome.output().get("limit").isTextual()).isTrue();
        assertThat(outcome.ruleVersion()).startsWith("3@sha256:");
        assertThat(outcome.businessDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(outcome.matchedRules()).containsExactly("R4");
        assertThat(outcome.matched()).isTrue();
    }

    @Test
    void withoutAsOfUsesTheActivityClockAndRecordsTheDate() {
        WorkflowClient client = start(engine(allTables()), clockAt("2026-10-20"));

        DecisionOutcome outcome = stub(client, ViaActivity.class).evaluate(DecisionRequest.of("loan_eligibility",
                Facts.of("applicant", adult("20000000", "0"), "amount", new BigDecimal("50000000"))));

        assertThat(outcome.businessDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(outcome.ruleVersion()).startsWith("2@sha256:");
    }

    @Test
    void carriesMoneyDatesAndDecimalsExactly() {
        RuleStoneEngine engine = engine(allTables());
        WorkflowClient client = start(engine, clockAt("2026-11-15"));
        Money spend = Money.of("123456789012.345", "VND");
        BigDecimal rate = new BigDecimal("0.0010");
        Map<String, Object> customer = Map.of("birthDate", LocalDate.of(1980, 5, 20));
        DecisionRequest request = DecisionRequest
                .of("card_fee", Map.of("customer", customer, "spend", spend, "rate", rate))
                .withAsOf(LocalDate.of(2026, 11, 15));

        assertThat(request.facts().get("spend").asText()).isEqualTo("123456789012.345 VND");
        assertThat(request.facts().get("rate").asText()).isEqualTo("0.0010");
        assertThat(request.facts().get("customer").get("birthDate").asText()).isEqualTo("1980-05-20");

        DecisionOutcome outcome = stub(client, ViaActivity.class).evaluate(request);

        DecisionResult direct = engine.evaluate(DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 15)),
                Facts.of("customer", customer, "spend", spend, "rate", rate));
        assertThat(outcome.money("fee")).contains((Money) direct.output().get("fee"));
        assertThat(outcome.date("nextReview")).contains(LocalDate.of(2027, 5, 15));
        assertThat(outcome.text("tier")).contains("STANDARD");
    }

    @Test
    void unknownDecisionIsNonRetryable() {
        WorkflowClient client = start();

        ApplicationFailure failure = failureOf(client, DecisionRequest.of("no_such_decision", Map.of()));

        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.DECISION_NOT_FOUND);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failure.getOriginalMessage()).isEqualTo("unknown decision 'no_such_decision'");
        assertThat(activities.calls).hasValue(1);
    }

    @Test
    void unknownVersionIsNonRetryable() {
        WorkflowClient client = start();

        ApplicationFailure failure = failureOf(client,
                DecisionRequest.of("loan_eligibility", Map.of()).withVersion("9"));

        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.DECISION_NOT_FOUND);
        assertThat(failure.getOriginalMessage()).isEqualTo("decision 'loan_eligibility' has no version '9'");
        assertThat(activities.calls).hasValue(1);
    }

    @Test
    void evaluationFailureIsNonRetryableAndRevealsNoInputValues() {
        WorkflowClient client = start();
        DecisionRequest request = DecisionRequest.of("loan_eligibility",
                Facts.of("applicant", adult("0", SECRET_DEBT), "amount", new BigDecimal("50000000")))
                .withAsOf(LocalDate.of(2026, 11, 15));

        ApplicationFailure failure = failureOf(client, request);

        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.DECISION_EVALUATION_FAILED);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failure.getOriginalMessage()).startsWith("loan_eligibility@3@sha256:");
        assertThat(failure.getDetails().get(0, String.class)).isEqualTo("loan_eligibility");
        assertThat(failure.getDetails().get(1, String.class)).startsWith("3@sha256:");
        assertThat(failureText(failure)).doesNotContain(SECRET_DEBT).doesNotContain("98765432");
        assertThat(activities.calls).hasValue(1);
    }

    @Test
    void malformedFactsAreNonRetryableAndRevealNoInputValues() {
        WorkflowClient client = start();
        DecisionRequest request = DecisionRequest.of("card_fee", Map.of(
                        "spend", "SECRET-SPEND VND",
                        "rate", "0.0010",
                        "customer", Map.of("birthDate", "20/05/1980"),
                        "nickname", "SECRET-NAME"))
                .withAsOf(LocalDate.of(2026, 11, 15));

        ApplicationFailure failure = failureOf(client, request);

        assertThat(failure.getType()).isEqualTo(RuleStoneFailures.INVALID_REQUEST);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failure.getOriginalMessage())
                .startsWith("card_fee@1: ")
                .contains("spend", "customer.birthDate", "nickname");
        assertThat(failureText(failure)).doesNotContain("SECRET").doesNotContain("20/05/1980");
        assertThat(activities.calls).hasValue(1);
    }

    @Test
    void suppliedModelResultsAreReadAndReachTheEngine() {
        WorkflowClient client = start();
        DecisionRequest request = DecisionRequest.of("loan_eligibility",
                        Facts.of("applicant", adult("20000000", "0"), "amount", new BigDecimal("50000000")))
                .withAsOf(LocalDate.of(2026, 11, 15));

        ObjectNode invalid = JsonNodeFactory.instance.objectNode();
        invalid.putObject("pd").put("failure", "SLOW");
        ApplicationFailure malformed = failureOf(client, request.withModels(invalid));
        assertThat(malformed.getType()).isEqualTo(RuleStoneFailures.INVALID_REQUEST);
        assertThat(malformed.getOriginalMessage()).endsWith("models.pd.failure: expected \"TIMEOUT\" or \"ERROR\"");

        // The activity evaluates tables, which have no model nodes, so the engine rejects the results.
        ObjectNode models = JsonNodeFactory.instance.objectNode();
        models.putObject("pd").put("modelId", "retail_pd").put("modelVersion", "3.1.0").putObject("outputs")
                .put("pd", "0.031");
        ApplicationFailure rejected = failureOf(client, request.withModels(models));
        assertThat(rejected.getType()).isEqualTo(RuleStoneFailures.DECISION_EVALUATION_FAILED);
        assertThat(rejected.getOriginalMessage())
                .endsWith("model results supplied for [pd], but a decision table has no model nodes");
        assertThat(request.withModels(models).models()).isEqualTo(models);
        assertThat(request.models()).isNull();
    }

    @Test
    void unexpectedErrorsAreRetried() {
        RuleStoneEngine broken = (ref, facts) -> {
            throw new IllegalStateException("rule store unavailable");
        };
        WorkflowClient client = start(broken, clockAt("2026-11-15"));

        ApplicationFailure failure = failureOf(client, DecisionRequest.of("loan_eligibility",
                Facts.of("applicant", adult("20000000", "0"), "amount", new BigDecimal("1")))
                .withAsOf(LocalDate.of(2026, 11, 15)));

        assertThat(failure.getType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.isNonRetryable()).isFalse();
        assertThat(activities.calls).hasValue(ViaActivityImpl.MAX_ATTEMPTS);
    }
}
