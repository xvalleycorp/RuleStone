//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CelExpressionTest {

    record Applicant(int age, BigDecimal income, String segment) {}

    enum Segment { RETAIL, SME }

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("applicant", ValueType.struct(Map.of(
                    "age", ValueType.INT,
                    "income", ValueType.DECIMAL,
                    "segment", ValueType.STRING)))
            .variable("amount", ValueType.DECIMAL)
            .variable("balances", ValueType.listOf(ValueType.DECIMAL))
            .build();

    private final Bindings inputs = env.bind(Map.of(
            "applicant", new Applicant(30, new BigDecimal("120000000"), "RETAIL"),
            "amount", new BigDecimal("500000000"),
            "balances", List.of(new BigDecimal("10.50"), new BigDecimal("-2"))));

    private Object eval(String source, ValueType type) {
        return env.compile(source, type).evaluate(inputs);
    }

    @Test
    void evaluatesTypedExpressionsOverRecords() {
        assertThat(eval("applicant.age >= 18 && applicant.segment in ['RETAIL', 'SME']", ValueType.BOOL))
                .isEqualTo(true);
        assertThat(eval("amount <= applicant.income * 5", ValueType.BOOL)).isEqualTo(true);
    }

    @Test
    void acceptsMapsAndEnums() {
        Bindings fromMap = env.bind(Map.of(
                "applicant", Map.of("age", 17, "income", 1000, "segment", Segment.SME),
                "amount", 1,
                "balances", List.of()));

        assertThat(env.compile("applicant.age < 18 && applicant.segment == 'SME'", ValueType.BOOL)
                .evaluate(fromMap)).isEqualTo(true);
    }

    @Test
    void decimalArithmeticIsExact() {
        assertThat(eval("decimal('0.1') + decimal('0.2') == decimal('0.3')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("amount * decimal('0.015')", ValueType.DECIMAL))
                .isEqualTo(new BigDecimal("7500000.000"));
        assertThat(eval("-amount + 1", ValueType.DECIMAL)).isEqualTo(new BigDecimal("-499999999"));
    }

    @Test
    void decimalEqualityIgnoresScale() {
        assertThat(eval("decimal('1.0') == decimal('1.00')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("decimal('1.0') in [decimal('1')]", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("balances.exists(b, b == decimal('10.5'))", ValueType.BOOL)).isEqualTo(true);
    }

    @Test
    void divisionRoundsToDecimal128AndRoundingIsExplicit() {
        assertThat(eval("decimal(1) / 3", ValueType.DECIMAL))
                .isEqualTo(new BigDecimal("0.3333333333333333333333333333333333"));
        assertThat(eval("decimal('2.345').round(2)", ValueType.DECIMAL)).isEqualTo(new BigDecimal("2.34"));
        assertThat(eval("decimal('2.345').round(2, 'HALF_UP')", ValueType.DECIMAL)).isEqualTo(new BigDecimal("2.35"));
        assertThat(eval("decimal('1500.5').round(0, 'floor')", ValueType.DECIMAL)).isEqualTo(new BigDecimal("1500"));
    }

    @Test
    void helperFunctions() {
        assertThat(eval("min(amount, applicant.income * 2)", ValueType.DECIMAL)).isEqualTo(new BigDecimal("240000000"));
        assertThat(eval("max(balances[0], balances[1].abs())", ValueType.DECIMAL)).isEqualTo(new BigDecimal("10.50"));
        assertThat(eval("string(balances[0])", ValueType.STRING)).isEqualTo("10.50");
    }

    @Test
    void rejectsFloatingPointAtCompileTime() {
        assertThatThrownBy(() -> env.compile("amount > 1.5", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("no matching overload");
    }

    @Test
    void rejectsDecimalEqualityWithIntAtCompileTime() {
        assertThatThrownBy(() -> env.compile("applicant.income == 120000000", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void rejectsUnknownFieldsAndVariablesAtCompileTime() {
        assertThatThrownBy(() -> env.compile("applicant.agee > 1", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("undefined field 'agee'");
        assertThatThrownBy(() -> env.compile("income > 0", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void rejectsWrongResultTypeAtCompileTime() {
        assertThatThrownBy(() -> env.compile("applicant.age", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("must produce bool");
    }

    @Test
    void reportsRuntimeErrors() {
        assertThatThrownBy(() -> eval("amount / decimal(0) > 1", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("division by zero");
        assertThatThrownBy(() -> eval("decimal(applicant.segment) > 1", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("not a valid number");
        assertThatThrownBy(() -> eval("decimal(1).round(2, applicant.segment) > 1", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown rounding mode");
    }

    @Test
    void errorMessagesNeverContainInputValues() {
        // "RETAIL" is an input value; it must not leak into messages that end up in logs.
        assertThatThrownBy(() -> eval("decimal(applicant.segment) > 1", ValueType.BOOL))
                .satisfies(e -> assertThat(e).hasStackTraceContaining("not a valid number"))
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("RETAIL"));
        assertThatThrownBy(() -> eval("decimal(1).round(2, applicant.segment) > 1", ValueType.BOOL))
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("RETAIL"));
        assertThatThrownBy(() -> env.bind(Map.of(
                "applicant", Map.of("age", new java.math.BigInteger("123456789012345678901234567890")),
                "amount", 9.87654321, "balances", List.of())))
                .satisfies(e -> assertThat(stackTraceOf(e))
                        .doesNotContain("123456789012345678901234567890")
                        .doesNotContain("9.87654321"));
    }

    private static String stackTraceOf(Throwable t) {
        var out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    @Test
    void rejectsInvalidInputsWithPath() {
        assertThatThrownBy(() -> env.bind(Map.of(
                "applicant", Map.of("age", 30, "income", 1.5e8, "segment", "RETAIL"),
                "amount", BigDecimal.ONE,
                "balances", List.of())))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageContaining("applicant.income")
                .hasMessageContaining("floating-point");
        assertThatThrownBy(() -> env.bind(Map.of("applicant", Map.of(), "balances", List.of())))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageContaining("amount: missing input");
    }

    @Test
    void absentStructFieldsCanBeTestedWithHas() {
        Bindings partial = env.bind(Map.of("applicant", Map.of("age", 30), "amount", 1, "balances", List.of()));

        assertThat(env.compile("has(applicant.income)", ValueType.BOOL).evaluate(partial)).isEqualTo(false);
        assertThatThrownBy(() -> env.compile("applicant.income > 0", ValueType.BOOL).evaluate(partial))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void boundsComprehensions() {
        ExpressionEnvironment small = ExpressionEnvironment.builder()
                .variable("xs", ValueType.listOf(ValueType.INT))
                .maxIterations(3)
                .build();
        Bindings many = small.bind(Map.of("xs", List.of(1, 2, 3, 4, 5)));

        assertThatThrownBy(() -> small.compile("xs.all(x, x > 0)", ValueType.BOOL).evaluate(many))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void rejectsBindingsFromAnotherEnvironment() {
        ExpressionEnvironment other = ExpressionEnvironment.builder().variable("x", ValueType.INT).build();

        assertThatThrownBy(() -> env.compile("true", ValueType.BOOL).evaluate(other.bind(Map.of("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
