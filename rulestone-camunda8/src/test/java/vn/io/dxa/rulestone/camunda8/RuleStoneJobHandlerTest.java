//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static vn.io.dxa.rulestone.camunda8.TestSupport.SECRET_DEBT;
import static vn.io.dxa.rulestone.camunda8.TestSupport.SECRET_INCOME;
import static vn.io.dxa.rulestone.camunda8.TestSupport.job;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.TraceStep;

class RuleStoneJobHandlerTest {

    private static final String LOAN_HEADERS_ID = "loan_eligibility";

    private final RuleStoneJobHandler handler = new RuleStoneJobHandler(TestSupport.engine(), TestSupport.tables(),
            RuleStoneWorkerOptions.builder().clock(TestSupport.CLOCK).retryBackoff(Duration.ofSeconds(30)).build());

    private final TestSupport.Client client = new TestSupport.Client();

    private static Map<String, String> headers(String... keyValues) {
        Map<String, String> headers = new HashMap<>();
        headers.put(RuleStoneHeaders.DECISION_ID, LOAN_HEADERS_ID);
        for (int i = 0; i < keyValues.length; i += 2) {
            headers.put(keyValues[i], keyValues[i + 1]);
        }
        return headers;
    }

    private static String applicant(String income, String debt) {
        return """
                {"age": 35, "monthlyIncome": %s, "monthlyDebt": %s, "segment": "MASS", "blacklisted": false}"""
                .formatted(income, debt);
    }

    private static String loanVariables(String income, String debt) {
        return """
                {"applicant": %s, "amount": "300000000", "applicationDate": "2026-11-05",
                 "customerName": "Nguyen Van A", "score": 0.1234567890123456789, "note": null}"""
                .formatted(applicant(income, debt));
    }

    private JsonNode completed() throws Exception {
        ArgumentCaptor<String> variables = ArgumentCaptor.forClass(String.class);
        verify(client.complete).variables(variables.capture());
        verify(client.client, never()).newFailCommand(org.mockito.ArgumentMatchers.any(ActivatedJob.class));
        verify(client.client, never()).newThrowErrorCommand(org.mockito.ArgumentMatchers.any(ActivatedJob.class));
        return JobVariables.MAPPER.readTree(variables.getValue());
    }

