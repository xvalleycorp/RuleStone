//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Result of one decision, as a Temporal payload: returned by {@link RuleStoneActivities#evaluate}
 * and by {@link RuleStoneWorkflow#evaluate}.
 *
 * <p>Outputs are held in RuleStone's JSON forms (see {@link DecisionRequest}): a {@code decimal}
 * output is a string such as {@code "120000000"}, a {@code money} output a string such as
 * {@code "1500000.50 VND"}, a {@code date} output a string such as {@code "2026-11-01"}. They are
 * exact and survive any JSON payload converter. Read them back with the typed accessors
 * ({@link #text}, {@link #decimal}, {@link #money}, {@link #date}, {@link #integer}, {@link #bool},
 * or {@link #outputValue} for lists).
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
        @JsonDeserialize(contentUsing = PayloadJson.ExactValueDeserializer.class)
        Map<String, Object> output,
        List<String> reasonCodes,
        List<String> matchedRules,
        List<TraceStep> trace) {

    /**
     * Validates the outcome and converts outputs into JSON forms.
     *
     * @throws IllegalArgumentException if an output is {@code null}, a floating-point number or of an
     *                                  unsupported type
     */
    public DecisionOutcome {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(businessDate, "businessDate");
        output = PayloadJson.write(output == null ? Map.of() : output, "output", false);
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
                result.output(), result.reasonCodes(), result.matchedRules(), result.trace());
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
     * @param type the type the decision declares for it, e.g. {@code ValueType.listOf(ValueType.MONEY)}
     * @return the value as RuleStone returns it ({@code BigDecimal}, {@code Money}, {@code LocalDate},
     *         {@code Long}, {@code String}, {@code Boolean} or a {@code List} of these), or empty if the
     *         decision did not set it
     * @throws IllegalArgumentException if the output does not have the form of {@code type}
     */
    public Optional<Object> outputValue(String name, ValueType type) {
        Object json = output.get(name);
        return json == null ? Optional.empty() : Optional.of(PayloadJson.readValue(json, type, "output", name));
    }

    /**
     * Reads a {@code string} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not a string
     */
    public Optional<String> text(String name) {
        return outputValue(name, ValueType.STRING).map(String.class::cast);
    }

    /**
     * Reads a {@code decimal} output, exactly and with its scale.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not a decimal
     */
    public Optional<BigDecimal> decimal(String name) {
        return outputValue(name, ValueType.DECIMAL).map(BigDecimal.class::cast);
    }

    /**
     * Reads a {@code money} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not money
     */
    public Optional<Money> money(String name) {
        return outputValue(name, ValueType.MONEY).map(Money.class::cast);
    }

    /**
     * Reads a {@code date} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not a date
     */
    public Optional<LocalDate> date(String name) {
        return outputValue(name, ValueType.DATE).map(LocalDate.class::cast);
    }

    /**
     * Reads an {@code int} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not an integer
     */
    public Optional<Long> integer(String name) {
        return outputValue(name, ValueType.INT).map(Long.class::cast);
    }

    /**
     * Reads a {@code bool} output.
     *
     * @param name the output name
     * @return the value, or empty if the decision did not set it
     * @throws IllegalArgumentException if the output is not a boolean
     */
    public Optional<Boolean> bool(String name) {
        return outputValue(name, ValueType.BOOL).map(Boolean.class::cast);
    }
}
