//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionVersions;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidFactsException;

/**
 * Reads test cases in the {@code .stone-test.json} format.
 *
 * <pre>{@code
 * {
 *   "decision": "loan_eligibility",
 *   "cases": [
 *     { "name": "adult with low debt is approved",
 *       "asOf": "2026-12-01",
 *       "facts": { "applicant": { "age": 30, "monthlyIncome": 25750000.50, … }, "amount": 300000000 },
 *       "expect": { "output": { "decision": "APPROVE", "limit": 309000000 }, "reasonCodes": [], "matchedRules": ["R4"] } }
 *   ]
 * }
 * }</pre>
 *
 * <p>A case of a decision flow can also expect the {@code "path"}: the ids of the nodes the
 * evaluation visits, in order, from the start node to the end node. {@code "matchedRules"} then
 * lists the matched rules of every table node, in order.
 *
 * <p>A case of a flow with {@code model} nodes gives the models' results in {@code "models"}, in the
 * form that {@link FactsJson#readModels} reads: node id to {@code { "modelId", "modelVersion",
 * "outputs", … }}, or to {@code { "failure": "TIMEOUT" }} or {@code "ERROR"}. A model node without a
 * result fails with {@code MISSING}. Build the engine under test with
 * {@code RuleStone.builder().suppliedModelsOnly()}, so that nodes in {@code invoke} mode use these
 * results too and the tests need no model server.
 *
 * <p>Given the decision tables (and flows), facts and expected outputs are read with the types the
 * table (or flow) declares, as by {@link FactsJson}: money as {@code "1500000 VND"}, dates as
 * {@code "2026-11-01"}, decimals as numbers or strings; undeclared inputs, struct fields and
 * outputs are problems. The table is the version the case selects with {@code "version"} or
 * {@code "asOf"}; without either, all versions of the decision must declare the same types. Cases
 * for a decision or version that is not among the tables are read without types.
 *
 * <p>Without tables, numbers are read exactly: integers as {@code Long} ({@code BigInteger}
 * beyond 64 bits), all other numbers as {@code BigDecimal} with their written scale; strings stay
 * strings, so money and date inputs cannot be written. No number ever passes through
 * {@code double}. JSON objects become {@code Map}s and arrays {@code List}s.
 *
 * <p>Reading is strict, like {@code DecisionTableJson}: unknown or duplicate keys are errors,
 * so a misspelt {@code "matchedRule"} cannot silently turn off a check. Every problem is
 * reported at once, with its JSON path, in an {@link InvalidStoneTestException}.
 */
public final class StoneTestJson {

    /** File name suffix that {@link StoneTests} looks for when scanning a directory. */
    public static final String FILE_SUFFIX = ".stone-test.json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // Keep the written scale: 25750000.50 stays 25750000.50, not 25750000.5.
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final Set<String> FILE_FIELDS = Set.of("decision", "description", "cases");
    private static final Set<String> CASE_FIELDS =
            Set.of("name", "description", "version", "asOf", "facts", "models", "expect");
    private static final Set<String> EXPECT_FIELDS =
            Set.of("output", "exactOutput", "reasonCodes", "matchedRules", "path", "ruleVersion", "error");

    private StoneTestJson() {}

    /**
     * Reads the test cases of one file.
     *
     * @param file a {@code .stone-test.json} file, read as UTF-8
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the file
     * @throws UncheckedIOException      if the file cannot be read
     */
    public static List<StoneTestCase> read(Path file) {
        Path fileName = file.getFileName();
        return read(readString(file), fileName == null ? file.toString() : fileName.toString());
    }

    /**
     * Reads the test cases of one file, with the types declared by {@code tables}.
     *
     * @param file   a {@code .stone-test.json} file, read as UTF-8
     * @param tables the decision tables under test, usually the ones the engine was built from
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the file, including values that do
     *                                   not match their declared types
     * @throws UncheckedIOException      if the file cannot be read
     */
    public static List<StoneTestCase> read(Path file, Collection<DecisionTable> tables) {
        Path fileName = file.getFileName();
        return read(readString(file), fileName == null ? file.toString() : fileName.toString(), tables);
    }

    /**
     * Reads test cases from JSON text.
     *
     * @param json   the content of a {@code .stone-test.json} file
     * @param source name used in test names and messages, usually the file name
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the text
     */
    public static List<StoneTestCase> read(String json, String source) {
        return parse(json, source, null);
    }

