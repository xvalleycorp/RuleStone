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
 * <p>Fields are written in a fixed order. Absent optional fields are left out, also in the
 * steps of the path: a {@code switch} step, for example, has {@code taken} but no
 * {@code version} or {@code matchedRules}. Output
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
                    .put("type", step.type());
            step.decision().ifPresent(decision -> node.put("decision", decision));
            step.version().ifPresent(version -> node.put("version", version));
            // A table step always lists its matched rules, even none; other steps have none to list.
            if (step.type().equals("table") || !step.matchedRules().isEmpty()) {
                ArrayNode matched = node.putArray("matchedRules");
                step.matchedRules().forEach(matched::add);
            }
            step.taken().ifPresent(taken -> node.put("taken", taken));
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

    /** The record as compact JSON text, one line. */
    public static String write(DecisionRecord record) {
        try {
            return MAPPER.writeValueAsString(toJson(record));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }
}
