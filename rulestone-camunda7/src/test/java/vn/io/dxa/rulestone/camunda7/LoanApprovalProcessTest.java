//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.camunda7.TestSupport.SECRET_DEBT;
import static vn.io.dxa.rulestone.camunda7.TestSupport.SECRET_INCOME;
import static vn.io.dxa.rulestone.camunda7.TestSupport.applicant;
import static vn.io.dxa.rulestone.camunda7.TestSupport.loanVariables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the sample processes on a real, in-memory Camunda 7 engine. */
class LoanApprovalProcessTest {

    private static final String ENGINE = "rulestone-camunda7-test";

    private static ProcessEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = TestSupport.processEngine(ENGINE);
        engine.getRepositoryService().createDeployment()
                .addClasspathResource("loan-approval.bpmn")
                .addClasspathResource("loan-approval-expression.bpmn")
                .addClasspathResource("decision-by-name.bpmn")
                .deploy();
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
        RuleStoneRegistry.unregister(ENGINE);
    }

    private static ProcessInstance start(String process, Map<String, Object> variables) {
        return engine.getRuntimeService().startProcessInstanceByKey(process, variables);
    }

    private static String waitingAt(ProcessInstance instance) {
        return engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult()
                .getTaskDefinitionKey();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decision(ProcessInstance instance) {
        return (Map<String, Object>) engine.getRuntimeService().getVariable(instance.getId(), "decision");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(ProcessInstance instance) {
        return (Map<String, Object>) decision(instance).get("output");
    }

    @Test
    void approvesThroughTheGateway() {
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(35, new BigDecimal("25750000"), new BigDecimal("5000000")), "2026-11-05"));

        assertThat(waitingAt(instance)).isEqualTo("Disburse");
        Map<String, Object> decision = decision(instance);
        assertThat(decision).containsOnlyKeys("output", "reasonCodes", "matchedRules", "ruleVersion", "matched");
        assertThat(output(instance)).containsEntry("decision", "APPROVE");
        assertThat(output(instance).get("limit")).isEqualTo(new BigDecimal("309000000"));
        assertThat(decision.get("matchedRules")).isEqualTo(List.of("R4"));
        assertThat((String) decision.get("ruleVersion")).matches("3@sha256:[0-9a-f]{64}");
        assertThat(decision.get("matched")).isEqualTo(true);
    }

    @Test
    void theResultIsAJavaSerializedObjectThatKeepsDecimalsExact() {
        // Decimals as text and integers as Integer are accepted too.
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(35, "25750000.55", 12875000), "2026-11-05"));

        ObjectValue stored = engine.getRuntimeService().getVariableTyped(instance.getId(), "decision", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/x-java-serialized-object");
        // 25750000.55 * 12 = 309000006.60, rounded down to the million.
        assertThat(output(instance).get("limit")).isEqualTo(new BigDecimal("309000000"));
    }

    @Test
    void rejectsThroughTheGateway() {
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(17, new BigDecimal("25750000"), BigDecimal.ZERO), "2026-11-05"));

        assertThat(waitingAt(instance)).isEqualTo("NotifyRejection");
        assertThat(decision(instance).get("reasonCodes")).isEqualTo(List.of("UNDERAGE"));
    }

    @Test
    void referralsTakeTheDefaultFlow() {
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(35, new BigDecimal("10000000"), BigDecimal.ZERO), "2026-11-05"));

        assertThat(waitingAt(instance)).isEqualTo("ManualReview");
    }

    @Test
    void theAsOfVariableSelectsTheVersion() {
        Map<String, Object> variables = loanVariables(applicant(35, new BigDecimal("60000000"), BigDecimal.ZERO), null);
        variables.put("applicationDate", LocalDate.of(2026, 10, 15));

        ProcessInstance instance = start("loan-approval", variables);

        assertThat((String) decision(instance).get("ruleVersion")).startsWith("2@sha256:");
        assertThat(output(instance).get("limit")).isEqualTo(new BigDecimal("360000000"));
    }

    @Test
    void anEvaluationErrorIsCaughtByTheBoundaryEvent() {
        // Income 0: the debt-to-income rule divides by zero.
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(35, BigDecimal.ZERO, new BigDecimal(SECRET_DEBT)), "2026-11-05"));

        assertThat(waitingAt(instance)).isEqualTo("InvestigateFailure");
        String message = (String) engine.getRuntimeService().getVariable(instance.getId(), "decisionError");
        assertThat(message).startsWith("RuleStone decision failed: loan_eligibility@3@sha256:").doesNotContain(SECRET_DEBT);
    }

    @Test
    void aDoubleIsRejectedNamingTheVariable() {
        ProcessInstance instance = start("loan-approval",
                loanVariables(applicant(35, 25750123.0d, new BigDecimal(SECRET_DEBT)), "2026-11-05"));

        assertThat(waitingAt(instance)).isEqualTo("InvestigateFailure");
        String message = (String) engine.getRuntimeService().getVariable(instance.getId(), "decisionError");
        assertThat(message)
                .isEqualTo("RuleStone decision loan_eligibility@3: invalid process variables: "
                        + "applicant.monthlyIncome: floating-point numbers are not supported; use BigDecimal")
                .doesNotContain(SECRET_INCOME, SECRET_DEBT);
    }

    @Test
    void aMissingInputIsABpmnError() {
        Map<String, Object> variables = loanVariables(applicant(35, BigDecimal.ONE, BigDecimal.ONE), "2026-11-05");
        variables.remove("amount");

        ProcessInstance instance = start("loan-approval", variables);

        assertThat(waitingAt(instance)).isEqualTo("InvestigateFailure");
        assertThat((String) engine.getRuntimeService().getVariable(instance.getId(), "decisionError")).contains("amount");
    }

    @Test
    void anExpressionCanCallADecisionFromAGateway() {
        ProcessInstance approved = start("loan-approval-expression",
                loanVariables(applicant(35, new BigDecimal("25750000"), BigDecimal.ZERO), "2026-11-05"));
        ProcessInstance referred = start("loan-approval-expression",
                loanVariables(applicant(35, new BigDecimal("1000000"), BigDecimal.ZERO), "2026-11-05"));

        assertThat(waitingAt(approved)).isEqualTo("Disburse");
        assertThat(waitingAt(referred)).isEqualTo("ManualReview");
    }

    @Test
    void anUnknownDecisionFailsTheCallerWhenSynchronous() {
        assertThatThrownBy(() -> start("decision-by-name", Map.of("decisionToUse", "loan_eligibilty")))
                .isInstanceOf(RuleStoneConfigurationException.class)
                .hasMessageStartingWith("RuleStone decision not found: unknown decision 'loan_eligibilty'.");
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("decision-by-name")
                .count()).isZero();
    }

    @Test
    void anUnknownDecisionInAnAsyncTaskBecomesAnIncident() {
        ProcessInstance instance = start("decision-by-name-async", Map.of("decisionToUse", "loan_eligibilty"));
        Job job = engine.getManagementService().createJobQuery().processInstanceId(instance.getId()).singleResult();

        assertThatThrownBy(() -> engine.getManagementService().executeJob(job.getId()))
                .isInstanceOf(RuleStoneConfigurationException.class);

        Incident incident = engine.getRuntimeService().createIncidentQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(incident).isNotNull();
        assertThat(incident.getIncidentMessage()).startsWith("RuleStone decision not found: unknown decision");
    }

    @Test
    void noMatchIsABpmnErrorWhenTheTaskFailsOnNoMatch() {
        // Without a boundary event, an uncaught BPMN error ends the process instance.
        ProcessInstance instance = start("decision-by-name", Map.of("decisionToUse", "risk_band", "score", 720));

        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instance.getId())
                .count()).isZero();
    }

    @Test
    void aMatchCompletesTheTask() {
        ProcessInstance instance = start("decision-by-name", Map.of("decisionToUse", "risk_band", "score", 420));

        assertThat(waitingAt(instance)).isEqualTo("Done");
        assertThat(output(instance)).containsEntry("band", "HIGH");
    }
}
