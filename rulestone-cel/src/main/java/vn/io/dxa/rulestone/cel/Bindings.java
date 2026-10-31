//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.runtime.CelEvaluationException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inputs already converted and type-checked for one {@link ExpressionEnvironment}.
 * Create once per evaluation and share across all expressions of that environment.
 *
 * <p>The environment's local values ({@link ExpressionEnvironment.Builder#local}) are computed
 * when an expression first reads them, at most once per {@code Bindings}, and never if no
 * expression reads them.
 */
public final class Bindings {

    final ExpressionEnvironment environment;
    final Map<String, Object> values;
    /** Local values computed so far: the CEL value, or the {@link LocalValueException} it failed with. */
    private final Map<String, Object> locals = new ConcurrentHashMap<>();

    Bindings(ExpressionEnvironment environment, Map<String, Object> values) {
        this.environment = environment;
        this.values = Map.copyOf(values);
    }

    /** Resolves a variable for CEL: an input, or a local value computed on first use. */
    Optional<Object> find(String name) {
        Object value = values.get(name);
        if (value != null) {
            return Optional.of(value);
        }
        CelExpression local = environment.locals().get(name);
        if (local == null) {
            return Optional.empty();
        }
        Object computed = locals.get(name);
        if (computed == null) {
            computed = compute(name, local);
            locals.put(name, computed);
        }
        if (computed instanceof LocalValueException failure) {
            throw failure;
        }
        return Optional.of(computed);
    }

    private Object compute(String name, CelExpression local) {
        try {
            return local.evaluateRaw(this);
        } catch (CelEvaluationException e) {
            // A local value that failed because another one did reports the other one.
            LocalValueException inner = LocalValueException.in(e);
            return inner != null ? inner : new LocalValueException(name, e.getMessage() + " in: " + local.source(), e);
        }
    }

    /**
     * The bound values in canonical form, for hashing: maps sorted by key, {@code decimal} as
     * {@link java.math.BigDecimal} and {@code money} as {@link vn.io.dxa.rulestone.api.Money}
     * with trailing zeros removed (so {@code 1.0} and {@code 1.00}, which rules treat as equal,
     * are equal here too), {@code int} as {@link Long}, {@code date} as
     * {@link java.time.LocalDate}, lists as lists. Unset struct fields are absent. Local values
     * are not included: they follow from the inputs.
     *
     * <p>The values are the inputs themselves and may be personal data: hash them, never log them.
     */
    public Map<String, Object> canonicalValues() {
        Map<String, Object> canonical = new TreeMap<>();
        values.forEach((name, value) -> canonical.put(name, Values.canonical(value)));
        return Collections.unmodifiableMap(canonical);
    }

    @Override
    public String toString() {
        // Names only: values may hold personal data and must not leak into logs.
        return "Bindings" + values.keySet();
    }
}