    /**
     * Reads test cases from JSON text, with the types declared by {@code tables}.
     *
     * @param json   the content of a {@code .stone-test.json} file
     * @param source name used in test names and messages, usually the file name
     * @param tables the decision tables under test, usually the ones the engine was built from
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the text, including values that do
     *                                   not match their declared types
     */
    public static List<StoneTestCase> read(String json, String source, Collection<DecisionTable> tables) {
        return parse(json, source, List.copyOf(tables));
    }

    /**
     * Reads the test cases of one file, with the types declared by {@code tables} and {@code flows}.
     *
     * @param file   a {@code .stone-test.json} file, read as UTF-8
     * @param tables the decision tables under test, including those the flows use
     * @param flows  the decision flows under test
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the file, including values that do
     *                                   not match their declared types
     * @throws UncheckedIOException      if the file cannot be read
     */
    public static List<StoneTestCase> read(Path file, Collection<DecisionTable> tables,
                                           Collection<DecisionFlow> flows) {
        Path fileName = file.getFileName();
        return parse(readString(file), fileName == null ? file.toString() : fileName.toString(),
                List.copyOf(tables), List.copyOf(flows));
    }

    /**
     * Reads test cases from JSON text, with the types declared by {@code tables} and {@code flows}.
     *
     * @param json   the content of a {@code .stone-test.json} file
     * @param source name used in test names and messages, usually the file name
     * @param tables the decision tables under test, including those the flows use
     * @param flows  the decision flows under test
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the text, including values that do
     *                                   not match their declared types
     */
    public static List<StoneTestCase> read(String json, String source, Collection<DecisionTable> tables,
                                           Collection<DecisionFlow> flows) {
        return parse(json, source, List.copyOf(tables), List.copyOf(flows));
    }

    /** Reads with the types of {@code tables}, or without types when it is null. */
    static List<StoneTestCase> parse(String json, String source, List<DecisionTable> tables) {
        return parse(json, source, tables, tables == null ? null : List.of());
    }

    /** Reads with the types of {@code tables} and {@code flows}, or without types when they are null. */
    static List<StoneTestCase> parse(String json, String source, List<DecisionTable> tables,
                                     List<DecisionFlow> flows) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidStoneTestException(source, List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return new Reader(source, tables, flows).file(root);
    }

