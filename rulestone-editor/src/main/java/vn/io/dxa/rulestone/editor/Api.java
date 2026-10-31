//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FactsJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.InvalidFactsException;
import vn.io.dxa.rulestone.core.RuleStone;

/** The JSON API under {@code /api}. See docs/editor.md for the reference. */
final class Api {

    /** A JSON response. */
    record Response(int status, JsonNode body, Map<String, String> headers) {
        Response(int status, JsonNode body) {
            this(status, body, Map.of());
        }
    }

    private static final String TABLES = "/api/tables";
    private static final Pattern NAME_PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final RulesDirectory directory;
    private final TableValidator validator;
    private final String editorVersion;
    private final long maxBodyBytes;

    Api(RulesDirectory directory, long maxBodyBytes) {
        this.directory = directory;
        this.validator = new TableValidator(directory);
        this.editorVersion = Catalog.editorVersion();
        this.maxBodyBytes = maxBodyBytes;
    }

    Response handle(HttpRequest request) {
        String path = request.path();
        String method = request.method();
        switch (path) {
            case "/api/meta" -> {
                requireMethod(method, "GET");
                return new Response(200, Catalog.meta(editorVersion, maxBodyBytes));
            }
            case TABLES -> {
                requireMethod(method, "GET", "POST");
                return method.equals("GET") ? listTables() : createTable(request);
            }
            case "/api/validate" -> {
                requireMethod(method, "POST");
                return validate(request);
            }
            case "/api/evaluate" -> {
                requireMethod(method, "POST");
                return evaluate(request);
            }
            case "/api/format" -> {
                requireMethod(method, "POST");
                return format(request);
            }
            default -> {
                if (path.startsWith(TABLES + "/")) {
                    String file = path.substring(TABLES.length() + 1);
                    requireMethod(method, "GET", "PUT");
                    return method.equals("GET") ? getTable(file) : saveTable(file, request);
                }
                throw new ApiException(404, "not_found", "no such endpoint");
            }
        }
    }

    // ---- GET /api/tables ----------------------------------------------------------------------------------------

