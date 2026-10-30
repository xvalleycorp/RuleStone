//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.ValueType;

/** Checks the examples in docs/expressions.md. Update both together. */
class ExpressionsDocTest {

    private static final Path DOC = Path.of("..", "docs", "expressions.md");
    private static final List<ValueType> RESULT_TYPES = List.of(ValueType.BOOL, ValueType.INT, ValueType.STRING,
            ValueType.DECIMAL, ValueType.MONEY, ValueType.DATE, ValueType.listOf(ValueType.STRING));

    /** Inputs that the documented examples refer to. */
    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("applicant", ValueType.struct(Map.of(
                    "age", ValueType.INT, "blacklisted", ValueType.BOOL, "segment", ValueType.STRING,
                    "monthlyIncome", ValueType.DECIMAL, "monthlyDebt", ValueType.DECIMAL,
                    "idNumber", ValueType.STRING, "birthDate", ValueType.DATE)))
            .variable("amount", ValueType.DECIMAL)
            .variable("fee", ValueType.DECIMAL)
            .variable("balance", ValueType.DECIMAL)
            .variable("income", ValueType.DECIMAL)
            .variable("x", ValueType.DECIMAL)
            .variable("m", ValueType.MONEY)
            .variable("transfer", ValueType.struct(Map.of("amount", ValueType.DECIMAL)))
            .variable("payment", ValueType.struct(Map.of("amount", ValueType.MONEY)))
            .variable("loan", ValueType.struct(Map.of("balance", ValueType.MONEY, "maturityDate", ValueType.DATE)))
            .variable("invoice", ValueType.struct(Map.of("dueDate", ValueType.DATE)))
            .variable("customer", ValueType.struct(Map.of("segment", ValueType.STRING, "fullName", ValueType.STRING,
                    "accounts", ValueType.listOf(ValueType.struct(Map.of(
                            "type", ValueType.STRING, "balance", ValueType.DECIMAL))))))
            .variable("watchlist", ValueType.listOf(ValueType.STRING))
            .variable("loans", ValueType.listOf(ValueType.struct(Map.of(
                    "type", ValueType.STRING, "outstanding", ValueType.MONEY))))
            .variable("transactions", ValueType.listOf(ValueType.struct(Map.of(
                    "channel", ValueType.STRING, "amount", ValueType.MONEY))))
            .variable("scores", ValueType.listOf(ValueType.DECIMAL))
            .variable("businessDate", ValueType.DATE)
            .build();

    private static final Map<String, Object> INPUTS = Map.ofEntries(
            Map.entry("applicant", Map.of()), Map.entry("amount", 1), Map.entry("fee", 1), Map.entry("balance", 1),
            Map.entry("income", 1), Map.entry("x", 1), Map.entry("m", Money.of("1500000", "VND")),
            Map.entry("transfer", Map.of("amount", 1)), Map.entry("payment", Map.of("amount", Money.of("1500000", "VND"))),
            Map.entry("loan", Map.of()),
            Map.entry("invoice", Map.of()), Map.entry("customer", Map.of()), Map.entry("watchlist", List.of()),
            Map.entry("loans", List.of()), Map.entry("transactions", List.of()), Map.entry("scores", List.of()),
            Map.entry("businessDate", LocalDate.of(2026, 11, 1)));

    private Object eval(String source) {
        ExpressionException last = null;
        for (ValueType type : RESULT_TYPES) {
            CelExpression expression;
            try {
                expression = env.compile(source, type);
            } catch (ExpressionException e) {
                last = e;
                continue;
            }
            return expression.evaluate(env.bind(INPUTS));
        }
        throw new AssertionError("does not compile: " + source, last);
    }

    private void compiles(String source) {
        for (ValueType type : RESULT_TYPES) {
            try {
                env.compile(source, type);
                return;
            } catch (ExpressionException e) {
                // try the next type
            }
        }
        throw new AssertionError("does not compile: " + source);
    }

    private void typeError(String source) {
        for (ValueType type : RESULT_TYPES) {
            assertThatThrownBy(() -> env.compile(source, type)).as(source)
                    .isInstanceOf(ExpressionException.class)
                    .hasMessageNotContaining("must produce");
        }
    }

    @Test
    void everyExampleArrowResultHolds() throws IOException {
        // Table cells of the form  `expression` → result
        Pattern example = Pattern.compile("`([^`]+)` → `?([^`|]+?)`?\\s*\\|");
        List<String> checked = new ArrayList<>();
        for (String line : Files.readAllLines(DOC)) {
            Matcher m = example.matcher(line);
            while (m.find()) {
                String expected = m.group(2).strip();
                if (expected.startsWith("'") && expected.endsWith("'")) {
                    expected = expected.substring(1, expected.length() - 1);
                }
                assertThat(String.valueOf(eval(m.group(1)))).as(m.group(1)).isEqualTo(expected);
                checked.add(m.group(1));
            }
        }
        assertThat(checked).contains("money('1234.565', 'USD').round()", "date('2026-11-01').dayOfWeek()",
                "yearsBetween(date('2008-11-02'), date('2026-11-01'))", "string(date('2026-11-01'))");
        assertThat(checked).hasSizeGreaterThanOrEqualTo(13);
    }

