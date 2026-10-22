//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Types that rule inputs, outputs and expressions may have.
 *
 * <p>There is deliberately no floating-point type: ratios and plain amounts are
 * {@link #DECIMAL}, backed by {@link java.math.BigDecimal}; amounts with a currency are
 * {@link #MONEY}, backed by {@link vn.io.dxa.rulestone.api.Money}. Business dates are
 * {@link #DATE}, backed by {@link java.time.LocalDate}.
 */
public sealed interface ValueType permits ValueType.Primitive, ValueType.ListOf, ValueType.Struct {

    ValueType BOOL = Primitive.BOOL;
    ValueType INT = Primitive.INT;
    ValueType STRING = Primitive.STRING;
    ValueType DECIMAL = Primitive.DECIMAL;
    ValueType MONEY = Primitive.MONEY;
    ValueType DATE = Primitive.DATE;

    static ValueType listOf(ValueType element) {
        return new ListOf(element);
    }

    static ValueType struct(Map<String, ValueType> fields) {
        return new Struct(fields);
    }

    enum Primitive implements ValueType {
        BOOL("bool"),
        INT("int"),
        STRING("string"),
        DECIMAL("decimal"),
        MONEY("money"),
        DATE("date");

        private final String typeName;

        Primitive(String typeName) {
            this.typeName = typeName;
        }

        /** The name used for this type in rule files, e.g. {@code decimal}. */
        public String typeName() {
            return typeName;
        }

        @Override
        public String toString() {
            return typeName;
        }
    }

    record ListOf(ValueType element) implements ValueType {
        public ListOf {
            Objects.requireNonNull(element, "element");
        }

        @Override
        public String toString() {
            return "list<" + element + ">";
        }
    }

    /** An object with named, typed fields. Field order is preserved for readable messages. */
    record Struct(Map<String, ValueType> fields) implements ValueType {
        public Struct {
            Objects.requireNonNull(fields, "fields");
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("struct must declare at least one field");
            }
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public String toString() {
            return "struct" + fields;
        }
    }
}
