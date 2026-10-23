//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inputs already converted and type-checked for one {@link ExpressionEnvironment}.
 * Create once per evaluation and share across all expressions of that environment.
 */
public final class Bindings {

    final ExpressionEnvironment environment;
    final Map<String, Object> values;

    Bindings(ExpressionEnvironment environment, Map<String, Object> values) {
        this.environment = environment;
        this.values = Map.copyOf(values);
    }

    /**
     * The bound values in canonical form, for hashing: maps sorted by key, {@code decimal} as
     * {@link java.math.BigDecimal} and {@code money} as {@link vn.io.dxa.rulestone.api.Money}
     * with trailing zeros removed (so {@code 1.0} and {@code 1.00}, which rules treat as equal,
     * are equal here too), {@code int} as {@link Long}, {@code date} as
     * {@link java.time.LocalDate}, lists as lists. Unset struct fields are absent.
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
