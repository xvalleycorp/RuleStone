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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import vn.io.dxa.rulestone.api.Facts;

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
 * <p>Numbers are read exactly: integers as {@code Long} ({@code BigInteger} beyond 64 bits),
 * all other numbers as {@code BigDecimal} with their written scale. No number ever passes
 * through {@code double}. JSON objects become {@code Map}s and arrays {@code List}s.
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
    private static final Set<String> CASE_FIELDS = Set.of("name", "description", "version", "asOf", "facts", "expect");
    private static final Set<String> EXPECT_FIELDS =
            Set.of("output", "exactOutput", "reasonCodes", "matchedRules", "ruleVersion", "error");

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
     * Reads test cases from JSON text.
     *
     * @param json   the content of a {@code .stone-test.json} file
     * @param source name used in test names and messages, usually the file name
     * @return the cases, in file order
     * @throws InvalidStoneTestException listing every problem in the text
     */
    public static List<StoneTestCase> read(String json, String source) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidStoneTestException(source, List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return new Reader(source).file(root);
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
        private final List<String> problems = new ArrayList<>();

        Reader(String source) {
            this.source = source;
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
            Facts facts = facts(node.get("facts"), path + ".facts");

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
                    // null means "this output must be absent"
                    outputNode.properties().forEach(e -> output.put(e.getKey(),
                            e.getValue().isNull() ? null : value(e.getValue(), at + ".output." + e.getKey())));
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
                            + ": a failed evaluation has no output, reason codes or matched rules");
                }
            }
            if (problems.size() > before) {
                return null;
            }
            return new StoneTestCase(source, decision, name, description, version, asOf, facts,
                    output, exactOutput, reasonCodes, matchedRules, ruleVersion, error);
        }

        private Facts facts(JsonNode node, String path) {
            if (node == null || !node.isObject()) {
                problem(path, "required object of fact names to values");
                return Facts.empty();
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
}
