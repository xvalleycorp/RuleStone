//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidFactsException;

/**
 * Evaluates the decision a service task names in its custom headers, and completes the job with the
 * result.
 *
 * <p>The handler reads the task's {@link RuleStoneHeaders headers}, selects the decision version (an
 * exact {@code version}, or the version effective on the business date), reads the decision's
 * declared inputs from the job's variables with {@link FactsJson} (other variables are ignored),
 * evaluates, and completes the job with one variable, named by {@code resultVariable}:
 *
 * <pre>{@code
 * { "decision": { "output": {"decision": "APPROVE", "limit": "309000000"},
 *                 "reasonCodes": [], "matchedRules": ["R4"],
 *                 "ruleVersion": "3@sha256:…", "matched": true } }
 * }</pre>
 *
 * <p>Outputs are in RuleStone's JSON forms: decimals, money and dates are strings, integers numbers.
 *
 * <p>Failures:
 *
 * <ul>
 *   <li>Invalid input (a value in the wrong form, a missing input or as-of date), a failing rule, a
 *       {@code UNIQUE} violation, or no match when {@code failOnNoMatch} is {@code true}: the task's
 *       BPMN error ({@code errorCode}), for a boundary event to catch.</li>
 *   <li>Unknown decision or version, no version effective on the date, or invalid headers: the job
 *       fails with no retries left, which raises an incident.</li>
 *   <li>Anything else: the job fails with one retry fewer, after the configured backoff.</li>
 * </ul>
 *
 * <p>Messages name the decision, inputs and types, never variable values. The handler logs nothing.
 * It is thread-safe: one instance serves all jobs of a worker.
 */
public final class RuleStoneJobHandler implements JobHandler {

    private final RuleStoneEngine engine;
    private final DecisionTables tables;
    private final RuleStoneWorkerOptions options;

    /**
     * Creates a handler with {@link RuleStoneWorkerOptions#defaults() default options}.
     *
     * @param engine the engine
     * @param tables every decision table registered with the engine; their input types say how to
     *               read the variables
     */
    public RuleStoneJobHandler(RuleStoneEngine engine, Collection<DecisionTable> tables) {
        this(engine, tables, RuleStoneWorkerOptions.defaults());
    }

    /**
     * Creates a handler.
     *
     * @param engine  the engine
     * @param tables  every decision table registered with the engine
     * @param options the worker options; the handler uses the error code, backoff and clock
     */
    public RuleStoneJobHandler(RuleStoneEngine engine, Collection<DecisionTable> tables,
            RuleStoneWorkerOptions options) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tables = new DecisionTables(Objects.requireNonNull(tables, "tables"));
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public void handle(JobClient client, ActivatedJob job) {
        String errorCode = options.defaultErrorCode();
        String decisionId = null;
        String variables;
        try {
            DecisionTask task = DecisionTask.fromHeaders(job.getCustomHeaders(), options.defaultErrorCode());
            errorCode = task.errorCode();
            decisionId = task.decisionId();
            variables = decide(task, job.getVariables());
        } catch (TaskFailure failure) {
            if (failure.kind() == TaskFailure.Kind.INCIDENT) {
                client.newFailCommand(job).retries(0)
                        .errorMessage(failure.getMessage() + where(job))
                        .send().join();
            } else {
                client.newThrowErrorCommand(job).errorCode(errorCode)
                        .errorMessage(failure.getMessage())
                        .send().join();
            }
            return;
        } catch (RuntimeException e) {
            client.newFailCommand(job).retries(Math.max(0, job.getRetries() - 1))
                    .retryBackoff(options.retryBackoff())
                    .errorMessage(unexpected(decisionId, e) + where(job))
                    .send().join();
            return;
        }
        client.newCompleteCommand(job).variables(variables).send().join();
    }

