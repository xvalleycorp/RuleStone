//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Evaluates RuleStone decisions as a Temporal activity. The activity type is
 * {@code RuleStoneEvaluate}.
 *
 * <p>Use the activity style when the workflow should not load rules itself: the workflow is written
 * in another language, the rules live with a dedicated worker, or you want each decision's inputs and
 * outcome visible as an activity in workflow history. Implemented by {@link RuleStoneActivitiesImpl}.
 *
 * <p>Errors: {@link RuleStoneFailures#DECISION_NOT_FOUND}, {@link RuleStoneFailures#DECISION_EVALUATION_FAILED}
 * and {@link RuleStoneFailures#INVALID_REQUEST} are non-retryable application failures; any other
 * error is a normal, retryable activity failure. Recommended options: a start-to-close timeout of a
 * few seconds (evaluation does no I/O), and a retry policy with a maximum interval and a maximum
 * number of attempts, so an unexpected error does not retry forever. See {@code docs/temporal.md}.
 */
@ActivityInterface(namePrefix = "RuleStone")
public interface RuleStoneActivities {

    /**
     * Evaluates one decision.
     *
     * @param request the decision, version or business date, and facts in JSON forms
     * @return the outcome, including the resolved business date and exact rule version
     */
    @ActivityMethod
    DecisionOutcome evaluate(DecisionRequest request);
}
