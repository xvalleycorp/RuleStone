//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import com.google.common.collect.ImmutableList;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aggregate functions on lists of {@code int}, {@code decimal} and {@code money}:
 * {@code list.sum()}, {@code list.sum(zero)}, {@code list.min()}, {@code list.max()} and
 * {@code list.avg()}.
 *
 * <ul>
 *   <li>{@code sum()} of an empty list is {@code 0} or {@code decimal('0')}; an empty money list
 *       has no currency, so it fails: {@code sum(zero)} gives {@code zero} for an empty list and,
 *       for money, requires every element to be in the currency of {@code zero}.</li>
 *   <li>{@code min()}, {@code max()} and {@code avg()} of an empty list fail. Of equal elements,
 *       {@code min()} and {@code max()} return the first.</li>
 *   <li>{@code avg()} divides as {@code /} does (34 significant digits, HALF_EVEN): a decimal for
 *       int and decimal lists, money for money lists.</li>
 *   <li>Money in different currencies fails, as {@code +} does; an int sum beyond 64 bits fails.</li>
 * </ul>
 *
 * <p>Each call is a macro that expands to a comprehension collecting the list, then calls the
 * function on the result: {@code list.sum()} becomes, in effect,
 * {@code list.map(@it, @it)} followed by {@code @result.sum()}. Every element therefore counts
 * towards the iteration budget shared by all list functions of an evaluation, and the target is
 * evaluated once. The browser engine evaluates them the same way.
 */
final class AggregateLibrary {

    /** The comprehension's iteration variable; '@' cannot appear in an identifier a rule writes. */
    private static final String ITEM = "@it";

    private static final CelType INT = SimpleType.INT;
    private static final CelType DECIMAL = DecimalLibrary.TYPE;
    private static final CelType MONEY = MoneyLibrary.TYPE;

    private AggregateLibrary() {}

    static List<CelMacro> macros() {
        return List.of(
                CelMacro.newReceiverMacro("sum", 0, (factory, target, args) -> expand("sum", factory, target, args)),
                CelMacro.newReceiverMacro("sum", 1, (factory, target, args) -> expand("sum", factory, target, args)),
                CelMacro.newReceiverMacro("min", 0, (factory, target, args) -> expand("min", factory, target, args)),
                CelMacro.newReceiverMacro("max", 0, (factory, target, args) -> expand("max", factory, target, args)),
                CelMacro.newReceiverMacro("avg", 0, (factory, target, args) -> expand("avg", factory, target, args)));
    }

    /** {@code target.f(args)} becomes a comprehension over {@code target} whose result is {@code @result.f(args)}. */
    private static Optional<CelExpr> expand(String function, CelMacroExprFactory factory, CelExpr target,
                                            ImmutableList<CelExpr> args) {
        String accu = factory.getAccumulatorVarName();
        CelExpr step = factory.newGlobalCall("_+_", factory.newIdentifier(accu),
                factory.newList(factory.newIdentifier(ITEM)));
        CelExpr result = factory.newReceiverCall(function, factory.newIdentifier(accu), args);
        return Optional.of(factory.fold(ITEM, target, accu, factory.newList(), factory.newBoolLiteral(true), step, result));
    }

    static List<CelFunctionDecl> declarations() {
        List<CelFunctionDecl> decls = new ArrayList<>();
        decls.add(CelFunctionDecl.newFunctionDeclaration("sum",
                member("rulestone_sum_list_int", INT, ListType.create(INT)),
                member("rulestone_sum_list_decimal", DECIMAL, ListType.create(DECIMAL)),
                member("rulestone_sum_list_money", MONEY, ListType.create(MONEY)),
                member("rulestone_sum_list_int_int", INT, ListType.create(INT), INT),
                member("rulestone_sum_list_decimal_decimal", DECIMAL, ListType.create(DECIMAL), DECIMAL),
                member("rulestone_sum_list_money_money", MONEY, ListType.create(MONEY), MONEY)));
        for (String function : List.of("min", "max")) {
            decls.add(CelFunctionDecl.newFunctionDeclaration(function,
                    member("rulestone_" + function + "_list_int", INT, ListType.create(INT)),
                    member("rulestone_" + function + "_list_decimal", DECIMAL, ListType.create(DECIMAL)),
                    member("rulestone_" + function + "_list_money", MONEY, ListType.create(MONEY))));
        }
        decls.add(CelFunctionDecl.newFunctionDeclaration("avg",
                member("rulestone_avg_list_int", DECIMAL, ListType.create(INT)),
                member("rulestone_avg_list_decimal", DECIMAL, ListType.create(DECIMAL)),
                member("rulestone_avg_list_money", MONEY, ListType.create(MONEY))));
        return decls;
    }

    private static CelOverloadDecl member(String id, CelType result, CelType... params) {
        return CelOverloadDecl.newMemberOverload(id, result, params);
    }

