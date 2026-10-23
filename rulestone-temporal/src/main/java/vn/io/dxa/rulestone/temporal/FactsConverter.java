//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.Facts;

/**
 * Converts between RuleStone values and JSON-like values that travel safely in Temporal payloads.
 *
 * <p>A <em>JSON-like</em> value is one of: {@link String}, {@link Boolean}, {@link Long}, {@link BigInteger}
 * (beyond 64 bits only), {@link BigDecimal}, a {@link List} of JSON-like values, or a {@link Map} with
 * {@code String} keys and JSON-like values. Normalization accepts more and converts it:
 *
 * <ul>
 *   <li>{@code Integer}, {@code Short} and {@code Byte} become {@code Long};</li>
 *   <li>enums become their {@code name()};</li>
 *   <li>records become maps of their components;</li>
 *   <li>any {@link Collection} becomes a list.</li>
 * </ul>
 *
 * <p>{@code Double} and {@code Float} are <strong>rejected</strong>: decimals must be {@link BigDecimal}.
 * Every other type is rejected too. Error messages name the offending path (for example
 * {@code fact 'applicant.monthlyIncome'}), never the value.
 *
 * <p>{@link JsonValueSerializer} and {@link JsonValueDeserializer} keep these values exact in JSON:
 * a {@code BigDecimal} is always written with a fraction or an exponent (scale-0 values in exponent
 * form, e.g. {@code 1.20000000E8}), so it is read back as the same {@code BigDecimal} with the same
 * scale, never as a {@code double} or an integer. {@link DecisionRequest} and {@link DecisionOutcome}
 * carry these annotations, so Temporal's default Jackson payload converter round-trips them without
 * precision loss.
 */
public final class FactsConverter {

    private FactsConverter() {
    }

    /**
     * Converts JSON-like values into {@link Facts}, normalizing them as described in the class comment.
     *
     * @param values named inputs; top-level values must not be {@code null}, nested values may be
     *               (a {@code null} struct field is left unset)
     * @return the facts
     * @throws IllegalArgumentException if a value is {@code null} at the top level, a floating-point
     *                                  number, or of an unsupported type; the message names the path only
     */
    public static Facts toFacts(Map<String, ?> values) {
        return Facts.from(normalizeMap(values, "fact", false));
    }

    /**
     * Converts values, such as {@code DecisionResult.output()}, into payload-safe JSON-like values.
     * {@code BigDecimal} stays {@code BigDecimal}, with its scale.
     *
     * @param values named values; top-level values must not be {@code null}
     * @return an unmodifiable, normalized copy that preserves iteration order
     * @throws IllegalArgumentException if a value is {@code null} at the top level, a floating-point
     *                                  number, or of an unsupported type; the message names the path only
     */
    public static Map<String, Object> toPayload(Map<String, ?> values) {
        return normalizeMap(values, "value", false);
    }

    static Map<String, Object> normalizeMap(Map<String, ?> values, String kind, boolean topLevelNullAllowed) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            String name = e.getKey();
            if (name == null) {
                throw new IllegalArgumentException(kind + " with a null name");
            }
            copy.put(name, normalize(e.getValue(), kind, name, topLevelNullAllowed));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object normalize(Object value, String kind, String path, boolean nullAllowed) {
        if (value == null) {
            if (nullAllowed) {
                return null;
            }
            throw new IllegalArgumentException(describe(kind, path) + " is null");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big) {
            return big.bitLength() < Long.SIZE ? (Object) big.longValueExact() : big;
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(describe(kind, path) + " is a floating-point number ("
                    + value.getClass().getSimpleName() + "); use BigDecimal for decimals");
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(describe(kind, path) + " has a key that is not text");
                }
                copy.put(key, normalize(e.getValue(), kind, path + "." + key, true));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            int index = 0;
            for (Object element : collection) {
                copy.add(normalize(element, kind, path + "[" + index + "]", true));
                index++;
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Record record) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String field = component.getName();
                copy.put(field, normalize(read(record, component, kind, path), kind, path + "." + field, true));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException(describe(kind, path) + " has unsupported type "
                + value.getClass().getName()
                + "; use text, booleans, integers, BigDecimal, lists, maps or records");
    }

