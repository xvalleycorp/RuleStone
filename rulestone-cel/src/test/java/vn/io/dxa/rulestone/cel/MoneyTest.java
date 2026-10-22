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
import vn.io.dxa.rulestone.api.Money;

class MoneyTest {

    record Account(Money balance, String currencyCode) {}

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("amount", ValueType.MONEY)
            .variable("usd", ValueType.MONEY)
            .variable("rate", ValueType.DECIMAL)
            .variable("account", ValueType.struct(Map.of("balance", ValueType.MONEY, "currencyCode", ValueType.STRING)))
            .variable("fees", ValueType.listOf(ValueType.MONEY))
            .build();

    private final Bindings inputs = env.bind(Map.of(
            "amount", Money.of("1500000", "VND"),
            "usd", Money.of("1234.565", "USD"),
            "rate", new BigDecimal("0.015"),
            "account", new Account(Money.of("987654321", "VND"), "SECRET"),
            "fees", List.of(Money.of("2000", "VND"), Money.of("3000.00", "VND"))));

    private Object eval(String source, ValueType type) {
        return env.compile(source, type).evaluate(inputs);
    }

    private Object money(String source) {
        return eval(source, ValueType.MONEY);
    }

    @Test
    void constructsAndReadsMoney() {
        assertThat(money("money('1500000.50', 'VND')")).hasToString("1500000.50 VND");
        assertThat(money("money(decimal('12.5'), 'USD')")).isEqualTo(Money.of("12.5", "USD"));
        assertThat(money("money(2000, 'VND')")).isEqualTo(Money.of("2000", "VND"));
        assertThat(eval("amount.amount()", ValueType.DECIMAL)).isEqualTo(new BigDecimal("1500000"));
        assertThat(eval("amount.currency()", ValueType.STRING)).isEqualTo("VND");
        assertThat(eval("string(money('1500000', 'VND'))", ValueType.STRING)).isEqualTo("1500000 VND");
        assertThat(eval("string(usd)", ValueType.STRING)).isEqualTo("1234.565 USD");
    }

    @Test
    void arithmeticKeepsFullPrecision() {
        assertThat(money("amount + money(2000, 'VND')")).hasToString("1502000 VND");
        assertThat(money("amount - fees[0]")).hasToString("1498000 VND");
        assertThat(money("amount * rate")).hasToString("22500.000 VND");
        assertThat(money("rate * amount")).hasToString("22500.000 VND");
        assertThat(money("amount * 2")).hasToString("3000000 VND");
        assertThat(money("3 * amount")).hasToString("4500000 VND");
        assertThat(money("money(100, 'VND') / 3")).hasToString("33.33333333333333333333333333333333 VND");
        assertThat(money("amount / decimal('0.5')")).hasToString("3000000 VND");
        assertThat(money("-amount")).hasToString("-1500000 VND");
        assertThat(money("(-amount).abs()")).hasToString("1500000 VND");
    }

