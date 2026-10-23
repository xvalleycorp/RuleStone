//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.temporal.samples.LoanApplication;

/** Workflows used only by the tests. */
final class TestWorkflows {

    private TestWorkflows() {
    }

    /** Passes a request to the activity, with fast, bounded retries. */
    @WorkflowInterface
    public interface ViaActivity {
        @WorkflowMethod
        DecisionOutcome evaluate(DecisionRequest request);
    }

    public static class ViaActivityImpl implements ViaActivity {

        static final int MAX_ATTEMPTS = 3;

        private final RuleStoneActivities rules = Workflow.newActivityStub(RuleStoneActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setMaximumAttempts(MAX_ATTEMPTS)
                                .build())
                        .build());

        @Override
        public DecisionOutcome evaluate(DecisionRequest request) {
            return rules.evaluate(request);
        }
    }

    /** Counts activity executions, to prove that failures were not retried. */
    public static final class CountingActivities implements RuleStoneActivities {

        final AtomicInteger calls = new AtomicInteger();
        private final RuleStoneActivities delegate;

        CountingActivities(RuleStoneActivities delegate) {
            this.delegate = delegate;
        }

        @Override
        public DecisionOutcome evaluate(DecisionRequest request) {
            calls.incrementAndGet();
            return delegate.evaluate(request);
        }
    }

    /** Decides twice, fifteen days apart in workflow time. */
    @WorkflowInterface
    public interface TwoDates {
        @WorkflowMethod
        List<DecisionOutcome> decideTwice(LoanApplication application);
    }

    public static class TwoDatesImpl implements TwoDates {

        private final RuleStoneWorkflow rules;

        TwoDatesImpl(RuleStoneEngine engine, ZoneId zone) {
            this.rules = RuleStoneWorkflow.of(engine, zone);
        }

        @Override
        public List<DecisionOutcome> decideTwice(LoanApplication application) {
            Facts facts = Facts.of("applicant", application.applicant(), "amount", application.amount());
            DecisionOutcome first = rules.evaluate(DecisionRef.of("loan_eligibility"), facts);
            Workflow.sleep(Duration.ofDays(15));
            DecisionOutcome second = rules.evaluate(DecisionRef.of("loan_eligibility"), facts);
            return List.of(first, second);
        }
    }

    /** Card fee inputs as typed workflow data. */
    public record CardFeeInput(LocalDate birthDate, Money spend, BigDecimal rate) {
    }

    /** Evaluates {@code card_fee} inside the workflow. */
    @WorkflowInterface
    public interface CardFee {
        @WorkflowMethod
        DecisionOutcome fee(CardFeeInput input);
    }

    public static class CardFeeImpl implements CardFee {

        private final RuleStoneWorkflow rules;

        CardFeeImpl(RuleStoneEngine engine, ZoneId zone) {
            this.rules = RuleStoneWorkflow.of(engine, zone);
        }

        @Override
        public DecisionOutcome fee(CardFeeInput input) {
            return rules.evaluate(DecisionRef.of("card_fee"), Facts.of(
                    "customer", java.util.Map.of("birthDate", input.birthDate()),
                    "spend", input.spend(),
                    "rate", input.rate()));
        }
    }
}
