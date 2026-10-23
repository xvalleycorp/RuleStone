//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.temporal.DecisionOutcome;
import vn.io.dxa.rulestone.temporal.DecisionRequest;
import vn.io.dxa.rulestone.temporal.RuleStoneActivities;
import vn.io.dxa.rulestone.temporal.RuleStoneFailures;

/** Activity style, with the recommended activity options and error handling. */
public class LoanActivityWorkflowImpl implements LoanActivityWorkflow {

    static final ActivityOptions RULESTONE_OPTIONS = ActivityOptions.newBuilder()
            // Evaluation does no I/O: a short timeout detects a lost worker quickly.
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryPolicy(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setMaximumInterval(Duration.ofMinutes(1))
                    // Bounded: an unexpected error is a bug or an outage; do not retry forever.
                    .setMaximumAttempts(10)
                    // RuleStone failures are non-retryable already; this states it in the policy.
                    .setDoNotRetry(RuleStoneFailures.DECISION_NOT_FOUND,
                            RuleStoneFailures.DECISION_EVALUATION_FAILED,
                            RuleStoneFailures.INVALID_REQUEST)
                    .build())
            .build();

    private final RuleStoneActivities rules =
            Workflow.newActivityStub(RuleStoneActivities.class, RULESTONE_OPTIONS);

    @Override
    public DecisionOutcome decide(LoanApplication application) {
        DecisionRequest request = DecisionRequest
                .of("loan_eligibility",
                        Facts.of("applicant", application.applicant(), "amount", application.amount()))
                .withAsOf(application.applicationDate());
        try {
            return rules.evaluate(request);
        } catch (ActivityFailure e) {
            if (e.getCause() instanceof ApplicationFailure failure
                    && RuleStoneFailures.DECISION_EVALUATION_FAILED.equals(failure.getType())) {
                // Never substitute a default decision: hand the case to a person.
                return manualReview(application, failure);
            }
            throw e;
        }
    }

    private DecisionOutcome manualReview(LoanApplication application, ApplicationFailure failure) {
        // A real workflow would start a human task here; this sample fails the workflow.
        throw failure;
    }
}
