//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.util.Objects;
import java.util.Optional;

/**
 * What one task or expression asks for.
 *
 * @param decisionId     the decision to evaluate
 * @param version        an exact version label
 * @param asOfVariable   the variable holding the business date
 * @param modelsVariable the variable holding supplied model results
 * @param resultVariable the variable the delegate writes the result to
 * @param failOnNoMatch  whether "no rule matched" throws the BPMN error
 * @param errorCode      the BPMN error code
 */
record DecisionCall(
        String decisionId,
        Optional<String> version,
        Optional<String> asOfVariable,
        Optional<String> modelsVariable,
        String resultVariable,
        boolean failOnNoMatch,
        String errorCode) {

    DecisionCall {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asOfVariable, "asOfVariable");
        Objects.requireNonNull(modelsVariable, "modelsVariable");
        Objects.requireNonNull(resultVariable, "resultVariable");
        Objects.requireNonNull(errorCode, "errorCode");
    }

    static DecisionCall of(String decisionId, Optional<String> asOfVariable) {
        return new DecisionCall(decisionId, Optional.empty(), asOfVariable, Optional.empty(),
                RuleStoneDecisionService.DEFAULT_RESULT_VARIABLE, false, RuleStoneDecisionService.DEFAULT_ERROR_CODE);
    }
}
