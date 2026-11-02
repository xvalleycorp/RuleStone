//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Routing;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.api.ModelStatus;

/**
 * Writes a {@link DecisionRecord} as JSON, for audit sinks that log or publish records, and reads it
 * back, to replay the decision.
 *
 * <pre>{@code
 * RuleStone.builder()
 *     .auditSink(record -> auditLog.info(DecisionRecordJson.write(record)))
 * }</pre>
 *
 * <p>Fields are written in a fixed order. Absent optional fields are left out, also in the
 * steps of the path: a {@code switch} step, for example, has {@code taken} but no
 * {@code version} or {@code matchedRules}, and a {@code model} step has the model's id and
 * version, mode, status and, when it succeeded, its outputs and ranked attributions. Output
 * values use the {@link FactsJson} forms, e.g. {@code "25000 VND"} for money. {@code params}, which
 * lists the parameter set versions used, is left out when there are none.
 *
 * <p>{@link #read} is strict: it accepts what {@code write} writes, and reports unknown and duplicate
 * fields and every other problem together. A record carries no types, so values read back are the
 * Java forms of their JSON: a {@code String} for a decimal, money or date such as {@code "0.0980"},
 * a {@code Long} for an integer, a {@code Boolean}, a {@code List} or a {@code Map}. Writing them
 * again gives the same JSON, and {@code RuleStoneEngine.replay} compares outputs in these forms.
 */
