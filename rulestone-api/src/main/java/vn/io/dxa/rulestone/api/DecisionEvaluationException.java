//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * A decision could not be evaluated: invalid input, a failing rule expression, or a
 * hit-policy violation. The decision produced no result; callers must not fall back
 * to a guessed outcome.
 */
public final class DecisionEvaluationException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    private final String decisionId;
    private final String ruleVersion;

    public DecisionEvaluationException(String decisionId, String ruleVersion, String message, Throwable cause) {
        super(decisionId + "@" + ruleVersion + ": " + message, cause);
        this.decisionId = decisionId;
        this.ruleVersion = ruleVersion;
    }

    public String decisionId() {
        return decisionId;
    }

    public String ruleVersion() {
        return ruleVersion;
    }
}
