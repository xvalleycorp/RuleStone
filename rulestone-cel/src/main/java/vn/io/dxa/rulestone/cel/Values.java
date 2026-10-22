//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.Money;

/** Converts values between their Java form and their CEL runtime form, checking declared types. */
final class Values {

    private Values() {}

    /**
     * Converts a Java value to its CEL runtime form.
     *
     * <p>Accepted: {@code Boolean}; integral boxes and {@code BigInteger} for int;
     * {@code String} and enums for string; {@code BigDecimal} and integral numbers for decimal;
     * {@link Money} for money; {@link LocalDate} for date; collections for lists; maps and records for structs. Floating-point numbers are rejected.
     * Struct fields that are absent or null are left unset, so rules can test them with {@code has()}.
     */
    static Object toCel(Object value, ValueType type, String path) {
        if (value == null) {
            throw new InvalidValueException(path, "value is null");
        }
        if (type instanceof ValueType.Primitive primitive) {
            return switch (primitive) {
                case BOOL -> {
                    if (value instanceof Boolean) {
                        yield value;
                    }
                    throw mismatch(path, type, value);
                }
                case INT -> toLong(value, path);
                case STRING -> {
                    if (value instanceof String) {
                        yield value;
                    }
                    if (value instanceof Enum<?> e) {
                        yield e.name();
                    }
                    throw mismatch(path, type, value);
                }
                case DECIMAL -> DecimalLibrary.wrap(toBigDecimal(value, path));
                case MONEY -> {
                    if (value instanceof Money money) {
                        yield new CelMoney(money.amount(), money.currency());
                    }
                    throw new InvalidValueException(path, "expected money but got " + value.getClass().getName()
                            + "; pass a " + Money.class.getName());
                }
                case DATE -> {
                    if (value instanceof LocalDate) {
                        yield value;
                    }
                    throw new InvalidValueException(path, "expected date but got " + value.getClass().getName()
                            + "; pass a java.time.LocalDate");
                }
            };
        }
        if (type instanceof ValueType.ListOf list) {
            if (!(value instanceof Collection<?> items)) {
                throw mismatch(path, type, value);
            }
            List<Object> converted = new ArrayList<>(items.size());
            int i = 0;
            for (Object item : items) {
                converted.add(toCel(item, list.element(), path + "[" + i++ + "]"));
            }
            return Collections.unmodifiableList(converted);
        }
        ValueType.Struct struct = (ValueType.Struct) type;
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, ValueType> field : struct.fields().entrySet()) {
            Object fieldValue = readField(value, field.getKey(), path);
            if (fieldValue != null) {
                converted.put(field.getKey(), toCel(fieldValue, field.getValue(), path + "." + field.getKey()));
            }
        }
        return Collections.unmodifiableMap(converted);
    }

    /** Converts a CEL result back to Java, verifying it has the expected type. */
    static Object toJava(Object value, ValueType expected, String path) {
        if (expected instanceof ValueType.Primitive primitive) {
            Object result = switch (primitive) {
                case BOOL -> value instanceof Boolean ? value : null;
                case INT -> value instanceof Long ? value : null;
                case STRING -> value instanceof String ? value : null;
                case DECIMAL -> value instanceof CelDecimal d ? d.value : null;
                case MONEY -> value instanceof CelMoney m ? new Money(m.amount, m.currency) : null;
                case DATE -> value instanceof LocalDate ? value : null;
            };
            if (result == null) {
                throw new InvalidValueException(path, "expected " + expected + " but expression produced " + describe(value));
            }
            return result;
        }
        if (expected instanceof ValueType.ListOf list && value instanceof List<?> items) {
            List<Object> converted = new ArrayList<>(items.size());
            int i = 0;
            for (Object item : items) {
                converted.add(toJava(item, list.element(), path + "[" + i++ + "]"));
            }
            return List.copyOf(converted);
        }
        throw new InvalidValueException(path, "expected " + expected + " but expression produced " + describe(value));
    }

    private static Object readField(Object value, String field, String path) {
        if (value instanceof Map<?, ?> map) {
            return map.get(field);
        }
        if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                if (component.getName().equals(field)) {
                    try {
                        var accessor = component.getAccessor();
                        accessor.trySetAccessible();
                        return accessor.invoke(record);
                    } catch (ReflectiveOperationException e) {
                        throw new InvalidValueException(path + "." + field, "cannot read record component: " + e);
                    }
                }
            }
            return null;
        }
        throw new InvalidValueException(path, "expected a Map or a record but got " + value.getClass().getName());
    }

    private static Long toLong(Object value, String path) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big) {
            try {
                return big.longValueExact();
            } catch (ArithmeticException e) {
                throw new InvalidValueException(path, "integer out of 64-bit range");
            }
        }
        throw mismatch(path, ValueType.INT, value);
    }

    private static BigDecimal toBigDecimal(Object value, String path) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger big) {
            return new BigDecimal(big);
        }
        if (value instanceof Double || value instanceof Float) {
            throw new InvalidValueException(path,
                    "floating-point value is not accepted for decimal; pass a BigDecimal");
        }
        throw mismatch(path, ValueType.DECIMAL, value);
    }

    private static InvalidValueException mismatch(String path, ValueType expected, Object value) {
        return new InvalidValueException(path, "expected " + expected + " but got " + value.getClass().getName());
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CelDecimal) {
            return "decimal";
        }
        if (value instanceof CelMoney) {
            return "money";
        }
        if (value instanceof LocalDate) {
            return "date";
        }
        return value.getClass().getSimpleName();
    }
}
