//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.RuleStone;

class EditorApiTest {

    private static final String V3 = "loan_eligibility.v3.stone.json";

    @TempDir
    Path temp;

    private EditorHarness editor;

    @BeforeEach
    void start() {
        editor = EditorHarness.start(temp);
    }

    @AfterEach
    void stop() {
        editor.close();
    }

    // ---- meta ----

    @Test
    void metaListsEveryEngineTypeAndHitPolicy() {
        EditorHarness.Response response = editor.get("/api/meta");

        assertThat(response.status()).isEqualTo(200);
        JsonNode meta = response.json();
        List<String> types = new ArrayList<>();
        meta.get("types").forEach(t -> types.add(t.get("name").asText()));
        assertThat(types).containsExactly(
                java.util.Arrays.stream(ValueType.Primitive.values()).map(ValueType.Primitive::typeName).toArray(String[]::new));
        assertThat(types).contains("money", "date");
        assertThat(meta.get("hitPolicies").findValuesAsText("name")).containsExactly("UNIQUE", "FIRST", "COLLECT");
        assertThat(meta.get("functions").findValuesAsText("name"))
                .contains("decimal", "round", "money", "date", "yearsBetween", "nameSimilarity", "exists", "has");
        assertThat(meta.get("implicitInputs").get(0).get("name").asText()).isEqualTo("businessDate");
        assertThat(meta.get("editorVersion").asText()).isNotBlank();
    }

    // ---- list ----

    @Test
    void listsEveryTableWithItsMetadata() {
        JsonNode body = editor.get("/api/tables").json();

        List<String> files = body.get("tables").findValuesAsText("file");
        assertThat(files).contains(V3, "loan_eligibility.v2.stone.json", "deposit_rate.stone.json", "transfer_fees.stone.json");
        JsonNode v3 = find(body.get("tables"), V3);
        assertThat(v3.get("id").asText()).isEqualTo("loan_eligibility");
        assertThat(v3.get("version").asText()).isEqualTo("3");
        assertThat(v3.get("effectiveFrom").asText()).isEqualTo("2026-11-01");
        assertThat(v3.get("hitPolicy").asText()).isEqualTo("FIRST");
        assertThat(v3.get("ruleCount").asInt()).isEqualTo(5);
        assertThat(v3.get("ruleVersion").asText()).matches("3@sha256:[0-9a-f]{64}");
        assertThat(v3.get("problems")).isEmpty();
    }

    @Test
    void listReportsLoadProblemsAndVersionOverlaps() throws Exception {
        Files.writeString(editor.dir.resolve("broken.stone.json"), "{ \"id\": \"broken\", ");
        String overlapping = EditorHarness.read(editor.dir.resolve(V3))
                .replace("\"version\": \"3\"", "\"version\": \"3b\"")
                .replace("\"effectiveFrom\": \"2026-11-01\"", "\"effectiveFrom\": \"2026-12-01\"");
        Files.writeString(editor.dir.resolve("loan_eligibility.v3b.stone.json"), overlapping);

        JsonNode tables = editor.get("/api/tables").json().get("tables");

        assertThat(find(tables, "broken.stone.json").get("problems").get(0).get("message").asText()).startsWith("invalid JSON");
        JsonNode problem = find(tables, "loan_eligibility.v3b.stone.json").get("problems").get(0);
        assertThat(problem.get("message").asText()).contains("overlaps version 3 in " + V3);
        assertThat(problem.get("location").get("field").asText()).isEqualTo("effectiveFrom");
        assertThat(problem.get("location").get("file").asText()).isEqualTo(V3);
        assertThat(find(tables, V3).get("problems").get(0).get("location").get("field").asText()).isEqualTo("effectiveTo");
    }

    // ---- get ----

