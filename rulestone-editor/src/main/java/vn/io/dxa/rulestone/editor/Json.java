//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared Jackson configuration: strict, exact numbers, no floating point. */
final class Json {

    /**
     * Rejects duplicate keys and keeps every number exact: integers become longs or big integers,
     * fractions become {@link java.math.BigDecimal}s with their original scale. Never doubles.
     */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {}

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /**
     * Parses a request body. The error never quotes the body: it may contain customer data.
     *
     * @throws ApiException 400 with the line and column of the first syntax error
     */
    static JsonNode parse(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new ApiException(400, "invalid_json", "request body must be a JSON document");
            }
            return node;
        } catch (JsonProcessingException e) {
            String where = e.getLocation() == null ? ""
                    : " at line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw new ApiException(400, "invalid_json", "request body is not valid JSON" + where);
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON string literal for {@code text}, with the escapes JSON requires and nothing else. */
    static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
