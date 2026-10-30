//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.FlowNode.Branch;
import vn.io.dxa.rulestone.core.FlowNode.Bucket;

/**
 * Reads and writes decision flows in the {@code .flow.json} format.
 *
 * <pre>{@code
 * {
 *   "id": "card_origination",
 *   "version": "1",
 *   "effectiveFrom": "2026-11-01",
 *   "inputs":  { "applicant": { "id": "string", "age": "int", "income": "money" } },
 *   "outputs": { "decision": "string" },
 *   "start": "policy",
 *   "nodes": {
 *     "policy":   { "type": "table", "decision": "card_policy",
 *                   "next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "approved" } ] },
 *     "approved": { "type": "end", "set": { "decision": "'APPROVE'" } },
 *     "rejected": { "type": "end", "set": { "decision": "'REJECT'" } }
 *   }
 * }
 * }</pre>
 *
 * <p>The header ({@code id}, {@code version}, {@code description}, {@code effectiveFrom},
 * {@code effectiveTo}, {@code inputs}, {@code outputs}) is the same as for a decision table.
 * Node types are {@code table}, {@code switch}, {@code derive}, {@code model}, {@code split} and
 * {@code end}. The type {@code flow} is reserved for sub-flows, which are not supported yet: a file
 * that uses it is rejected.
 *
 * <p>Reading is strict, as for decision tables: unknown or duplicate keys are errors, and every
 * problem is reported at once with its JSON path.
 */
public final class DecisionFlowJson {

    /** File name suffix of decision flows. */
    public static final String FILE_SUFFIX = ".flow.json";

    private static final Set<String> FLOW_FIELDS = Set.of(
            "id", "version", "description", "effectiveFrom", "effectiveTo", "inputs", "outputs", "start", "nodes");
    private static final Set<String> TABLE_FIELDS = Set.of("type", "description", "decision", "version", "facts", "as", "next");
    private static final Set<String> SWITCH_FIELDS = Set.of("type", "description", "cases");
    private static final Set<String> DERIVE_FIELDS = Set.of("type", "description", "outputs", "set", "as", "next");
    private static final Set<String> MODEL_FIELDS = Set.of("type", "description", "mode", "model", "features",
            "outputs", "valid", "minConfidence", "timeoutMs", "reasonCodes", "maxReasonCodes", "onFailure", "as", "next");
    /** Model node fields of the design that later steps add; reported as not supported yet. */
    private static final Set<String> MODEL_FIELDS_PLANNED = Set.of("kind", "shadow", "prompt");
    private static final Set<String> MODEL_REF_FIELDS = Set.of("id", "version");
    private static final Set<String> ON_FAILURE_FIELDS = Set.of("to", "reasonCode");
    private static final Set<String> SPLIT_FIELDS = Set.of("type", "description", "key", "salt", "buckets");
    private static final Set<String> END_FIELDS = Set.of("type", "description", "set");
    private static final Set<String> BRANCH_FIELDS = Set.of("when", "to");
    private static final Set<String> BUCKET_FIELDS = Set.of("name", "percent", "to");
    private static final String NODE_TYPES = "table, switch, derive, model, split or end";

    private DecisionFlowJson() {}

    /** @throws InvalidDecisionException listing every structural problem found */
    public static DecisionFlow read(String json) {
        return new FlowReader().flow(DecisionTableJson.parse(json));
    }