    private static Object read(Record record, RecordComponent component, String kind, String path) {
        Method accessor = component.getAccessor();
        try {
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            // The cause is not attached: its message could contain the component's value.
            throw new IllegalArgumentException(describe(kind, path + "." + component.getName())
                    + " cannot be read: " + e.getClass().getSimpleName());
        }
    }

    private static String describe(String kind, String path) {
        return kind + " '" + path + "'";
    }

    /**
     * Writes a {@code BigDecimal} as a JSON number that always contains a fraction or an exponent and
     * that parses back to an equal {@code BigDecimal} with the same scale.
     */
    static String encodeDecimal(BigDecimal value) {
        if (value.scale() > 0) {
            return value.toPlainString();
        }
        String digits = value.unscaledValue().abs().toString();
        StringBuilder text = new StringBuilder(digits.length() + 8);
        if (value.signum() < 0) {
            text.append('-');
        }
        text.append(digits.charAt(0));
        if (digits.length() > 1) {
            text.append('.').append(digits, 1, digits.length());
        }
        // Mantissa has (digits - 1) fraction digits, so this exponent restores the original scale.
        return text.append('E').append(digits.length() - 1L - value.scale()).toString();
    }

    /**
     * Jackson serializer for JSON-like values, keeping {@code BigDecimal} exact. Applied with
     * {@code @JsonSerialize(contentUsing = FactsConverter.JsonValueSerializer.class)} on map-valued
     * properties. Rejects floating-point numbers.
     */
    public static final class JsonValueSerializer extends StdSerializer<Object> {

        private static final long serialVersionUID = 1L;

        /** Creates the serializer; Jackson instantiates it from the annotation. */
        public JsonValueSerializer() {
            super(Object.class);
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            Object normalized;
            try {
                normalized = normalize(value, "value", currentPath(gen), true);
            } catch (IllegalArgumentException e) {
                throw JsonMappingException.from(gen, e.getMessage());
            }
            write(normalized, gen);
        }

        private static String currentPath(JsonGenerator gen) {
            String name = gen.getOutputContext().getCurrentName();
            return name == null ? "?" : name;
        }

        private static void write(Object value, JsonGenerator gen) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else if (value instanceof BigDecimal decimal) {
                gen.writeNumber(encodeDecimal(decimal));
            } else if (value instanceof Long number) {
                gen.writeNumber(number);
            } else if (value instanceof BigInteger number) {
                gen.writeNumber(number);
            } else if (value instanceof Boolean flag) {
                gen.writeBoolean(flag);
            } else if (value instanceof String text) {
                gen.writeString(text);
            } else if (value instanceof Map<?, ?> map) {
                gen.writeStartObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    gen.writeFieldName((String) e.getKey());
                    write(e.getValue(), gen);
                }
                gen.writeEndObject();
            } else {
                gen.writeStartArray();
                for (Object element : (List<?>) value) {
                    write(element, gen);
                }
                gen.writeEndArray();
            }
        }
    }

    /**
     * Jackson deserializer for JSON-like values: objects become {@code LinkedHashMap}, arrays
     * {@code ArrayList}, integers {@code Long} ({@code BigInteger} beyond 64 bits), and numbers with a
     * fraction or exponent {@code BigDecimal}, parsed exactly from their text. Never produces a
     * {@code double}. Applied with
     * {@code @JsonDeserialize(contentUsing = FactsConverter.JsonValueDeserializer.class)}.
     */
    public static final class JsonValueDeserializer extends StdDeserializer<Object> {

        private static final long serialVersionUID = 1L;

        /** Creates the deserializer; Jackson instantiates it from the annotation. */
        public JsonValueDeserializer() {
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
