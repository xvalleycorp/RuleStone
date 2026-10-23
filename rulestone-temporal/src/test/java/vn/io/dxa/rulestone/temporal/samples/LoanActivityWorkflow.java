//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import vn.io.dxa.rulestone.temporal.DecisionOutcome;

/** Decides a loan application by calling the RuleStone activity. */
@WorkflowInterface
public interface LoanActivityWorkflow {

    @WorkflowMethod
    DecisionOutcome decide(LoanApplication application);
}
