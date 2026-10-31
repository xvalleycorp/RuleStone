//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionRecordJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Writes what the Java engine computes into fixtures that the browser engine's tests
 * (sdk/packages/engine) compare against, and fails if a committed fixture is stale.
 *
 * <ul>
 *   <li>{@code java-hashes.json}: the rule version and canonical form of every example table
 *       and of the SDK's extra test tables, followed by those of every parameter set;</li>
 *   <li>{@code java-records.json}: the decision record and trace of every case of every
 *       example test file;</li>
 *   <li>{@code java-probes.json}: the result, or the error, of every expression in
 *       {@code probes.json};</li>
 *   <li>{@code java-invalid-tables.json}: the problems reported for every table, and parameter
 *       set, in {@code invalid-tables.json}.</li>
 * </ul>
 *
 * <p>After changing the engine, the examples or the probes, regenerate them with
 * {@code mvn -pl rulestone-test -am test -Dtest=SdkFixturesTest -Dsurefire.failIfNoSpecifiedTests=false
 * -Drulestone.sdk.updateFixtures=true}.
 */
class SdkFixturesTest {

    static final Path FIXTURES = Path.of("..", "sdk", "packages", "engine", "test", "fixtures");
    private static final boolean UPDATE = Boolean.getBoolean("rulestone.sdk.updateFixtures");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The clock of the example tests: business date 2026-12-01 in Hanoi. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Test
    void tableHashes() {
        ObjectNode json = NODES.objectNode();
        for (Path file : tableFiles()) {
            DecisionTable table = DecisionTableJson.read(file);
            json.putObject(file.getFileName().toString())
                    .put("ruleVersion", ruleVersion(table))
                    .put("canonical", DecisionTableJson.write(table));
        }
        for (Path file : files(ParameterSetJson.FILE_SUFFIX)) {
            ParameterSet set = ParameterSetJson.read(file);
            String canonical = ParameterSetJson.write(set);
            json.putObject(file.getFileName().toString())
                    .put("ruleVersion", set.version() + "@sha256:" + sha256(canonical))
                    .put("canonical", canonical);
        }
        check("java-hashes.json", json);
    }

