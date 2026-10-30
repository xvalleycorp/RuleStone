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
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.Iso4217;

/**
 * The {@code money} type: an exact amount in an ISO 4217 currency.
 *
 * <ul>
 *   <li>Construction: {@code money('1500000.50', 'VND')}, {@code money(decimal, 'VND')},
 *       {@code money(int, 'VND')}</li>
 *   <li>{@code m.amount()} (a decimal) and {@code m.currency()} (a string)</li>
 *   <li>{@code + -} and {@code < <= > >=} between moneys of the same currency; {@code *} by a
 *       decimal or int on either side; {@code /} by a decimal or int; unary {@code -}.
 *       Mixing currencies fails the evaluation.</li>
 *   <li>No implicit rounding: results keep full precision (division keeps 34 significant
 *       digits) until {@code m.round()} rounds to the currency's minor units, HALF_EVEN, or
 *       {@code m.round('HALF_UP')} with any {@link RoundingMode} name.</li>
 *   <li>{@code m.abs()}, {@code min(a, b)}, {@code max(a, b)}, {@code string(m)}</li>
 * </ul>
 *
 * <p>Minor units come from RuleStone's fixed ISO 4217 table ({@link Iso4217}) unless the
 * environment overrides them.
 */
final class MoneyLibrary {

    static final CelType TYPE = OpaqueType.create("money");

    private static final CelType DECIMAL = DecimalLibrary.TYPE;
    private static final CelType INT = SimpleType.INT;
    private static final CelType BOOL = SimpleType.BOOL;
    private static final CelType STRING = SimpleType.STRING;

    private MoneyLibrary() {}

