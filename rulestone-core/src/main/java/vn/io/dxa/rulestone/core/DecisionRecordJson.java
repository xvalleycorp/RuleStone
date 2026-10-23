//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.io.dxa.rulestone.api.DecisionRecord;

/**
 * Writes a {@link DecisionRecord} as JSON, for audit sinks that log or publish records.
 *
 * <pre>{@code
 * RuleStone.builder()
 *     .auditSink(record -> auditLog.info(DecisionRecordJson.write(record)))
 * }</pre>
 *
 * <p>Fields are written in a fixed order. Absent optional fields are left out. Output
 * values use the {@link FactsJson} forms, e.g. {@code "25000 VND"} for money.
 */
public final class DecisionRecordJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DecisionRecordJson() {}

    /** The record as a JSON object. */
    public static ObjectNode toJson(DecisionRecord record) {
        ObjectNode json = NODES.objectNode();
        json.put("recordId", record.recordId());
        record.correlationId().ifPresent(id -> json.put("correlationId", id));
        json.put("decision", record.decision());
        record.version().ifPresent(version -> json.put("version", version));
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
                    .put("type", step.type())
                    .put("version", step.version());
            ArrayNode matched = node.putArray("matchedRules");
            step.matchedRules().forEach(matched::add);
        });
        record.error().ifPresent(error -> json.put("error", error));
        return json;
    }

    /** The record as compact JSON text, one line. */
    public static String write(DecisionRecord record) {
        try {
            return MAPPER.writeValueAsString(toJson(record));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }
}
