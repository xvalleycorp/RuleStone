//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;

/**
 * A type-checked CEL expression, compiled once by an {@link ExpressionEnvironment}
 * and safe to evaluate concurrently.
 */
public final class CelExpression {

    private final ExpressionEnvironment environment;
    private final String source;
    private final ValueType resultType;
    private final CelRuntime.Program program;

    CelExpression(ExpressionEnvironment environment, String source, ValueType resultType, CelRuntime.Program program) {
        this.environment = environment;
        this.source = source;
        this.resultType = resultType;
        this.program = program;
    }

    /**
     * Evaluates the expression and returns its value in Java form
     * ({@code Boolean}, {@code Long}, {@code String}, {@code BigDecimal} or {@code List}).
     *
     * @throws ExpressionException if evaluation fails or produces a value of the wrong type
     */
    public Object evaluate(Bindings bindings) {
        if (bindings.environment != environment) {
            throw new IllegalArgumentException("bindings were created by a different environment");
        }
        Object result;
        try {
            result = program.eval(bindings.values);
        } catch (CelEvaluationException e) {
            throw new ExpressionException(e.getMessage() + " in: " + source, e);
        }
        return Values.toJava(result, resultType, "result");
    }

    public String source() {
        return source;
    }

    public ValueType resultType() {
        return resultType;
    }

    @Override
    public String toString() {
        return "CelExpression[" + source + "]";
    }
}
