//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * The single place where this module converts between RuleStone values and the JSON forms carried in
 * Temporal payloads. The forms are those of RuleStone's JSON facts format: decimals as strings in plain
 * notation ({@code "25750000.50"}), money as {@code "<amount> <CCY>"} ({@code "1500000.50 VND"}),
 * dates as {@code "yyyy-MM-dd"}, integers as JSON integers, structs as objects and lists as arrays.
 *
 * <p>Messages name the location and the expected form, never the value.
 */
final class PayloadJson {

    private PayloadJson() {
    }

    // ---------------------------------------------------------------- writing

    /**
     * Converts Java values into JSON forms. Accepts what {@link Facts} and {@code DecisionResult}
     * hold: text, booleans, integers, {@link BigDecimal}, {@link Money}, {@link LocalDate}, enums,
     * lists, maps with text keys and records. JSON forms are left unchanged, so this is idempotent.
     *
     * @throws IllegalArgumentException for a floating-point number, an unsupported type, or a
     *                                  top-level {@code null} when not allowed
     */
    static Map<String, Object> write(Map<String, ?> values, String kind, boolean topLevelNullAllowed) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException(kind + " with a null name");
            }
            copy.put(e.getKey(), writeValue(e.getValue(), new Path(kind, e.getKey()), topLevelNullAllowed));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object writeValue(Object value, Path at, boolean nullAllowed) {
        if (value == null) {
            if (nullAllowed) {
                return null;
            }
            throw new IllegalArgumentException(at + " is null");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big) {
            if (big.bitLength() >= Long.SIZE) {
                throw new IllegalArgumentException(at + " is outside the 64-bit integer range");
            }
            return big.longValueExact();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Money money) {
            return money.toString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(at + " is a floating-point number ("
                    + value.getClass().getSimpleName() + "); use BigDecimal or Money");
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(at + " has a key that is not text");
                }
                copy.put(key, writeValue(e.getValue(), at.field(key), true));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            int index = 0;
            for (Object element : collection) {
                copy.add(writeValue(element, at.element(index++), false));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Record record) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String field = component.getName();
                Path fieldAt = at.field(field);
                copy.put(field, writeValue(readComponent(record, component, fieldAt), fieldAt, true));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException(at + " has unsupported type " + value.getClass().getName());
    }

    private static Object readComponent(Record record, RecordComponent component, Path at) {
        try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No cause: its message could contain the value.
            throw new IllegalArgumentException(at + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads facts in JSON forms, guided by the decision's declared inputs. Strict: a name or struct
     * field that is not declared, a value of the wrong form, a floating-point number or a top-level
     * {@code null} is a problem. Absent inputs are left out (the engine reports them); absent or
     * {@code null} struct fields are left out (rules see them as unset).
     *
     * @throws IllegalArgumentException listing every problem
     */
    static Facts readFacts(Map<String, ?> json, Map<String, ValueType> inputs) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        json.forEach((name, value) -> {
            ValueType type = inputs.get(name);
            Path at = new Path("fact", name);
            if (type == null) {
                problems.add(at + " is not an input of the decision");
            } else if (value == null) {
                problems.add(at + " is null");
            } else {
                Object read = read(value, type, at, problems);
                if (read != null) {
                    values.put(name, read);
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return Facts.from(values);
    }

    /**
     * Reads one value in its JSON form as {@code type}.
     *
     * @throws IllegalArgumentException if the value does not have the form of {@code type}
     */
    static Object readValue(Object json, ValueType type, String kind, String name) {
        List<String> problems = new ArrayList<>();
        Object value = read(json, type, new Path(kind, name), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return value;
    }

    /** Returns the converted value, or {@code null} after recording a problem. */
    private static Object read(Object json, ValueType type, Path at, List<String> problems) {
        if (type instanceof ValueType.ListOf list) {
            if (!(json instanceof List<?> elements)) {
                return problem(problems, at, "a list");
            }
            List<Object> copy = new ArrayList<>(elements.size());
            for (int i = 0; i < elements.size(); i++) {
                Path elementAt = at.element(i);
                if (elements.get(i) == null) {
                    problem(problems, elementAt, "a value, not null");
                } else {
                    copy.add(read(elements.get(i), list.element(), elementAt, problems));
                }
            }
            return Collections.unmodifiableList(copy);
        }
        if (type instanceof ValueType.Struct struct) {
            if (!(json instanceof Map<?, ?> fields)) {
                return problem(problems, at, "an object");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            fields.forEach((key, value) -> {
                Path fieldAt = at.field(String.valueOf(key));
                ValueType fieldType = struct.fields().get(String.valueOf(key));
                if (fieldType == null) {
                    problems.add(fieldAt + " is not a declared field");
                } else if (value != null) {
                    Object read = read(value, fieldType, fieldAt, problems);
                    if (read != null) {
                        copy.put((String) key, read);
                    }
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        return switch ((ValueType.Primitive) type) {
            case BOOL -> json instanceof Boolean ? json : problem(problems, at, "true or false");
            case STRING -> json instanceof String ? json : problem(problems, at, "a string");
            case INT -> readInt(json, at, problems);
            case DECIMAL -> readDecimal(json, at, problems);
            case MONEY -> readMoney(json, at, problems);
            case DATE -> readDate(json, at, problems);
        };
    }

    private static Object readInt(Object json, Path at, List<String> problems) {
        if (json instanceof Long || json instanceof Integer || json instanceof Short || json instanceof Byte) {
            return ((Number) json).longValue();
        }
        if (json instanceof BigInteger big && big.bitLength() < Long.SIZE) {
            return big.longValueExact();
        }
        return problem(problems, at, "a 64-bit integer");
    }

    private static Object readDecimal(Object json, Path at, List<String> problems) {
        if (json instanceof BigDecimal decimal) {
            return decimal;
        }
        if (json instanceof Long || json instanceof Integer || json instanceof Short || json instanceof Byte) {
            return BigDecimal.valueOf(((Number) json).longValue());
        }
        if (json instanceof BigInteger big) {
            return new BigDecimal(big);
        }
        if (json instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return problem(problems, at, "a decimal number, e.g. \"0.0980\"");
            }
        }
        return problem(problems, at, "a decimal number or a string such as \"0.0980\"");
    }

    private static Object readMoney(Object json, Path at, List<String> problems) {
        if (json instanceof String text) {
            int space = text.indexOf(' ');
            if (space > 0 && space == text.lastIndexOf(' ')) {
                try {
                    return Money.of(text.substring(0, space), text.substring(space + 1));
                } catch (IllegalArgumentException e) {
                    // NumberFormatException included; fall through to the generic problem
                }
            }
        }
        return problem(problems, at, "money as \"<amount> <CCY>\", e.g. \"1500000.50 VND\"");
    }

    private static Object readDate(Object json, Path at, List<String> problems) {
        if (json instanceof String text && text.length() == 10) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                // fall through
            }
        }
        return problem(problems, at, "a date as \"yyyy-MM-dd\"");
    }

    private static Object problem(List<String> problems, Path at, String expected) {
        problems.add(at + ": expected " + expected);
        return null;
    }

    /** A location for messages, e.g. {@code fact 'applicant.accounts[0].balance'}. */
    private record Path(String kind, String path) {

        Path field(String name) {
            return new Path(kind, path + "." + name);
        }

        Path element(int index) {
            return new Path(kind, path + "[" + index + "]");
        }

        @Override
        public String toString() {
            return kind + " '" + path + "'";
        }
    }

    // ---------------------------------------------------------------- Jackson

    /**
     * Reads JSON-like values without ever producing a {@code double}: integers become {@code Long}
     * ({@code BigInteger} beyond 64 bits) and other numbers {@code BigDecimal}, parsed exactly from
     * their text. Guards payloads written by clients that send decimals as JSON numbers.
     */
    static final class ExactValueDeserializer extends StdDeserializer<Object> {

        private static final long serialVersionUID = 1L;

        ExactValueDeserializer() {
            super(Object.class);
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == null) {
                return ctxt.handleUnexpectedToken(Object.class, p);
            }
            switch (token) {
                case START_OBJECT -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                        String name = p.currentName();
                        p.nextToken();
                        map.put(name, deserialize(p, ctxt));
                    }
                    return map;
                }
                case START_ARRAY -> {
                    List<Object> list = new ArrayList<>();
                    for (JsonToken t = p.nextToken(); t != JsonToken.END_ARRAY; t = p.nextToken()) {
                        list.add(deserialize(p, ctxt));
                    }
                    return list;
                }
                case VALUE_STRING -> {
                    return p.getText();
                }
                case VALUE_NUMBER_INT -> {
                    return p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                            ? p.getBigIntegerValue()
                            : (Object) p.getLongValue();
                }
                case VALUE_NUMBER_FLOAT -> {
                    return p.getDecimalValue();
                }
                case VALUE_TRUE -> {
                    return Boolean.TRUE;
                }
                case VALUE_FALSE -> {
                    return Boolean.FALSE;
                }
                case VALUE_NULL -> {
                    return null;
                }
                default -> {
                    return ctxt.handleUnexpectedToken(Object.class, p);
                }
            }
        }
    }
}