    /** Evaluates the task's decision and returns the completion variables as JSON text. */
    private String decide(DecisionTask task, String json) {
        ObjectNode variables = JobVariables.parse(json);
        LocalDate businessDate = businessDate(task, variables);

        DecisionTable table;
        try {
            table = tables.select(task.decisionId(), task.version(), businessDate);
        } catch (DecisionNotFoundException e) {
            throw notFound(e);
        }
        ObjectNode inputs = JobVariables.select(variables, table.inputs().keySet());
        List<String> inexact = JobVariables.inexactNumbers(inputs);
        if (!inexact.isEmpty()) {
            throw invalidInput(table, inexact);
        }
        Facts facts;
        try {
            facts = FactsJson.readFacts(inputs, table);
        } catch (InvalidFactsException e) {
            throw invalidInput(table, e.problems());
        }

        DecisionRef ref = DecisionRef.of(task.decisionId()).asOf(businessDate);
        if (task.version().isPresent()) {
            ref = ref.version(task.version().get());
        }
        DecisionResult result;
        try {
            result = engine.evaluate(ref, facts);
        } catch (DecisionNotFoundException e) {
            throw notFound(e);
        } catch (DecisionEvaluationException e) {
            throw TaskFailure.bpmnError("RuleStone decision failed: " + e.getMessage());
        }
        if (!result.matched() && task.failOnNoMatch()) {
            throw TaskFailure.bpmnError("RuleStone decision " + result.decisionId() + "@" + result.ruleVersion()
                    + " matched no rule, and the task sets " + RuleStoneHeaders.FAIL_ON_NO_MATCH + "=true");
        }
        return completionVariables(task.resultVariable(), result);
    }

    private LocalDate businessDate(DecisionTask task, ObjectNode variables) {
        if (task.asOfVariable().isEmpty()) {
            return LocalDate.now(options.clock());
        }
        String name = task.asOfVariable().get();
        JsonNode value = variables.get(name);
        if (value == null || value.isNull()) {
            throw TaskFailure.bpmnError("RuleStone task: as-of variable '" + name + "' is missing"
                    + (options.fetchVariables().isEmpty() ? "" : "; is it among the worker's fetched variables?"));
        }
        try {
            return (LocalDate) FactsJson.readValue(value, ValueType.DATE);
        } catch (InvalidFactsException e) {
            throw TaskFailure.bpmnError("RuleStone task: as-of variable '" + name
                    + "' must be a date as \"yyyy-MM-dd\"");
        }
    }

    /** The result as {@code {resultVariable: {...}}}, in RuleStone's JSON forms. */
    static String completionVariables(String resultVariable, DecisionResult result) {
        ObjectNode decision = JobVariables.MAPPER.createObjectNode();
        decision.set("output", FactsJson.writeValues(result.output()));
        decision.set("reasonCodes", strings(result.reasonCodes()));
        decision.set("matchedRules", strings(result.matchedRules()));
        decision.put("ruleVersion", result.ruleVersion());
        decision.put("matched", result.matched());
        ObjectNode variables = JobVariables.MAPPER.createObjectNode();
        variables.set(resultVariable, decision);
        try {
            return JobVariables.MAPPER.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write the result as JSON");
        }
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = JobVariables.MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static TaskFailure notFound(DecisionNotFoundException e) {
        return TaskFailure.incident("RuleStone decision not found: " + e.getMessage()
                + ". Register the decision with the worker's engine, or fix the task's "
                + RuleStoneHeaders.DECISION_ID + "/" + RuleStoneHeaders.VERSION
                + " headers, then resolve the incident");
    }

    private static TaskFailure invalidInput(DecisionTable table, List<String> problems) {
        return TaskFailure.bpmnError("RuleStone decision " + table.ref() + ": invalid process variables: "
                + String.join("; ", problems));
    }

    /** Names the exception type and where it was thrown; its message may quote variable values. */
    static String unexpected(String decisionId, RuntimeException e) {
        StackTraceElement[] trace = e.getStackTrace();
        String at = trace.length == 0 ? "" : " at " + trace[0];
        return "RuleStone worker failed unexpectedly"
                + (decisionId == null ? "" : " evaluating decision '" + decisionId + "'")
                + ": " + e.getClass().getName() + at
                + " (message not shown: it may contain variable values)";
    }

    private static String where(ActivatedJob job) {
        return " [process '" + job.getBpmnProcessId() + "', element '" + job.getElementId() + "']";
    }
}