    static List<CelFunctionDecl> declarations() {
        List<CelFunctionDecl> decls = new ArrayList<>();
        decls.add(CelFunctionDecl.newFunctionDeclaration("money",
                CelOverloadDecl.newGlobalOverload("rulestone_money_string_string", TYPE, STRING, STRING),
                CelOverloadDecl.newGlobalOverload("rulestone_money_decimal_string", TYPE, DECIMAL, STRING),
                CelOverloadDecl.newGlobalOverload("rulestone_money_int_string", TYPE, INT, STRING)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("amount",
                CelOverloadDecl.newMemberOverload("rulestone_amount_money", DECIMAL, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("currency",
                CelOverloadDecl.newMemberOverload("rulestone_currency_money", STRING, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("_+_",
                CelOverloadDecl.newGlobalOverload("rulestone_add_money_money", TYPE, TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("_-_",
                CelOverloadDecl.newGlobalOverload("rulestone_subtract_money_money", TYPE, TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("_*_",
                CelOverloadDecl.newGlobalOverload("rulestone_multiply_money_decimal", TYPE, TYPE, DECIMAL),
                CelOverloadDecl.newGlobalOverload("rulestone_multiply_money_int", TYPE, TYPE, INT),
                CelOverloadDecl.newGlobalOverload("rulestone_multiply_decimal_money", TYPE, DECIMAL, TYPE),
                CelOverloadDecl.newGlobalOverload("rulestone_multiply_int_money", TYPE, INT, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("_/_",
                CelOverloadDecl.newGlobalOverload("rulestone_divide_money_decimal", TYPE, TYPE, DECIMAL),
                CelOverloadDecl.newGlobalOverload("rulestone_divide_money_int", TYPE, TYPE, INT)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("-_",
                CelOverloadDecl.newGlobalOverload("rulestone_negate_money", TYPE, TYPE)));
        for (Comparison op : Comparison.values()) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(op.function,
                    CelOverloadDecl.newGlobalOverload(op.overloadId("money_money"), BOOL, TYPE, TYPE)));
        }
        decls.add(CelFunctionDecl.newFunctionDeclaration("round",
                CelOverloadDecl.newMemberOverload("rulestone_round_money", TYPE, TYPE),
                CelOverloadDecl.newMemberOverload("rulestone_round_money_string", TYPE, TYPE, STRING)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("abs",
                CelOverloadDecl.newMemberOverload("rulestone_abs_money", TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("min",
                CelOverloadDecl.newGlobalOverload("rulestone_min_money_money", TYPE, TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("max",
                CelOverloadDecl.newGlobalOverload("rulestone_max_money_money", TYPE, TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("string",
                CelOverloadDecl.newGlobalOverload("rulestone_string_money", STRING, TYPE)));
        return decls;
    }

    /**
     * @param scales minor units per currency code, overriding ISO 4217
     */
    static List<CelFunctionBinding> bindings(Map<String, Integer> scales) {
        List<CelFunctionBinding> bindings = new ArrayList<>();
        bindings.add(CelFunctionBinding.from("rulestone_money_string_string", String.class, String.class,
                (amount, currency) -> create(parseAmount(amount), currency)));
        bindings.add(CelFunctionBinding.from("rulestone_money_decimal_string", CelDecimal.class, String.class,
                (amount, currency) -> create(amount.value, currency)));
        bindings.add(CelFunctionBinding.from("rulestone_money_int_string", Long.class, String.class,
                (amount, currency) -> create(BigDecimal.valueOf(amount), currency)));
        bindings.add(CelFunctionBinding.from("rulestone_amount_money", CelMoney.class, m -> DecimalLibrary.wrap(m.amount)));
        bindings.add(CelFunctionBinding.from("rulestone_currency_money", CelMoney.class, m -> m.currency));
        bindings.add(CelFunctionBinding.from("rulestone_add_money_money", CelMoney.class, CelMoney.class,
                (a, b) -> new CelMoney(a.amount.add(sameCurrency(a, b).amount), a.currency)));
        bindings.add(CelFunctionBinding.from("rulestone_subtract_money_money", CelMoney.class, CelMoney.class,
                (a, b) -> new CelMoney(a.amount.subtract(sameCurrency(a, b).amount), a.currency)));
        bindings.add(CelFunctionBinding.from("rulestone_multiply_money_decimal", CelMoney.class, CelDecimal.class,
                (m, d) -> times(m, d.value)));
        bindings.add(CelFunctionBinding.from("rulestone_multiply_money_int", CelMoney.class, Long.class,
                (m, i) -> times(m, BigDecimal.valueOf(i))));
        bindings.add(CelFunctionBinding.from("rulestone_multiply_decimal_money", CelDecimal.class, CelMoney.class,
                (d, m) -> times(m, d.value)));
        bindings.add(CelFunctionBinding.from("rulestone_multiply_int_money", Long.class, CelMoney.class,
                (i, m) -> times(m, BigDecimal.valueOf(i))));
        bindings.add(CelFunctionBinding.from("rulestone_divide_money_decimal", CelMoney.class, CelDecimal.class,
                (m, d) -> divide(m, d.value)));
        bindings.add(CelFunctionBinding.from("rulestone_divide_money_int", CelMoney.class, Long.class,
                (m, i) -> divide(m, BigDecimal.valueOf(i))));
        bindings.add(CelFunctionBinding.from("rulestone_negate_money", CelMoney.class,
                m -> new CelMoney(m.amount.negate(), m.currency)));
        for (Comparison op : Comparison.values()) {
            bindings.add(CelFunctionBinding.from(op.overloadId("money_money"), CelMoney.class, CelMoney.class,
                    (a, b) -> op.test(a.amount.compareTo(sameCurrency(a, b).amount))));
        }
        bindings.add(CelFunctionBinding.from("rulestone_round_money", CelMoney.class,
                m -> round(m, RoundingMode.HALF_EVEN, scales)));
        bindings.add(CelFunctionBinding.from("rulestone_round_money_string", CelMoney.class, String.class,
                (m, mode) -> round(m, DecimalLibrary.roundingMode(mode), scales)));
        bindings.add(CelFunctionBinding.from("rulestone_abs_money", CelMoney.class,
                m -> new CelMoney(m.amount.abs(), m.currency)));
        bindings.add(CelFunctionBinding.from("rulestone_min_money_money", CelMoney.class, CelMoney.class,
                (a, b) -> a.amount.compareTo(sameCurrency(a, b).amount) <= 0 ? a : b));
        bindings.add(CelFunctionBinding.from("rulestone_max_money_money", CelMoney.class, CelMoney.class,
                (a, b) -> a.amount.compareTo(sameCurrency(a, b).amount) >= 0 ? a : b));
        bindings.add(CelFunctionBinding.from("rulestone_string_money", CelMoney.class, CelMoney::toString));
        return bindings;
    }

    /** Whether {@code code} is an upper-case ISO 4217 code in RuleStone's table ({@link Iso4217}). */
    static boolean isCurrencyCode(String code) {
        return Iso4217.isCurrencyCode(code);
    }

    // Error messages never quote amounts or argument text: they may come from customer inputs.
    // Currency codes are named only once they are known to be valid ISO 4217 codes.

    private static CelMoney create(BigDecimal amount, String currency) throws CelEvaluationException {
        if (!isCurrencyCode(currency)) {
            throw new CelEvaluationException("money() currency is not an ISO 4217 code in upper case", null);
        }
        return new CelMoney(amount, currency);
    }

    private static BigDecimal parseAmount(String text) throws CelEvaluationException {
        try {
            return new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw new CelEvaluationException("money() amount is not a valid number", null);
        }
    }

    /** Returns {@code b} if it has the currency of {@code a}, else fails naming both currencies. */
    private static CelMoney sameCurrency(CelMoney a, CelMoney b) throws CelEvaluationException {
        requireSameCurrency(a, b);
        return b;
    }

    /** Fails, naming both currencies, unless {@code a} and {@code b} have the same currency. */
    static void requireSameCurrency(CelMoney a, CelMoney b) throws CelEvaluationException {
        if (!a.currency.equals(b.currency)) {
            throw new CelEvaluationException("money currencies differ: " + a.currency + " and " + b.currency);
        }
    }

    private static CelMoney times(CelMoney m, BigDecimal factor) {
        return new CelMoney(m.amount.multiply(factor), m.currency);
    }

    private static CelMoney divide(CelMoney m, BigDecimal divisor) throws CelEvaluationException {
        if (divisor.signum() == 0) {
            throw new CelEvaluationException("money division by zero");
        }
        return new CelMoney(m.amount.divide(divisor, MathContext.DECIMAL128), m.currency);
    }

    private static CelMoney round(CelMoney m, RoundingMode mode, Map<String, Integer> scales)
            throws CelEvaluationException {
        Integer override = scales.get(m.currency);
        int scale = override != null ? override : Iso4217.minorUnits(m.currency).orElse(-1);
        if (scale < 0) {
            throw new CelEvaluationException("currency " + m.currency
                    + " has no minor unit in ISO 4217; configure one with currencyScale(\"" + m.currency + "\", digits)");
        }
        return new CelMoney(m.amount.setScale(scale, mode), m.currency);
    }

    /** Ordering comparisons, shared with {@link DateLibrary}. */
    enum Comparison {
        LESS("_<_", "less"),
        LESS_EQUALS("_<=_", "less_equals"),
        GREATER("_>_", "greater"),
        GREATER_EQUALS("_>=_", "greater_equals");

        final String function;
        private final String name;

        Comparison(String function, String name) {
            this.function = function;
            this.name = name;
        }

        String overloadId(String signature) {
            return "rulestone_" + name + "_" + signature;
        }

        /** Applies this comparison to the result of a {@code compareTo}. */
        boolean test(int comparison) {
            return switch (this) {
                case LESS -> comparison < 0;
                case LESS_EQUALS -> comparison <= 0;
                case GREATER -> comparison > 0;
                case GREATER_EQUALS -> comparison >= 0;
            };
        }
    }
}