    private Response listTables() {
        List<String> files = directory.list();
        Map<String, DecisionTable> parsed = new LinkedHashMap<>();
        Map<String, ObjectNode> entries = new LinkedHashMap<>();
        for (String file : files) {
            ObjectNode entry = Json.object();
            entry.put("file", file);
            List<Problem> problems = new ArrayList<>();
            try {
                String text = directory.read(file);
                TableValidator.Result result = validator.validate(text, file, Map.of());
                JsonNode tree = result.tree();
                if (tree != null && tree.isObject()) {
                    for (String field : List.of("id", "version", "description", "effectiveFrom", "effectiveTo", "hitPolicy")) {
                        JsonNode value = tree.get(field);
                        if (value != null && value.isTextual()) {
                            entry.put(field, value.asText());
                        }
                    }
                    JsonNode rules = tree.get("rules");
                    entry.put("ruleCount", rules != null && rules.isArray() ? rules.size() : 0);
                }
                if (result.table() != null) {
                    parsed.put(file, result.table());
                }
                if (result.ruleVersion() != null) {
                    entry.put("ruleVersion", result.ruleVersion());
                }
                // Version conflicts are recomputed below from the already parsed tables.
                result.problems().stream().filter(p -> p.location().file() == null).forEach(problems::add);
            } catch (ApiException e) {
                problems.add(Problem.table(null, e.getMessage()));
            }
            entry.set("problems", problemsJson(problems));
            entries.put(file, entry);
        }
        parsed.forEach((file, table) -> {
            Map<String, DecisionTable> others = new LinkedHashMap<>(parsed);
            others.remove(file);
            ArrayNode problems = (ArrayNode) entries.get(file).get("problems");
            TableValidator.versionProblems(table, others).forEach(p -> problems.add(p.toJson()));
        });
        List<ObjectNode> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing((ObjectNode e) -> e.path("id").asText(e.get("file").asText()))
                .thenComparing(e -> e.path("effectiveFrom").asText(""))
                .thenComparing(e -> e.get("file").asText()));
        ObjectNode body = Json.object();
        body.put("directory", directory.root().getFileName() == null ? "" : directory.root().getFileName().toString());
        ArrayNode tables = body.putArray("tables");
        sorted.forEach(tables::add);
        return new Response(200, body);
    }

    // ---- GET/PUT /api/tables/{file} -----------------------------------------------------------------------------

    private Response getTable(String file) {
        String text = directory.read(file);
        TableValidator.Result result = validator.validate(text, file, Map.of());
        String etag = RulesDirectory.etag(text);
        ObjectNode body = Json.object();
        body.put("file", file);
        body.put("etag", etag);
        body.put("content", text);
        body.set("table", result.tree() != null && result.tree().isObject() ? result.tree() : null);
        body.put("valid", result.valid());
        body.set("problems", problemsJson(result.problems()));
        body.put("ruleVersion", result.ruleVersion());
        return new Response(200, body, Map.of("ETag", "\"" + etag + "\""));
    }

    private Response saveTable(String file, HttpRequest request) {
        directory.resolve(file);
        if (!directory.exists(file)) {
            throw new ApiException(404, "not_found", "no such file: " + file + " (create it with POST " + TABLES + ")");
        }
        String text = request.body();
        JsonNode tree = tableObject(Json.parse(text));
        String ifMatch = request.header("If-Match");
        String expected = ifMatch == null || ifMatch.isBlank() || ifMatch.strip().equals("*")
                ? null : ifMatch.strip().replace("\"", "");
        TableValidator.Result result = validator.validate(text, file, Map.of());
        if (!result.valid() && !request.flag("force")) {
            return refused(result.problems());
        }
        String etag = directory.write(file, TableFormatter.format(tree), expected, false);
        return new Response(200, saved(file, etag, result), Map.of("ETag", "\"" + etag + "\""));
    }

    // ---- POST /api/tables ---------------------------------------------------------------------------------------

    private Response createTable(HttpRequest request) {
        JsonNode body = Json.parse(request.body());
        if (!body.isObject() || !body.has("table")) {
            throw new ApiException(400, "bad_request", "expected {\"table\": {...}, \"file\": \"optional name\"}");
        }
        for (String field : (Iterable<String>) body::fieldNames) {
            if (!List.of("file", "table").contains(field)) {
                throw new ApiException(400, "bad_request", "unknown field '" + field + "' (allowed: file, table)");
            }
        }
        JsonNode tree = tableObject(body.get("table"));
        String file = body.hasNonNull("file") ? body.get("file").asText() : defaultFileName(tree);
        directory.resolve(file);
        if (directory.exists(file)) {
            throw new ApiException(409, "already_exists", "file already exists: " + file);
        }
        String text = Json.write(tree);

        // A new version never edits another file: it supersedes the previous version from its effectiveFrom.
        TableValidator.Result result = validator.validate(text, file, Map.of());
        List<Problem> problems = new ArrayList<>(result.problems());
        if (!problems.isEmpty() && !request.flag("force")) {
            return refused(problems);
        }
        String etag = directory.write(file, TableFormatter.format(tree), null, true);
        ObjectNode response = saved(file, etag, result);
        return new Response(201, response, Map.of("ETag", "\"" + etag + "\""));
    }

    private static String defaultFileName(JsonNode tree) {
        String id = tree.path("id").asText("");
        String version = tree.path("version").asText("");
        if (!NAME_PART.matcher(id).matches() || !NAME_PART.matcher(version).matches()) {
            throw new ApiException(400, "bad_request", "give a \"file\" name: id and version cannot form one");
        }
        return id + ".v" + version + RulesDirectory.SUFFIX;
    }

    // ---- POST /api/validate, /api/format ------------------------------------------------------------------------

    private Response validate(HttpRequest request) {
        String text = request.body();
        String self = request.query("file");
        if (self != null) {
            directory.resolve(self);
        }
        TableValidator.Result result = validator.validate(text, self, Map.of());
        ObjectNode body = Json.object();
        body.put("valid", result.valid());
        body.set("problems", problemsJson(result.problems()));
        body.put("ruleVersion", result.ruleVersion());
        return new Response(200, body);
    }

    private Response format(HttpRequest request) {
        JsonNode tree = tableObject(Json.parse(request.body()));
        ObjectNode body = Json.object();
        body.put("content", TableFormatter.format(tree));
        return new Response(200, body);
    }

    // ---- POST /api/evaluate -------------------------------------------------------------------------------------

    private Response evaluate(HttpRequest request) {
        JsonNode body = Json.parse(request.body());
        if (!body.isObject() || !body.has("table")) {
            throw new ApiException(400, "bad_request", "expected {\"table\": {...}, \"facts\": {...}, \"asOf\": \"yyyy-MM-dd\"}");
        }
        for (String field : (Iterable<String>) body::fieldNames) {
            if (!List.of("table", "facts", "models", "asOf").contains(field)) {
                throw new ApiException(400, "bad_request", "unknown field '" + field
                        + "' (allowed: asOf, facts, models, table)");
            }
        }
        LocalDate asOf = null;
        JsonNode asOfNode = body.get("asOf");
        if (asOfNode != null && !asOfNode.isNull() && !asOfNode.asText().isEmpty()) {
            try {
                asOf = LocalDate.parse(asOfNode.asText());
            } catch (DateTimeParseException e) {
                throw new ApiException(400, "bad_request", "asOf: expected an ISO date (yyyy-MM-dd)");
            }
        }
        String text = Json.write(tableObject(body.get("table")));
        DecisionTable table;
        try {
            table = DecisionTableJson.read(text);
        } catch (InvalidDecisionException e) {
            return invalidTable(new ProblemParser(body.get("table")).parseAll(e.problems()));
        }
        RuleStoneEngine engine;
        try {
            engine = RuleStone.builder().decision(table).build();
        } catch (InvalidDecisionException e) {
            return invalidTable(new ProblemParser(body.get("table")).parseAll(e.problems()));
        }
        JsonNode factsJson = body.get("facts");
        Facts facts;
        try {
            facts = FactsJson.readFacts(factsJson == null || factsJson.isNull() ? Json.object() : factsJson,
                    table.inputs(), body.get("models"));
        } catch (InvalidFactsException e) {
            // FactsJson messages name locations and expected forms, never values.
            ObjectNode error = Json.object();
            ObjectNode detail = error.putObject("error");
            detail.put("code", "invalid_facts");
            detail.put("message", String.join("; ", e.problems()));
            ArrayNode list = error.putArray("factProblems");
            e.problems().forEach(list::add);
            return new Response(422, error);
        }
        DecisionRef ref = DecisionRef.of(table.id()).version(table.version());
        if (asOf != null) {
            ref = ref.asOf(asOf);
        }
        DecisionResult result;
        try {
            result = engine.evaluate(ref, facts);
        } catch (DecisionEvaluationException e) {
            throw new ApiException(422, "evaluation_failed", e.getMessage());
        } catch (DecisionNotFoundException e) {
            throw new ApiException(422, "not_found", e.getMessage());
        }
        ObjectNode response = Json.object();
        response.put("decisionId", result.decisionId());
        response.put("ruleVersion", result.ruleVersion());
        if (asOf != null) {
            response.put("asOf", asOf.toString());
            response.put("effectiveOnAsOf", table.isEffectiveOn(asOf));
        }
        response.put("matched", result.matched());
        response.set("output", FactsJson.writeValues(result.output()));
        ArrayNode reasons = response.putArray("reasonCodes");
        result.reasonCodes().forEach(reasons::add);
        ArrayNode matched = response.putArray("matchedRules");
        result.matchedRules().forEach(matched::add);
        ArrayNode trace = response.putArray("trace");
        for (TraceStep step : result.trace()) {
            trace.addObject().put("ruleId", step.ruleId()).put("matched", step.matched());
        }
        return new Response(200, response);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static JsonNode tableObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ApiException(400, "bad_request", "the table must be a JSON object");
        }
        return node;
    }

    private static Response refused(List<Problem> problems) {
        ObjectNode body = Json.object();
        body.put("saved", false);
        body.put("valid", false);
        body.set("problems", problemsJson(problems));
        ObjectNode error = body.putObject("error");
        error.put("code", "invalid_table");
        error.put("message", "the table has " + problems.size() + (problems.size() == 1 ? " problem" : " problems")
                + "; fix them, or save anyway with ?force=true");
        return new Response(422, body);
    }

    private static Response invalidTable(List<Problem> problems) {
        ObjectNode body = Json.object();
        body.put("valid", false);
        body.set("problems", problemsJson(problems));
        ObjectNode error = body.putObject("error");
        error.put("code", "invalid_table");
        error.put("message", "the table has problems; fix them before evaluating");
        return new Response(422, body);
    }

    private static ObjectNode saved(String file, String etag, TableValidator.Result result) {
        ObjectNode body = Json.object();
        body.put("saved", true);
        body.put("file", file);
        body.put("etag", etag);
        body.put("valid", result.valid());
        body.set("problems", problemsJson(result.problems()));
        body.put("ruleVersion", result.ruleVersion());
        return body;
    }

    static ArrayNode problemsJson(List<Problem> problems) {
        ArrayNode array = Json.array();
        problems.forEach(p -> array.add(p.toJson()));
        return array;
    }

    private static void requireMethod(String method, String... allowed) {
        for (String a : allowed) {
            if (a.equals(method)) {
                return;
            }
        }
        throw new ApiException(405, "method_not_allowed", "use " + String.join(" or ", allowed));
    }
}
