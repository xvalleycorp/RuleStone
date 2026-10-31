//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.camunda7.TestSupport.SECRET_DEBT;
import static vn.io.dxa.rulestone.camunda7.TestSupport.SECRET_INCOME;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.TypedValue;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.Money;

/** Variable reading, results and errors of the service, on a variable scope without an engine. */
class RuleStoneDecisionServiceTest {

    private final RuleStoneDecisionService service = TestSupport.service();

    /** A variable scope over typed values; only the reading methods are implemented. */
    private static VariableScope scope(Map<String, ?> variables) {
        Map<String, TypedValue> typed = new HashMap<>();
        variables.forEach((name, value) -> typed.put(name,
                value instanceof TypedValue t ? t : Variables.untypedValue(value)));
        return (VariableScope) Proxy.newProxyInstance(VariableScope.class.getClassLoader(),
                new Class<?>[] {VariableScope.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getVariableTyped" -> typed.get((String) args[0]);
                    case "getVariable" -> typed.containsKey((String) args[0]) ? typed.get((String) args[0]).getValue() : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> result) {
        return (Map<String, Object>) result.get("output");
    }

    private static Map<String, Object> cardFeeVariables(Object spend) {
        return Map.of("customer", Map.of("birthDate", LocalDate.of(1980, 2, 29)), "spend", spend, "rate", "0.02");
    }

    @Test
    void readsMoneyAndDatesAndStoresMoneyAsText() {
        Map<String, Object> result = service.evaluate("card_fee", scope(cardFeeVariables("1500000.50 VND")));

        assertThat(output(result)).containsEntry("fee", "30000.0100 VND")
                .containsEntry("nextReview", LocalDate.of(2027, 5, 5))
                .containsEntry("tier", "STANDARD");
    }

    @Test
    void acceptsMoneyObjects() {
        Map<String, Object> result = service.evaluate("card_fee",
                scope(cardFeeVariables(new Money(new BigDecimal("1500000.50"), "VND"))));

        assertThat(output(result)).containsEntry("fee", "30000.0100 VND");
    }

    @Test
    void readsJsonVariablesWithoutDeserializingThem() {
        TypedValue applicant = Variables.serializedObjectValue("""
                {"age": 35, "monthlyIncome": "25750000", "monthlyDebt": 5000000.00, "segment": "MASS", "blacklisted": false}""")
                .serializationDataFormat("application/json")
                .objectTypeName("java.util.LinkedHashMap")
                .create();

        Map<String, Object> result = service.evaluate("loan_eligibility",
                scope(Map.of("applicant", applicant, "amount", 300000000L)));

        assertThat(output(result)).containsEntry("decision", "APPROVE")
                .containsEntry("limit", new BigDecimal("309000000"));
        assertThat(result.get("reasonCodes")).isEqualTo(List.of());
    }

    @Test
    void readsSuppliedModelResultsFromTheModelsVariable() {
        DecisionCall call = new DecisionCall("risk_band", Optional.empty(), Optional.empty(), Optional.of("scores"),
                "decision", false, "RULESTONE_DECISION_FAILED");

        assertThatThrownBy(() -> service.evaluate(call, scope(Map.of("score", 420))))
                .isInstanceOf(BpmnError.class)
                .hasMessage("RuleStone: models variable 'scores' is missing");
        assertThatThrownBy(() -> service.evaluate(call, scope(Map.of("score", 420,
                "scores", Map.of("pd", Map.of("failure", "SLOW"))))))
                .isInstanceOf(BpmnError.class)
                .hasMessage("RuleStone decision risk_band@1: invalid process variables: "
                        + "scores.pd.failure: expected \"TIMEOUT\" or \"ERROR\"");
        // The service evaluates decision tables, which have no model nodes: the engine rejects the results.
        TypedValue json = Variables.serializedObjectValue("{\"pd\": {\"failure\": \"TIMEOUT\"}}")
                .serializationDataFormat("application/json").objectTypeName("java.util.LinkedHashMap").create();
        assertThatThrownBy(() -> service.evaluate(call, scope(Map.of("score", 420, "scores", json))))
                .isInstanceOf(BpmnError.class)
                .hasMessageEndingWith("model results supplied for [pd], but a decision table has no model nodes");
    }

    @Test
    void readsOnlyTheDeclaredInputs() {
        Map<String, Object> variables = new HashMap<>(Map.of("score", 420));
        variables.put("unrelated", 0.1d);
        variables.put("blob", new Object());

        assertThat(output(service.evaluate("risk_band", scope(variables)))).containsEntry("band", "HIGH");
    }

    @Test
    void floatingPointInputsAreABpmnErrorNamingThePath() {
        Map<String, Object> variables = Map.of("applicant", Map.of("age", 35, "monthlyIncome", 25750123.0d,
                "monthlyDebt", new BigDecimal(SECRET_DEBT), "segment", "MASS", "blacklisted", false), "amount", 1L);

        assertThatThrownBy(() -> service.evaluate("loan_eligibility", scope(variables)))
                .isInstanceOf(BpmnError.class)
                .hasMessage("RuleStone decision loan_eligibility@3: invalid process variables: "
                        + "applicant.monthlyIncome: floating-point numbers are not supported; use BigDecimal")
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode()).isEqualTo("RULESTONE_DECISION_FAILED"));
    }