    @Test
    void decisionRecords() throws IOException {
        List<DecisionTable> tables = ExamplesStoneTest.exampleTables();
        List<DecisionRecord> records = new ArrayList<>();
        RuleStoneEngine engine = RuleStone.builder()
                .clock(CLOCK)
                .decisions(tables)
                .paramSets(ExamplesStoneTest.exampleParamSets())
                .recordIds(() -> "fixture")
                .auditSink(records::add)
                .build();
        ObjectNode json = NODES.objectNode();
        try (Stream<Path> files = Files.list(ExamplesStoneTest.EXAMPLES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(StoneTestJson.FILE_SUFFIX)).sorted().toList()) {
                ObjectNode cases = json.putObject(file.getFileName().toString());
                for (StoneTestCase testCase : StoneTestJson.read(file, tables)) {
                    DecisionResult result = null;
                    try {
                        result = engine.evaluate(testCase.ref(), testCase.facts());
                    } catch (RuleStoneException e) {
                        // recorded below
                    }
                    ObjectNode entry = DecisionRecordJson.toJson(records.get(records.size() - 1));
                    entry.remove(List.of("recordId", "evaluatedAt", "engine"));
                    if (result != null) {
                        ArrayNode trace = entry.putArray("trace");
                        result.trace().forEach(step -> trace.addArray().add(step.ruleId()).add(step.matched()));
                    }
                    cases.set(testCase.name(), entry);
                }
            }
        }
        check("java-records.json", json);
    }

    @Test
    void expressionProbes() throws IOException {
        JsonNode input = MAPPER.readTree(FIXTURES.resolve("probes.json").toFile());
        // The declared variables, read as the inputs of a table so that they use the .stone.json type syntax.
        ObjectNode table = NODES.objectNode().put("id", "probes").put("version", "1").put("hitPolicy", "FIRST");
        table.set("inputs", input.get("variables"));
        table.putObject("outputs");
        table.putArray("rules").addObject().put("id", "R").put("when", "true");
        Map<String, ValueType> variables = DecisionTableJson.read(MAPPER.writeValueAsString(table)).inputs();
        ExpressionEnvironment.Builder builder = ExpressionEnvironment.builder()
                .variables(variables)
                .maxIterations(input.get("maxIterations").intValue());
        input.get("currencyScales").properties().forEach(e -> builder.currencyScale(e.getKey(), e.getValue().intValue()));
        ExpressionEnvironment env = builder.build();
        Facts facts = FactsJson.readFacts(input.get("inputs"), variables);

        ArrayNode out = NODES.arrayNode();
        for (JsonNode probe : input.get("probes")) {
            String source = probe.get("expr").textValue();
            ObjectNode entry = out.addObject().put("expr", source);
            List<ValueType> types = probe.has("type") ? List.of(type(probe.get("type").textValue())) : PROBE_TYPES;
            CelExpression expression = null;
            String error = null;
            for (ValueType type : types) {
                try {
                    expression = env.compile(source, type);
                    entry.put("type", type.toString());
                    break;
                } catch (ExpressionException e) {
                    if (error == null) {
                        error = e.getMessage();
                    }
                }
            }
            if (expression == null) {
                entry.put("compileError", error);
                continue;
            }
            try {
                Object value = expression.evaluate(env.bind(facts.asMap()));
                entry.set("json", FactsJson.writeValue(value));
                entry.put("text", String.valueOf(value));
            } catch (ExpressionException e) {
                entry.put("evalError", e.getMessage());
            }
        }
        check("java-probes.json", out);
    }

    @Test
    void invalidTables() throws IOException {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode testCase : MAPPER.readTree(FIXTURES.resolve("invalid-tables.json").toFile())) {
            ObjectNode entry = out.addObject().put("name", testCase.get("name").textValue());
            ArrayNode read = entry.putArray("read");
            List<DecisionTable> tables = new ArrayList<>();
            for (JsonNode text : testCase.get("tables")) {
                try {
                    tables.add(DecisionTableJson.read(text.textValue()));
                    read.addNull();
                } catch (InvalidDecisionException e) {
                    ArrayNode problems = read.addArray();
                    e.problems().forEach(problems::add);
                }
            }
            List<ParameterSet> sets = new ArrayList<>();
            if (testCase.has("paramSets")) {
                ArrayNode readParams = entry.putArray("readParams");
                for (JsonNode text : testCase.get("paramSets")) {
                    try {
                        sets.add(ParameterSetJson.read(text.textValue()));
                        readParams.addNull();
                    } catch (InvalidDecisionException e) {
                        ArrayNode problems = readParams.addArray();
                        e.problems().forEach(problems::add);
                    }
                }
            }
            ArrayNode build = entry.putArray("build");
            try {
                RuleStone.builder().paramSets(sets).decisions(tables).build();
            } catch (InvalidDecisionException e) {
                e.problems().forEach(build::add);
            }
        }
        check("java-invalid-tables.json", out);
    }

    @Test
    void versionSelection() throws IOException {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode testCase : MAPPER.readTree(FIXTURES.resolve("version-selection.json").toFile())) {
            ObjectNode entry = out.addObject().put("name", testCase.get("name").textValue());
            List<DecisionTable> tables = new ArrayList<>();
            testCase.get("tables").forEach(text -> tables.add(DecisionTableJson.read(text.textValue())));
            RuleStoneEngine engine = RuleStone.builder().clock(CLOCK).decisions(tables).build();
            ArrayNode results = entry.putArray("results");
            for (JsonNode date : testCase.get("dates")) {
                ObjectNode result = results.addObject().put("date", date.textValue());
                try {
                    DecisionResult decided = engine.evaluate(
                            DecisionRef.of(tables.get(0).id()).asOf(LocalDate.parse(date.textValue())), Facts.empty());
                    result.put("ruleVersion", decided.ruleVersion());
                } catch (DecisionNotFoundException e) {
                    result.put("error", e.getMessage());
                }
            }
        }
        check("java-version-selection.json", out);
    }

    /** Result types tried in order for a probe without a type, as ExpressionsDocTest does. */
    static final List<ValueType> PROBE_TYPES = List.of(ValueType.BOOL, ValueType.INT, ValueType.STRING,
            ValueType.DECIMAL, ValueType.MONEY, ValueType.DATE, ValueType.listOf(ValueType.STRING),
            ValueType.listOf(ValueType.INT), ValueType.listOf(ValueType.DECIMAL), ValueType.listOf(ValueType.BOOL),
            ValueType.listOf(ValueType.MONEY), ValueType.listOf(ValueType.DATE),
            ValueType.listOf(ValueType.listOf(ValueType.INT)));

    private static ValueType type(String name) {
        if (name.startsWith("list<") && name.endsWith(">")) {
            return ValueType.listOf(type(name.substring(5, name.length() - 1)));
        }
        for (ValueType.Primitive primitive : ValueType.Primitive.values()) {
            if (primitive.typeName().equals(name)) {
                return primitive;
            }
        }
        throw new IllegalArgumentException("unknown probe type " + name);
    }

    /** The rule version the engine reports for a table, read from a result or a failure. */
    private static String ruleVersion(DecisionTable table) {
        RuleStoneEngine engine = RuleStone.builder().clock(CLOCK).decision(table)
                .paramSets(ExamplesStoneTest.exampleParamSets()).build();
        try {
            // On its first day, so that a parameter set version is in force too.
            DecisionRef ref = DecisionRef.of(table.id()).version(table.version());
            return engine.evaluate(table.effectiveFrom().map(ref::asOf).orElse(ref), Facts.empty()).ruleVersion();
        } catch (DecisionEvaluationException e) {
            return e.ruleVersion();
        }
    }

    private static List<Path> tableFiles() {
        return files(".stone.json");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The files with {@code suffix} in examples/, then in the fixtures' tables/, each sorted. */
    private static List<Path> files(String suffix) {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(ExamplesStoneTest.EXAMPLES, FIXTURES.resolve("tables"))) {
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static void check(String name, JsonNode json) {
        Path file = FIXTURES.resolve(name);
        String expected;
        try {
            expected = MAPPER.writeValueAsString(json).replace("\r\n", "\n") + "\n";
            if (UPDATE) {
                Files.writeString(file, expected, StandardCharsets.UTF_8);
                return;
            }
            String actual = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            assertThat(actual)
                    .as("%s is stale: regenerate it with mvn -pl rulestone-test -am test -Dtest=SdkFixturesTest "
                            + "-Dsurefire.failIfNoSpecifiedTests=false -Drulestone.sdk.updateFixtures=true", file)
                    .isEqualTo(expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
