//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The configuration of one service task, read from its custom headers.
 *
 * @param decisionId    the decision to evaluate
 * @param version       an exact version label
 * @param asOfVariable  the variable holding the business date
 * @param resultVariable the variable the result is written to
 * @param failOnNoMatch whether "no rule matched" throws the BPMN error
 * @param errorCode     the BPMN error code
 */
record DecisionTask(
        String decisionId,
        Optional<String> version,
        Optional<String> asOfVariable,
        String resultVariable,
        boolean failOnNoMatch,
        String errorCode) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Reads the headers. Header values are configuration, not process data, so messages may quote them.
     *
     * @throws TaskFailure an incident, if a header is missing or invalid
     */
    static DecisionTask fromHeaders(Map<String, String> headers, String defaultErrorCode) {
        String decisionId = header(headers, RuleStoneHeaders.DECISION_ID)
                .orElseThrow(() -> invalid("missing header '" + RuleStoneHeaders.DECISION_ID + "'"));
        String resultVariable = header(headers, RuleStoneHeaders.RESULT_VARIABLE)
                .orElse(RuleStoneHeaders.DEFAULT_RESULT_VARIABLE);
        if (!NAME.matcher(resultVariable).matches()) {
            throw invalid("header '" + RuleStoneHeaders.RESULT_VARIABLE + "' must be a variable name"
                    + " (letters, digits and '_', not starting with a digit), was '" + resultVariable + "'");
        }
        Optional<String> asOfVariable = header(headers, RuleStoneHeaders.AS_OF_VARIABLE);
        boolean failOnNoMatch = header(headers, RuleStoneHeaders.FAIL_ON_NO_MATCH)
                .map(DecisionTask::bool)
                .orElse(false);
        String errorCode = header(headers, RuleStoneHeaders.ERROR_CODE).orElse(defaultErrorCode);
        return new DecisionTask(decisionId, header(headers, RuleStoneHeaders.VERSION), asOfVariable,
                resultVariable, failOnNoMatch, errorCode);
    }

    private static Optional<String> header(Map<String, String> headers, String name) {
        String value = headers == null ? null : headers.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static boolean bool(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("header '" + RuleStoneHeaders.FAIL_ON_NO_MATCH
                    + "' must be 'true' or 'false', was '" + value + "'");
        };
    }

    private static TaskFailure invalid(String problem) {
        return TaskFailure.incident("invalid RuleStone task configuration: " + problem
                + ". Fix the task headers in the BPMN model; retrying will not help.");
    }
}
