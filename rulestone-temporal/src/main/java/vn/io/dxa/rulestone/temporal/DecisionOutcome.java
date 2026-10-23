//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.FactsJson;

/**
 * Result of one decision, as a Temporal payload: returned by {@link RuleStoneActivities#evaluate}
 * and by {@link RuleStoneWorkflow#evaluate}.
 *
 * <p>Outputs are a JSON object in RuleStone's JSON forms (see {@link DecisionRequest}): a
 * {@code decimal} output is a string such as {@code "120000000"}, {@code money} a string such as
 * {@code "1500000.50 VND"}, {@code date} a string such as {@code "2026-11-01"}. They are exact and
 * survive any JSON payload converter. Read them with the typed accessors ({@link #text},
 * {@link #decimal}, {@link #money}, {@link #date}, {@link #integer}, {@link #bool}, or
 * {@link #outputValue} for lists).
 *
 * <p>Nothing here contains input values, so an outcome is safe to log and to store for audit.
 *
 * @param decisionId   the evaluated decision
 * @param ruleVersion  exact rule version used, {@code <version>@sha256:<hash>}
 * @param businessDate the business date the decision was evaluated on, whether given or resolved
 * @param output       the outputs in JSON forms, in the order the decision declares them
 * @param reasonCodes  reason codes of the matching rule(s)
 * @param matchedRules ids of the matching rules, in evaluation order
 * @param trace        each evaluated rule and whether it matched
 */
public record DecisionOutcome(
        String decisionId,
        String ruleVersion,
        LocalDate businessDate,
        @JsonDeserialize(using = PayloadJson.ExactObjectDeserializer.class)
        ObjectNode output,
        List<String> reasonCodes,
        List<String> matchedRules,
        List<TraceStep> trace) {

    /** Validates the outcome and keeps copies of its parts. */
    public DecisionOutcome {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(businessDate, "businessDate");
        output = output == null ? JsonNodeFactory.instance.objectNode() : output.deepCopy();
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    /**
     * Converts an engine result.
     *
     * @param result       the result
     * @param businessDate the business date it was evaluated on
     * @return the outcome
     */
    public static DecisionOutcome from(DecisionResult result, LocalDate businessDate) {
        return new DecisionOutcome(result.decisionId(), result.ruleVersion(), businessDate,
                FactsJson.writeValues(result.output()), result.reasonCodes(), result.matchedRules(),
                result.trace());
    }

    /**
     * The outputs in JSON forms.
     *
     * @return a copy of the outputs
     */
    @Override
    public ObjectNode output() {
        return output.deepCopy();
    }

    /**
     * Whether at least one rule matched. Under {@code FIRST} and {@code UNIQUE}, handle {@code false}:
     * the decision then has no outputs.
     *
     * @return whether any rule matched
     */
    public boolean matched() {
        return !matchedRules.isEmpty();
    }

    /**
     * Reads an output as {@code type}.
     *
     * @param name the output name
     * @param type its type, e.g. {@code ValueType.listOf(ValueType.MONEY)} for a {@code money} output
     *             under {@code COLLECT}
     * @return the value as the engine returns it, or empty if the decision did not set it
     * @throws vn.io.dxa.rulestone.core.InvalidFactsException if the output does not have the form of {@code type}
     */
    public Optional<Object> outputValue(String name, ValueType type) {
        JsonNode json = output.get(name);
        return json == null || json.isNull() ? Optional.empty() : Optional.of(FactsJson.readValue(json, type));
    }

    /**
     * Reads a {@code string} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<String> text(String name) {
        return outputValue(name, ValueType.STRING).map(String.class::cast);
    }

    /**
     * Reads a {@code decimal} output, exactly and with its scale.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<BigDecimal> decimal(String name) {
        return outputValue(name, ValueType.DECIMAL).map(BigDecimal.class::cast);
    }

    /**
     * Reads a {@code money} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<Money> money(String name) {
        return outputValue(name, ValueType.MONEY).map(Money.class::cast);
    }

    /**
     * Reads a {@code date} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<LocalDate> date(String name) {
        return outputValue(name, ValueType.DATE).map(LocalDate.class::cast);
    }

    /**
     * Reads an {@code int} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<Long> integer(String name) {
        return outputValue(name, ValueType.INT).map(Long.class::cast);
    }

    /**
     * Reads a {@code bool} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     */
    public Optional<Boolean> bool(String name) {
        return outputValue(name, ValueType.BOOL).map(Boolean.class::cast);
    }
}