    /** The test files at {@code path}: the file itself, or every {@code *.stone-test.json} below a directory, sorted. */
    static List<Path> find(Path path) {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("no such file or directory: " + path.toAbsolutePath().normalize());
        }
        try (Stream<Path> files = Files.walk(path)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Collects every problem instead of stopping at the first. */
    private static final class Reader {

        private final String source;
        private final List<DecisionTable> tables;   // null: read without types
        private final List<DecisionFlow> flows;     // null: read without types
        private final List<String> problems = new ArrayList<>();

        Reader(String source, List<DecisionTable> tables, List<DecisionFlow> flows) {
            this.source = source;
            this.tables = tables;
            this.flows = flows;
        }

        List<StoneTestCase> file(JsonNode root) {
            if (root == null || !root.isObject()) {
                throw new InvalidStoneTestException(source, List.of("$: expected a JSON object"));
            }
            unknownFields(root, FILE_FIELDS, "$");
            String decision = requiredText(root, "decision", "$");
            optionalText(root, "description", "$");
            JsonNode cases = root.get("cases");
            List<StoneTestCase> result = new ArrayList<>();
            if (cases == null || !cases.isArray() || cases.isEmpty()) {
                problem("$.cases", "required non-empty array of test cases");
            } else {
                Set<String> names = new HashSet<>();
                for (int i = 0; i < cases.size(); i++) {
                    StoneTestCase c = testCase(cases.get(i), "$.cases[" + i + "]", decision);
                    if (c != null && !names.add(c.name())) {
                        problem("$.cases[" + i + "].name", "duplicate case name " + Expectations.quote(c.name()));
                    }
                    if (c != null) {
                        result.add(c);
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw new InvalidStoneTestException(source, problems);
            }
            return List.copyOf(result);
        }

        private StoneTestCase testCase(JsonNode node, String path, String decision) {
            if (!node.isObject()) {
                problem(path, "expected an object");
                return null;
            }
            int before = problems.size();
            unknownFields(node, CASE_FIELDS, path);
            String name = requiredText(node, "name", path);
            Optional<String> description = optionalText(node, "description", path);
            Optional<String> version = optionalText(node, "version", path);
            Optional<LocalDate> asOf = optionalText(node, "asOf", path).flatMap(text -> {
                try {
                    return Optional.of(LocalDate.parse(text));
                } catch (DateTimeParseException e) {
                    problem(path + ".asOf", "expected an ISO date (yyyy-MM-dd) but got '" + text + "'");
                    return Optional.empty();
                }
            });
            Schema schema = schema(decision, version, asOf, path);
            Facts facts = facts(node.get("facts"), path + ".facts", schema);
            JsonNode models = node.get("models");
            if (models != null) {
                try {
                    facts = facts.withModels(FactsJson.readModels(models));
                } catch (InvalidFactsException e) {
                    e.problems().forEach(p -> problems.add(path + ".models" + (p.startsWith("$") ? p.substring(1) : "." + p)));
                }
            }

            JsonNode expect = node.get("expect");
            String at = path + ".expect";
            if (expect == null || !expect.isObject()) {
                problem(at, "required object (use {} to check only that the evaluation succeeds)");
                return null;
            }
            unknownFields(expect, EXPECT_FIELDS, at);
            Map<String, Object> output = new LinkedHashMap<>();
            JsonNode outputNode = expect.get("output");
            if (outputNode != null) {
                if (outputNode.isObject()) {
                    output = outputs(outputNode, at + ".output", schema);
                } else {
                    problem(at + ".output", "expected an object of output names to values");
                }
            }
            boolean exactOutput = false;
            JsonNode exactNode = expect.get("exactOutput");
            if (exactNode != null) {
                if (!exactNode.isBoolean()) {
                    problem(at + ".exactOutput", "expected true or false");
                } else if (outputNode == null) {
                    problem(at + ".exactOutput", "requires \"output\"");
                }
                exactOutput = exactNode.asBoolean();
            }
            Optional<List<String>> reasonCodes = stringList(expect, "reasonCodes", at);
            Optional<List<String>> matchedRules = stringList(expect, "matchedRules", at);
            Optional<List<String>> nodes = stringList(expect, "path", at);
            Optional<String> ruleVersion = optionalText(expect, "ruleVersion", at);
            Optional<String> error = optionalText(expect, "error", at);
            if (error.isPresent()) {
                if (error.get().isBlank()) {
                    problem(at + ".error", "required non-empty text that the error message must contain");
                }
                List<String> others = new ArrayList<>();
                expect.fieldNames().forEachRemaining(f -> {
                    if (!f.equals("error") && EXPECT_FIELDS.contains(f)) {
                        others.add(f);
                    }
                });
                if (!others.isEmpty()) {
                    problem(at, "\"error\" cannot be combined with " + others
                            + ": a failed evaluation has no output, reason codes, matched rules or path");
                }
            }
            if (problems.size() > before) {
                return null;
            }
            return new StoneTestCase(source, decision, name, description, version, asOf, facts,
                    output, exactOutput, reasonCodes, matchedRules, nodes, ruleVersion, error);
        }

        /** The declared types for a case, or null to read without types. */
        private Schema schema(String decision, Optional<String> version, Optional<LocalDate> asOf, String path) {
            if (tables == null) {
                return null;
            }
            List<DecisionFlow> flowVersions = flows == null ? List.of()
                    : flows.stream().filter(f -> f.id().equals(decision)).toList();
            if (!flowVersions.isEmpty()) {
                List<DecisionFlow> candidates = version.isPresent()
                        ? flowVersions.stream().filter(f -> f.version().equals(version.get())).toList()
                        : asOf.map(date -> DecisionVersions.inForce(flowVersions, date).stream().toList())
                                .orElse(flowVersions);
                if (candidates.isEmpty()) {
                    return null;   // the engine reports the unknown version
                }
                Schema schema = new Schema(candidates.get(0).inputs(), candidates.get(0).outputs());
                for (DecisionFlow flow : candidates) {
                    if (!flow.inputs().equals(schema.inputs()) || !flow.outputs().equals(schema.results())) {
                        problem(path, "set \"version\" or \"asOf\": the versions of " + Expectations.quote(decision)
                                + " declare different inputs or outputs");
                        return null;
                    }
                }
                return schema;
            }
            List<DecisionTable> versions = tables.stream().filter(t -> t.id().equals(decision)).toList();
            List<DecisionTable> candidates = version.isPresent()
                    ? versions.stream().filter(t -> t.version().equals(version.get())).toList()
                    : asOf.map(date -> DecisionVersions.inForce(versions, date).stream().toList()).orElse(versions);
            if (candidates.isEmpty()) {
                return null;   // the engine reports the unknown decision or version
            }
            Schema schema = new Schema(candidates.get(0).inputs(), FactsJson.resultTypes(candidates.get(0)));
            for (DecisionTable table : candidates) {
                if (!table.inputs().equals(schema.inputs()) || !FactsJson.resultTypes(table).equals(schema.results())) {
                    problem(path, "set \"version\" or \"asOf\": the versions of " + Expectations.quote(decision)
                            + " declare different inputs or outputs");
                    return null;
                }
            }
            return schema;
        }

        private Facts facts(JsonNode node, String path, Schema schema) {
            if (node == null || !node.isObject()) {
                problem(path, "required object of fact names to values");
                return Facts.empty();
            }
            if (schema != null) {
                if (nulls(node, path)) {
                    return Facts.empty();
                }
                try {
                    return FactsJson.readFacts(node, schema.inputs());
                } catch (InvalidFactsException e) {
                    e.problems().forEach(p -> problems.add(path + "." + p));
                    return Facts.empty();
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                Object value = value(e.getValue(), path + "." + e.getKey());
                if (value != null) {
                    values.put(e.getKey(), value);
                }
            });
            return Facts.from(values);
        }

        /** Expected outputs; a JSON null means "this output must be absent". */
        private Map<String, Object> outputs(JsonNode node, String path, Schema schema) {
            Map<String, Object> output = new LinkedHashMap<>();
            if (schema == null) {
                node.properties().forEach(e -> output.put(e.getKey(),
                        e.getValue().isNull() ? null : value(e.getValue(), path + "." + e.getKey())));
                return output;
            }
            ObjectNode values = node.deepCopy();
            node.properties().forEach(e -> {
                if (e.getValue().isNull()) {
                    values.remove(e.getKey());
                    if (!schema.results().containsKey(e.getKey())) {
                        problem(path + "." + e.getKey(), "not declared (declared: "
                                + String.join(", ", schema.results().keySet()) + ")");
                    }
                }
            });
            Map<String, Object> converted = Map.of();
            try {
                converted = FactsJson.readValues(values, schema.results());
            } catch (InvalidFactsException e) {
                e.problems().forEach(p -> problems.add(path + "." + p));
            }
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                output.put(e.getKey(), e.getValue().isNull() ? null : converted.get(e.getKey()));
            }
            return output;
        }

        /** Reports every JSON null in {@code node}: test files never use null for a value. */
        private boolean nulls(JsonNode node, String path) {
            if (node.isNull()) {
                problem(path, "null is not a value; leave the field out instead");
                return true;
            }
            boolean found = false;
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    found |= nulls(node.get(i), path + "[" + i + "]");
                }
            } else if (node.isObject()) {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    found |= nulls(e.getValue(), path + "." + e.getKey());
                }
            }
            return found;
        }

