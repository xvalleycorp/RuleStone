//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads a job's variables and selects a decision's inputs among them. Conversion to typed values is
 * left to {@code FactsJson}; this class only adds the check that Zeebe's storage requires.
 *
 * <p>Zeebe stores a JSON number with a fraction as a 64-bit binary floating-point number. Such a
 * number reaches the worker as the shortest text that identifies that float, which is the decimal
 * that was sent only if it had at most {@value #EXACT_DIGITS} significant digits. Longer fractional
 * numbers are therefore rejected rather than read as a value nobody sent. Decimal strings, such as
 * {@code "25750000.50"}, are always exact.
 */
final class JobVariables {

    /** Significant decimal digits that survive a round trip through a 64-bit binary float. */
    static final int EXACT_DIGITS = 15;

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private JobVariables() {
    }

    /** Parses the job's variables; a parse error carries no message, which could quote values. */
    static ObjectNode parse(String json) {
        JsonNode node;
        try {
            node = json == null || json.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("job variables are not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("job variables are not a JSON object");
        }
        return object;
    }

    /**
     * The variables named in {@code names}, leaving out absent and {@code null} ones: the engine then
     * reports a missing input by name.
     */
    static ObjectNode select(ObjectNode variables, Collection<String> names) {
        ObjectNode selected = MAPPER.createObjectNode();
        for (String name : names) {
            JsonNode value = variables.get(name);
            if (value != null && !value.isNull()) {
                selected.set(name, value);
            }
        }
        return selected;
    }

    /** Problems for fractional numbers that a 64-bit float may not have kept exactly, by location. */
    static List<String> inexactNumbers(ObjectNode inputs) {
        List<String> problems = new ArrayList<>();
        inputs.properties().forEach(e -> check(e.getValue(), e.getKey(), problems));
        return problems;
    }

    private static void check(JsonNode node, String path, List<String> problems) {
        if (node.isFloatingPointNumber()
                && node.decimalValue().stripTrailingZeros().precision() > EXACT_DIGITS) {
            problems.add(path + ": a JSON number with more than " + EXACT_DIGITS
                    + " significant digits is not exact in Zeebe, which stores it as floating point;"
                    + " send decimals as strings, e.g. \"25750000.50\"");
        } else if (node.isObject()) {
            node.properties().forEach(e -> check(e.getValue(), path + "." + e.getKey(), problems));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                check(node.get(i), path + "[" + i + "]", problems);
            }
        }
    }
}
