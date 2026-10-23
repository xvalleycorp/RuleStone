//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.core.FactsJson;

/**
 * Temporal glue around {@link FactsJson}, which defines the JSON forms of facts and outputs.
 * Adds only what payloads need: records (accepted by {@code Facts}) written as objects, and a
 * deserializer that never turns a JSON number into a {@code double}.
 */
final class PayloadJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private PayloadJson() {
    }

    /**
     * Writes named values in RuleStone's JSON forms.
     *
     * @throws IllegalArgumentException for floating point or an unsupported type; the message names
     *                                  the location, never the value
     */
    static ObjectNode write(Map<String, ?> values) {
        Map<String, Object> plain = new LinkedHashMap<>();
        values.forEach((name, value) -> plain.put(name, recordsAsMaps(value, name)));
        return FactsJson.writeValues(plain);
    }

    /** Records become maps of their non-null components (a null struct field is unset). */
    private static Object recordsAsMaps(Object value, String path) {
        if (value instanceof Record rec) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (RecordComponent component : rec.getClass().getRecordComponents()) {
                Object field = read(rec, component, path);
                if (field != null) {
                    map.put(component.getName(), recordsAsMaps(field, path + "." + component.getName()));
                }
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, recordsAsMaps(v, path + "." + k)));
            return copy;
        }
        if (value instanceof Collection<?> items) {
            List<Object> copy = new ArrayList<>(items.size());
            int i = 0;
            for (Object item : items) {
                copy.add(recordsAsMaps(item, path + "[" + i++ + "]"));
            }
            return copy;
        }
        return value;
    }

    private static Object read(Record rec, RecordComponent component, String path) {
        try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(rec);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No cause: its message could contain the value.
            throw new IllegalArgumentException(path + "." + component.getName() + ": cannot be read ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Reads a JSON object as a tree, parsing every number exactly: integers as integral nodes,
     * other numbers as {@code BigDecimal} nodes with their written scale. Guards payloads from
     * clients that send decimals as JSON numbers.
     */
    static final class ExactObjectDeserializer extends StdDeserializer<ObjectNode> {

        private static final long serialVersionUID = 1L;

        ExactObjectDeserializer() {
            super(ObjectNode.class);
        }

        @Override
        public ObjectNode deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = read(p, ctxt);
            if (node instanceof ObjectNode object) {
                return object;
            }
            return (ObjectNode) ctxt.handleUnexpectedToken(ObjectNode.class, p);
        }

        private static JsonNode read(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == null) {
                return (JsonNode) ctxt.handleUnexpectedToken(JsonNode.class, p);
            }
            switch (token) {
                case START_OBJECT -> {
                    ObjectNode object = NODES.objectNode();
                    for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                        String name = p.currentName();
                        p.nextToken();
                        object.set(name, read(p, ctxt));
                    }
                    return object;
                }
                case START_ARRAY -> {
                    ArrayNode array = NODES.arrayNode();
                    for (JsonToken t = p.nextToken(); t != JsonToken.END_ARRAY; t = p.nextToken()) {
                        array.add(read(p, ctxt));
                    }
                    return array;
                }
                case VALUE_STRING -> {
                    return NODES.textNode(p.getText());
                }
                case VALUE_NUMBER_INT -> {
                    return p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                            ? NODES.numberNode(p.getBigIntegerValue())
                            : NODES.numberNode(p.getLongValue());
                }
                case VALUE_NUMBER_FLOAT -> {
                    return NODES.numberNode(p.getDecimalValue());
                }
                case VALUE_TRUE -> {
                    return NODES.booleanNode(true);
                }
                case VALUE_FALSE -> {
                    return NODES.booleanNode(false);
                }
                case VALUE_NULL -> {
                    return NODES.nullNode();
                }
                default -> {
                    return (JsonNode) ctxt.handleUnexpectedToken(JsonNode.class, p);
                }
            }
        }
    }
}
