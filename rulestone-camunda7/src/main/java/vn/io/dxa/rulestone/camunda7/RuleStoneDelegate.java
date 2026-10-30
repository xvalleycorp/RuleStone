//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.util.Optional;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * A service task that evaluates a RuleStone decision and stores the result in a process variable.
 *
 * <pre>{@code
 * <serviceTask id="CheckEligibility" camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate">
 *   <extensionElements>
 *     <camunda:field name="decisionId" stringValue="loan_eligibility" />
 *     <camunda:field name="asOfVariable" stringValue="applicationDate" />
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <p>Fields, each a string or an expression:
 *
 * <table>
 *   <caption>Fields</caption>
 *   <tr><th>Field</th><th>Required</th><th>Meaning</th></tr>
 *   <tr><td>{@code decisionId}</td><td>yes</td><td>The decision to evaluate.</td></tr>
 *   <tr><td>{@code version}</td><td>no</td><td>An exact version label; otherwise the version effective
 *       on the business date.</td></tr>
 *   <tr><td>{@code asOfVariable}</td><td>no</td><td>Variable holding the business date; otherwise today
 *       according to the service's clock.</td></tr>
 *   <tr><td>{@code modelsVariable}</td><td>no</td><td>Variable holding supplied model results, a JSON
 *       object of model node id to result (see {@code FactsJson.readModels}); otherwise none.</td></tr>
 *   <tr><td>{@code resultVariable}</td><td>no</td><td>Default {@code decision}.</td></tr>
 *   <tr><td>{@code failOnNoMatch}</td><td>no</td><td>{@code true} to throw the BPMN error when no rule
 *       matches; default {@code false}.</td></tr>
 *   <tr><td>{@code errorCode}</td><td>no</td><td>BPMN error code; default
 *       {@code RULESTONE_DECISION_FAILED}.</td></tr>
 * </table>
 *
 * <p>The result is described in {@link RuleStoneDecisionService}. It is stored as a Java-serialized
 * object, whatever the engine's default serialization format, because JSON formats read decimals back
 * as floating point. Failures: see {@link RuleStoneDecisionService}; invalid fields also throw
 * {@link RuleStoneConfigurationException}.
 *
 * <p>With {@code camunda:class}, the engine creates an instance per execution, and the no-argument
 * constructor finds the service registered for the executing engine by
 * {@link RuleStoneProcessEnginePlugin}. With Spring and {@code camunda:delegateExpression}, create the
 * delegate with {@link #RuleStoneDelegate(RuleStoneDecisionService)} as a <em>prototype</em> bean:
 * field injection on a shared singleton is not thread-safe.
 */
public class RuleStoneDelegate implements JavaDelegate {

    private final RuleStoneDecisionService service;

    private Expression decisionId;
    private Expression version;
    private Expression asOfVariable;
    private Expression modelsVariable;
    private Expression resultVariable;
    private Expression failOnNoMatch;
    private Expression errorCode;

    /** Creates a delegate that uses the service registered for the executing process engine. */
    public RuleStoneDelegate() {
        this.service = null;
    }

    /**
     * Creates a delegate that uses {@code service}.
     *
     * @param service the decision service
     */
    public RuleStoneDelegate(RuleStoneDecisionService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    /**
     * Sets the decision to evaluate. Required.
     *
     * @param decisionId the decision id
     */
    public void setDecisionId(Expression decisionId) {
        this.decisionId = decisionId;
    }

    /**
     * Sets an exact version label.
     *
     * @param version the version label
     */
    public void setVersion(Expression version) {
        this.version = version;
    }

    /**
     * Sets the name of the variable holding the business date.
     *
     * @param asOfVariable the variable name
     */
    public void setAsOfVariable(Expression asOfVariable) {
        this.asOfVariable = asOfVariable;
    }

    /**
     * Sets the name of the variable holding supplied model results.
     *
     * @param modelsVariable the variable name
     */
    public void setModelsVariable(Expression modelsVariable) {
        this.modelsVariable = modelsVariable;
    }

    /**
     * Sets the variable the result is written to.
     *
     * @param resultVariable the variable name
     */
    public void setResultVariable(Expression resultVariable) {
        this.resultVariable = resultVariable;
    }

    /**
     * Sets whether "no rule matched" throws the BPMN error.
     *
     * @param failOnNoMatch {@code true} or {@code false}
     */
    public void setFailOnNoMatch(Expression failOnNoMatch) {
        this.failOnNoMatch = failOnNoMatch;
    }

    /**
     * Sets the BPMN error code.
     *
     * @param errorCode the error code
     */
    public void setErrorCode(Expression errorCode) {
        this.errorCode = errorCode;
    }

    /**
     * Evaluates the decision and stores the result.
     *
     * @param execution the execution
     * @throws BpmnError                        if the decision fails on this case's data
     * @throws RuleStoneConfigurationException if the task or the deployment is misconfigured
     */
    @Override
    public void execute(DelegateExecution execution) {
        RuleStoneDecisionService decisions = service != null ? service
                : RuleStoneRegistry.find(execution.getProcessEngine().getName()).orElseThrow(() ->
                        new RuleStoneConfigurationException("RuleStone: no decision service is registered for process"
                                + " engine '" + execution.getProcessEngine().getName()
                                + "'; add RuleStoneProcessEnginePlugin to its configuration"));
        DecisionCall call = new DecisionCall(
                field(decisionId, "decisionId", execution)
                        .orElseThrow(() -> invalid("the field 'decisionId' is required", execution)),
                field(version, "version", execution),
                field(asOfVariable, "asOfVariable", execution),
                field(modelsVariable, "modelsVariable", execution),
                field(resultVariable, "resultVariable", execution)
                        .orElse(RuleStoneDecisionService.DEFAULT_RESULT_VARIABLE),
                bool(field(failOnNoMatch, "failOnNoMatch", execution), execution),
                field(errorCode, "errorCode", execution).orElse(RuleStoneDecisionService.DEFAULT_ERROR_CODE));
        execution.setVariable(call.resultVariable(),
                RuleStoneDecisionService.asVariable(decisions.evaluate(call, execution)));
    }

    private static Optional<String> field(Expression expression, String name, DelegateExecution execution) {
        if (expression == null) {
            return Optional.empty();
        }
        Object value = expression.getValue(execution);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String) && !(value instanceof Boolean)) {
            throw invalid("the field '" + name + "' must be a string, not " + value.getClass().getSimpleName(), execution);
        }
        String text = value.toString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static boolean bool(Optional<String> value, DelegateExecution execution) {
        return switch (value.orElse("false")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("the field 'failOnNoMatch' must be 'true' or 'false'", execution);
        };
    }

    private static RuleStoneConfigurationException invalid(String problem, DelegateExecution execution) {
        return new RuleStoneConfigurationException("invalid RuleStone task configuration: " + problem
                + " [activity '" + execution.getCurrentActivityId() + "']");
    }
}
