//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.camunda.bpm.engine.variable.value.SerializableValue;
import org.camunda.bpm.engine.variable.value.TypedValue;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidFactsException;

/**
 * Evaluates RuleStone decisions on Camunda 7 process variables. {@link RuleStoneDelegate} uses it,
 * and it is the expression bean that {@link RuleStoneProcessEnginePlugin} registers as
 * {@code rulestone}:
 *
 * <pre>{@code
 * ${rulestone.evaluate('loan_eligibility', execution).output.decision == 'APPROVE'}
 * }</pre>
 *
 * <p><b>Reading variables.</b> Only the inputs the decision declares are read, each with its declared
 * type, through {@link FactsJson}: a variable may hold a {@code Boolean}, {@code String}, integer,
 * {@code BigDecimal}, {@code Money}, {@code LocalDate}, enum, {@code Map} or {@code Collection} of
 * these, or a JSON value (such as a Spin {@code JSON} variable), and text forms work too: a decimal as
 * {@code "25750000.50"}, money as {@code "1500000.50 VND"}, a date as {@code "yyyy-MM-dd"}.
 * {@code Double} and {@code Float} are rejected, naming the variable: they cannot hold exact decimals.
 * The as-of variable holds a {@code LocalDate} or a {@code "yyyy-MM-dd"} string.
 *
 * <p><b>Result.</b> A {@code Map}: {@code output} (the decision's outputs), {@code reasonCodes},
 * {@code matchedRules}, {@code ruleVersion} and {@code matched}. Output values are {@code Boolean},
 * {@code Long}, {@code String}, {@code BigDecimal} and {@code LocalDate} (and lists of them), so JUEL
 * compares them exactly; money becomes its text form {@code "<amount> <CCY>"}.
 *
 * <p><b>Errors.</b> Invalid or missing input, a failing rule and (when asked) no match throw a
 * {@link BpmnError}; an unknown decision or version, or none effective on the date, throws a
 * {@link RuleStoneConfigurationException}. Messages name the decision, inputs and types, never values.
 *
 * <p>Thread-safe; it logs nothing.
 */
public final class RuleStoneDecisionService {

    /** The variable {@link RuleStoneDelegate} writes the result to by default. */
    public static final String DEFAULT_RESULT_VARIABLE = "decision";

    /** The default code of the BPMN error thrown when a decision fails. */
    public static final String DEFAULT_ERROR_CODE = "RULESTONE_DECISION_FAILED";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final String JSON_FORMAT = "application/json";

    private final RuleStoneEngine engine;
    private final DecisionTables tables;
    private final Clock clock;

    /**
     * Creates a service with the system clock in the JVM's default time zone. Prefer
     * {@link #RuleStoneDecisionService(RuleStoneEngine, Collection, Clock)} with your business time zone.
     *
     * @param engine the engine
     * @param tables every decision table registered with the engine; their input types say how to
     *               read the variables
     */
    public RuleStoneDecisionService(RuleStoneEngine engine, Collection<DecisionTable> tables) {
        this(engine, tables, Clock.systemDefaultZone());
    }