    @Test
    void comparesWithinOneCurrency() {
        assertThat(eval("amount > money(1000000, 'VND')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("amount <= money('1500000.00', 'VND')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("amount < fees[0]", ValueType.BOOL)).isEqualTo(false);
        assertThat(eval("amount >= fees[1]", ValueType.BOOL)).isEqualTo(true);
        assertThat(money("min(fees[0], fees[1])")).hasToString("2000 VND");
        assertThat(money("max(fees[0], fees[1])")).hasToString("3000.00 VND");
    }

    @Test
    void equalityIgnoresScaleButNotCurrency() {
        assertThat(eval("fees[1] == money(3000, 'VND')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("money(3000, 'VND') in fees", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("money(1, 'VND') == money(1, 'USD')", ValueType.BOOL)).isEqualTo(false);
        assertThat(eval("money(1, 'VND') != money(1, 'USD')", ValueType.BOOL)).isEqualTo(true);
    }

    @Test
    void roundsToCurrencyMinorUnits() {
        assertThat(money("usd.round()")).hasToString("1234.56 USD");
        assertThat(money("usd.round('HALF_UP')")).hasToString("1234.57 USD");
        assertThat(money("(amount * decimal('0.0005')).round()")).hasToString("750 VND");
        assertThat(money("money('2.5', 'VND').round()")).hasToString("2 VND");
        assertThat(money("money('2.5', 'VND').round('half_up')")).hasToString("3 VND");
        assertThat(money("money('1.2345', 'KWD').round()")).hasToString("1.234 KWD");
        assertThat(money("money('100', 'JPY').round()")).hasToString("100 JPY");
    }

    @Test
    void currencyScaleOverridesIso4217() {
        ExpressionEnvironment custom = ExpressionEnvironment.builder()
                .variable("x", ValueType.INT)
                .currencyScale("VND", 2)
                .currencyScale("XAU", 4)
                .build();
        Bindings none = custom.bind(Map.of("x", 1));

        assertThat(custom.compile("money('1.005', 'VND').round()", ValueType.MONEY).evaluate(none))
                .hasToString("1.00 VND");
        assertThat(custom.compile("money('1.23456', 'XAU').round()", ValueType.MONEY).evaluate(none))
                .hasToString("1.2346 XAU");
        assertThat(custom.currencyScales()).containsEntry("XAU", 4);
    }

    @Test
    void currencyWithoutMinorUnitNeedsAnOverride() {
        assertThatThrownBy(() -> money("money('1.5', 'XAU').round()"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("currency XAU has no minor unit")
                .hasMessageContaining("currencyScale");
    }

    @Test
    void rejectsInvalidCurrencyScales() {
        assertThatThrownBy(() -> ExpressionEnvironment.builder().currencyScale("vnd", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionEnvironment.builder().currencyScale("VND", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionEnvironment.builder().currencyScale("VND", 19))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixingCurrenciesFailsNamingOnlyTheCurrencies() {
        for (String source : List.of("amount + usd", "amount - usd", "amount < usd", "amount >= usd",
                "min(amount, usd)", "max(usd, amount)")) {
            assertThatThrownBy(() -> eval(source, source.contains("<") || source.contains(">=")
                    ? ValueType.BOOL : ValueType.MONEY))
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(e.getMessage()).contains("money currencies differ: ")
                            .containsAnyOf("VND and USD", "USD and VND"))
                    .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("1500000").doesNotContain("1234.565"));
        }
    }

    @Test
    void reportsRuntimeErrorsWithoutInputValues() {
        assertThatThrownBy(() -> money("amount / decimal(0)"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("money division by zero");
        assertThatThrownBy(() -> money("amount / 0")).hasMessageContaining("money division by zero");
        assertThatThrownBy(() -> money("money(account.currencyCode, 'VND')"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("money() amount is not a valid number")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("SECRET"));
        assertThatThrownBy(() -> money("money(1, account.currencyCode)"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("money() currency is not an ISO 4217 code")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("SECRET"));
        assertThatThrownBy(() -> money("account.balance.round(account.currencyCode)"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown rounding mode")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("SECRET").doesNotContain("987654321"));
    }

    @Test
    void rejectsTypeErrorsAtCompileTime() {
        for (String source : List.of("amount + 1 == amount", "amount * amount == amount", "amount == decimal(1)",
                "2 / amount == amount", "amount > 1", "amount * 1.5 == amount", "money(1.5, 'VND') == amount")) {
            assertThatThrownBy(() -> env.compile(source, ValueType.BOOL))
                    .as(source)
                    .isInstanceOf(ExpressionException.class)
                    .hasMessageNotContaining("must produce");
        }
    }

    @Test
    void acceptsOnlyMoneyInputs() {
        assertThatThrownBy(() -> env.bind(Map.of(
                "amount", new BigDecimal("1500000"), "usd", Money.of("1", "USD"), "rate", BigDecimal.ONE,
                "account", Map.of(), "fees", List.of())))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage("amount: expected money but got java.math.BigDecimal; pass a vn.io.dxa.rulestone.api.Money")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("1500000"));
        assertThatThrownBy(() -> env.bind(Map.of(
                "amount", Money.of("1", "VND"), "usd", Money.of("1", "USD"), "rate", BigDecimal.ONE,
                "account", Map.of("balance", 98.765), "fees", List.of())))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageStartingWith("account.balance: expected money but got java.lang.Double")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("98.765"));
    }

    private static String stackTraceOf(Throwable t) {
        var out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
