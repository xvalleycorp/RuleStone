//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

/**
 * Names and defaults of the custom task headers that configure a RuleStone service task.
 *
 * <p>Headers are strings. A header that is absent or blank takes its default.
 *
 * <table>
 *   <caption>Task headers</caption>
 *   <tr><th>Header</th><th>Required</th><th>Meaning</th></tr>
 *   <tr><td>{@value #DECISION_ID}</td><td>yes</td><td>The decision to evaluate.</td></tr>
 *   <tr><td>{@value #VERSION}</td><td>no</td><td>An exact version label; otherwise the version
 *       effective on the business date.</td></tr>
 *   <tr><td>{@value #AS_OF_VARIABLE}</td><td>no</td><td>Name of a process variable holding the
 *       business date as {@code "yyyy-MM-dd"}; otherwise today according to the worker's clock.</td></tr>
 *   <tr><td>{@value #MODELS_VARIABLE}</td><td>no</td><td>Name of a process variable holding
 *       supplied model results, as a JSON object of model node id to result (see
 *       {@code FactsJson.readModels}); otherwise none.</td></tr>
 *   <tr><td>{@value #RESULT_VARIABLE}</td><td>no</td><td>Variable the result is written to; default
 *       {@value #DEFAULT_RESULT_VARIABLE}.</td></tr>
 *   <tr><td>{@value #FAIL_ON_NO_MATCH}</td><td>no</td><td>{@code true} to throw the BPMN error when
 *       no rule matches; default {@code false}.</td></tr>
 *   <tr><td>{@value #ERROR_CODE}</td><td>no</td><td>Code of the BPMN error thrown when the decision
 *       fails; default {@value #DEFAULT_ERROR_CODE}, or the worker's configured default.</td></tr>
 * </table>
 */
public final class RuleStoneHeaders {

    /** Header naming the decision to evaluate. Required. */
    public static final String DECISION_ID = "decisionId";

    /** Header with an exact version label. */
    public static final String VERSION = "version";

    /** Header naming the process variable that holds the business date. */
    public static final String AS_OF_VARIABLE = "asOfVariable";

    /**
     * Header naming the process variable that holds supplied model results, for the {@code model}
     * nodes of a decision flow in {@code supplied} mode.
     */
    public static final String MODELS_VARIABLE = "modelsVariable";

    /** Header naming the variable the result is written to. */
    public static final String RESULT_VARIABLE = "resultVariable";

    /** Header that turns "no rule matched" into a BPMN error: {@code true} or {@code false}. */
    public static final String FAIL_ON_NO_MATCH = "failOnNoMatch";

    /** Header with the code of the BPMN error thrown when the decision fails. */
    public static final String ERROR_CODE = "errorCode";

    /** Default of the result variable header. */
    public static final String DEFAULT_RESULT_VARIABLE = "decision";

    /** Default of the error code header. */
    public static final String DEFAULT_ERROR_CODE = "RULESTONE_DECISION_FAILED";

    private RuleStoneHeaders() {
    }
}