public final class DecisionRecordJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonMapper READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final Set<String> RECORD_FIELDS = Set.of("recordId", "correlationId", "decision", "version",
            "params", "businessDate", "evaluatedAt", "engine", "status", "factsHash", "output", "reasonCodes", "path",
            "error", "routing");
    private static final Set<String> STEP_FIELDS = Set.of("node", "type", "decision", "version", "matchedRules",
            "taken", "mode", "model", "status", "detail", "latencyMs", "outputs", "confidence", "attributionMethod",
            "attributions");
    private static final Set<String> MODEL_FIELDS = Set.of("mode", "model", "status", "detail", "latencyMs",
            "outputs", "confidence", "attributionMethod", "attributions");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DecisionRecordJson() {}

    /** The record as a JSON object. */
    public static ObjectNode toJson(DecisionRecord record) {
        ObjectNode json = NODES.objectNode();
        json.put("recordId", record.recordId());
        record.correlationId().ifPresent(id -> json.put("correlationId", id));
        json.put("decision", record.decision());
        record.version().ifPresent(version -> json.put("version", version));
        // Left out when no parameter set was used, as in records written before parameter sets existed.
        if (!record.params().isEmpty()) {
            ArrayNode params = json.putArray("params");
            record.params().forEach(p -> params.addObject().put("set", p.set()).put("version", p.version()));
        }
        json.put("businessDate", record.businessDate().toString());
        json.put("evaluatedAt", record.evaluatedAt().toString());
        json.put("engine", record.engine());
        json.put("status", record.status().name());
        record.factsHash().ifPresent(hash -> json.put("factsHash", hash));
        json.set("output", FactsJson.writeValues(record.output()));
        ArrayNode reasons = json.putArray("reasonCodes");
        record.reasonCodes().forEach(r -> reasons.addObject().put("code", r.code()).put("source", r.source()));
        ArrayNode path = json.putArray("path");
        record.path().forEach(step -> {
            ObjectNode node = path.addObject()
                    .put("node", step.node())
                    .put("type", step.type());
            step.decision().ifPresent(decision -> node.put("decision", decision));
            step.version().ifPresent(version -> node.put("version", version));
            // A table step always lists its matched rules, even none; other steps have none to list.
            if (step.type().equals("table") || !step.matchedRules().isEmpty()) {
                ArrayNode matched = node.putArray("matchedRules");
                step.matchedRules().forEach(matched::add);
            }
            step.taken().ifPresent(taken -> node.put("taken", taken));
            step.model().ifPresent(model -> model(node, model));
        });
        record.error().ifPresent(error -> json.put("error", error));
        record.routing().ifPresent(routing -> {
            ObjectNode node = json.putObject("routing")
                    .put("kind", routing.kind().name())
                    .put("champion", routing.champion());
            routing.share().ifPresent(share -> node.put("share", share));
            routing.shadowOf().ifPresent(id -> node.put("shadowOf", id));
        });
        return json;
    }

    /** The fields of a model step; the outputs, confidence and attributions only when the model succeeded. */
    private static void model(ObjectNode node, ModelStep model) {
        node.put("mode", model.mode().jsonName());
        node.putObject("model").put("id", model.modelId()).put("version", model.modelVersion());
        node.put("status", model.status().name());
        model.detail().ifPresent(detail -> node.put("detail", detail));
        model.latencyMs().ifPresent(latency -> node.put("latencyMs", latency));
        if (model.status().failed()) {
            return;
        }
        node.set("outputs", FactsJson.writeValues(model.outputs()));
        model.confidence().ifPresent(confidence -> node.put("confidence", confidence.toPlainString()));
        model.attributionMethod().ifPresent(method -> node.put("attributionMethod", method));
        ArrayNode attributions = node.putArray("attributions");
        model.attributions().forEach(a -> {
            ObjectNode attribution = attributions.addObject()
                    .put("feature", a.feature())
                    .put("rank", a.rank())
                    .put("direction", a.direction().name());
            a.reasonCode().ifPresent(code -> attribution.put("reasonCode", code));
        });
    }

    /**
     * Reads a record that {@link #write} wrote.
     *
     * @throws InvalidRecordException listing every problem found
     */
    public static DecisionRecord read(String json) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = READER.readTree(json);
        } catch (JsonProcessingException e) {
            // The parser's message may quote the text, which holds output values: give the location only.
            String location = e.getLocation() == null ? ""
                    : " at line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw new InvalidRecordException(List.of("$: invalid JSON" + location));
        }
        return read(root);
    }

    /** Reads a record from UTF-8 JSON text; see {@link #read(String)}. */
    public static DecisionRecord read(InputStream in) {
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a record from a UTF-8 file; see {@link #read(String)}. */
    public static DecisionRecord read(Path file) {
        try {
            return read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a record from a JSON object, e.g. one of {@link #toJson}. Numbers with a fraction must
     * have been parsed as {@code BigDecimal}.
     *
     * @throws InvalidRecordException listing every problem found
     */
    public static DecisionRecord read(JsonNode json) {
        return new Reader().record(json);
    }

    /** The record as compact JSON text, one line. */
    public static String write(DecisionRecord record) {
        try {
            return MAPPER.writeValueAsString(toJson(record));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }

    /** Reads one record, collecting every problem. */
    private static final class Reader {

        private final DecisionTableJson.Reader text = new DecisionTableJson.Reader();

        DecisionRecord record(JsonNode root) {
            if (root == null || !root.isObject()) {
                throw new InvalidRecordException(List.of("$: expected a JSON object"));
            }
            text.unknownFields(root, RECORD_FIELDS, "$");
            String recordId = text.requiredText(root, "recordId", "$");
            Optional<String> correlationId = text.optionalText(root, "correlationId", "$");
            String decision = text.requiredText(root, "decision", "$");
            Optional<String> version = text.optionalText(root, "version", "$");
            List<ParamSetVersion> params = params(root.get("params"));
            LocalDate businessDate = date(root, "businessDate", "$");
            Instant evaluatedAt = instant(root, "evaluatedAt", "$");
            String engine = text.requiredText(root, "engine", "$");
            Status status = enumValue(root, "status", "$", Status.class);
            Optional<String> factsHash = text.optionalText(root, "factsHash", "$");
            Map<String, Object> output = values(root.get("output"), "$.output", true);
            List<ReasonCode> reasonCodes = reasonCodes(root.get("reasonCodes"));
            List<PathStep> path = path(root.get("path"));
            Optional<String> error = text.optionalText(root, "error", "$");
            Optional<Routing> routing = routing(root.get("routing"));
            if (!text.problems.isEmpty()) {
                throw new InvalidRecordException(text.problems);
            }
            try {
                return new DecisionRecord(recordId, correlationId, decision, version, businessDate, evaluatedAt, engine,
                        status, factsHash, output, reasonCodes, path, error, routing, params);
            } catch (IllegalArgumentException e) {
                throw new InvalidRecordException(List.of("$: " + e.getMessage()));
            }
        }

        private List<ParamSetVersion> params(JsonNode node) {
            List<ParamSetVersion> params = new ArrayList<>();
            if (node == null) {
                return params;
            }
            if (!node.isArray()) {
                text.problem("$.params", "expected an array of { \"set\", \"version\" }");
                return params;
            }
            for (int i = 0; i < node.size(); i++) {
                String at = "$.params[" + i + "]";
                JsonNode item = node.get(i);
                if (!item.isObject()) {
                    text.problem(at, "expected an object");
                    continue;
                }
                text.unknownFields(item, Set.of("set", "version"), at);
                params.add(new ParamSetVersion(text.requiredText(item, "set", at), text.requiredText(item, "version", at)));
            }
            return params;
        }

        private List<ReasonCode> reasonCodes(JsonNode node) {
            List<ReasonCode> codes = new ArrayList<>();
            if (node == null || !node.isArray()) {
                text.problem("$.reasonCodes", "required array of { \"code\", \"source\" }");
                return codes;
            }
            for (int i = 0; i < node.size(); i++) {
                String at = "$.reasonCodes[" + i + "]";
                JsonNode item = node.get(i);
                if (!item.isObject()) {
                    text.problem(at, "expected an object");
                    continue;
                }
                text.unknownFields(item, Set.of("code", "source"), at);
                codes.add(new ReasonCode(text.requiredText(item, "code", at), text.requiredText(item, "source", at)));
            }
            return codes;
        }

        private List<PathStep> path(JsonNode node) {
            List<PathStep> path = new ArrayList<>();
            if (node == null || !node.isArray()) {
                text.problem("$.path", "required array of steps");
                return path;
            }
            for (int i = 0; i < node.size(); i++) {
                String at = "$.path[" + i + "]";
                JsonNode item = node.get(i);
                if (!item.isObject()) {
                    text.problem(at, "expected an object");
                    continue;
                }
                PathStep step = step(item, at);
                if (step != null) {
                    path.add(step);
                }
            }
            return path;
        }

        private PathStep step(JsonNode node, String at) {
            text.unknownFields(node, STEP_FIELDS, at);
            String id = text.requiredText(node, "node", at);
            String type = text.requiredText(node, "type", at);
            Optional<String> decision = text.optionalText(node, "decision", at);
            Optional<String> version = text.optionalText(node, "version", at);
            List<String> matched = new ArrayList<>();
            JsonNode rules = node.get("matchedRules");
            if (rules != null) {
                if (!rules.isArray()) {
                    text.problem(at + ".matchedRules", "expected an array of rule ids");
                } else {
                    for (int i = 0; i < rules.size(); i++) {
                        if (rules.get(i).isTextual()) {
                            matched.add(rules.get(i).asText());
                        } else {
                            text.problem(at + ".matchedRules[" + i + "]", "expected a rule id");
                        }
                    }
                }
            }
            Optional<String> taken = text.optionalText(node, "taken", at);
            Optional<ModelStep> model = Optional.empty();
            if (type.equals("model")) {
                model = Optional.ofNullable(model(node, at));
            } else {
                MODEL_FIELDS.forEach(field -> {
                    if (node.has(field)) {
                        text.problem(at + "." + field, "only a model step has this field");
                    }
                });
            }
            return new PathStep(id, type, decision, version, matched, taken, model);
        }

        private ModelStep model(JsonNode node, String at) {
            ModelMode mode = null;
            Optional<String> modeText = text.optionalText(node, "mode", at);
            if (modeText.isEmpty()) {
                text.problem(at + ".mode", "required: invoke or supplied");
            } else {
                mode = Arrays.stream(ModelMode.values()).filter(m -> m.jsonName().equals(modeText.get())).findFirst()
                        .orElse(null);
                if (mode == null) {
                    text.problem(at + ".mode", "expected invoke or supplied");
                }
            }
            JsonNode pinned = node.get("model");
            String modelId = "";
            String modelVersion = "";
            if (pinned == null || !pinned.isObject()) {
                text.problem(at + ".model", "required object of { \"id\", \"version\" }");
            } else {
                text.unknownFields(pinned, Set.of("id", "version"), at + ".model");
                modelId = text.requiredText(pinned, "id", at + ".model");
                modelVersion = text.requiredText(pinned, "version", at + ".model");
            }
            ModelStatus status = enumValue(node, "status", at, ModelStatus.class);
            Optional<String> detail = text.optionalText(node, "detail", at);
            OptionalLong latency = OptionalLong.empty();
            JsonNode latencyNode = node.get("latencyMs");
            if (latencyNode != null) {
                if (latencyNode.isIntegralNumber() && latencyNode.canConvertToLong() && latencyNode.asLong() >= 0) {
                    latency = OptionalLong.of(latencyNode.asLong());
                } else {
                    text.problem(at + ".latencyMs", "expected a whole number of milliseconds");
                }
            }
            Map<String, Object> outputs = node.has("outputs") ? values(node.get("outputs"), at + ".outputs", true)
                    : Map.of();
            Optional<BigDecimal> confidence = Optional.empty();
            Optional<String> confidenceText = text.optionalText(node, "confidence", at);
            if (confidenceText.isPresent()) {
                try {
                    confidence = Optional.of(new BigDecimal(confidenceText.get()));
                } catch (NumberFormatException e) {
                    text.problem(at + ".confidence", "expected a decimal as a string");
                }
            }
            Optional<String> method = text.optionalText(node, "attributionMethod", at);
            List<RankedAttribution> attributions = attributions(node.get("attributions"), at + ".attributions");
            if (mode == null || status == null) {
                return null;
            }
            try {
                return new ModelStep(modelId, modelVersion, mode, status, detail, latency, outputs, confidence, method,
                        attributions);
            } catch (IllegalArgumentException e) {
                text.problem(at, e.getMessage());
                return null;
            }
        }

        private List<RankedAttribution> attributions(JsonNode node, String path) {
            List<RankedAttribution> attributions = new ArrayList<>();
            if (node == null) {
                return attributions;
            }
            if (!node.isArray()) {
                text.problem(path, "expected an array of attributions");
                return attributions;
            }
            for (int i = 0; i < node.size(); i++) {
                String at = path + "[" + i + "]";
                JsonNode item = node.get(i);
                if (!item.isObject()) {
                    text.problem(at, "expected an object");
                    continue;
                }
                text.unknownFields(item, Set.of("feature", "rank", "direction", "reasonCode"), at);
                String feature = text.requiredText(item, "feature", at);
                JsonNode rank = item.get("rank");
                if (rank == null || !rank.isIntegralNumber() || !rank.canConvertToInt() || rank.asInt() < 1) {
                    text.problem(at + ".rank", "required whole number, 1 or more");
                    continue;
                }
                RankedAttribution.Direction direction = enumValue(item, "direction", at, RankedAttribution.Direction.class);
                Optional<String> code = text.optionalText(item, "reasonCode", at);
                if (direction != null) {
                    attributions.add(new RankedAttribution(feature, rank.asInt(), direction, code));
                }
            }
            return attributions;
        }

        private Optional<Routing> routing(JsonNode node) {
            if (node == null) {
                return Optional.empty();
            }
            String at = "$.routing";
            if (!node.isObject()) {
                text.problem(at, "expected an object");
                return Optional.empty();
            }
            text.unknownFields(node, Set.of("kind", "champion", "share", "shadowOf"), at);
            Routing.Kind kind = enumValue(node, "kind", at, Routing.Kind.class);
            String champion = text.requiredText(node, "champion", at);
            OptionalDouble share = OptionalDouble.empty();
            JsonNode shareNode = node.get("share");
            if (shareNode != null) {
                if (shareNode.isNumber()) {
                    share = OptionalDouble.of(shareNode.asDouble());
                } else {
                    text.problem(at + ".share", "expected a number");
                }
            }
            Optional<String> shadowOf = text.optionalText(node, "shadowOf", at);
            if (kind == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Routing(kind, champion, share, shadowOf));
            } catch (IllegalArgumentException e) {
                text.problem(at, e.getMessage());
                return Optional.empty();
            }
        }

        /** An object of named values, in the Java forms of their JSON. */
        private Map<String, Object> values(JsonNode node, String path, boolean required) {
            Map<String, Object> values = new LinkedHashMap<>();
            if (node == null || !node.isObject()) {
                if (required || node != null) {
                    text.problem(path, "required object of named values");
                }
                return values;
            }
            node.properties().forEach(e -> {
                Object value = value(e.getValue(), path + "." + e.getKey());
                if (value != null) {
                    values.put(e.getKey(), value);
                }
            });
            return values;
        }

        private Object value(JsonNode node, String path) {
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isIntegralNumber()) {
                return node.canConvertToLong() ? (Object) node.asLong() : node.bigIntegerValue();
            }
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isArray()) {
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < node.size(); i++) {
                    Object item = value(node.get(i), path + "[" + i + "]");
                    if (item != null) {
                        items.add(item);
                    }
                }
                return items;
            }
            if (node.isObject()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                node.properties().forEach(e -> {
                    Object field = value(e.getValue(), path + "." + e.getKey());
                    if (field != null) {
                        fields.put(e.getKey(), field);
                    }
                });
                return fields;
            }
            text.problem(path, "expected a value; null is not one");
            return null;
        }

        private LocalDate date(JsonNode node, String field, String path) {
            String value = text.requiredText(node, field, path);
            if (value.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                text.problem(path + "." + field, "expected a date as \"yyyy-MM-dd\"");
                return null;
            }
        }

        private Instant instant(JsonNode node, String field, String path) {
            String value = text.requiredText(node, field, path);
            if (value.isEmpty()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                text.problem(path + "." + field, "expected an instant such as \"2026-11-01T02:15:04.218Z\"");
                return null;
            }
        }

        private <E extends Enum<E>> E enumValue(JsonNode node, String field, String path, Class<E> type) {
            String value = text.requiredText(node, field, path);
            if (value.isEmpty()) {
                return null;
            }
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value)) {
                    return constant;
                }
            }
            text.problem(path + "." + field, "expected one of " + String.join(", ",
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
            return null;
        }
    }
}
