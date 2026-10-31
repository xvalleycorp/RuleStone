//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.common.types.CelType;
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
    private final CelType celType;
    private final CelRuntime.Program program;

    CelExpression(ExpressionEnvironment environment, String source, ValueType resultType, CelType celType,
                  CelRuntime.Program program) {
        this.environment = environment;
        this.source = source;
        this.resultType = resultType;
        this.celType = celType;
        this.program = program;
    }

    /**
     * Evaluates the expression and returns its value in Java form
     * ({@code Boolean}, {@code Long}, {@code String}, {@code BigDecimal},
     * {@link vn.io.dxa.rulestone.api.Money}, {@code LocalDate} or {@code List}).
     *
     * @throws ExpressionException if evaluation fails or produces a value of the wrong type
     */
    public Object evaluate(Bindings bindings) {
        if (bindings.environment != environment) {
            throw new IllegalArgumentException("bindings were created by a different environment");
        }
        Object result;
        try {
            result = evaluateRaw(bindings);
        } catch (CelEvaluationException e) {
            LocalValueException local = LocalValueException.in(e);
            if (local != null) {
                // The failure of a local value is reported as such, wherever it was read.
                throw new ExpressionException(local.getMessage(), e);
            }
            throw new ExpressionException(e.getMessage() + " in: " + source, e);
        }
        return Values.toJava(result, resultType, "result");
    }

    /** Evaluates to the CEL runtime value, reading inputs and local values from {@code bindings}. */
    Object evaluateRaw(Bindings bindings) throws CelEvaluationException {
        return program.eval(bindings::find);
    }

    public String source() {
        return source;
    }

    /** The type of the result: the type required when compiling, or the inferred type of a local value. */
    public ValueType resultType() {
        return resultType;
    }

    CelType celType() {
        return celType;
    }

    ExpressionEnvironment environment() {
        return environment;
    }

    @Override
    public String toString() {
        return "CelExpression[" + source + "]";
    }
}
