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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Explanation;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.DecisionRecordJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.Explanations;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
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
 *   <li>{@code java-invalid-tables.json}: the problems reported for every table, parameter
 *       set and reason-code catalog in {@code invalid-tables.json};</li>
 *   <li>{@code java-explanations.json}: the explanation, in English and Vietnamese, of the record
 *       of every case of every example flow test file, with the record, and of two cases of every
 *       example table test file: the first with reason codes and the first without;</li>
 *   <li>{@code java-approvals.json}: signed approval statements, with the trusted keys, and the
 *       problems an engine built with {@code requireApproved} reports for each case.</li>
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
            RuleStone.Builder builder = RuleStone.builder().paramSets(sets).decisions(tables);
            if (testCase.has("reasonCodes")) {
                try {
                    builder.reasonCodeCatalog(ReasonCodeCatalogJson.read(testCase.get("reasonCodes").textValue()));
                    entry.putNull("readReasonCodes");
                } catch (InvalidDecisionException e) {
                    ArrayNode problems = entry.putArray("readReasonCodes");
                    e.problems().forEach(problems::add);
                }
            }
            if (testCase.path("requireReasonCodes").asBoolean(false)) {
                builder.requireReasonCodeCatalog();
            }
            ArrayNode build = entry.putArray("build");
            try {
                builder.build();
            } catch (InvalidDecisionException e) {
                e.problems().forEach(build::add);
            }
        }
        check("java-invalid-tables.json", out);
    }

    /**
     * The explanation, in every locale of the examples' catalog, of the records of the example test
     * files: every case of a flow, with its record, since the browser engine runs no flows; and two
     * cases of each table, which the browser engine evaluates itself.
     */
    @Test
    void explanations() throws IOException {
        ReasonCodeCatalog catalog = ExamplesStoneTest.exampleCatalog();
        List<DecisionRecord> records = new ArrayList<>();
        RuleStoneEngine engine = RuleStone.builder()
                .clock(CLOCK)
                .decisions(ExamplesStoneTest.allExampleTables())
                .flows(ExamplesStoneTest.exampleFlows())
                .paramSets(ExamplesStoneTest.exampleParamSets())
                .suppliedModelsOnly()
                .reasonCodeCatalog(catalog)
                .requireReasonCodeCatalog()
                .recordIds(() -> "fixture")
                .auditSink(records::add)
                .build();
        ObjectNode json = NODES.objectNode();
        json.putObject("catalog")
                .put("file", ReasonCodeCatalogJson.FILE_NAME)
                .put("version", ReasonCodeCatalogJson.exactVersion(catalog))
                .put("canonical", ReasonCodeCatalogJson.write(catalog));
        ObjectNode byFile = json.putObject("files");
        List<Path> testFiles = new ArrayList<>();
        for (Path dir : List.of(ExamplesStoneTest.EXAMPLES, ExamplesStoneTest.FLOWS)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(StoneTestJson.FILE_SUFFIX)).sorted().forEach(testFiles::add);
            }
        }
        for (Path file : testFiles) {
            String name = ExamplesStoneTest.EXAMPLES.relativize(file).toString().replace('\\', '/');
            ObjectNode cases = byFile.putObject(name);
            Set<String> kinds = new HashSet<>();
            for (StoneTestCase testCase : StoneTestJson.read(file, ExamplesStoneTest.allExampleTables(),
                    ExamplesStoneTest.exampleFlows())) {
                try {
                    engine.evaluate(testCase.ref(), testCase.facts());
                } catch (RuleStoneException e) {
                    // the record is written all the same
                }
                DecisionRecord record = records.get(records.size() - 1);
                // Every case of a flow; of a table, the first case with reasons and the first without,
                // which the browser engine re-evaluates, so that the fixture stays small.
                boolean flow = file.startsWith(ExamplesStoneTest.FLOWS);
                String kind = record.reasonCodes().isEmpty() ? "without" : "with";
                if (!flow && !kinds.add(kind)) {
                    continue;
                }
                ObjectNode entry = cases.putObject(testCase.name());
                if (flow) {
                    entry.set("record", DecisionRecordJson.toJson(record));
                }
                for (String locale : catalog.locales()) {
                    Explanation explanation = engine.explain(record, locale);
                    entry.putObject(locale)
                            .put("json", Explanations.write(explanation))
                            .put("text", Explanations.text(explanation));
                }
            }
        }
        check("java-explanations.json", json);
    }

    /** The rule version and canonical form of every example flow and of the tables the flows use. */
    @Test
    void flowHashes() {
        ObjectNode json = NODES.objectNode();
        for (Path file : flowFiles(".stone.json")) {
            DecisionTable table = DecisionTableJson.read(file);
            json.putObject(file.getFileName().toString())
                    .put("ruleVersion", ruleVersion(table))
                    .put("canonical", DecisionTableJson.write(table));
        }
        for (Path file : flowFiles(DecisionFlowJson.FILE_SUFFIX)) {
            DecisionFlow flow = DecisionFlowJson.read(file);
            String canonical = DecisionFlowJson.write(flow);
            json.putObject(file.getFileName().toString())
                    .put("ruleVersion", flow.version() + "@sha256:" + sha256(canonical))
                    .put("canonical", canonical);
        }
        check("java-flow-hashes.json", json);
    }

    /**
     * The decision record and trace of every case of every example flow test file, evaluated as
     * {@link ExamplesStoneTest} does: every model node takes its result from the case.
     */
    @Test
    void flowRecords() throws IOException {
        List<DecisionRecord> records = new ArrayList<>();
        RuleStoneEngine engine = RuleStone.builder()
                .clock(CLOCK)
                .decisions(ExamplesStoneTest.allExampleTables())
                .flows(ExamplesStoneTest.exampleFlows())
                .paramSets(ExamplesStoneTest.exampleParamSets())
                .suppliedModelsOnly()
                .recordIds(() -> "fixture")
                .auditSink(records::add)
                .build();
        ObjectNode json = NODES.objectNode();
        for (Path file : flowFiles(StoneTestJson.FILE_SUFFIX)) {
            ObjectNode cases = json.putObject(file.getFileName().toString());
            for (StoneTestCase testCase : StoneTestJson.read(file, ExamplesStoneTest.allExampleTables(),
                    ExamplesStoneTest.exampleFlows())) {
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
        check("java-flow-records.json", json);
    }

    /**
     * The problems reported for every flow in {@code invalid-flows.json}: when it is read, and when an
     * engine is built with it, its tables, parameter sets and reason-code catalog. A definition is
     * JSON text, or a JSON object that is written as text first.
     */
    @Test
    void invalidFlows() throws IOException {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode testCase : MAPPER.readTree(FIXTURES.resolve("invalid-flows.json").toFile())) {
            ObjectNode entry = out.addObject().put("name", testCase.get("name").textValue());
            List<DecisionTable> tables = new ArrayList<>();
            for (JsonNode text : testCase.path("tables")) {
                tables.add(DecisionTableJson.read(text(text)));
            }
            List<ParameterSet> sets = new ArrayList<>();
            for (JsonNode text : testCase.path("paramSets")) {
                sets.add(ParameterSetJson.read(text(text)));
            }
            ArrayNode read = entry.putArray("read");
            List<DecisionFlow> flows = new ArrayList<>();
            for (JsonNode text : testCase.get("flows")) {
                try {
                    flows.add(DecisionFlowJson.read(text(text)));
                    read.addNull();
                } catch (InvalidDecisionException e) {
                    ArrayNode problems = read.addArray();
                    e.problems().forEach(problems::add);
                }
            }
            RuleStone.Builder builder = RuleStone.builder().paramSets(sets).decisions(tables).flows(flows);
            for (JsonNode id : testCase.path("invokers")) {
                builder.modelInvoker(id.textValue(), call -> null);
            }
            if (testCase.path("suppliedModelsOnly").asBoolean(false)) {
                builder.suppliedModelsOnly();
            }
            if (testCase.has("reasonCodes")) {
                builder.reasonCodeCatalog(ReasonCodeCatalogJson.read(text(testCase.get("reasonCodes"))));
            }
            ArrayNode build = entry.putArray("build");
            try {
                builder.build();
            } catch (InvalidDecisionException e) {
                e.problems().forEach(build::add);
            }
        }
        check("java-invalid-flows.json", out);
    }

    /** A definition in a fixture: JSON text, or a JSON object written as compact text. */
    private static String text(JsonNode node) throws IOException {
        return node.isTextual() ? node.textValue() : JsonMapper.builder().build().writeValueAsString(node);
    }

    /** The files with {@code suffix} in examples/flows/, sorted. */
    private static List<Path> flowFiles(String suffix) {
        try (Stream<Path> list = Files.list(ExamplesStoneTest.FLOWS)) {
            return list.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * The problems an engine built with {@code requireApproved} reports for signed approval
     * statements; see {@link ApprovalFixtures}.
     */
    @Test
    void approvals() throws IOException {
        check("java-approvals.json", new ApprovalFixtures().write());
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
