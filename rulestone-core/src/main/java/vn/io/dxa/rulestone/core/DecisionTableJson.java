//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Reads and writes decision tables in the {@code .stone.json} format.
 *
 * <pre>{@code
 * {
 *   "id": "loan_eligibility",
 *   "version": "3",
 *   "effectiveFrom": "2026-11-01",
 *   "hitPolicy": "FIRST",
 *   "inputs":  { "applicant": { "age": "int", "income": "decimal" }, "amount": "decimal" },
 *   "outputs": { "decision": "string", "limit": "decimal" },
 *   "rules": [
 *     { "id": "R1", "when": "applicant.age < 18", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Types are {@code "bool"}, {@code "int"}, {@code "string"}, {@code "decimal"}, {@code "money"},
 * {@code "date"}, an object of named field types (a struct), or a one-element array (a list of
 * that element type).
 * Reading is strict: unknown or duplicate keys are errors, so a misspelt
 * {@code "efectiveFrom"} cannot silently change which rules are in force.
 */
public final class DecisionTableJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Set<String> TABLE_FIELDS = Set.of(
            "id", "version", "description", "effectiveFrom", "effectiveTo", "hitPolicy", "inputs", "outputs", "rules");
    private static final Set<String> RULE_FIELDS = Set.of("id", "description", "when", "then", "reasonCode");

    private DecisionTableJson() {}

    /** @throws InvalidDecisionException listing every structural problem found */
    public static DecisionTable read(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidDecisionException(List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return new Reader().table(root);
    }

    public static DecisionTable read(InputStream in) {
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DecisionTable read(Path file) {
        try {
            return read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the canonical form: compact, keys sorted, absent optionals omitted. Two tables
     * with the same content always produce the same text, which is what the content hash
     * in a rule version is computed from.
     */
    public static String write(DecisionTable table) {
        Map<String, Object> json = new TreeMap<>();
        json.put("id", table.id());
        json.put("version", table.version());
        table.description().ifPresent(d -> json.put("description", d));
        table.effectiveFrom().ifPresent(d -> json.put("effectiveFrom", d.toString()));
        table.effectiveTo().ifPresent(d -> json.put("effectiveTo", d.toString()));
        json.put("hitPolicy", table.hitPolicy().name());
        json.put("inputs", typesJson(table.inputs()));
        json.put("outputs", typesJson(table.outputs()));
        List<Object> rules = new ArrayList<>();
        for (Rule rule : table.rules()) {
            Map<String, Object> r = new TreeMap<>();
            r.put("id", rule.id());
            rule.description().ifPresent(d -> r.put("description", d));
            r.put("when", rule.when());
            r.put("then", new TreeMap<>(rule.then()));
            rule.reasonCode().ifPresent(c -> r.put("reasonCode", c));
            rules.add(r);
        }
        json.put("rules", rules);
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> typesJson(Map<String, ValueType> types) {
        Map<String, Object> json = new TreeMap<>();
        types.forEach((name, type) -> json.put(name, typeJson(type)));
        return json;
    }

    private static Object typeJson(ValueType type) {
        if (type instanceof ValueType.Primitive primitive) {
            return primitive.typeName();
        }
        if (type instanceof ValueType.ListOf list) {
            return List.of(typeJson(list.element()));
        }
        return typesJson(((ValueType.Struct) type).fields());
    }

    /** Collects every problem instead of stopping at the first. */
    private static final class Reader {

        private final List<String> problems = new ArrayList<>();

        DecisionTable table(JsonNode root) {
            if (!root.isObject()) {
                throw new InvalidDecisionException(List.of("$: expected a JSON object"));
            }
            unknownFields(root, TABLE_FIELDS, "$");
            String id = requiredText(root, "id", "$");
            String version = requiredText(root, "version", "$");
            Optional<String> description = optionalText(root, "description", "$");
            Optional<LocalDate> from = optionalDate(root, "effectiveFrom");
            Optional<LocalDate> to = optionalDate(root, "effectiveTo");
            if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
                problem("$.effectiveTo", "must be after effectiveFrom");
            }
            HitPolicy hitPolicy = hitPolicy(root);
            Map<String, ValueType> inputs = typeMap(root, "inputs");
            Map<String, ValueType> outputs = typeMap(root, "outputs");
            List<Rule> rules = rules(root);
            if (!problems.isEmpty()) {
                throw new InvalidDecisionException(problems);
            }
            return new DecisionTable(id, version, description, from, to, hitPolicy, inputs, outputs, rules);
        }

        private List<Rule> rules(JsonNode root) {
            JsonNode node = root.get("rules");
            if (node == null || !node.isArray()) {
                problem("$.rules", "required array");
                return List.of();
            }
            List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                String path = "$.rules[" + i + "]";
                JsonNode r = node.get(i);
                if (!r.isObject()) {
                    problem(path, "expected an object");
                    continue;
                }
                unknownFields(r, RULE_FIELDS, path);
                String id = requiredText(r, "id", path);
                Optional<String> description = optionalText(r, "description", path);
                String when = requiredText(r, "when", path);
                Map<String, String> then = new LinkedHashMap<>();
                JsonNode thenNode = r.get("then");
                if (thenNode != null && !thenNode.isObject()) {
                    problem(path + ".then", "expected an object of output expressions");
                } else if (thenNode != null) {
                    thenNode.properties().forEach(e -> {
                        if (e.getValue().isTextual()) {
                            then.put(e.getKey(), e.getValue().asText());
                        } else {
                            problem(path + ".then." + e.getKey(),
                                    "expected a CEL expression string (string literals need inner quotes, e.g. \"'APPROVE'\")");
                        }
                    });
                }
                Optional<String> reasonCode = optionalText(r, "reasonCode", path);
                rules.add(new Rule(id, description, when, then, reasonCode));
            }
            return rules;
        }

        private HitPolicy hitPolicy(JsonNode root) {
            String text = requiredText(root, "hitPolicy", "$");
            if (text.isEmpty()) {
                return HitPolicy.FIRST;
            }
            try {
                return HitPolicy.valueOf(text);
            } catch (IllegalArgumentException e) {
                problem("$.hitPolicy", "unknown hit policy '" + text + "' (expected UNIQUE, FIRST or COLLECT)");
                return HitPolicy.FIRST;
            }
        }

        private Map<String, ValueType> typeMap(JsonNode root, String field) {
            JsonNode node = root.get(field);
            if (node == null || !node.isObject()) {
                problem("$." + field, "required object of name to type");
                return Map.of();
            }
            Map<String, ValueType> types = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                ValueType type = type(e.getValue(), "$." + field + "." + e.getKey());
                if (type != null) {
                    types.put(e.getKey(), type);
                }
            });
            return types;
        }

        private ValueType type(JsonNode node, String path) {
            if (node.isTextual()) {
                String name = node.asText();
                for (ValueType.Primitive primitive : ValueType.Primitive.values()) {
                    if (primitive.typeName().equals(name)) {
                        return primitive;
                    }
                }
                String hint = Set.of("double", "float", "number").contains(name)
                        ? " (floating point is not supported; use decimal)" : "";
                problem(path, "unknown type '" + name + "'" + hint);
                return null;
            }
            if (node.isArray()) {
                if (node.size() != 1) {
                    problem(path, "a list type is written as a one-element array, e.g. [\"decimal\"]");
                    return null;
                }
                ValueType element = type(node.get(0), path + "[]");
                return element == null ? null : ValueType.listOf(element);
            }
            if (node.isObject()) {
                if (node.isEmpty()) {
                    problem(path, "a struct type must declare at least one field");
                    return null;
                }
                Map<String, ValueType> fields = new LinkedHashMap<>();
                boolean valid = true;
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    ValueType fieldType = type(e.getValue(), path + "." + e.getKey());
                    valid &= fieldType != null;
                    fields.put(e.getKey(), fieldType);
                }
                return valid ? ValueType.struct(fields) : null;
            }
            problem(path, "expected a type name, a struct object or a one-element list array");
            return null;
        }

        private Optional<LocalDate> optionalDate(JsonNode root, String field) {
            return optionalText(root, field, "$").flatMap(text -> {
                try {
                    return Optional.of(LocalDate.parse(text));
                } catch (DateTimeParseException e) {
                    problem("$." + field, "expected an ISO date (yyyy-MM-dd) but got '" + text + "'");
                    return Optional.empty();
                }
            });
        }

        private String requiredText(JsonNode node, String field, String path) {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                problem(path + "." + field, "required non-empty string");
                return "";
            }
            return value.asText();
        }

        private Optional<String> optionalText(JsonNode node, String field, String path) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            if (!value.isTextual()) {
                problem(path + "." + field, "expected a string");
                return Optional.empty();
            }
            return Optional.of(value.asText());
        }

        private void unknownFields(JsonNode node, Set<String> allowed, String path) {
            node.fieldNames().forEachRemaining(name -> {
                if (!allowed.contains(name)) {
                    problem(path + "." + name,
                            "unknown field (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
                }
            });
        }

        private void problem(String path, String message) {
            problems.add(path + ": " + message);
        }
    }
}
