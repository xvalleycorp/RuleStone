//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import io.temporal.failure.ApplicationFailure;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;

/**
 * Stable {@link ApplicationFailure#getType() failure types} for RuleStone errors in Temporal.
 *
 * <p>Both {@link RuleStoneActivities} and {@link RuleStoneWorkflow} report these failures as
 * <strong>non-retryable</strong>: evaluation is deterministic, so retrying the same request gives the
 * same error. Messages and details name the decision, rule version, rule and input involved, never
 * input values. The causing exception is not attached, so no stack trace or nested message reaches
 * the workflow history.
 *
 * <p>From an activity the failure arrives in the workflow as the cause of an
 * {@link io.temporal.failure.ActivityFailure}; from {@link RuleStoneWorkflow} it is thrown directly.
 * Compare {@link ApplicationFailure#getType()} with these constants.
 */
public final class RuleStoneFailures {

    /**
     * No decision with that id, no such version, or no version effective on the business date:
     * a configuration error. Details: none.
     */
    public static final String DECISION_NOT_FOUND = "RuleStone.DecisionNotFound";

    /**
     * The decision could not be evaluated: invalid or missing input, a failing rule expression, or a
     * {@code UNIQUE} violation. Details: decision id, then rule version
     * ({@code <version>@sha256:<hash>}). Do not substitute a default decision; route the case to manual
     * review.
     */
    public static final String DECISION_EVALUATION_FAILED = "RuleStone.DecisionEvaluationFailed";

    /**
     * The activity request is malformed: a fact the decision does not declare, a value not in the
     * expected JSON form, or a {@code null} fact. Details: none.
     */
    public static final String INVALID_REQUEST = "RuleStone.InvalidRequest";

    private RuleStoneFailures() {
    }

    static ApplicationFailure decisionNotFound(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, DECISION_NOT_FOUND);
    }

    static ApplicationFailure decisionNotFound(DecisionNotFoundException e) {
        return decisionNotFound(e.getMessage());
    }

    static ApplicationFailure evaluationFailed(DecisionEvaluationException e) {
        return ApplicationFailure.newNonRetryableFailure(
                e.getMessage(), DECISION_EVALUATION_FAILED, e.decisionId(), e.ruleVersion());
    }

    static ApplicationFailure invalidRequest(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, INVALID_REQUEST);
    }
}
