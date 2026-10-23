//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Converts between JSON and the Java values a decision takes and produces, guided by the types the
 * decision declares.
 *
 * <p>JSON has no money, date or exact decimal type, so this class fixes one text form for each:
 *
 * <table>
 *   <caption>JSON forms</caption>
 *   <tr><th>Type</th><th>JSON</th><th>Java</th></tr>
 *   <tr><td>{@code bool}</td><td>{@code true}</td><td>{@code Boolean}</td></tr>
 *   <tr><td>{@code int}</td><td>an integer, e.g. {@code 30}</td><td>{@code Long}</td></tr>
 *   <tr><td>{@code string}</td><td>a string</td><td>{@code String}</td></tr>
 *   <tr><td>{@code decimal}</td><td>a number, e.g. {@code 25750000.50}, or a string, e.g. {@code "0.0980"}</td>
 *       <td>{@code BigDecimal}, with the written scale</td></tr>
 *   <tr><td>{@code money}</td><td>{@code "<amount> <CCY>"}, e.g. {@code "1500000.50 VND"}</td>
 *       <td>{@link Money}</td></tr>
 *   <tr><td>{@code date}</td><td>{@code "yyyy-MM-dd"}, e.g. {@code "2026-11-01"}</td><td>{@code LocalDate}</td></tr>
 *   <tr><td>list</td><td>an array</td><td>{@code List}</td></tr>
 *   <tr><td>struct</td><td>an object</td><td>{@code Map}</td></tr>
 * </table>
 *
 * <p>The money form is the one {@link Money#toString()} produces. Writing ({@link #writeValue})
 * produces the same forms, with decimals as strings in plain notation, so values round-trip
 * without passing through floating point.
 *
 * <p>Reading is strict and reports every problem at once in an {@link InvalidFactsException}:
 * a value of the wrong JSON type, a name or struct field that is not declared, a malformed
 * amount, currency or date, a floating-point number, or {@code null} where a value is required. A
 * struct field that is absent, or {@code null}, is left out of the result, so that
 * {@code has(...)} in rules sees it as unset. Inputs that are absent are left out too: the engine
 * reports a missing input when the decision is evaluated. Messages name the location and the
 * expected form, never the values.
 *
 * <p>This class is stateless and thread-safe.
 */
public final class FactsJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // Keep the written scale: 25750000.50 stays 25750000.50.
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Pattern MONEY = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?) ([A-Z]{3})");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    static final String MONEY_FORM = "money as \"<amount> <CCY>\", e.g. \"1500000 VND\"";
    static final String DATE_FORM = "a date as \"yyyy-MM-dd\"";

    private FactsJson() {}

    // --- JSON to Java ---------------------------------------------------------------------------

    /**
     * Reads facts for a decision from JSON text.
     *
     * @param json   a JSON object of input names to values
     * @param inputs the declared inputs, e.g. {@link DecisionTable#inputs()}
     * @return the facts, ready for {@code RuleStoneEngine.evaluate}
     * @throws InvalidFactsException listing every problem, including malformed JSON
     */
    public static Facts readFacts(String json, Map<String, ValueType> inputs) {
        return readFacts(parse(json), inputs);
    }

    /**
     * Reads facts for a decision from a JSON tree.
     *
     * <p>Read the tree with {@code DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS}: a
     * floating-point node is rejected rather than converted.
     *
     * @param json   a JSON object of input names to values
     * @param inputs the declared inputs, e.g. {@link DecisionTable#inputs()}
     * @return the facts, ready for {@code RuleStoneEngine.evaluate}
     * @throws InvalidFactsException listing every problem
     */
    public static Facts readFacts(JsonNode json, Map<String, ValueType> inputs) {
        return Facts.from(readValues(json, inputs));
    }

    /**
     * Reads facts for {@code table} from a JSON tree; the same as
     * {@code readFacts(json, table.inputs())}.
     *
     * @param json  a JSON object of input names to values
     * @param table the decision the facts are for
     * @return the facts, ready for {@code RuleStoneEngine.evaluate}
     * @throws InvalidFactsException listing every problem
     */
    public static Facts readFacts(JsonNode json, DecisionTable table) {
        return readFacts(json, table.inputs());
    }

    /**
     * Reads a JSON object of named values, such as facts or the outputs of a decision.
     *
     * @param json  a JSON object of names to values; every name must be declared in {@code types}
     * @param types the declared types by name, e.g. {@link DecisionTable#inputs()} or {@link #resultTypes}
     * @return the values, in JSON order; names absent from {@code json} are absent from the map
     * @throws InvalidFactsException listing every problem
     */
    public static Map<String, Object> readValues(JsonNode json, Map<String, ValueType> types) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(types, "types");
        Reader reader = new Reader();
        Map<String, Object> values = new LinkedHashMap<>();
        if (!json.isObject()) {
            reader.problem("$", "expected an object of names to values");
        } else {
            json.properties().forEach(e -> {
                ValueType type = types.get(e.getKey());
                if (type == null) {
                    reader.problem(e.getKey(), "not declared (declared: " + String.join(", ", types.keySet()) + ")");
                } else if (e.getValue().isNull()) {
                    reader.problem(e.getKey(), "null is not a value; leave it out instead");
                } else {
                    Object value = reader.value(e.getValue(), type, e.getKey());
                    if (value != null) {
                        values.put(e.getKey(), value);
                    }
                }
            });
        }
        reader.throwIfAny();
        return Collections.unmodifiableMap(values);
    }

    /**
     * Reads one value of a declared type.
     *
     * @param json the value
     * @param type its declared type
     * @return the Java value, as listed in the class documentation
     * @throws InvalidFactsException listing every problem; the value itself is located as {@code value}
     */
    public static Object readValue(JsonNode json, ValueType type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        Reader reader = new Reader();
        Object value = null;
        if (json.isNull()) {
            reader.problem("value", "null is not a value");
        } else {
            value = reader.value(json, type, "value");
        }
        reader.throwIfAny();
        return value;
    }

    /**
     * The types of the values in {@code DecisionResult.output()} for {@code table}: its declared
     * outputs, each wrapped in a list under the {@code COLLECT} hit policy.
     *
     * @param table a decision table
     * @return output names to the types of their result values
     */
    public static Map<String, ValueType> resultTypes(DecisionTable table) {
        Map<String, ValueType> types = new LinkedHashMap<>();
        table.outputs().forEach((name, type) ->
                types.put(name, table.hitPolicy() == HitPolicy.COLLECT ? ValueType.listOf(type) : type));
        return Collections.unmodifiableMap(types);
    }

    // --- Java to JSON ---------------------------------------------------------------------------

    /**
     * Writes a value produced by a decision, or accepted as a fact, as JSON.
     *
     * <p>{@code Money} becomes {@code "<amount> <CCY>"}, {@code LocalDate} {@code "yyyy-MM-dd"},
     * {@code BigDecimal} a string in plain notation with its scale, integers numbers, enums their
     * name, collections arrays and maps objects.
     *
     * @param value a {@code Boolean}, {@code String}, {@code Long} (or another integer type),
     *              {@code BigDecimal}, {@code Money}, {@code LocalDate}, enum, {@code Collection} or
     *              {@code Map} with {@code String} keys of these
     * @return the JSON tree
     * @throws IllegalArgumentException for {@code null}, floating point or any other type; the message
     *                                  names the location and the Java type, never the value
     */
    public static JsonNode writeValue(Object value) {
        return write(value, "value");
    }

    /**
     * Writes named values, such as {@code DecisionResult.output()}, as a JSON object.
     *
     * @param values names to values, written as by {@link #writeValue}
     * @return a JSON object, in the map's iteration order
     * @throws IllegalArgumentException as for {@link #writeValue}
     */
    public static ObjectNode writeValues(Map<String, ?> values) {
        ObjectNode object = NODES.objectNode();
        values.forEach((name, value) -> object.set(name, write(value, name)));
        return object;
    }

    /**
     * Writes named values, such as {@code DecisionResult.output()}, as compact JSON text.
     *
     * @param values names to values, written as by {@link #writeValue}
     * @return JSON text, e.g. {@code {"fee":"25000 VND","settles":"2026-11-02"}}
     * @throws IllegalArgumentException as for {@link #writeValue}
     */
    public static String write(Map<String, ?> values) {
        try {
            return MAPPER.writeValueAsString(writeValues(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }

    private static JsonNode write(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException(path + ": null is not a value");
        }
        if (value instanceof Boolean b) {
            return NODES.booleanNode(b);
        }
        if (value instanceof String s) {
            return NODES.textNode(s);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return NODES.numberNode(((Number) value).longValue());
        }
        if (value instanceof BigInteger i) {
            return NODES.numberNode(i);
        }
        if (value instanceof BigDecimal d) {
            return NODES.textNode(d.toPlainString());
        }
        if (value instanceof Money m) {
            return NODES.textNode(m.toString());
        }
        if (value instanceof LocalDate d) {
            return NODES.textNode(d.toString());
        }
        if (value instanceof Enum<?> e) {
            return NODES.textNode(e.name());
        }
        if (value instanceof Collection<?> items) {
            ArrayNode array = NODES.arrayNode();
            int i = 0;
            for (Object item : items) {
                array.add(write(item, path + "[" + i++ + "]"));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(path + ": map keys must be strings");
                }
                object.set(key, write(e.getValue(), path + "." + key));
            }
            return object;
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(path + ": floating-point numbers are not supported; use BigDecimal");
        }
        throw new IllegalArgumentException(path + ": unsupported type " + value.getClass().getName());
    }

    private static JsonNode parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new InvalidFactsException(List.of("$: expected a JSON object"));
            }
            return node;
        } catch (JsonProcessingException e) {
            // The parser's own message may quote the text, which may be customer data.
            String location = e.getLocation() == null ? ""
                    : " at line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw new InvalidFactsException(List.of("$: invalid JSON" + location));
        }
    }

    /** Converts values and collects every problem. */
    private static final class Reader {

        private final List<String> problems = new ArrayList<>();

        void problem(String path, String message) {
            problems.add(path + ": " + message);
        }

        void throwIfAny() {
            if (!problems.isEmpty()) {
                throw new InvalidFactsException(problems);
            }
        }

        /** The converted value, or null after reporting a problem. {@code node} is never JSON null. */
        Object value(JsonNode node, ValueType type, String path) {
            if (type instanceof ValueType.Primitive primitive) {
                return switch (primitive) {
                    case BOOL -> bool(node, path);
                    case INT -> integer(node, path);
                    case STRING -> string(node, path);
                    case DECIMAL -> decimal(node, path);
                    case MONEY -> money(node, path);
                    case DATE -> date(node, path);
                };
            }
            if (type instanceof ValueType.ListOf list) {
                return list(node, list.element(), path);
            }
            return struct(node, (ValueType.Struct) type, path);
        }

        private Object bool(JsonNode node, String path) {
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            problem(path, "expected true or false");
            return null;
        }

        private Object integer(JsonNode node, String path) {
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                return node.longValue();
            }
            problem(path, node.isIntegralNumber()
                    ? "expected an integer between " + Long.MIN_VALUE + " and " + Long.MAX_VALUE
                    : "expected an integer");
            return null;
        }

        private Object string(JsonNode node, String path) {
            if (node.isTextual()) {
                return node.textValue();
            }
            problem(path, "expected a string");
            return null;
        }

        private Object decimal(JsonNode node, String path) {
            if (node.isIntegralNumber()) {
                return new BigDecimal(node.bigIntegerValue());
            }
            if (node.isBigDecimal()) {
                return node.decimalValue();
            }
            if (node.isFloatingPointNumber()) {
                problem(path, "floating-point number; read the JSON with USE_BIG_DECIMAL_FOR_FLOATS"
                        + " or write the decimal as a string, e.g. \"0.0980\"");
                return null;
            }
            if (node.isTextual() && DECIMAL.matcher(node.textValue()).matches()) {
                return new BigDecimal(node.textValue());
            }
            problem(path, "expected a decimal as a number or a string in plain notation, e.g. \"0.0980\"");
            return null;
        }

        private Object money(JsonNode node, String path) {
            Matcher m = node.isTextual() ? MONEY.matcher(node.textValue()) : null;
            if (m == null || !m.matches()) {
                problem(path, "expected " + MONEY_FORM);
                return null;
            }
            try {
                return Money.of(new BigDecimal(m.group(1)), m.group(2));
            } catch (IllegalArgumentException e) {
                problem(path, "expected " + MONEY_FORM + ", with an ISO 4217 currency code");
                return null;
            }
        }

        private Object date(JsonNode node, String path) {
            if (!node.isTextual() || !DATE.matcher(node.textValue()).matches()) {
                problem(path, "expected " + DATE_FORM);
                return null;
            }
            try {
                return LocalDate.parse(node.textValue());
            } catch (DateTimeParseException e) {
                problem(path, "expected an existing date as \"yyyy-MM-dd\"");
                return null;
            }
        }

        private Object list(JsonNode node, ValueType element, String path) {
            if (!node.isArray()) {
                problem(path, "expected an array");
                return null;
            }
            List<Object> items = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                String at = path + "[" + i + "]";
                JsonNode item = node.get(i);
                if (item.isNull()) {
                    problem(at, "null is not a value");
                } else {
                    items.add(value(item, element, at));
                }
            }
            return Collections.unmodifiableList(items);
        }

        private Object struct(JsonNode node, ValueType.Struct struct, String path) {
            if (!node.isObject()) {
                problem(path, "expected an object");
                return null;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                String at = path + "." + e.getKey();
                ValueType type = struct.fields().get(e.getKey());
                if (type == null) {
                    problem(at, "unknown field (declared: " + String.join(", ", struct.fields().keySet()) + ")");
                } else if (!e.getValue().isNull()) {
                    // null, like an absent field, leaves the field unset
                    Object value = value(e.getValue(), type, at);
                    if (value != null) {
                        fields.put(e.getKey(), value);
                    }
                }
            });
            return Collections.unmodifiableMap(fields);
        }
    }
}
