//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Converts facts sent as JSON into the Java values the engine accepts, guided by the table's input types.
 *
 * <ul>
 *   <li>{@code int}: a JSON integer, or a string of digits, as {@link Long}.</li>
 *   <li>{@code decimal}: a JSON number or a numeric string, as an exact {@link BigDecimal}. JSON numbers are
 *       parsed as decimals, never as doubles; browsers should send strings to avoid JavaScript rounding.</li>
 *   <li>{@code bool}, {@code string}: the JSON value.</li>
 *   <li>{@code date} (when the engine supports it): an ISO {@code yyyy-MM-dd} string, as {@link LocalDate}.</li>
 *   <li>Other types: the plain JSON value (string, number as {@link BigDecimal}/{@link Long}, list, map), which the
 *       engine then checks.</li>
 * </ul>
 * {@code null} and absent struct fields are left unset; facts the table does not declare are ignored.
 * Error messages name the input path and the expected type, never the value.
 */
final class FactsConverter {

    private FactsConverter() {}

    /** @throws ApiException 422 naming the first fact that does not match its declared type */
    static Map<String, Object> convert(JsonNode facts, Map<String, ValueType> inputs) {
        if (facts == null || facts.isNull()) {
            return Map.of();
        }
        if (!facts.isObject()) {
            throw invalid("facts", "a JSON object of input name to value");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        inputs.forEach((name, type) -> {
            JsonNode node = facts.get(name);
            if (node != null && !node.isNull()) {
                values.put(name, value(node, type, name));
            }
        });
        return values;
    }

    private static Object value(JsonNode node, ValueType type, String path) {
        if (type instanceof ValueType.Primitive primitive) {
            return primitive(node, primitive, path);
        }
        if (type instanceof ValueType.ListOf list) {
            if (!node.isArray()) {
                throw invalid(path, "a JSON array");
            }
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                if (item.isNull()) {
                    throw invalid(path + "[" + i + "]", "a value (lists cannot contain null)");
                }
                items.add(value(item, list.element(), path + "[" + i + "]"));
            }
            return items;
        }
        ValueType.Struct struct = (ValueType.Struct) type;
        if (!node.isObject()) {
            throw invalid(path, "a JSON object");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        struct.fields().forEach((name, fieldType) -> {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull()) {
                fields.put(name, value(field, fieldType, path + "." + name));
            }
        });
        return fields;
    }

    private static Object primitive(JsonNode node, ValueType.Primitive type, String path) {
        String name = type.typeName();
        switch (name) {
            case "bool" -> {
                if (node.isBoolean()) {
                    return node.booleanValue();
                }
            }
            case "string" -> {
                if (node.isTextual()) {
                    return node.textValue();
                }
            }
            case "int" -> {
                if (node.isIntegralNumber() && node.canConvertToLong()) {
                    return node.longValue();
                }
                if (node.isTextual() && node.textValue().strip().matches("-?[0-9]{1,19}")) {
                    try {
                        return Long.parseLong(node.textValue().strip());
                    } catch (NumberFormatException e) {
                        throw invalid(path, "an integer within 64-bit range");
                    }
                }
            }
            case "decimal" -> {
                if (node.isNumber()) {
                    return node.decimalValue();
                }
                if (node.isTextual()) {
                    return decimal(node.textValue(), path);
                }
            }
            case "date" -> {
                if (node.isTextual()) {
                    try {
                        return LocalDate.parse(node.textValue().strip());
                    } catch (DateTimeParseException e) {
                        throw invalid(path, "an ISO date (yyyy-MM-dd)");
                    }
                }
            }
            default -> {
                return plain(node);
            }
        }
        throw invalid(path, describe(name));
    }

    private static BigDecimal decimal(String text, String path) {
        String s = text.strip();
        if (!s.matches("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)") || s.length() > 400) {
            throw invalid(path, "a decimal number in plain notation, e.g. \"1234.50\"");
        }
        return new BigDecimal(s);
    }

    /** JSON as plain Java values, with exact numbers. */
    static Object plain(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.longValue() : (Object) node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(plain(item)));
            return items;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                if (!e.getValue().isNull()) {
                    map.put(e.getKey(), plain(e.getValue()));
                }
            });
            return map;
        }
        return null;
    }

    private static String describe(String typeName) {
        return switch (typeName) {
            case "bool" -> "true or false";
            case "string" -> "a JSON string";
            case "int" -> "an integer";
            case "decimal" -> "a decimal number (a JSON number or a numeric string)";
            case "date" -> "an ISO date string (yyyy-MM-dd)";
            default -> "a " + typeName;
        };
    }

    private static ApiException invalid(String path, String expected) {
        return new ApiException(422, "invalid_facts", path + ": expected " + expected);
    }

    /** Converts an engine output value to JSON-friendly Java: decimals become plain-notation strings. */
    static Object outputValue(Object value) {
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof BigInteger i) {
            return i.toString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FactsConverter::outputValue).toList();
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }
}
