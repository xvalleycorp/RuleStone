//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.Money;

class AggregateTest {

    record Loan(Money outstanding, String type) {}

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("counts", ValueType.listOf(ValueType.INT))
            .variable("scores", ValueType.listOf(ValueType.DECIMAL))
            .variable("loans", ValueType.listOf(ValueType.struct(Map.of(
                    "outstanding", ValueType.MONEY, "type", ValueType.STRING))))
            .variable("none", ValueType.listOf(ValueType.MONEY))
            .build();

    private final Bindings inputs = env.bind(Map.of(
            "counts", List.of(3, 1, 2),
            "scores", List.of(new BigDecimal("0.70"), new BigDecimal("0.9"), new BigDecimal("0.5")),
            "loans", List.of(new Loan(Money.of("100000000", "VND"), "HOME"),
                    new Loan(Money.of("25000000.50", "VND"), "CARD"),
                    new Loan(Money.of("5000000", "VND"), "CARD")),
            "none", List.of()));

    private Object eval(String source, ValueType type) {
        return env.compile(source, type).evaluate(inputs);
    }

    @Test
    void sumsIntsDecimalsAndMoney() {
        assertThat(eval("counts.sum()", ValueType.INT)).isEqualTo(6L);
        assertThat(eval("scores.sum()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("2.10"));
        assertThat(eval("loans.map(l, l.outstanding).sum()", ValueType.MONEY)).hasToString("130000000.50 VND");
        assertThat(eval("loans.filter(l, l.type == 'CARD').map(l, l.outstanding).sum(money(0, 'VND'))",
                ValueType.MONEY)).hasToString("30000000.50 VND");
        assertThat(eval("[1, 2].sum() == 3", ValueType.BOOL)).isEqualTo(true);
    }

    @Test
    void emptyLists() {
        assertThat(eval("counts.filter(c, c > 5).sum()", ValueType.INT)).isEqualTo(0L);
        assertThat(eval("scores.filter(s, s > 5).sum()", ValueType.DECIMAL)).isEqualTo(BigDecimal.ZERO);
        assertThat(eval("none.sum(money(0, 'VND'))", ValueType.MONEY)).hasToString("0 VND");
        assertThat(eval("counts.filter(c, c > 5).sum(7)", ValueType.INT)).isEqualTo(7L);
        assertThatThrownBy(() -> eval("none.sum()", ValueType.MONEY))
                .hasMessageStartingWith("sum() of an empty money list has no currency");
        assertThatThrownBy(() -> eval("none.min()", ValueType.MONEY)).hasMessageStartingWith("min() of an empty list");
        assertThatThrownBy(() -> eval("none.max()", ValueType.MONEY)).hasMessageStartingWith("max() of an empty list");
        assertThatThrownBy(() -> eval("none.avg()", ValueType.MONEY)).hasMessageStartingWith("avg() of an empty list");
    }

    @Test
    void minMaxAndAverage() {
        assertThat(eval("counts.min()", ValueType.INT)).isEqualTo(1L);
        assertThat(eval("counts.max()", ValueType.INT)).isEqualTo(3L);
        assertThat(eval("scores.max()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("0.9"));
        assertThat(eval("[decimal('1.0'), decimal('1.00')].max()", ValueType.DECIMAL)).hasToString("1.0");
        assertThat(eval("loans.map(l, l.outstanding).min()", ValueType.MONEY)).hasToString("5000000 VND");
        assertThat(eval("counts.avg()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("2"));
        assertThat(eval("[1, 2].avg()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("1.5"));
        assertThat(eval("[1, 1, 2].avg()", ValueType.DECIMAL)).hasToString("1.333333333333333333333333333333333");
        assertThat(eval("scores.avg()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("0.70"));
        assertThat(eval("[money(100, 'VND'), money(0, 'VND'), money(0, 'VND')].avg()", ValueType.MONEY))
                .hasToString("33.33333333333333333333333333333333 VND");
    }

    @Test
    void existingTwoArgumentMinAndMaxStillWork() {
        assertThat(eval("min(decimal(1), decimal(2))", ValueType.DECIMAL)).isEqualTo(BigDecimal.ONE);
        assertThat(eval("max(money(1, 'VND'), money(2, 'VND'))", ValueType.MONEY)).hasToString("2 VND");
    }

    @Test
    void failsOnMixedCurrenciesAndOverflow() {
        assertThatThrownBy(() -> eval("[money(1, 'VND'), money(1, 'USD')].sum()", ValueType.MONEY))
                .hasMessageStartingWith("money currencies differ: VND and USD");
        assertThatThrownBy(() -> eval("[money(1, 'USD')].sum(money(0, 'VND'))", ValueType.MONEY))
                .hasMessageStartingWith("money currencies differ: VND and USD");
        assertThatThrownBy(() -> eval("[money(1, 'VND'), money(1, 'USD')].max()", ValueType.MONEY))
                .hasMessageStartingWith("money currencies differ: VND and USD");
        assertThatThrownBy(() -> eval("[9223372036854775807, 1].sum()", ValueType.INT))
                .hasMessageStartingWith("sum() result is out of int range");
        assertThat(eval("[9223372036854775807, 1, -1].sum()", ValueType.INT)).isEqualTo(Long.MAX_VALUE);
        assertThat(eval("[9223372036854775807, 9223372036854775807].avg()", ValueType.DECIMAL))
                .isEqualTo(new BigDecimal("9223372036854775807"));
    }

    @Test
    void rejectsOtherElementTypesWhenLoading() {
        assertThatThrownBy(() -> env.compile("['a'].sum() == 'a'", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("found no matching overload for 'sum'");
        assertThatThrownBy(() -> env.compile("counts.sum(decimal(0)) > 1", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void everyElementCountsTowardsTheIterationLimit() {
        ExpressionEnvironment limited = ExpressionEnvironment.builder()
                .variable("xs", ValueType.listOf(ValueType.INT))
                .maxIterations(5)
                .build();
        Bindings five = limited.bind(Map.of("xs", List.of(1, 2, 3, 4, 5)));
        assertThat(limited.compile("xs.sum()", ValueType.INT).evaluate(five)).isEqualTo(15L);
        Bindings six = limited.bind(Map.of("xs", List.of(1, 2, 3, 4, 5, 6)));
        assertThatThrownBy(() -> limited.compile("xs.sum()", ValueType.INT).evaluate(six))
                .hasMessageContaining("Iteration budget exceeded: 5");
        // map and sum share the budget of the evaluation
        Bindings three = limited.bind(Map.of("xs", List.of(1, 2, 3)));
        assertThatThrownBy(() -> limited.compile("xs.map(x, x).sum()", ValueType.INT).evaluate(three))
                .hasMessageContaining("Iteration budget exceeded: 5");
    }

    @Test
    void largeListsAreSummedInLinearTime() {
        List<Integer> many = new ArrayList<>(Collections.nCopies(10_000, 1));
        Bindings bindings = env.bind(Map.of("counts", many, "scores", List.of(), "loans", List.of(), "none", List.of()));
        assertThat(env.compile("counts.sum()", ValueType.INT).evaluate(bindings)).isEqualTo(10_000L);
    }
}