    @Test
    void inputsInTheWrongFormAreABpmnErrorWithoutValues() {
        Map<String, Object> variables = Map.of("applicant", Map.of("age", "thirty", "monthlyIncome", SECRET_INCOME,
                "monthlyDebt", "x" + SECRET_DEBT, "segment", "MASS", "blacklisted", false), "amount", 1L);

        assertThatThrownBy(() -> service.evaluate("loan_eligibility", scope(variables)))
                .isInstanceOf(BpmnError.class)
                .hasMessageContaining("applicant.age")
                .hasMessageContaining("applicant.monthlyDebt")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("thirty", SECRET_INCOME, SECRET_DEBT));
    }

    @Test
    void theAsOfVariableMayBeALocalDateOrText() {
        Map<String, Object> variables = new HashMap<>(Map.of("applicant", Map.of("age", 35,
                "monthlyIncome", 60000000L, "monthlyDebt", 0L, "segment", "MASS", "blacklisted", false),
                "amount", 1L));
        variables.put("applicationDate", LocalDate.of(2026, 10, 15));
        assertThat((String) service.evaluateAsOf("loan_eligibility", "applicationDate", scope(variables)).get("ruleVersion"))
                .startsWith("2@sha256:");

        variables.put("applicationDate", "2026-11-01");
        assertThat((String) service.evaluateAsOf("loan_eligibility", "applicationDate", scope(variables)).get("ruleVersion"))
                .startsWith("3@sha256:");
    }

    @Test
    void aJavaUtilDateIsNotABusinessDate() {
        Map<String, Object> variables = Map.of("score", 1, "applicationDate", new Date(0));

        assertThatThrownBy(() -> service.evaluateAsOf("risk_band", "applicationDate", scope(variables)))
                .isInstanceOf(BpmnError.class)
                .hasMessage("RuleStone: as-of variable 'applicationDate' must be a LocalDate or a date as \"yyyy-MM-dd\"");
    }

    @Test
    void noMatchCompletesWithMatchedFalseUnlessAsked() {
        Map<String, Object> result = service.evaluate("risk_band", scope(Map.of("score", 720)));

        assertThat(result).containsEntry("matched", false).containsEntry("output", Map.of());

        DecisionCall strict = new DecisionCall("risk_band", Optional.empty(), Optional.empty(), Optional.empty(), "decision", true, "NO_BAND");
        assertThatThrownBy(() -> service.evaluate(strict, scope(Map.of("score", 720))))
                .isInstanceOf(BpmnError.class)
                .hasMessageEndingWith("matched no rule, and the task sets failOnNoMatch=true")
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode()).isEqualTo("NO_BAND"));
    }

    @Test
    void unknownDecisionsAndVersionsAreConfigurationErrors() {
        assertThatThrownBy(() -> service.evaluate("nope", scope(Map.of())))
                .isInstanceOf(RuleStoneConfigurationException.class)
                .hasMessageStartingWith("RuleStone decision not found: unknown decision 'nope'.");

        DecisionCall v9 = new DecisionCall("loan_eligibility", Optional.of("9"), Optional.empty(), Optional.empty(), "decision", false, "X");
        assertThatThrownBy(() -> service.evaluate(v9, scope(Map.of())))
                .isInstanceOf(RuleStoneConfigurationException.class)
                .hasMessageContaining("decision 'loan_eligibility' has no version '9'");

        assertThatThrownBy(() -> service.evaluate(" ", scope(Map.of())))
                .isInstanceOf(RuleStoneConfigurationException.class)
                .hasMessage("RuleStone: decisionId must not be blank");
    }

    @Test
    void theStoredResultIsAJavaSerializedObject() {
        Map<String, Object> result = service.evaluate("risk_band", scope(Map.of("score", 1)));

        assertThat(RuleStoneDecisionService.asVariable(result).getSerializationDataFormat())
                .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
    }
}