    @Test
    void quickReferenceAndCodeExamplesCompile() throws IOException {
        String doc = Files.readString(DOC);
        Matcher blocks = Pattern.compile("```\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        int count = 0;
        while (blocks.find()) {
            for (String line : blocks.group(1).split("\\n")) {
                String source = line.replaceAll("//.*", "").strip();
                if (!source.isEmpty()) {
                    compiles(source);
                    count++;
                }
            }
        }
        assertThat(count).isGreaterThan(30);
    }

    @Test
    void money() {
        assertThat(eval("money('1500000.50', 'VND')")).hasToString("1500000.50 VND");
        assertThat(eval("money(decimal('12.5'), 'USD')")).hasToString("12.5 USD");
        assertThat(eval("money(2000, 'VND')")).hasToString("2000 VND");
        assertThat(eval("money('1.0', 'USD') == money('1.00', 'USD')")).isEqualTo(true);
        assertThat(eval("money(1, 'VND') == money(1, 'USD')")).isEqualTo(false);
        assertThat(eval("money('100', 'VND') / 3")).hasToString("33.33333333333333333333333333333333 VND");
        assertThat(eval("m + money(2000, m.currency())")).hasToString("1502000 VND");
        assertThat(eval("m > money(1000000, 'VND')")).isEqualTo(true);
        assertThat(eval("m.amount()")).isEqualTo(new BigDecimal("1500000"));
        assertThat(eval("m.currency()")).isEqualTo("VND");
        assertThat(eval("string(m)")).isEqualTo("1500000 VND");
        typeError("m + 2000");
        typeError("m > 1000000");
        typeError("m * m");
    }

    @Test
    void mixingCurrenciesFails() {
        assertThatThrownBy(() -> eval("money(1, 'VND') + money(1, 'USD')"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("money currencies differ: VND and USD");
        assertThatThrownBy(() -> eval("money(1, 'vnd')")).hasMessageContaining("not an ISO 4217 code");
        assertThatThrownBy(() -> eval("money('abc', 'VND')")).hasMessageContaining("not a valid number");
    }

    @Test
    void minorUnitsComeFromIso4217() {
        assertThat(eval("money('1.23456', 'VND').round()")).hasToString("1 VND");
        assertThat(eval("money('1.23456', 'JPY').round()")).hasToString("1 JPY");
        assertThat(eval("money('1.23456', 'USD').round()")).hasToString("1.23 USD");
        assertThat(eval("money('1.23456', 'EUR').round()")).hasToString("1.23 EUR");
        assertThat(eval("money('1.23456', 'KWD').round()")).hasToString("1.235 KWD");
        assertThat(eval("money('2.5', 'VND').round()")).hasToString("2 VND");
        assertThatThrownBy(() -> eval("money('1.5', 'XAU').round()"))
                .hasMessageContaining("currency XAU has no minor unit");
    }

    @Test
    void paymentFeeExample() {
        String fee = "max(money(2000, 'VND'), (payment.amount * decimal('0.0005')).round())";
        ExpressionEnvironment payment = ExpressionEnvironment.builder()
                .variable("payment", ValueType.struct(Map.of("amount", ValueType.MONEY)))
                .build();
        CelExpression expression = payment.compile(fee, ValueType.MONEY);

        assertThat(expression.evaluate(payment.bind(Map.of("payment", Map.of("amount", Money.of("1500000", "VND"))))))
                .hasToString("2000 VND");
        assertThat(expression.evaluate(payment.bind(Map.of("payment", Map.of("amount", Money.of("10000000", "VND"))))))
                .hasToString("5000 VND");
    }

    @Test
    void dates() {
        assertThat(eval("date('2026-11-01')")).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(eval("date('2026-11-02') > date('2026-11-01')")).isEqualTo(true);
        assertThat(eval("daysBetween(date('2026-11-01'), date('2026-10-01'))")).isEqualTo(-31L);
        assertThatThrownBy(() -> eval("date('01/11/2026')")).hasMessageContaining("not a valid ISO date");
        assertThatThrownBy(() -> eval("date('2026-02-30')")).hasMessageContaining("not a valid ISO date");
    }

    @Test
    void businessDateExamples() {
        DecisionTable table = new DecisionTable("doc", "1", Optional.empty(), Optional.empty(), Optional.empty(),
                HitPolicy.FIRST,
                Map.of("applicant", ValueType.struct(Map.of("birthDate", ValueType.DATE)),
                        "loan", ValueType.struct(Map.of("maturityDate", ValueType.DATE))),
                Map.of("adult", ValueType.BOOL, "weekend", ValueType.BOOL, "maturing", ValueType.BOOL),
                List.of(Rule.of("ALL", "true", Map.of(
                        "adult", "yearsBetween(applicant.birthDate, businessDate) >= 18",
                        "weekend", "businessDate.dayOfWeek() >= 6",
                        "maturing", "loan.maturityDate <= businessDate.plusMonths(3)"))));

        DecisionResult result = RuleStone.builder().decision(table).build().evaluate(
                DecisionRef.of("doc").asOf(LocalDate.of(2026, 11, 1)),
                Facts.of("applicant", Map.of("birthDate", LocalDate.of(2008, 11, 1)),
                        "loan", Map.of("maturityDate", LocalDate.of(2027, 2, 1))));

        assertThat(result.output()).containsEntry("adult", true).containsEntry("weekend", true)
                .containsEntry("maturing", true);
    }
}