    /**
     * Creates a service.
     *
     * @param engine the engine
     * @param tables every decision table registered with the engine
     * @param clock  sets the business date when no as-of variable is given, e.g.
     *               {@code Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))}
     */
    public RuleStoneDecisionService(RuleStoneEngine engine, Collection<DecisionTable> tables, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tables = new DecisionTables(Objects.requireNonNull(tables, "tables"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Evaluates the version of a decision effective today, according to the service's clock.
     *
     * @param decisionId the decision
     * @param variables  the execution or task whose variables hold the inputs
     * @return the result map
     * @throws BpmnError                        with code {@value #DEFAULT_ERROR_CODE} if the decision fails
     * @throws RuleStoneConfigurationException if the decision is not found
     */
    public Map<String, Object> evaluate(String decisionId, VariableScope variables) {
        return evaluate(DecisionCall.of(text(decisionId, "decisionId"), Optional.empty()), variables);
    }

    /**
     * Evaluates the version of a decision effective on the business date held in a variable.
     *
     * @param decisionId   the decision
     * @param asOfVariable the variable holding the business date
     * @param variables    the execution or task whose variables hold the inputs
     * @return the result map
     * @throws BpmnError                        with code {@value #DEFAULT_ERROR_CODE} if the decision fails
     * @throws RuleStoneConfigurationException if the decision is not found
     */
    public Map<String, Object> evaluateAsOf(String decisionId, String asOfVariable, VariableScope variables) {
        return evaluate(DecisionCall.of(text(decisionId, "decisionId"),
                Optional.of(text(asOfVariable, "asOfVariable"))), variables);
    }

    Map<String, Object> evaluate(DecisionCall call, VariableScope variables) {
        LocalDate businessDate = businessDate(call, variables);
        DecisionTable table;
        try {
            table = tables.select(call.decisionId(), call.version(), businessDate);
        } catch (DecisionNotFoundException e) {
            throw notFound(e);
        }
        Facts facts = readFacts(call, table, variables);

        DecisionRef ref = DecisionRef.of(call.decisionId()).asOf(businessDate);
        if (call.version().isPresent()) {
            ref = ref.version(call.version().get());
        }
        DecisionResult result;
        try {
            result = engine.evaluate(ref, facts);
        } catch (DecisionNotFoundException e) {
            throw notFound(e);
        } catch (DecisionEvaluationException e) {
            throw new BpmnError(call.errorCode(), "RuleStone decision failed: " + e.getMessage());
        }
        if (!result.matched() && call.failOnNoMatch()) {
            throw new BpmnError(call.errorCode(), "RuleStone decision " + result.decisionId() + "@"
                    + result.ruleVersion() + " matched no rule, and the task sets failOnNoMatch=true");
        }
        return resultMap(result);
    }

    /** The result as a process variable: a Java-serialized map, so that decimals stay exact. */
    static ObjectValue asVariable(Map<String, Object> result) {
        return Variables.objectValue(result)
                .serializationDataFormat(Variables.SerializationDataFormats.JAVA)
                .create();
    }

    private LocalDate businessDate(DecisionCall call, VariableScope variables) {
        if (call.asOfVariable().isEmpty()) {
            return LocalDate.now(clock);
        }
        String name = call.asOfVariable().get();
        Object value = variables.getVariable(name);
        if (value == null) {
            throw new BpmnError(call.errorCode(), "RuleStone: as-of variable '" + name + "' is missing");
        }
        try {
            return (LocalDate) FactsJson.readValue(FactsJson.writeValue(value), ValueType.DATE);
        } catch (InvalidFactsException | IllegalArgumentException e) {
            throw new BpmnError(call.errorCode(), "RuleStone: as-of variable '" + name
                    + "' must be a LocalDate or a date as \"yyyy-MM-dd\"");
        }
    }

    private static Facts readFacts(DecisionCall call, DecisionTable table, VariableScope variables) {
        ObjectNode inputs = JSON.createObjectNode();
        List<String> problems = new ArrayList<>();
        for (String name : table.inputs().keySet()) {
            JsonNode value = read(name, variables, problems);
            if (value != null && !value.isNull()) {
                inputs.set(name, value);
            }
        }
        if (problems.isEmpty()) {
            try {
                return FactsJson.readFacts(inputs, table);
            } catch (InvalidFactsException e) {
                problems.addAll(e.problems());
            }
        }
        throw new BpmnError(call.errorCode(), "RuleStone decision " + table.ref() + ": invalid process variables: "
                + String.join("; ", problems));
    }

    /** One variable as JSON, or null if it is absent. Problems name the variable, never its value. */
    private static JsonNode read(String name, VariableScope variables, List<String> problems) {
        try {
            TypedValue typed = variables.getVariableTyped(name, false);
            if (typed == null) {
                return null;
            }
            if (typed instanceof SerializableValue serializable && !serializable.isDeserialized()) {
                if (JSON_FORMAT.equals(serializable.getSerializationDataFormat())) {
                    String text = serializable.getValueSerialized();
                    return text == null ? null : parse(name, text, problems);
                }
                return toJson(name, variables.getVariable(name), problems);
            }
            return toJson(name, typed.getValue(), problems);
        } catch (RuntimeException e) {
            // e.g. a serialized object whose class is missing; the message may quote data.
            problems.add(name + ": cannot be read (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static JsonNode toJson(String name, Object value, List<String> problems) {
        if (value == null) {
            return null;
        }
        try {
            return FactsJson.writeValues(Map.of(name, value)).get(name);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    private static JsonNode parse(String name, String json, List<String> problems) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            problems.add(name + ": invalid JSON");
            return null;
        }
    }

    private static Map<String, Object> resultMap(DecisionResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        result.output().forEach((name, value) -> output.put(name, storable(value)));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("output", output);
        map.put("reasonCodes", new ArrayList<>(result.reasonCodes()));
        map.put("matchedRules", new ArrayList<>(result.matchedRules()));
        map.put("ruleVersion", result.ruleVersion());
        map.put("matched", result.matched());
        return map;
    }

    /** JDK types only, so that the map serializes and deserializes without RuleStone classes. */
    private static Object storable(Object value) {
        if (value instanceof Money money) {
            return money.toString();
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(storable(item)));
            return copy;
        }
        return value;
    }

    private static RuleStoneConfigurationException notFound(DecisionNotFoundException e) {
        return new RuleStoneConfigurationException("RuleStone decision not found: " + e.getMessage()
                + ". Register the decision with the RuleStone engine, or fix the task's decisionId/version");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new RuleStoneConfigurationException("RuleStone: " + name + " must not be blank");
        }
        return value.trim();
    }
}