        /** Converts a JSON value to Java without floating point; reports and returns null for JSON null. */
        private Object value(JsonNode node, String path) {
            if (node.isNull()) {
                problem(path, "null is not a value; leave the field out instead");
                return null;
            }
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            if (node.isIntegralNumber()) {
                return node.canConvertToLong() ? (Object) node.longValue() : node.bigIntegerValue();
            }
            if (node.isBigDecimal()) {
                return node.decimalValue();
            }
            if (node.isArray()) {
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < node.size(); i++) {
                    items.add(value(node.get(i), path + "[" + i + "]"));
                }
                return Collections.unmodifiableList(items);
            }
            if (node.isObject()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                node.properties().forEach(e -> fields.put(e.getKey(), value(e.getValue(), path + "." + e.getKey())));
                return Collections.unmodifiableMap(fields);
            }
            problem(path, "unsupported value (floating point is never used; write numbers as plain JSON numbers)");
            return null;
        }

        private Optional<List<String>> stringList(JsonNode node, String field, String path) {
            JsonNode value = node.get(field);
            if (value == null) {
                return Optional.empty();
            }
            if (!value.isArray()) {
                problem(path + "." + field, "expected an array of strings ([] for none)");
                return Optional.empty();
            }
            List<String> items = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (item.isTextual()) {
                    items.add(item.asText());
                } else {
                    problem(path + "." + field + "[" + i + "]", "expected a string");
                }
            }
            return Optional.of(items);
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
            if (value == null) {
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

    /** The declared input types and result types of the decision version a case evaluates. */
    private record Schema(Map<String, ValueType> inputs, Map<String, ValueType> results) {}
}
