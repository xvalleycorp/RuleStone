//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.util.Map;

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

    @Override
    public String toString() {
        // Names only: values may hold personal data and must not leak into logs.
        return "Bindings" + values.keySet();
    }
}
