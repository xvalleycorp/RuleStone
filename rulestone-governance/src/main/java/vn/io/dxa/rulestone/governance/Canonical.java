//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON for the log and the policy: compact, keys sorted at every level, so that one value
 * has one text and one hash. Values are strings, whole numbers, booleans, lists and maps.
 */
final class Canonical {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private Canonical() {}

    static String write(Object json) {
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException if {@code text} is not JSON */
    static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage());
        }
    }

    /** {@code sha256:<64 lower-case hex digits>} of the UTF-8 bytes of {@code text}. */
    static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /**
     * A JSON value as plain Java: a map (sorted by key), a list, a string, a long or a boolean.
     *
     * @throws IllegalArgumentException for a null, a fraction or an integer beyond a long
     */
    static Object plain(JsonNode node, String path) {
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            node.fieldNames().forEachRemaining(name -> map.put(name, plain(node.get(name), path + "." + name)));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                list.add(plain(node.get(i), path + "[" + i + "]"));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        throw new IllegalArgumentException(path + ": expected a string, a whole number, a boolean, a list or an object");
    }

    /**
     * A deep, immutable copy of a value made of strings, whole numbers, booleans, lists and maps with
     * string keys; whole numbers become longs and maps are sorted by key.
     *
     * @throws IllegalArgumentException for anything else
     */
    static Object copy(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(path + ": keys must be strings");
                }
                copy.put(key, copy(entry.getValue(), path + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                copy.add(copy(list.get(i), path + "[" + i + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        throw new IllegalArgumentException(path + ": expected a string, a whole number, a boolean, a list or a map");
    }
}