    public static DecisionFlow read(InputStream in) {
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DecisionFlow read(Path file) {
        try {
            return read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the canonical form: compact, keys sorted, absent optionals omitted, and a single
     * unconditional {@code next} written as the node id. Two flows with the same content always
     * produce the same text, which is what the content hash in a flow version is computed from.
     */
    public static String write(DecisionFlow flow) {
        Map<String, Object> json = new TreeMap<>();
        json.put("id", flow.id());
        json.put("version", flow.version());
        flow.description().ifPresent(d -> json.put("description", d));
        flow.effectiveFrom().ifPresent(d -> json.put("effectiveFrom", d.toString()));
        flow.effectiveTo().ifPresent(d -> json.put("effectiveTo", d.toString()));
        json.put("inputs", DecisionTableJson.typesJson(flow.inputs()));
        json.put("outputs", DecisionTableJson.typesJson(flow.outputs()));
        json.put("start", flow.start());
        Map<String, Object> nodes = new TreeMap<>();
        flow.nodes().forEach((id, node) -> nodes.put(id, nodeJson(node)));
        json.put("nodes", nodes);
        return DecisionTableJson.writeCanonical(json);
    }

    private static Map<String, Object> nodeJson(FlowNode node) {
        Map<String, Object> json = new TreeMap<>();
        json.put("type", node.type());
        node.description().ifPresent(d -> json.put("description", d));
        if (node instanceof FlowNode.Table table) {
            json.put("decision", table.decision());
            table.version().ifPresent(v -> json.put("version", v));
            if (!table.facts().isEmpty()) {
                json.put("facts", new TreeMap<>(table.facts()));
            }
            table.as().ifPresent(as -> json.put("as", as));
            json.put("next", nextJson(table.next()));
        } else if (node instanceof FlowNode.Switch sw) {
            json.put("cases", sw.cases().stream().map(DecisionFlowJson::branchJson).toList());
        } else if (node instanceof FlowNode.Derive derive) {
            json.put("outputs", DecisionTableJson.typesJson(derive.outputs()));
            json.put("set", new TreeMap<>(derive.set()));
            derive.as().ifPresent(as -> json.put("as", as));
            json.put("next", nextJson(derive.next()));
        } else if (node instanceof FlowNode.Model model) {
            json.put("mode", model.mode().jsonName());
            Map<String, Object> ref = new TreeMap<>();
            ref.put("id", model.modelId());
            ref.put("version", model.modelVersion());
            json.put("model", ref);
            if (!model.features().isEmpty()) {
                json.put("features", new TreeMap<>(model.features()));
            }
            json.put("outputs", DecisionTableJson.typesJson(model.outputs()));
            model.valid().ifPresent(valid -> json.put("valid", valid));
            model.minConfidence().ifPresent(min -> json.put("minConfidence", min.toPlainString()));
            model.timeoutMs().ifPresent(timeout -> json.put("timeoutMs", timeout));
            if (!model.reasonCodes().isEmpty()) {
                json.put("reasonCodes", new TreeMap<>(model.reasonCodes()));
            }
            model.maxReasonCodes().ifPresent(max -> json.put("maxReasonCodes", max));
            Map<String, Object> onFailure = new TreeMap<>();
            onFailure.put("to", model.onFailure().to());
            onFailure.put("reasonCode", model.onFailure().reasonCode());
            json.put("onFailure", onFailure);
            model.as().ifPresent(as -> json.put("as", as));
            json.put("next", nextJson(model.next()));
        } else if (node instanceof FlowNode.Split split) {
            json.put("key", split.key());
            json.put("salt", split.salt());
            List<Object> buckets = new ArrayList<>();
            for (Bucket bucket : split.buckets()) {
                Map<String, Object> b = new TreeMap<>();
                b.put("name", bucket.name());
                b.put("percent", bucket.percent());
                b.put("to", bucket.to());
                buckets.add(b);
            }
            json.put("buckets", buckets);
        } else if (node instanceof FlowNode.End end) {
            json.put("set", new TreeMap<>(end.set()));
        }
        return json;
    }

    private static Object nextJson(List<Branch> next) {
        if (next.size() == 1 && next.get(0).when().isEmpty()) {
            return next.get(0).to();
        }
        return next.stream().map(DecisionFlowJson::branchJson).toList();
    }

    private static Map<String, Object> branchJson(Branch branch) {
        Map<String, Object> json = new TreeMap<>();
        branch.when().ifPresent(when -> json.put("when", when));
        json.put("to", branch.to());
        return json;
    }

    /** Collects every problem instead of stopping at the first. */
    private static final class FlowReader {

        private final DecisionTableJson.Reader reader = new DecisionTableJson.Reader();

        DecisionFlow flow(JsonNode root) {
            if (root == null || !root.isObject()) {
                throw new InvalidDecisionException(List.of("$: expected a JSON object"));
            }
            reader.unknownFields(root, FLOW_FIELDS, "$");
            String id = reader.requiredText(root, "id", "$");
            String version = reader.requiredText(root, "version", "$");
            Optional<String> description = reader.optionalText(root, "description", "$");
            Optional<LocalDate> from = reader.optionalDate(root, "effectiveFrom");
            Optional<LocalDate> to = reader.optionalDate(root, "effectiveTo");
            if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
                reader.problem("$.effectiveTo", "must be after effectiveFrom");
            }
            Map<String, ValueType> inputs = reader.typeMap(root, "inputs");
            Map<String, ValueType> outputs = reader.typeMap(root, "outputs");
            String start = reader.requiredText(root, "start", "$");
            Map<String, FlowNode> nodes = new LinkedHashMap<>();
            JsonNode nodesNode = root.get("nodes");
            if (nodesNode == null || !nodesNode.isObject() || nodesNode.isEmpty()) {
                reader.problem("$.nodes", "required non-empty object of node id to node");
            } else {
                nodesNode.properties().forEach(e -> {
                    FlowNode node = node(e.getValue(), "$.nodes." + e.getKey());
                    if (node != null) {
                        nodes.put(e.getKey(), node);
                    }
                });
            }
            if (!reader.problems.isEmpty()) {
                throw new InvalidDecisionException(reader.problems);
            }
            return new DecisionFlow(id, version, description, from, to, inputs, outputs, start, nodes);
        }

        private FlowNode node(JsonNode node, String path) {
            if (!node.isObject()) {
                reader.problem(path, "expected a node object with a \"type\"");
                return null;
            }
            JsonNode typeNode = node.get("type");
            if (typeNode == null || !typeNode.isTextual()) {
                reader.problem(path + ".type", "required: " + NODE_TYPES);
                return null;
            }
            String type = typeNode.asText();
            Optional<String> description = reader.optionalText(node, "description", path);
            switch (type) {
                case "table" -> {
                    reader.unknownFields(node, TABLE_FIELDS, path);
                    String decision = reader.requiredText(node, "decision", path);
                    Optional<String> version = reader.optionalText(node, "version", path);
                    Map<String, String> facts = expressions(node, "facts", path, false);
                    Optional<String> as = reader.optionalText(node, "as", path);
                    List<Branch> next = next(node, path);
                    return new FlowNode.Table(description, decision, version, facts, as, next);
                }
                case "switch" -> {
                    reader.unknownFields(node, SWITCH_FIELDS, path);
                    return new FlowNode.Switch(description, branches(node.get("cases"), path + ".cases"));
                }
                case "derive" -> {
                    reader.unknownFields(node, DERIVE_FIELDS, path);
                    Map<String, ValueType> outputs = reader.typeMap(node, "outputs", path);
                    Map<String, String> set = expressions(node, "set", path, true);
                    Optional<String> as = reader.optionalText(node, "as", path);
                    List<Branch> next = next(node, path);
                    return new FlowNode.Derive(description, outputs, set, as, next);
                }
                case "split" -> {
                    reader.unknownFields(node, SPLIT_FIELDS, path);
                    String key = reader.requiredText(node, "key", path);
                    String salt = reader.requiredText(node, "salt", path);
                    return new FlowNode.Split(description, key, salt, buckets(node.get("buckets"), path + ".buckets"));
                }
                case "end" -> {
                    reader.unknownFields(node, END_FIELDS, path);
                    return new FlowNode.End(description, expressions(node, "set", path, true));
                }
                case "model" -> {
                    return model(node, description, path);
                }
                case "flow" -> {
                    reader.problem(path + ".type", "flow nodes (calling another flow) are not supported yet");
                    return null;
                }
                default -> {
                    reader.problem(path + ".type", "unknown node type '" + type + "' (expected " + NODE_TYPES + ")");
                    return null;
                }
            }
        }

        private FlowNode.Model model(JsonNode node, Optional<String> description, String path) {
            Set<String> allowed = new HashSet<>(MODEL_FIELDS);
            allowed.addAll(MODEL_FIELDS_PLANNED);
            reader.unknownFields(node, allowed, path);
            MODEL_FIELDS_PLANNED.stream().sorted().forEach(field -> {
                if (node.has(field)) {
                    reader.problem(path + "." + field, "not supported yet: shadow models and LLM nodes are "
                            + "planned for a later release (see Hybrid AI decisioning)");
                }
            });
            String modeText = reader.requiredText(node, "mode", path);
            ModelMode mode = switch (modeText) {
                case "invoke" -> ModelMode.INVOKE;
                case "supplied" -> ModelMode.SUPPLIED;
                default -> {
                    if (!modeText.isEmpty()) {
                        reader.problem(path + ".mode", "expected \"invoke\" or \"supplied\"");
                    }
                    yield ModelMode.INVOKE;
                }
            };
            String modelId = "";
            String modelVersion = "";
            JsonNode model = node.get("model");
            if (model == null || !model.isObject()) {
                reader.problem(path + ".model", "required object { \"id\", \"version\" }");
            } else {
                reader.unknownFields(model, MODEL_REF_FIELDS, path + ".model");
                modelId = reader.requiredText(model, "id", path + ".model");
                modelVersion = reader.requiredText(model, "version", path + ".model");
            }
            Map<String, String> features = expressions(node, "features", path, false);
            Map<String, ValueType> outputs = reader.typeMap(node, "outputs", path);
            Optional<String> valid = reader.optionalText(node, "valid", path);
            Optional<BigDecimal> minConfidence = Optional.empty();
            JsonNode min = node.get("minConfidence");
            if (min != null) {
                if (min.isIntegralNumber() || min.isBigDecimal()) {
                    minConfidence = Optional.of(min.decimalValue());
                } else if (min.isTextual() && min.asText().matches("-?[0-9]+(\\.[0-9]+)?")) {
                    minConfidence = Optional.of(new BigDecimal(min.asText()));
                } else {
                    reader.problem(path + ".minConfidence", "expected a decimal from 0 to 1, e.g. \"0.80\"");
                }
            }
            Optional<Integer> timeoutMs = optionalInt(node, "timeoutMs", path);
            Map<String, String> reasonCodes = strings(node, "reasonCodes", path);
            Optional<Integer> maxReasonCodes = optionalInt(node, "maxReasonCodes", path);
            FlowNode.OnFailure onFailure = new FlowNode.OnFailure("", "");
            JsonNode failure = node.get("onFailure");
            if (failure == null || !failure.isObject()) {
                reader.problem(path + ".onFailure", "required object { \"to\", \"reasonCode\" }: where to go "
                        + "when the model fails, and the reason code to give");
            } else {
                reader.unknownFields(failure, ON_FAILURE_FIELDS, path + ".onFailure");
                onFailure = new FlowNode.OnFailure(reader.requiredText(failure, "to", path + ".onFailure"),
                        reader.requiredText(failure, "reasonCode", path + ".onFailure"));
            }
            Optional<String> as = reader.optionalText(node, "as", path);
            List<Branch> next = next(node, path);
            return new FlowNode.Model(description, mode, modelId, modelVersion, features, outputs, valid,
                    minConfidence, timeoutMs, reasonCodes, maxReasonCodes, onFailure, as, next);
        }

        private Optional<Integer> optionalInt(JsonNode node, String field, String path) {
            JsonNode value = node.get(field);
            if (value == null) {
                return Optional.empty();
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                reader.problem(path + "." + field, "expected a whole number");
                return Optional.empty();
            }
            return Optional.of(value.intValue());
        }

        /** An optional object of feature name to reason code. */
        private Map<String, String> strings(JsonNode node, String field, String path) {
            JsonNode object = node.get(field);
            if (object == null) {
                return Map.of();
            }
            if (!object.isObject()) {
                reader.problem(path + "." + field, "expected an object of feature name to reason code");
                return Map.of();
            }
            Map<String, String> strings = new LinkedHashMap<>();
            object.properties().forEach(e -> {
                if (e.getValue().isTextual()) {
                    strings.put(e.getKey(), e.getValue().asText());
                } else {
                    reader.problem(path + "." + field + "." + e.getKey(), "expected a reason code string");
                }
            });
            return strings;
        }

        /** {@code "next": "node"} or {@code "next": [ { "when": …, "to": … }, …, { "to": … } ]}. */
        private List<Branch> next(JsonNode node, String path) {
            JsonNode next = node.get("next");
            if (next != null && next.isTextual()) {
                if (next.asText().isBlank()) {
                    reader.problem(path + ".next", "required non-empty node id");
                }
                return List.of(Branch.to(next.asText()));
            }
            if (next == null || !next.isArray()) {
                reader.problem(path + ".next", "required: a node id, or an array of { \"when\", \"to\" } ending with { \"to\" }");
                return List.of();
            }
            return branches(next, path + ".next");
        }

        private List<Branch> branches(JsonNode array, String path) {
            if (array == null || !array.isArray() || array.isEmpty()) {
                reader.problem(path, "required non-empty array of { \"when\", \"to\" } ending with { \"to\" }");
                return List.of();
            }
            List<Branch> branches = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                String at = path + "[" + i + "]";
                JsonNode b = array.get(i);
                if (!b.isObject()) {
                    reader.problem(at, "expected an object with \"to\" and, except for the last, \"when\"");
                    continue;
                }
                reader.unknownFields(b, BRANCH_FIELDS, at);
                Optional<String> when = reader.optionalText(b, "when", at);
                String to = reader.requiredText(b, "to", at);
                boolean last = i == array.size() - 1;
                if (last && when.isPresent()) {
                    reader.problem(at + ".when", "the last alternative must have no \"when\", so that one "
                            + "always applies; add { \"to\": … } after it");
                } else if (!last && when.isEmpty()) {
                    reader.problem(at + ".when", "required: only the last alternative may leave out \"when\"");
                }
                branches.add(new Branch(when, to));
            }
            return branches;
        }

        private List<Bucket> buckets(JsonNode array, String path) {
            if (array == null || !array.isArray() || array.isEmpty()) {
                reader.problem(path, "required non-empty array of { \"name\", \"percent\", \"to\" }");
                return List.of();
            }
            List<Bucket> buckets = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                String at = path + "[" + i + "]";
                JsonNode b = array.get(i);
                if (!b.isObject()) {
                    reader.problem(at, "expected an object with \"name\", \"percent\" and \"to\"");
                    continue;
                }
                reader.unknownFields(b, BUCKET_FIELDS, at);
                String name = reader.requiredText(b, "name", at);
                String to = reader.requiredText(b, "to", at);
                JsonNode percent = b.get("percent");
                int value = 0;
                if (percent == null || !percent.isIntegralNumber() || !percent.canConvertToInt()) {
                    reader.problem(at + ".percent", "required whole number from 1 to 100");
                } else {
                    value = percent.intValue();
                }
                buckets.add(new Bucket(name, value, to));
            }
            return buckets;
        }

        /** An object of name to CEL expression text. */
        private Map<String, String> expressions(JsonNode node, String field, String path, boolean required) {
            JsonNode object = node.get(field);
            if (object == null && !required) {
                return Map.of();
            }
            if (object == null || !object.isObject()) {
                reader.problem(path + "." + field, (required ? "required " : "expected an ") + "object of name to CEL expression");
                return Map.of();
            }
            Map<String, String> expressions = new LinkedHashMap<>();
            object.properties().forEach(e -> {
                if (e.getValue().isTextual()) {
                    expressions.put(e.getKey(), e.getValue().asText());
                } else {
                    reader.problem(path + "." + field + "." + e.getKey(),
                            "expected a CEL expression string (string literals need inner quotes, e.g. \"'APPROVE'\")");
                }
            });
            return expressions;
        }
    }
}
