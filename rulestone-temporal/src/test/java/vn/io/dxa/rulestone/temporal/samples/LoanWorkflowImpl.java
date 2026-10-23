//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import java.time.ZoneId;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.temporal.DecisionOutcome;
import vn.io.dxa.rulestone.temporal.RuleStoneSearchAttributes;
import vn.io.dxa.rulestone.temporal.RuleStoneWorkflow;

/**
 * In-workflow style. The business date is the workflow's own time in the bank's zone, so the
 * rule version in force when the workflow runs is pinned in history.
 */
public class LoanWorkflowImpl implements LoanWorkflow {

    private final RuleStoneWorkflow rules;

    public LoanWorkflowImpl(RuleStoneEngine engine, ZoneId businessZone) {
        this.rules = RuleStoneWorkflow.of(engine, businessZone);
    }

    @Override
    public DecisionOutcome decide(LoanApplication application) {
        DecisionOutcome outcome = rules.evaluate(
                DecisionRef.of("loan_eligibility"),
                Facts.of("applicant", application.applicant(), "amount", application.amount()));
        RuleStoneSearchAttributes.upsert(outcome, "decision");
        return outcome;
    }
}
