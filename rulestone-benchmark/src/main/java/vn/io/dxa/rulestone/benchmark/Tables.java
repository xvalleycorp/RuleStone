//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.benchmark;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.HitPolicy;
import vn.io.dxa.rulestone.core.Rule;

/**
 * The decision tables and facts the benchmarks evaluate.
 *
 * <p>A table of {@code n} rules compares one input against {@code n} descending thresholds,
 * {@code n} million down to 1 million, followed by a catch-all rule. The facts lie halfway, so
 * that the first {@code n / 2} rules do not match and the rest do: under {@code FIRST},
 * {@code n / 2 + 1} rules are examined, and under {@code COLLECT} all {@code n + 1}, of which
 * half match.
 */
public final class Tables {

    /** The kind of comparison every rule makes. */
    public enum Input {
        /** {@code payment.amount >= money(…, 'VND')}: a money input in a struct. */
        MONEY,
        /** {@code payment.riskScore >= decimal('…')}: a decimal input in a struct. */
        DECIMAL,
        /** {@code payment.history.exists(t, t.amount >= money(…, 'VND'))}: a list of 20 structs. */
        LIST
    }

    static final String DECISION = "benchmark";

    /** Number of earlier transactions in {@code payment.history}. */
    static final int HISTORY = 20;

    private Tables() {}

    static DecisionTable table(HitPolicy hitPolicy, int rules, Input input) {
        ValueType transaction = ValueType.struct(Map.of("amount", ValueType.MONEY));
        ValueType payment = ValueType.struct(Map.of(
                "amount", ValueType.MONEY,
                "riskScore", ValueType.DECIMAL,
                "history", ValueType.listOf(transaction)));
        List<Rule> list = new ArrayList<>();
        for (int i = 0; i < rules; i++) {
            long threshold = (long) (rules - i) * 1_000_000;
            String when = switch (input) {
                case MONEY -> "payment.amount >= money(" + threshold + ", 'VND')";
                case DECIMAL -> "payment.riskScore >= decimal('" + threshold + ".5')";
                case LIST -> "payment.history.exists(t, t.amount >= money(" + threshold + ", 'VND'))";
            };
            list.add(Rule.of("R" + i, when, Map.of("action", "'REVIEW'", "level", String.valueOf(i)), "LEVEL_" + i));
        }
        list.add(Rule.of("DEFAULT", "true", Map.of("action", "'ALLOW'", "level", "-1")));
        return new DecisionTable(DECISION, "1", Optional.empty(), Optional.empty(), Optional.empty(), hitPolicy,
                Map.of("payment", payment),
                Map.of("action", ValueType.STRING, "level", ValueType.INT),
                list);
    }

    /** Facts halfway between the thresholds of a table of {@code rules} rules. */
    static Facts facts(int rules) {
        BigDecimal half = BigDecimal.valueOf((long) rules * 500_000 + 250_000);
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < HISTORY; i++) {
            // The largest earlier transaction comes last, so that exists() reads the whole list.
            history.add(Map.of("amount", Money.of(i == HISTORY - 1 ? half : BigDecimal.valueOf(1000L * i), "VND")));
        }
        return Facts.of("payment", Map.of(
                "amount", Money.of(half, "VND"),
                "riskScore", half,
                "history", history));
    }
}