    @Test
    void getsTheFileContentTableEtagAndRuleVersion() {
        EditorHarness.Response response = editor.get("/api/tables/" + V3);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("content").asText()).isEqualTo(EditorHarness.read(editor.dir.resolve(V3)));
        assertThat(body.get("table").get("rules").size()).isEqualTo(5);
        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(response.header("ETag")).isEqualTo("\"" + body.get("etag").asText() + "\"");
        assertThat(body.get("ruleVersion").asText()).isEqualTo(engineRuleVersion(editor.dir.resolve(V3)));
    }

    @Test
    void unknownFileIs404() {
        assertThat(editor.get("/api/tables/nothing.stone.json").status()).isEqualTo(404);
    }

    // ---- save ----

    @Test
    void savesInTheStableFormatWithoutChangingTheContentHash() {
        JsonNode loaded = editor.get("/api/tables/" + V3).json();
        String hashBefore = loaded.get("ruleVersion").asText();

        EditorHarness.Response saved = editor.put("/api/tables/" + V3, loaded.get("table").toString());

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.json().get("saved").asBoolean()).isTrue();
        assertThat(saved.json().get("ruleVersion").asText()).isEqualTo(hashBefore);
        String file = EditorHarness.read(editor.dir.resolve(V3));
        assertThat(file).isEqualTo(TableFormatter.format(Json.parse(file)));
        assertThat(file).startsWith("{\n  \"id\": \"loan_eligibility\",\n  \"version\": \"3\",\n");
        assertThat(file).contains("\"then\": { \"decision\": \"'REJECT'\" },");
        assertThat(engineRuleVersion(editor.dir.resolve(V3))).isEqualTo(hashBefore);
        assertThat(saved.json().get("etag").asText()).isEqualTo(RulesDirectory.etag(file));
    }

    @Test
    void refusesToSaveAnInvalidTableUnlessForced() {
        ObjectNode table = (ObjectNode) editor.get("/api/tables/" + V3).json().get("table");
        ((ObjectNode) table.get("rules").get(0)).put("when", "applicant.agee < 18");
        String before = EditorHarness.read(editor.dir.resolve(V3));

        EditorHarness.Response refused = editor.put("/api/tables/" + V3, table.toString());

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("saved").asBoolean()).isFalse();
        assertThat(refused.json().get("problems").get(0).get("location").get("rule").asText()).isEqualTo("R1");
        assertThat(EditorHarness.read(editor.dir.resolve(V3))).isEqualTo(before);

        EditorHarness.Response forced = editor.put("/api/tables/" + V3 + "?force=true", table.toString());

        assertThat(forced.status()).isEqualTo(200);
        assertThat(forced.json().get("valid").asBoolean()).isFalse();
        assertThat(EditorHarness.read(editor.dir.resolve(V3))).contains("applicant.agee < 18");
    }

    @Test
    void ifMatchProtectsAgainstConcurrentChanges() throws Exception {
        JsonNode loaded = editor.get("/api/tables/" + V3).json();
        String etag = loaded.get("etag").asText();
        Files.writeString(editor.dir.resolve(V3), EditorHarness.read(editor.dir.resolve(V3)) + "\n");

        EditorHarness.Response stale = editor.send("PUT", "/api/tables/" + V3, loaded.get("table").toString(),
                Map.of("If-Match", "\"" + etag + "\""));

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.json().get("error").get("code").asText()).isEqualTo("modified");
    }

    @Test
    void putDoesNotCreateFiles() {
        String table = editor.get("/api/tables/" + V3).json().get("table").toString();
        assertThat(editor.put("/api/tables/new.stone.json", table).status()).isEqualTo(404);
        assertThat(Files.exists(editor.dir.resolve("new.stone.json"))).isFalse();
    }

    // ---- create ----

    @Test
    void createsANewFileNamedAfterIdAndVersion() {
        ObjectNode table = (ObjectNode) editor.get("/api/tables/deposit_rate.stone.json").json().get("table");
        table.put("id", "deposit_rate_sme");

        EditorHarness.Response created = editor.post("/api/tables", "{\"table\": " + table + "}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("file").asText()).isEqualTo("deposit_rate_sme.v1.stone.json");
        assertThat(Files.exists(editor.dir.resolve("deposit_rate_sme.v1.stone.json"))).isTrue();
        assertThat(editor.post("/api/tables", "{\"table\": " + table + "}").status()).isEqualTo(409);
    }

    @Test
    void createRefusesADuplicateVersionOfTheSameDecision() {
        JsonNode table = editor.get("/api/tables/" + V3).json().get("table");

        EditorHarness.Response refused = editor.post("/api/tables",
                "{\"file\": \"copy.stone.json\", \"table\": " + table + "}");

        assertThat(refused.status()).isEqualTo(422);
        JsonNode problem = refused.json().get("problems").get(0);
        assertThat(problem.get("message").asText()).isEqualTo("version 3 already exists in " + V3);
        assertThat(problem.get("location").get("field").asText()).isEqualTo("version");
    }

    @Test
    void createsANewVersionAndClosesThePreviousOne() {
        JsonNode loaded = editor.get("/api/tables/" + V3).json();
        ObjectNode table = (ObjectNode) loaded.get("table");
        table.put("version", "4");
        table.put("effectiveFrom", "2027-01-01");

        EditorHarness.Response created = editor.post("/api/tables", "{\"table\": " + table
                + ", \"closePrevious\": {\"file\": \"" + V3 + "\", \"etag\": \"" + loaded.get("etag").asText() + "\"}}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("file").asText()).isEqualTo("loan_eligibility.v4.stone.json");
        assertThat(created.json().get("previous").get("effectiveTo").asText()).isEqualTo("2027-01-01");
        assertThat(EditorHarness.read(editor.dir.resolve(V3)))
                .contains("\"effectiveFrom\": \"2026-11-01\",\n  \"effectiveTo\": \"2027-01-01\",");
        assertThat(editor.get("/api/tables").json().get("tables").findValues("problems")).allMatch(JsonNode::isEmpty);
    }

    // ---- validate ----

    @Test
    void validateLocatesExpressionErrorsInTheExactCell() {
        ObjectNode table = (ObjectNode) editor.get("/api/tables/" + V3).json().get("table");
        ((ObjectNode) table.get("rules").get(3).get("then")).put("limit", "'a lot'");
        ((ObjectNode) table.get("rules").get(0)).put("when", "applicant.agee < 18");

        JsonNode body = editor.post("/api/validate?file=" + V3, table.toString()).json();

        assertThat(body.get("valid").asBoolean()).isFalse();
        JsonNode when = body.get("problems").get(0);
        assertThat(when.get("message").asText()).isEqualTo("undefined field 'agee'");
        assertThat(when.get("location").toString())
                .isEqualTo("{\"path\":\"$.rules[0].when\",\"rule\":\"R1\",\"ruleIndex\":0,\"field\":\"when\",\"line\":1,\"column\":10}");
        JsonNode then = body.get("problems").get(1);
        assertThat(then.get("location").get("rule").asText()).isEqualTo("R4");
        assertThat(then.get("location").get("ruleIndex").asInt()).isEqualTo(3);
        assertThat(then.get("location").get("field").asText()).isEqualTo("then");
        assertThat(then.get("location").get("output").asText()).isEqualTo("limit");
    }

    @Test
    void validateWithoutFileReportsTheDuplicateVersion() {
        String table = editor.get("/api/tables/" + V3).json().get("table").toString();

        assertThat(editor.post("/api/validate?file=" + V3, table).json().get("valid").asBoolean()).isTrue();
        assertThat(editor.post("/api/validate", table).json().get("problems").get(0).get("location").get("field").asText())
                .isEqualTo("version");
    }

    @Test
    void validateReportsStructuralProblemsWithTheirJsonPath() {
        String table = "{\"id\":\"t\",\"version\":\"1\",\"hitPolicy\":\"FIRST\",\"inputs\":{\"a\":\"double\"},"
                + "\"outputs\":{\"x\":\"int\"},\"rules\":[{\"id\":\"R1\",\"when\":\"true\",\"then\":{\"x\":1}}]}";

        JsonNode problems = editor.post("/api/validate", table).json().get("problems");

        assertThat(problems.get(0).get("location").get("field").asText()).isEqualTo("inputs");
        assertThat(problems.get(0).get("location").get("input").asText()).isEqualTo("a");
        assertThat(problems.get(1).get("location").get("rule").asText()).isEqualTo("R1");
        assertThat(problems.get(1).get("location").get("output").asText()).isEqualTo("x");
    }

    @Test
    void validateReportsInvalidJsonAsAProblem() {
        JsonNode body = editor.post("/api/validate", "{\"id\": ").json();

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("problems").get(0).get("location").has("line")).isTrue();
    }

    // ---- format ----

    @Test
    void formatReturnsTheFileTextThatSavingWouldWrite() {
        JsonNode table = editor.get("/api/tables/" + V3).json().get("table");
        assertThat(editor.post("/api/format", table.toString()).json().get("content").asText())
                .isEqualTo(TableFormatter.format(table));
    }

    // ---- evaluate ----

    @Test
    void evaluatesWithExactDecimals() {
        JsonNode table = editor.get("/api/tables/" + V3).json().get("table");
        String facts = "{\"applicant\": {\"age\": 30, \"monthlyIncome\": \"25750000.50\", \"monthlyDebt\": 5000000,"
                + " \"segment\": \"RETAIL\", \"blacklisted\": false}, \"amount\": 300000000}";

        EditorHarness.Response response = editor.post("/api/evaluate",
                "{\"table\": " + table + ", \"facts\": " + facts + ", \"asOf\": \"2026-12-01\"}");

        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json();
        assertThat(result.get("output").toString()).isEqualTo("{\"decision\":\"APPROVE\",\"limit\":\"309000000\"}");
        assertThat(result.get("matchedRules").toString()).isEqualTo("[\"R4\"]");
        assertThat(result.get("trace")).hasSize(4);
        assertThat(result.get("effectiveOnAsOf").asBoolean()).isTrue();
        assertThat(result.get("ruleVersion").asText()).isEqualTo(engineRuleVersion(editor.dir.resolve(V3)));
    }

    @Test
    void evaluatesMoneyAndDates() {
        String table = """
                {"id": "fee", "version": "1", "hitPolicy": "FIRST",
                 "inputs": {"transfer": {"amount": "money", "valueDate": "date"}},
                 "outputs": {"fee": "money", "settles": "date", "late": "bool"},
                 "rules": [{"id": "R1", "when": "true", "then": {
                   "fee": "max(money(2000, 'VND'), (transfer.amount * decimal('0.0005')).round())",
                   "settles": "transfer.valueDate.plusDays(1)",
                   "late": "transfer.valueDate < businessDate"}}]}""";
        String facts = "{\"transfer\": {\"amount\": \"150000000 VND\", \"valueDate\": \"2026-11-02\"}}";

        JsonNode result = editor.post("/api/evaluate",
                "{\"table\": " + table + ", \"facts\": " + facts + ", \"asOf\": \"2026-11-03\"}").json();

        assertThat(result.get("output").toString()).isEqualTo(
                "{\"fee\":\"75000 VND\",\"settles\":\"2026-11-03\",\"late\":true}");

        String small = "{\"transfer\": {\"amount\": \"1000.50 VND\", \"valueDate\": \"2026-11-02\"}}";
        assertThat(editor.post("/api/evaluate", "{\"table\": " + table + ", \"facts\": " + small + "}")
                .json().get("output").get("fee").asText()).isEqualTo("2000 VND");

        String wrong = "{\"transfer\": {\"amount\": \"1000 vnd\", \"valueDate\": \"2026-11-02\"}}";
        EditorHarness.Response refused = editor.post("/api/evaluate", "{\"table\": " + table + ", \"facts\": " + wrong + "}");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("error").get("message").asText()).startsWith("transfer.amount: expected money");
    }

    @Test
    void evaluationErrorsNeverEchoFactValues() {
        JsonNode table = editor.get("/api/tables/" + V3).json().get("table");
        String facts = "{\"applicant\": {\"age\": \"SECRET-123\"}, \"amount\": 1}";

        EditorHarness.Response response = editor.post("/api/evaluate", "{\"table\": " + table + ", \"facts\": " + facts + "}");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("error").get("message").asText()).isEqualTo("applicant.age: expected an integer");
        assertThat(response.body()).doesNotContain("SECRET");
    }

    @Test
    void evaluationFailuresAreReported() {
        JsonNode table = editor.get("/api/tables/" + V3).json().get("table");
        String facts = "{\"applicant\": {\"age\": 30, \"monthlyIncome\": 0, \"monthlyDebt\": 1, \"segment\": \"RETAIL\","
                + " \"blacklisted\": false}, \"amount\": 1}";

        EditorHarness.Response response = editor.post("/api/evaluate", "{\"table\": " + table + ", \"facts\": " + facts + "}");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("error").get("code").asText()).isEqualTo("evaluation_failed");
        assertThat(response.json().get("error").get("message").asText()).contains("rule R3").contains("division by zero");
    }

    @Test
    void evaluateRefusesAnInvalidTable() {
        EditorHarness.Response response = editor.post("/api/evaluate", "{\"table\": {\"id\": \"x\"}, \"facts\": {}}");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("problems").size()).isPositive();
    }

    @Test
    void jsonNumbersAreNeverDoubles() {
        String table = """
                {"id": "t", "version": "1", "hitPolicy": "FIRST", "inputs": {"x": "decimal"}, "outputs": {"y": "decimal"},
                 "rules": [{"id": "R1", "when": "true", "then": {"y": "x * 3"}}]}""";

        JsonNode result = editor.post("/api/evaluate", "{\"table\": " + table + ", \"facts\": {\"x\": 0.1000000000000000055511151231257827}}").json();

        assertThat(result.get("output").get("y").asText()).isEqualTo("0.3000000000000000166533453693773481");
    }

    // ---- errors ----

    @Test
    void malformedRequestsAreRefusedWithoutEchoingTheBody() {
        EditorHarness.Response invalid = editor.post("/api/evaluate", "{\"facts\": {\"x\": SECRET}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).doesNotContain("SECRET").contains("line 1");

        assertThat(editor.send("POST", "/api/validate", "{}", Map.of("Content-Type", "text/plain")).status()).isEqualTo(415);
        assertThat(editor.get("/api/nothing").status()).isEqualTo(404);
        assertThat(editor.send("DELETE", "/api/tables/" + V3, null, Map.of()).status()).isEqualTo(405);
    }

    @Test
    void largeBodiesAreRefused() {
        // Announced too large by Content-Length: refused before reading it.
        editor.close();
        editor = EditorHarness.start(temp.resolve("small"), 1024);
        String big = "{\"id\": \"" + "x".repeat(2000) + "\"}";
        EditorHarness.Response announced = editor.post("/api/validate", big);
        assertThat(announced.status()).isEqualTo(413);
        assertThat(announced.json().get("error").get("message").asText()).isEqualTo("request body is larger than 1024 bytes");
        editor.close();
        editor = EditorHarness.start(temp.resolve("default"));

        // Streamed without a length: refused once the limit is passed.
        int size = EditorServer.DEFAULT_MAX_BODY_BYTES + 1;
        String chunked = Integer.toHexString(size) + "\r\n" + "x".repeat(size) + "\r\n0\r\n\r\n";
        EditorHarness.Response streamedFull = editor.rawWithBody("POST", "/api/validate", chunked, Map.of(
                "Host", editor.host(), "Content-Type", "application/json", "Transfer-Encoding", "chunked"));
        assertThat(streamedFull.status()).isEqualTo(413);
    }

    @Test
    void everyResponseCarriesSecurityHeaders() {
        for (EditorHarness.Response response : List.of(editor.get("/"), editor.get("/api/meta"), editor.get("/api/nothing"))) {
            assertThat(response.header("Content-Security-Policy")).contains("default-src 'self'").contains("frame-ancestors 'none'");
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.header("Referrer-Policy")).isEqualTo("no-referrer");
        }
        assertThat(editor.get("/api/meta").header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void servesTheUserInterface() {
        EditorHarness.Response index = editor.get("/");
        assertThat(index.status()).isEqualTo(200);
        assertThat(index.header("Content-Type")).startsWith("text/html");
        assertThat(index.body()).contains("RuleStone");
        assertThat(editor.get("/assets/nothing.js").status()).isEqualTo(404);
    }

    private static JsonNode find(JsonNode tables, String file) {
        for (JsonNode t : tables) {
            if (t.get("file").asText().equals(file)) {
                return t;
            }
        }
        throw new AssertionError("no " + file);
    }

    /** The rule version the engine itself reports for the file. */
    private static String engineRuleVersion(Path file) {
        var table = DecisionTableJson.read(file);
        String facts = "{\"applicant\": {\"age\": 10, \"monthlyIncome\": 1, \"monthlyDebt\": 0, \"segment\": \"R\","
                + " \"blacklisted\": false}, \"amount\": 1}";
        return RuleStone.builder().decision(table).build()
                .evaluate(DecisionRef.of(table.id()).version(table.version()).asOf(LocalDate.of(2026, 12, 1)),
                        FactsJson.readFacts(facts, table.inputs()))
                .ruleVersion();
    }
}
