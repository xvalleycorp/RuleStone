//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.cel.Bindings;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ValueType;

/** Runs the code shown in docs/java-api.md. Update both together. */
class JavaApiDocTest {

    @Test
    void decisionTableBuiltInJava() {
        DecisionTable table = new DecisionTable(
                "risk_band", "1", Optional.empty(), Optional.empty(), Optional.empty(),
                HitPolicy.FIRST,
                Map.of("score", ValueType.INT),
                Map.of("band", ValueType.STRING),
                List.of(
                        Rule.of("HIGH", "score < 500", Map.of("band", "'HIGH'"), "LOW_SCORE"),
                        Rule.of("NORMAL", "true", Map.of("band", "'NORMAL'"))));

        DecisionResult result = RuleStone.builder().decision(table).build()
                .evaluate(DecisionRef.of("risk_band"), Facts.of("score", 420));

        assertThat(result.output()).containsEntry("band", "HIGH");
        assertThat(result.reasonCodes()).containsExactly("LOW_SCORE");
    }

    @Test
    void standaloneExpression() {
        ExpressionEnvironment env = ExpressionEnvironment.builder()
                .variable("amount", ValueType.DECIMAL)
                .variable("tenorMonths", ValueType.INT)
                .build();

        CelExpression fee = env.compile("(amount * decimal('0.01') / tenorMonths).round(0)", ValueType.DECIMAL);

        Bindings inputs = env.bind(Map.of("amount", new BigDecimal("12000000"), "tenorMonths", 12));
        BigDecimal value = (BigDecimal) fee.evaluate(inputs);

        assertThat(value).hasToString("10000");
    }
}
