//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable named inputs to a decision.
 *
 * <p>Values may be plain Java types, maps, records or other objects a registered
 * type adapter understands. Monetary amounts must be {@link java.math.BigDecimal}.
 */
public final class Facts {

    private static final Facts EMPTY = new Facts(Map.of());

    private final Map<String, Object> values;

    private Facts(Map<String, Object> values) {
        this.values = values;
    }

    public static Facts empty() {
        return EMPTY;
    }

    public static Facts of(String name, Object value) {
        return empty().with(name, value);
    }

    public static Facts of(String n1, Object v1, String n2, Object v2) {
        return of(n1, v1).with(n2, v2);
    }

    public static Facts of(String n1, Object v1, String n2, Object v2, String n3, Object v3) {
        return of(n1, v1, n2, v2).with(n3, v3);
    }

    public static Facts from(Map<String, ?> values) {
        Facts facts = EMPTY;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            facts = facts.with(e.getKey(), e.getValue());
        }
        return facts;
    }

    /** Returns a copy with {@code name} bound to {@code value}; rejects duplicate names. */
    public Facts with(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, () -> "value of fact '" + name + "'");
        if (values.containsKey(name)) {
            throw new IllegalArgumentException("duplicate fact: " + name);
        }
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(name, value);
        return new Facts(Map.copyOf(copy));
    }

    public Optional<Object> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Facts other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        // Names only: values may hold personal data and must not leak into logs.
        return "Facts" + values.keySet();
    }
}