    private String bpmnError(String expectedCode) {
        verify(client.error).errorCode(expectedCode);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client.errorWithCode).errorMessage(message.capture());
        verify(client.client, never()).newCompleteCommand(org.mockito.ArgumentMatchers.any(ActivatedJob.class));
        assertThat(message.getValue()).doesNotContain(SECRET_INCOME, SECRET_DEBT, "Nguyen");
        return message.getValue();
    }

    private String failure(int expectedRetries) {
        verify(client.fail).retries(expectedRetries);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client.failWithRetries).errorMessage(message.capture());
        verify(client.client, never()).newCompleteCommand(org.mockito.ArgumentMatchers.any(ActivatedJob.class));
        assertThat(message.getValue()).doesNotContain(SECRET_INCOME, SECRET_DEBT, "Nguyen");
        return message.getValue();
    }

    // --- success -----------------------------------------------------------------------------

    @Test
    void completesWithTheResultInJsonForms() throws Exception {
        handler.handle(client.client, job(headers(RuleStoneHeaders.AS_OF_VARIABLE, "applicationDate"),
                loanVariables("\"25750000\"", "\"5000000\"")));

        JsonNode result = completed();
        assertThat(result.properties()).hasSize(1);
        JsonNode decision = result.get(RuleStoneHeaders.DEFAULT_RESULT_VARIABLE);
        assertThat(decision.get("output").get("decision").asText()).isEqualTo("APPROVE");
        // A decimal is a string in plain notation: exact, whatever Zeebe does with numbers.
        assertThat(decision.get("output").get("limit").isTextual()).isTrue();
        assertThat(decision.get("output").get("limit").asText()).isEqualTo("309000000");
        assertThat(decision.get("matchedRules").toString()).isEqualTo("[\"R4\"]");
        assertThat(decision.get("reasonCodes").toString()).isEqualTo("[]");
        assertThat(decision.get("matched").asBoolean()).isTrue();
        assertThat(decision.get("ruleVersion").asText()).matches("3@sha256:[0-9a-f]{64}");
        assertThat(fieldNames(decision)).containsExactly("output", "reasonCodes", "matchedRules", "ruleVersion", "matched");
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    @Test
    void writesTheResultToTheConfiguredVariable() throws Exception {
        handler.handle(client.client, job(headers(RuleStoneHeaders.RESULT_VARIABLE, "eligibility"),
                loanVariables("25750000.50", "5000000")));

        assertThat(fieldNames(completed())).containsExactly("eligibility");
    }

    @Test
    void readsFractionalNumbersExactlyFromTheirJsonText() throws Exception {
        // Half of 25750000.55 is 12875000.275: a debt one cent above it is just over 50% of income.
        handler.handle(client.client, job(headers(), loanVariables("25750000.55", "12875000.28")));

        JsonNode decision = completed().get("decision");
        assertThat(decision.get("output").get("decision").asText()).isEqualTo("REJECT");
        assertThat(decision.get("reasonCodes").toString()).isEqualTo("[\"DTI_HIGH\"]");
    }

    @Test
    void moneyAndDateOutputsAreStrings() throws Exception {
        handler.handle(client.client, job(Map.of(RuleStoneHeaders.DECISION_ID, "card_fee"), """
                {"customer": {"birthDate": "1980-02-29"}, "spend": "1500000.50 VND", "rate": "0.02"}"""));

        JsonNode output = completed().get("decision").get("output");
        assertThat(output.get("fee").asText()).isEqualTo("30000.0100 VND");
        assertThat(output.get("nextReview").asText()).isEqualTo("2027-05-05");
        assertThat(output.get("tier").asText()).isEqualTo("STANDARD");
    }

    @Test
    void theCompletionPayloadKeepsDecimalsExact() throws Exception {
        Map<String, Object> rate = Map.of("rate", new BigDecimal("0.123456789012345678901234567890"));
        DecisionResult result = new DecisionResult("rate", "1@sha256:" + "0".repeat(64), rate,
                List.of(), List.of(new TraceStep("R1", true)), testRecord("rate", "1@sha256:" + "0".repeat(64), rate));

        String json = RuleStoneJobHandler.completionVariables("decision", result);

        assertThat(json).contains("\"rate\":\"0.123456789012345678901234567890\"");
        assertThat(new BigDecimal(JobVariables.MAPPER.readTree(json).get("decision").get("output").get("rate").asText()))
                .isEqualTo(new BigDecimal("0.123456789012345678901234567890"));
    }

    // --- versions and dates ------------------------------------------------------------------

    @Test
    void theAsOfVariableSelectsTheVersionInForceOnThatDate() throws Exception {
        String variables = loanVariables("\"25750000\"", "\"5000000\"").replace("2026-11-05", "2026-10-15");

        handler.handle(client.client, job(headers(RuleStoneHeaders.AS_OF_VARIABLE, "applicationDate"), variables));

        assertThat(completed().get("decision").get("ruleVersion").asText()).startsWith("2@sha256:");
    }

    @Test
    void anExactVersionOverridesTheDate() throws Exception {
        handler.handle(client.client, job(headers(RuleStoneHeaders.VERSION, "2"), loanVariables("\"25750000\"", "\"5000000\"")));

        assertThat(completed().get("decision").get("ruleVersion").asText()).startsWith("2@sha256:");
    }

    @Test
    void withoutAnAsOfVariableTheWorkerClockSetsTheDate() throws Exception {
        handler.handle(client.client, job(headers(), loanVariables("\"25750000\"", "\"5000000\"")));

        assertThat(completed().get("decision").get("ruleVersion").asText()).startsWith("3@sha256:");
    }

    @Test
    void aMalformedAsOfDateIsABpmnError() {
        String variables = loanVariables("\"25750000\"", "\"5000000\"").replace("2026-11-05", "05/11/2026");

        handler.handle(client.client, job(headers(RuleStoneHeaders.AS_OF_VARIABLE, "applicationDate"), variables));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .contains("as-of variable 'applicationDate' must be a date as \"yyyy-MM-dd\"")
                .doesNotContain("05/11");
    }

    @Test
    void aMissingAsOfVariableIsABpmnError() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.AS_OF_VARIABLE, "valueDate"),
                loanVariables("\"25750000\"", "\"5000000\"")));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE)).contains("as-of variable 'valueDate' is missing");
    }

    // --- supplied model results ----------------------------------------------------------------

    private static String withModels(String models) {
        String variables = loanVariables("\"25750000\"", "\"5000000\"");
        return variables.substring(0, variables.lastIndexOf('}')) + ", \"scores\": " + models + "}";
    }

    @Test
    void theModelsVariableIsMissing() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.MODELS_VARIABLE, "scores"),
                loanVariables("\"25750000\"", "\"5000000\"")));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE)).contains("models variable 'scores' is missing");
    }

    @Test
    void invalidModelResultsAreABpmnError() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.MODELS_VARIABLE, "scores"),
                withModels("{\"pd\": {\"failure\": \"SLOW\"}, \"lgd\": {\"modelId\": \"m\", \"modelVersion\": \"1\","
                        + " \"outputs\": {\"lgd\": 0.12345678901234567}}}")));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .contains("models.lgd.outputs.lgd: a JSON number with more than 15 significant digits")
                .doesNotContain("SLOW");
    }

    @Test
    void modelResultsReachTheEngine() {
        // The worker evaluates decision tables, which have no model nodes: the engine rejects the results.
        handler.handle(client.client, job(headers(RuleStoneHeaders.MODELS_VARIABLE, "scores"),
                withModels("{\"pd\": {\"failure\": \"TIMEOUT\"}}")));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .endsWith("model results supplied for [pd], but a decision table has no model nodes");
    }

    // --- BPMN errors -------------------------------------------------------------------------

    @Test
    void aFailingRuleIsABpmnErrorWithTheTaskErrorCode() {
        // monthlyIncome 0: the debt-to-income rule divides by zero.
        handler.handle(client.client, job(headers(RuleStoneHeaders.ERROR_CODE, "LOAN_RULES_FAILED"),
                loanVariables("0", "\"" + SECRET_DEBT + "\"")));

        assertThat(bpmnError("LOAN_RULES_FAILED")).startsWith("RuleStone decision failed: loan_eligibility@3@sha256:");
    }

    @Test
    void anInputInTheWrongFormIsABpmnErrorNamingTheInput() {
        String variables = loanVariables("\"" + SECRET_INCOME + "\"", "\"5000000\"").replace("\"age\": 35", "\"age\": \"thirty\"");

        handler.handle(client.client, job(headers(), variables));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .startsWith("RuleStone decision loan_eligibility@3: invalid process variables: ")
                .contains("applicant.age")
                .doesNotContain("thirty");
    }

    @Test
    void aMissingInputIsABpmnError() {
        handler.handle(client.client, job(headers(), "{\"amount\": \"300000000\"}"));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE)).contains("applicant");
    }

    @Test
    void aFractionalNumberThatFloatingPointMayHaveChangedIsRejected() {
        handler.handle(client.client, job(headers(), loanVariables("25750123.123456789", "5000000")));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .contains("applicant.monthlyIncome: a JSON number with more than 15 significant digits")
                .contains("send decimals as strings");
    }

    @Test
    void undeclaredVariablesAreIgnoredWhateverTheyHold() throws Exception {
        // loanVariables carries a customer name, a null and a 19-digit float the decision does not declare.
        handler.handle(client.client, job(headers(), loanVariables("\"25750000\"", "\"5000000\"")));

        assertThat(completed().get("decision").get("matched").asBoolean()).isTrue();
    }

    @Test
    void noMatchCompletesUnlessTheTaskFailsOnNoMatch() throws Exception {
        handler.handle(client.client, job(Map.of(RuleStoneHeaders.DECISION_ID, "risk_band"), "{\"score\": 720}"));

        JsonNode decision = completed().get("decision");
        assertThat(decision.get("matched").asBoolean()).isFalse();
        assertThat(decision.get("output").isEmpty()).isTrue();
    }

    @Test
    void noMatchIsABpmnErrorWhenTheTaskFailsOnNoMatch() {
        handler.handle(client.client, job(Map.of(RuleStoneHeaders.DECISION_ID, "risk_band",
                RuleStoneHeaders.FAIL_ON_NO_MATCH, "true"), "{\"score\": 720}"));

        assertThat(bpmnError(RuleStoneHeaders.DEFAULT_ERROR_CODE))
                .startsWith("RuleStone decision risk_band@1@sha256:")
                .endsWith("matched no rule, and the task sets failOnNoMatch=true")
                .doesNotContain("720");
    }

    @Test
    void theWorkerDefaultErrorCodeAppliesWithoutAnErrorCodeHeader() {
        RuleStoneJobHandler custom = new RuleStoneJobHandler(TestSupport.engine(), TestSupport.tables(),
                RuleStoneWorkerOptions.builder().defaultErrorCode("RULES_DOWN").build());

        custom.handle(client.client, job(Map.of(RuleStoneHeaders.DECISION_ID, "risk_band",
                RuleStoneHeaders.FAIL_ON_NO_MATCH, "true"), "{\"score\": 720}"));

        bpmnError("RULES_DOWN");
    }

    // --- incidents ---------------------------------------------------------------------------

    @Test
    void anUnknownDecisionRaisesAnIncident() {
        handler.handle(client.client, job(Map.of(RuleStoneHeaders.DECISION_ID, "loan_eligibilty"), "{}"));

        assertThat(failure(0))
                .startsWith("RuleStone decision not found: unknown decision 'loan_eligibilty'.")
                .endsWith("[process 'loan-approval', element 'CheckEligibility']");
    }

    @Test
    void anUnknownVersionRaisesAnIncident() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.VERSION, "9"), loanVariables("1", "1")));

        assertThat(failure(0)).contains("decision 'loan_eligibility' has no version '9'");
    }

    @Test
    void noVersionInForceRaisesAnIncident() {
        String variables = loanVariables("1", "1").replace("2026-11-05", "2025-06-01");

        handler.handle(client.client, job(headers(RuleStoneHeaders.AS_OF_VARIABLE, "applicationDate"), variables));

        assertThat(failure(0)).contains("no version of decision 'loan_eligibility' is effective on 2025-06-01");
    }

    @Test
    void aMissingDecisionIdHeaderRaisesAnIncident() {
        handler.handle(client.client, job(Map.of(), "{}"));

        assertThat(failure(0)).startsWith("invalid RuleStone task configuration: missing header 'decisionId'");
    }

    @Test
    void anInvalidFailOnNoMatchHeaderRaisesAnIncident() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.FAIL_ON_NO_MATCH, "yes"), "{}"));

        assertThat(failure(0)).contains("header 'failOnNoMatch' must be 'true' or 'false', was 'yes'");
    }

    @Test
    void anInvalidResultVariableHeaderRaisesAnIncident() {
        handler.handle(client.client, job(headers(RuleStoneHeaders.RESULT_VARIABLE, "loan decision"), "{}"));

        assertThat(failure(0)).contains("header 'resultVariable' must be a variable name");
    }

    @Test
    void headerDefaults() {
        DecisionTask task = DecisionTask.fromHeaders(Map.of(RuleStoneHeaders.DECISION_ID, " loan_eligibility ",
                RuleStoneHeaders.VERSION, " ", RuleStoneHeaders.ERROR_CODE, ""), "DEFAULT_CODE");

        assertThat(task).isEqualTo(new DecisionTask("loan_eligibility", java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), "decision", false, "DEFAULT_CODE"));
    }

    // --- unexpected failures -----------------------------------------------------------------

    @Test
    void anUnexpectedExceptionFailsTheJobForRetryWithoutItsMessage() {
        RuleStoneEngine broken = (ref, facts) -> {
            throw new IllegalStateException("cannot score income " + SECRET_INCOME);
        };
        RuleStoneJobHandler failing = new RuleStoneJobHandler(broken, TestSupport.tables(),
                RuleStoneWorkerOptions.builder().clock(TestSupport.CLOCK).retryBackoff(Duration.ofSeconds(30)).build());

        failing.handle(client.client, job(headers(), loanVariables("\"" + SECRET_INCOME + "\"", "\"5000000\"")));

        assertThat(failure(2))
                .startsWith("RuleStone worker failed unexpectedly evaluating decision 'loan_eligibility': "
                        + "java.lang.IllegalStateException at ")
                .contains("message not shown");
        verify(client.failWithRetries).retryBackoff(Duration.ofSeconds(30));
    }

    // --- registration ------------------------------------------------------------------------

    @Test
    void registerOpensAWorkerForTheConfiguredJobType() {
        CamundaClient camunda = mock(CamundaClient.class);
        JobWorkerBuilderStep1 step1 = mock(JobWorkerBuilderStep1.class);
        JobWorkerBuilderStep2 step2 = mock(JobWorkerBuilderStep2.class);
        JobWorkerBuilderStep3 step3 = mock(JobWorkerBuilderStep3.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        JobWorker worker = mock(JobWorker.class);
        when(camunda.newWorker()).thenReturn(step1);
        when(step1.jobType(anyString())).thenReturn(step2);
        when(step2.handler(org.mockito.ArgumentMatchers.any(JobHandler.class))).thenReturn(step3);
        when(step3.open()).thenReturn(worker);

        JobWorker opened = RuleStoneJobWorker.register(camunda, TestSupport.engine(), TestSupport.tables(),
                RuleStoneWorkerOptions.builder()
                        .jobType("rulestone:loan_eligibility")
                        .fetchVariables("applicant", "amount", "applicationDate")
                        .workerName("loan-rules")
                        .build());

        assertThat(opened).isSameAs(worker);
        verify(step1).jobType("rulestone:loan_eligibility");
        verify(step2).handler(org.mockito.ArgumentMatchers.any(RuleStoneJobHandler.class));
        verify(step3).fetchVariables(List.of("applicant", "amount", "applicationDate"));
        verify(step3).name("loan-rules");
    }

    @Test
    void registerWithDefaultsFetchesAllVariables() {
        CamundaClient camunda = mock(CamundaClient.class);
        JobWorkerBuilderStep1 step1 = mock(JobWorkerBuilderStep1.class);
        JobWorkerBuilderStep2 step2 = mock(JobWorkerBuilderStep2.class);
        JobWorkerBuilderStep3 step3 = mock(JobWorkerBuilderStep3.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(camunda.newWorker()).thenReturn(step1);
        when(step1.jobType(anyString())).thenReturn(step2);
        when(step2.handler(org.mockito.ArgumentMatchers.any(JobHandler.class))).thenReturn(step3);

        RuleStoneJobWorker.register(camunda, TestSupport.engine(), TestSupport.tables());

        verify(step1).jobType(RuleStoneWorkerOptions.DEFAULT_JOB_TYPE);
        verify(step3, never()).fetchVariables(org.mockito.ArgumentMatchers.<List<String>>any());
        verify(step3).open();
    }

    /** A minimal record for a hand-built result. */
    private static DecisionRecord testRecord(String decision, String version, Map<String, Object> output) {
        return new DecisionRecord("test", Optional.empty(), decision, Optional.of(version), LocalDate.of(2026, 11, 1),
                Instant.EPOCH, "test", DecisionRecord.Status.OK, Optional.empty(), output, List.of(), List.of(),
                Optional.empty());
    }
}