    static List<CelFunctionBinding> bindings() {
        List<CelFunctionBinding> bindings = new ArrayList<>();
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_int", List.class, list -> sumInt(list)));
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_decimal", List.class,
                list -> list.isEmpty() ? DecimalLibrary.wrap(BigDecimal.ZERO) : sumDecimal(list)));
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_money", List.class, list -> {
            if (list.isEmpty()) {
                throw new CelEvaluationException(
                        "sum() of an empty money list has no currency; give the result for an empty list, "
                                + "e.g. sum(money(0, 'VND'))");
            }
            return sumMoney(list, (CelMoney) list.get(0));
        }));
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_int_int", List.class, Long.class,
                (list, zero) -> list.isEmpty() ? zero : sumInt(list)));
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_decimal_decimal", List.class, CelDecimal.class,
                (list, zero) -> list.isEmpty() ? zero : sumDecimal(list)));
        bindings.add(CelFunctionBinding.from("rulestone_sum_list_money_money", List.class, CelMoney.class,
                (list, zero) -> list.isEmpty() ? zero : sumMoney(list, zero)));
        for (boolean min : new boolean[] {true, false}) {
            String function = min ? "min" : "max";
            for (String type : List.of("int", "decimal", "money")) {
                bindings.add(CelFunctionBinding.from("rulestone_" + function + "_list_" + type, List.class,
                        list -> extreme(list, function, min)));
            }
        }
        bindings.add(CelFunctionBinding.from("rulestone_avg_list_int", List.class, list -> {
            nonEmpty(list, "avg");
            return DecimalLibrary.wrap(new BigDecimal(exactIntSum(list)).divide(count(list), MathContext.DECIMAL128));
        }));
        bindings.add(CelFunctionBinding.from("rulestone_avg_list_decimal", List.class, list -> {
            nonEmpty(list, "avg");
            return DecimalLibrary.wrap(sumDecimal(list).value.divide(count(list), MathContext.DECIMAL128));
        }));
        bindings.add(CelFunctionBinding.from("rulestone_avg_list_money", List.class, list -> {
            nonEmpty(list, "avg");
            CelMoney total = sumMoney(list, (CelMoney) list.get(0));
            return new CelMoney(total.amount.divide(count(list), MathContext.DECIMAL128), total.currency);
        }));
        return bindings;
    }

    private static BigDecimal count(List<?> list) {
        return BigDecimal.valueOf(list.size());
    }

    private static void nonEmpty(List<?> list, String function) throws CelEvaluationException {
        if (list.isEmpty()) {
            throw new CelEvaluationException(function + "() of an empty list");
        }
    }

    private static BigInteger exactIntSum(List<?> list) {
        BigInteger total = BigInteger.ZERO;
        for (Object item : list) {
            total = total.add(BigInteger.valueOf((Long) item));
        }
        return total;
    }

    /** The exact total; fails only if the total itself is beyond 64 bits, whatever the order of the elements. */
    private static Long sumInt(List<?> list) throws CelEvaluationException {
        BigInteger total = exactIntSum(list);
        if (total.bitLength() > 63) {
            throw new CelEvaluationException("sum() result is out of int range");
        }
        return total.longValue();
    }

    private static CelDecimal sumDecimal(List<?> list) {
        BigDecimal total = ((CelDecimal) list.get(0)).value;
        for (int i = 1; i < list.size(); i++) {
            total = total.add(((CelDecimal) list.get(i)).value);
        }
        return DecimalLibrary.wrap(total);
    }

    /** Sums the amounts, requiring every element to have the currency of {@code reference}. */
    private static CelMoney sumMoney(List<?> list, CelMoney reference) throws CelEvaluationException {
        BigDecimal total = null;
        for (Object item : list) {
            CelMoney money = (CelMoney) item;
            MoneyLibrary.requireSameCurrency(reference, money);
            total = total == null ? money.amount : total.add(money.amount);
        }
        return new CelMoney(total, reference.currency);
    }

    /** The smallest ({@code min}) or largest element; the first of equal ones. */
    private static Object extreme(List<?> list, String function, boolean min) throws CelEvaluationException {
        nonEmpty(list, function);
        Object best = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            Object item = list.get(i);
            int c = compare(item, best);
            if (min ? c < 0 : c > 0) {
                best = item;
            }
        }
        return best;
    }

    private static int compare(Object a, Object b) throws CelEvaluationException {
        if (a instanceof Long x) {
            return Long.compare(x, (Long) b);
        }
        if (a instanceof CelDecimal x) {
            return x.compareTo((CelDecimal) b);
        }
        CelMoney x = (CelMoney) a;
        CelMoney y = (CelMoney) b;
        MoneyLibrary.requireSameCurrency(y, x);
        return x.amount.compareTo(y.amount);
    }
}
