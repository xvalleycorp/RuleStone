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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Reads and writes parameter sets in the {@code .params.json} format.
 *
 * <pre>{@code
 * {
 *   "id": "retail_credit",
 *   "version": "7",
 *   "description": "Retail credit policy limits, approved by the Credit Committee on 2026-11-20",
 *   "effectiveFrom": "2026-12-01",
 *   "values": {
 *     "maxDti": { "type": "decimal", "value": "0.45" },
 *     "minAge": { "type": "int", "value": 18 },
 *     "maxUnsecured": { "type": "money", "value": "500000000 VND" },
 *     "blockedSegments": { "type": ["string"], "value": ["SANCTIONED", "DORMANT"] }
 *   }
 * }
 * }</pre>
 *
 * <p>Types are written as for table inputs: {@code "bool"}, {@code "int"}, {@code "string"},
 * {@code "decimal"}, {@code "money"}, {@code "date"}, or a one-element array for a list. Parameters
 * have no fields: struct types are not allowed. Values use the {@link FactsJson} forms: money as
 * {@code "500000000 VND"}, decimals as numbers or strings (read exactly, never through floating
 * point), dates as {@code "yyyy-MM-dd"}. Reading is strict: unknown or duplicate keys and values that
 * do not match their type are errors, each reported with its location.
 */
public final class ParameterSetJson {

    /** File name suffix of parameter sets. */
    public static final String FILE_SUFFIX = ".params.json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // Keep the written scale: "0.450" stays 0.450.
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final Set<String> SET_FIELDS = Set.of(
            "id", "version", "description", "effectiveFrom", "effectiveTo", "values");
    private static final Set<String> VALUE_FIELDS = Set.of("type", "value");

    private ParameterSetJson() {}

    /** @throws InvalidDecisionException listing every problem found */
    public static ParameterSet read(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidDecisionException(List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return new Reader().set(root);
    }

    public static ParameterSet read(InputStream in) {
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ParameterSet read(Path file) {
        try {
            return read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the canonical form: compact, keys sorted, absent optionals omitted, values in the
     * {@link FactsJson} forms. The content hash in the set's version, e.g. {@code 7@sha256:…}, is
     * computed from it.
     *
     * @throws IllegalArgumentException if a value is of a Java type that has no JSON form
     */
    public static String write(ParameterSet set) {
        Map<String, Object> json = new TreeMap<>();
        json.put("id", set.id());
        json.put("version", set.version());
        set.description().ifPresent(d -> json.put("description", d));
        set.effectiveFrom().ifPresent(d -> json.put("effectiveFrom", d.toString()));
        set.effectiveTo().ifPresent(d -> json.put("effectiveTo", d.toString()));
        Map<String, Object> values = new TreeMap<>();
        set.values().forEach((name, parameter) -> {
            Map<String, Object> value = new TreeMap<>();
            value.put("type", DecisionTableJson.typeJson(parameter.type()));
            value.put("value", FactsJson.writeValue(parameter.value()));
            values.put(name, value);
        });
        json.put("values", values);
        return DecisionTableJson.writeCanonical(json);
    }

    /** Collects every problem instead of stopping at the first. */
    private static final class Reader {

        private final DecisionTableJson.Reader reader = new DecisionTableJson.Reader();

        ParameterSet set(JsonNode root) {
            if (root == null || !root.isObject()) {
                throw new InvalidDecisionException(List.of("$: expected a JSON object"));
            }
            reader.unknownFields(root, SET_FIELDS, "$");
            String id = reader.requiredText(root, "id", "$");
            String version = reader.requiredText(root, "version", "$");
            Optional<String> description = reader.optionalText(root, "description", "$");
            Optional<LocalDate> from = reader.optionalDate(root, "effectiveFrom");
            Optional<LocalDate> to = reader.optionalDate(root, "effectiveTo");
            if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
                reader.problem("$.effectiveTo", "must be after effectiveFrom");
            }
            Map<String, ParameterSet.Parameter> values = new LinkedHashMap<>();
            JsonNode node = root.get("values");
            if (node == null || !node.isObject()) {
                reader.problem("$.values", "required object of name to { \"type\", \"value\" }");
            } else {
                node.properties().forEach(e -> {
                    ParameterSet.Parameter parameter = parameter(e.getValue(), "$.values." + e.getKey());
                    if (parameter != null) {
                        values.put(e.getKey(), parameter);
                    }
                });
            }
            if (!reader.problems.isEmpty()) {
                throw new InvalidDecisionException(reader.problems);
            }
            return new ParameterSet(id, version, description, from, to, values);
        }

        private ParameterSet.Parameter parameter(JsonNode node, String path) {
            if (!node.isObject()) {
                reader.problem(path, "expected an object with \"type\" and \"value\"");
                return null;
            }
            reader.unknownFields(node, VALUE_FIELDS, path);
            JsonNode typeNode = node.get("type");
            ValueType type = null;
            if (typeNode == null || typeNode.isNull()) {
                reader.problem(path + ".type", "required: the parameter's type, e.g. \"decimal\"");
            } else {
                type = reader.type(typeNode, path + ".type");
                if (type != null && CompiledDecision.containsStruct(type)) {
                    reader.problem(path + ".type", Parameters.NO_STRUCTS);
                    type = null;
                }
            }
            JsonNode valueNode = node.get("value");
            if (valueNode == null || valueNode.isNull()) {
                reader.problem(path + ".value", "required: the parameter's value");
                return null;
            }
            if (type == null) {
                return null;
            }
            Object value = FactsJson.readValue(valueNode, type, path + ".value", reader.problems);
            return value == null ? null : new ParameterSet.Parameter(type, value);
        }
    }
}
