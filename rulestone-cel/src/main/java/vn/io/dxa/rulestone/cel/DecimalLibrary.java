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
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The {@code decimal} type and its functions.
 *
 * <ul>
 *   <li>Construction: {@code decimal('0.05')}, {@code decimal(5)}</li>
 *   <li>Arithmetic {@code + - * /} and comparison {@code < <= > >=} between decimals, or a
 *       decimal and an int. Equality requires both sides to be decimal.</li>
 *   <li>Division keeps 34 significant digits ({@link MathContext#DECIMAL128}, HALF_EVEN).</li>
 *   <li>{@code x.round(scale)} rounds HALF_EVEN; {@code x.round(scale, 'HALF_UP')} takes any
 *       {@link RoundingMode} name.</li>
 *   <li>{@code x.abs()}, {@code min(a, b)}, {@code max(a, b)}, {@code string(x)}</li>
 * </ul>
 *
 * <p>No overload accepts {@code double}, so mixing a decimal with a floating-point literal is
 * a compile-time error rather than a silent precision loss.
 */
final class DecimalLibrary {

    static final CelType TYPE = OpaqueType.create("decimal");

    private static final CelType INT = SimpleType.INT;
    private static final CelType BOOL = SimpleType.BOOL;
    private static final CelType STRING = SimpleType.STRING;

    private DecimalLibrary() {}

    static List<CelFunctionDecl> declarations() {
        List<CelFunctionDecl> decls = new ArrayList<>();
        decls.add(CelFunctionDecl.newFunctionDeclaration("decimal",
                CelOverloadDecl.newGlobalOverload("rulestone_decimal_string", TYPE, STRING),
                CelOverloadDecl.newGlobalOverload("rulestone_decimal_int", TYPE, INT)));
        for (Op op : Op.values()) {
            CelType result = op.comparison ? BOOL : TYPE;
            decls.add(CelFunctionDecl.newFunctionDeclaration(op.function,
                    CelOverloadDecl.newGlobalOverload(op.overloadId("decimal_decimal"), result, TYPE, TYPE),
                    CelOverloadDecl.newGlobalOverload(op.overloadId("decimal_int"), result, TYPE, INT),
                    CelOverloadDecl.newGlobalOverload(op.overloadId("int_decimal"), result, INT, TYPE)));
        }
        decls.add(CelFunctionDecl.newFunctionDeclaration("-_",
                CelOverloadDecl.newGlobalOverload("rulestone_negate_decimal", TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("string",
                CelOverloadDecl.newGlobalOverload("rulestone_string_decimal", STRING, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("round",
                CelOverloadDecl.newMemberOverload("rulestone_round_decimal_int", TYPE, TYPE, INT),
                CelOverloadDecl.newMemberOverload("rulestone_round_decimal_int_string", TYPE, TYPE, INT, STRING)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("abs",
                CelOverloadDecl.newMemberOverload("rulestone_abs_decimal", TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("min",
                CelOverloadDecl.newGlobalOverload("rulestone_min_decimal_decimal", TYPE, TYPE, TYPE)));
        decls.add(CelFunctionDecl.newFunctionDeclaration("max",
                CelOverloadDecl.newGlobalOverload("rulestone_max_decimal_decimal", TYPE, TYPE, TYPE)));
        return decls;
    }

    static List<CelFunctionBinding> bindings() {
        List<CelFunctionBinding> bindings = new ArrayList<>();
        bindings.add(CelFunctionBinding.from("rulestone_decimal_string", String.class, DecimalLibrary::parse));
        bindings.add(CelFunctionBinding.from("rulestone_decimal_int", Long.class, v -> wrap(BigDecimal.valueOf(v))));
        for (Op op : Op.values()) {
            bindings.add(CelFunctionBinding.from(op.overloadId("decimal_decimal"), CelDecimal.class, CelDecimal.class,
                    (a, b) -> op.apply(a.value, b.value)));
            bindings.add(CelFunctionBinding.from(op.overloadId("decimal_int"), CelDecimal.class, Long.class,
                    (a, b) -> op.apply(a.value, BigDecimal.valueOf(b))));
            bindings.add(CelFunctionBinding.from(op.overloadId("int_decimal"), Long.class, CelDecimal.class,
                    (a, b) -> op.apply(BigDecimal.valueOf(a), b.value)));
        }
        bindings.add(CelFunctionBinding.from("rulestone_negate_decimal", CelDecimal.class, a -> wrap(a.value.negate())));
        bindings.add(CelFunctionBinding.from("rulestone_string_decimal", CelDecimal.class, a -> a.value.toPlainString()));
        bindings.add(CelFunctionBinding.from("rulestone_round_decimal_int", CelDecimal.class, Long.class,
                (a, scale) -> round(a, scale, RoundingMode.HALF_EVEN)));
        bindings.add(CelFunctionBinding.from("rulestone_round_decimal_int_string",
                List.of(CelDecimal.class, Long.class, String.class),
                args -> round((CelDecimal) args[0], (Long) args[1], roundingMode((String) args[2]))));
        bindings.add(CelFunctionBinding.from("rulestone_abs_decimal", CelDecimal.class, a -> wrap(a.value.abs())));
        bindings.add(CelFunctionBinding.from("rulestone_min_decimal_decimal", CelDecimal.class, CelDecimal.class,
                (a, b) -> a.compareTo(b) <= 0 ? a : b));
        bindings.add(CelFunctionBinding.from("rulestone_max_decimal_decimal", CelDecimal.class, CelDecimal.class,
                (a, b) -> a.compareTo(b) >= 0 ? a : b));
        return bindings;
    }

    static CelDecimal wrap(BigDecimal value) {
        return new CelDecimal(value);
    }

    // Error messages never quote argument values: they may come from customer inputs.

    private static CelDecimal parse(String text) throws CelEvaluationException {
        try {
            return wrap(new BigDecimal(text.strip()));
        } catch (NumberFormatException e) {
            throw new CelEvaluationException("decimal() argument is not a valid number", null);
        }
    }

    private static CelDecimal round(CelDecimal value, long scale, RoundingMode mode) throws CelEvaluationException {
        if (scale < -1000 || scale > 1000) {
            throw new CelEvaluationException("round() scale must be between -1000 and 1000");
        }
        return wrap(value.value.setScale((int) scale, mode));
    }

    static RoundingMode roundingMode(String name) throws CelEvaluationException {
        try {
            RoundingMode mode = RoundingMode.valueOf(name.toUpperCase(Locale.ROOT));
            if (mode == RoundingMode.UNNECESSARY) {
                throw new IllegalArgumentException();
            }
            return mode;
        } catch (IllegalArgumentException e) {
            throw new CelEvaluationException(
                    "unknown rounding mode (expected HALF_EVEN, HALF_UP, HALF_DOWN, UP, DOWN, CEILING or FLOOR)", null);
        }
    }

    private enum Op {
        ADD("_+_", "add", false, BigDecimal::add),
        SUBTRACT("_-_", "subtract", false, BigDecimal::subtract),
        MULTIPLY("_*_", "multiply", false, BigDecimal::multiply),
        DIVIDE("_/_", "divide", false, (a, b) -> a.divide(b, MathContext.DECIMAL128)),
        LESS("_<_", "less", true, (a, b) -> a.compareTo(b) < 0),
        LESS_EQUALS("_<=_", "less_equals", true, (a, b) -> a.compareTo(b) <= 0),
        GREATER("_>_", "greater", true, (a, b) -> a.compareTo(b) > 0),
        GREATER_EQUALS("_>=_", "greater_equals", true, (a, b) -> a.compareTo(b) >= 0);

        final String function;
        final String name;
        final boolean comparison;
        private final BiFunction<BigDecimal, BigDecimal, Object> fn;

        Op(String function, String name, boolean comparison, BiFunction<BigDecimal, BigDecimal, Object> fn) {
            this.function = function;
            this.name = name;
            this.comparison = comparison;
            this.fn = fn;
        }

        String overloadId(String signature) {
            return "rulestone_" + name + "_" + signature;
        }

        Object apply(BigDecimal a, BigDecimal b) throws CelEvaluationException {
            if (this == DIVIDE && b.signum() == 0) {
                throw new CelEvaluationException("decimal division by zero");
            }
            Object result = fn.apply(a, b);
            return result instanceof BigDecimal d ? wrap(d) : result;
        }
    }
}
