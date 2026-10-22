//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The {@code date} type: a calendar date without time or time zone, such as a business date
 * or a date of birth. Its runtime form is {@link LocalDate}; CEL's {@code timestamp} is not
 * used, so no time zone can change a result.
 *
 * <ul>
 *   <li>Construction: {@code date('2026-11-01')} (ISO {@code yyyy-MM-dd} only)</li>
 *   <li>Comparison {@code == != < <= > >=}</li>
 *   <li>{@code d.plusDays(n)}, {@code d.plusMonths(n)}, {@code d.plusYears(n)}; {@code n} may be
 *       negative. Month and year arithmetic clamps to the end of the month, as {@link LocalDate}
 *       does: {@code date('2026-01-31').plusMonths(1)} is {@code 2026-02-28}.</li>
 *   <li>{@code d.year()}, {@code d.month()} (1 to 12), {@code d.day()},
 *       {@code d.dayOfWeek()} (1 = Monday to 7 = Sunday)</li>
 *   <li>{@code daysBetween(a, b)}, {@code monthsBetween(a, b)}, {@code yearsBetween(a, b)}:
 *       complete units from {@code a} to {@code b}, negative when {@code b} is before {@code a}</li>
 *   <li>{@code string(d)}: ISO text</li>
 * </ul>
 */
final class DateLibrary {

    static final CelType TYPE = OpaqueType.create("date");

    private static final CelType INT = SimpleType.INT;
    private static final CelType BOOL = SimpleType.BOOL;
    private static final CelType STRING = SimpleType.STRING;

    private DateLibrary() {}

    static List<CelFunctionDecl> declarations() {
        List<CelFunctionDecl> decls = new ArrayList<>();
        decls.add(CelFunctionDecl.newFunctionDeclaration("date",
                CelOverloadDecl.newGlobalOverload("rulestone_date_string", TYPE, STRING)));
        for (MoneyLibrary.Comparison op : MoneyLibrary.Comparison.values()) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(op.function,
                    CelOverloadDecl.newGlobalOverload(op.overloadId("date_date"), BOOL, TYPE, TYPE)));
        }
        for (Shift shift : Shift.values()) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(shift.function,
                    CelOverloadDecl.newMemberOverload(shift.overloadId(), TYPE, TYPE, INT)));
        }
        for (Field field : Field.values()) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(field.function,
                    CelOverloadDecl.newMemberOverload(field.overloadId(), INT, TYPE)));
        }
        for (Between between : Between.values()) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(between.function,
                    CelOverloadDecl.newGlobalOverload(between.overloadId(), INT, TYPE, TYPE)));
        }
        decls.add(CelFunctionDecl.newFunctionDeclaration("string",
                CelOverloadDecl.newGlobalOverload("rulestone_string_date", STRING, TYPE)));
        return decls;
    }

    static List<CelFunctionBinding> bindings() {
        List<CelFunctionBinding> bindings = new ArrayList<>();
        bindings.add(CelFunctionBinding.from("rulestone_date_string", String.class, DateLibrary::parse));
        for (MoneyLibrary.Comparison op : MoneyLibrary.Comparison.values()) {
            bindings.add(CelFunctionBinding.from(op.overloadId("date_date"), LocalDate.class, LocalDate.class,
                    (a, b) -> op.test(a.compareTo(b))));
        }
        for (Shift shift : Shift.values()) {
            bindings.add(CelFunctionBinding.from(shift.overloadId(), LocalDate.class, Long.class, shift::apply));
        }
        for (Field field : Field.values()) {
            bindings.add(CelFunctionBinding.from(field.overloadId(), LocalDate.class, d -> (long) field.fn.apply(d)));
        }
        for (Between between : Between.values()) {
            bindings.add(CelFunctionBinding.from(between.overloadId(), LocalDate.class, LocalDate.class,
                    (a, b) -> between.unit.between(a, b)));
        }
        bindings.add(CelFunctionBinding.from("rulestone_string_date", LocalDate.class, LocalDate::toString));
        return bindings;
    }

    // Error messages never quote argument values: they may come from customer inputs.

    private static LocalDate parse(String text) throws CelEvaluationException {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException e) {
            throw new CelEvaluationException("date() argument is not a valid ISO date (yyyy-MM-dd)", null);
        }
    }

    private enum Shift {
        DAYS("plusDays", LocalDate::plusDays),
        MONTHS("plusMonths", LocalDate::plusMonths),
        YEARS("plusYears", LocalDate::plusYears);

        final String function;
        private final BiFunction<LocalDate, Long, LocalDate> fn;

        Shift(String function, BiFunction<LocalDate, Long, LocalDate> fn) {
            this.function = function;
            this.fn = fn;
        }

        String overloadId() {
            return "rulestone_" + function + "_date_int";
        }

        LocalDate apply(LocalDate date, Long amount) throws CelEvaluationException {
            try {
                return fn.apply(date, amount);
            } catch (DateTimeException | ArithmeticException e) {
                throw new CelEvaluationException(function + "() result is outside the supported date range", null);
            }
        }
    }

    private enum Field {
        YEAR("year", LocalDate::getYear),
        MONTH("month", LocalDate::getMonthValue),
        DAY("day", LocalDate::getDayOfMonth),
        DAY_OF_WEEK("dayOfWeek", d -> d.getDayOfWeek().getValue());

        final String function;
        final Function<LocalDate, Integer> fn;

        Field(String function, Function<LocalDate, Integer> fn) {
            this.function = function;
            this.fn = fn;
        }

        String overloadId() {
            return "rulestone_" + function + "_date";
        }
    }

    private enum Between {
        DAYS("daysBetween", ChronoUnit.DAYS),
        MONTHS("monthsBetween", ChronoUnit.MONTHS),
        YEARS("yearsBetween", ChronoUnit.YEARS);

        final String function;
        final ChronoUnit unit;

        Between(String function, ChronoUnit unit) {
            this.function = function;
            this.unit = unit;
        }

        String overloadId() {
            return "rulestone_" + function + "_date_date";
        }
    }
}
