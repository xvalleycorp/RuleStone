//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DateTest {

    record Applicant(LocalDate birthDate, String idText) {}

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("today", ValueType.DATE)
            .variable("applicant", ValueType.struct(Map.of("birthDate", ValueType.DATE, "idText", ValueType.STRING)))
            .variable("holidays", ValueType.listOf(ValueType.DATE))
            .build();

    private final Bindings inputs = env.bind(Map.of(
            "today", LocalDate.of(2026, 11, 1),
            "applicant", new Applicant(LocalDate.of(2008, 11, 2), "1987-06-05X"),
            "holidays", List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 2))));

    private Object eval(String source, ValueType type) {
        return env.compile(source, type).evaluate(inputs);
    }

    @Test
    void constructsComparesAndPrintsDates() {
        assertThat(eval("date('2026-11-01')", ValueType.DATE)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(eval("today == date('2026-11-01')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("today != date('2026-11-02')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("today < date('2026-11-02')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("today <= date('2026-11-01')", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("today > applicant.birthDate", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("today >= date('2026-11-02')", ValueType.BOOL)).isEqualTo(false);
        assertThat(eval("date('2026-09-02') in holidays", ValueType.BOOL)).isEqualTo(true);
        assertThat(eval("string(today)", ValueType.STRING)).isEqualTo("2026-11-01");
    }

    @Test
    void shiftsDates() {
        assertThat(eval("today.plusDays(30)", ValueType.DATE)).hasToString("2026-12-01");
        assertThat(eval("today.plusDays(-1)", ValueType.DATE)).hasToString("2026-10-31");
        assertThat(eval("date('2026-01-31').plusMonths(1)", ValueType.DATE)).hasToString("2026-02-28");
        assertThat(eval("date('2028-02-29').plusYears(1)", ValueType.DATE)).hasToString("2029-02-28");
    }

    @Test
    void readsDateFields() {
        assertThat(eval("today.year()", ValueType.INT)).isEqualTo(2026L);
        assertThat(eval("today.month()", ValueType.INT)).isEqualTo(11L);
        assertThat(eval("today.day()", ValueType.INT)).isEqualTo(1L);
        assertThat(eval("today.dayOfWeek()", ValueType.INT)).isEqualTo(7L);       // a Sunday
        assertThat(eval("today.plusDays(1).dayOfWeek()", ValueType.INT)).isEqualTo(1L);
    }

    @Test
    void countsCompleteUnitsBetweenDates() {
        assertThat(eval("yearsBetween(applicant.birthDate, today)", ValueType.INT)).isEqualTo(17L);
        assertThat(eval("yearsBetween(applicant.birthDate, today.plusDays(1))", ValueType.INT)).isEqualTo(18L);
        assertThat(eval("monthsBetween(date('2026-01-31'), date('2026-02-28'))", ValueType.INT)).isEqualTo(0L);
        assertThat(eval("monthsBetween(date('2026-01-15'), date('2026-03-15'))", ValueType.INT)).isEqualTo(2L);
        assertThat(eval("daysBetween(date('2026-10-01'), today)", ValueType.INT)).isEqualTo(31L);
        assertThat(eval("daysBetween(today, date('2026-10-01'))", ValueType.INT)).isEqualTo(-31L);
    }

    @Test
    void rejectsTypeErrorsAtCompileTime() {
        for (String source : List.of("today == '2026-11-01'", "today < 5", "today.plusDays(decimal(1)) == today",
                "today + 1 == today", "date(20261101) == today")) {
            assertThatThrownBy(() -> env.compile(source, ValueType.BOOL))
                    .as(source)
                    .isInstanceOf(ExpressionException.class)
                    .hasMessageNotContaining("must produce");
        }
    }

    @Test
    void reportsRuntimeErrorsWithoutInputValues() {
        assertThatThrownBy(() -> eval("date(applicant.idText)", ValueType.DATE))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("date() argument is not a valid ISO date (yyyy-MM-dd)")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("1987-06-05X"));
        assertThatThrownBy(() -> eval("date('2026-02-30')", ValueType.DATE))
                .hasMessageContaining("not a valid ISO date");
        assertThatThrownBy(() -> eval("date('01/11/2026')", ValueType.DATE))
                .hasMessageContaining("not a valid ISO date");
        assertThatThrownBy(() -> eval("applicant.birthDate.plusYears(9223372036854775807)", ValueType.DATE))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("plusYears() result is outside the supported date range")
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("2008"));
    }

    @Test
    void acceptsOnlyLocalDateInputs() {
        for (Object wrong : List.of("2026-11-01", LocalDateTime.of(2026, 11, 1, 0, 0), 20261101)) {
            assertThatThrownBy(() -> env.bind(Map.of(
                    "today", wrong, "applicant", Map.of(), "holidays", List.of())))
                    .isInstanceOf(InvalidValueException.class)
                    .hasMessageStartingWith("today: expected date but got " + wrong.getClass().getName())
                    .hasMessageEndingWith("; pass a java.time.LocalDate")
                    .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("2026"));
        }
        assertThatThrownBy(() -> env.bind(Map.of(
                "today", LocalDate.of(2026, 11, 1), "applicant", Map.of("birthDate", "1990-01-01"),
                "holidays", List.of())))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageStartingWith("applicant.birthDate: expected date");
    }

    private static String stackTraceOf(Throwable t) {
        var out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
