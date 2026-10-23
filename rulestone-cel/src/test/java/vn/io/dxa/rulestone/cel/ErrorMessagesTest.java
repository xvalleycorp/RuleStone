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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Evaluation errors never quote input values, including those of CEL's standard functions. */
class ErrorMessagesTest {

    private static final String SECRET = "S3CRET-4711";

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("text", ValueType.STRING)
            .variable("position", ValueType.INT)
            .variable("names", ValueType.listOf(ValueType.STRING))
            .build();

    private final Bindings inputs = env.bind(Map.of("text", SECRET, "position", 4711L, "names", List.of(SECRET)));

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "int(text) > 0                 | int() argument is not a valid integer",
            "uint(text) > 0u               | uint() argument is not a valid unsigned integer",
            "double(text) > 0.0            | double() argument is not a valid number",
            "bool(text)                    | bool() argument is not a valid boolean",
            "names[position] == ''         | list index out of bounds",
            "{'a': 1}[text] == 1           | map has no such key",
            "'abc'.matches(text + '[')     | matches() pattern is not a valid regular expression",
            "matches('abc', text + '(')    | matches() pattern is not a valid regular expression",
            "timestamp(text) == timestamp(0) | timestamp() argument is not a valid timestamp",
            "duration(text) == duration('1s') | duration() argument is not a valid duration",
            "decimal(text) > decimal(0)    | decimal() argument is not a valid number",
            "money(text, 'VND') > money(0, 'VND') | money() amount is not a valid number",
            "money(1, text) > money(0, 'VND') | money() currency is not an ISO 4217 code in upper case",
            "date(text) > date('2026-01-01') | date() argument is not a valid ISO date (yyyy-MM-dd)"})
    void failuresNameTheFunctionButNotTheValue(String source, String message) {
        CelExpression expression = env.compile(source, ValueType.BOOL);
        assertThatThrownBy(() -> expression.evaluate(inputs))
                .isInstanceOf(ExpressionException.class)
                .hasMessage(message + " in: " + source)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("4711"));
    }

    @Test
    void valuesOfTheWrappedFunctionsAreUnchanged() {
        assertThat(eval("int('-42')", ValueType.INT)).isEqualTo(-42L);
        assertThat(eval("int('+42') == 42 && uint('7') == 7u && double('1.5') == 1.5", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("bool('true') && bool('t') && !bool('False')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("names[0]", ValueType.STRING)).isEqualTo(SECRET);
        assertThat(eval("{'a': 1}['a'] == 1 && 'abc'.matches('^a.c$') && matches('abc', 'b')", ValueType.BOOL))
                .isEqualTo(true);
        assertThat(eval("[0, 1].exists(i, names[i] == '" + SECRET + "')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("decimal(int('12'))", ValueType.DECIMAL)).isEqualTo(new BigDecimal("12"));
    }

    private Object eval(String source, ValueType type) {
        return env.compile(source, type).evaluate(inputs);
    }
}
