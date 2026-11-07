//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;

/**
 * Reads and writes {@link LogEntry log entries} in canonical JSON: compact, keys sorted at every
 * level, absent optionals omitted. A log is exported as one entry per line (JSON Lines).
 */
public final class LogEntryJson {

    private static final Set<String> ENTRY_FIELDS = Set.of("seq", "eventId", "at", "type", "actor", "changeSet",
            "subjects", "details", "prev", "hash");

    private LogEntryJson() {}

    /** The canonical form of the entry, with its {@code hash}. */
    public static String write(LogEntry entry) {
        Map<String, Object> json = json(entry);
        json.put("hash", entry.hash());
        return Canonical.write(json);
    }

    /** The canonical form without {@code hash}: the text that {@code hash} is the SHA-256 of. */
    static String writeUnsealed(LogEntry entry) {
        return Canonical.write(json(entry));
    }

    private static Map<String, Object> json(LogEntry entry) {
        Map<String, Object> json = new TreeMap<>();
        json.put("seq", entry.seq());
        json.put("eventId", entry.eventId());
        json.put("at", entry.at());
        json.put("type", entry.type().name());
        Map<String, Object> actor = new TreeMap<>();
        actor.put("principal", entry.actor().principal());
        entry.actor().via().ifPresent(v -> actor.put("via", v));
        json.put("actor", actor);
        entry.changeSet().ifPresent(c -> json.put("changeSet", c));
        List<Object> subjects = new ArrayList<>();
        for (ApprovalStatement.Subject s : entry.subjects()) {
            subjects.add(new TreeMap<>(Map.of("kind", s.kind().jsonName(), "id", s.id(), "version", s.version())));
        }
        json.put("subjects", subjects);
        json.put("details", entry.details());
        json.put("prev", entry.prev());
        return json;
    }

    /** @throws IllegalArgumentException if the text is not an entry; the hash is not checked, see {@link LogEntry#hashMatches()} */
    public static LogEntry read(String json) {
        JsonNode root = Canonical.parse(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("$: expected a JSON object");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!ENTRY_FIELDS.contains(name)) {
                throw new IllegalArgumentException("$." + name + ": unknown key");
            }
        }
        JsonNode seq = root.get("seq");
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) {
            throw new IllegalArgumentException("$.seq: expected a whole number from 1");
        }
        EventType type;
        try {
            type = EventType.valueOf(text(root, "type"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("$.type: unknown event type");
        }
        JsonNode actor = root.get("actor");
        if (actor == null || !actor.isObject()) {
            throw new IllegalArgumentException("$.actor: expected an object");
        }
        LogEntry.Actor who = new LogEntry.Actor(text(actor, "principal"),
                actor.has("via") ? Optional.of(text(actor, "via")) : Optional.empty());
        List<ApprovalStatement.Subject> subjects = new ArrayList<>();
        JsonNode array = root.get("subjects");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("$.subjects: expected an array");
        }
        for (JsonNode s : array) {
            ArtifactKind kind = ArtifactKind.fromJsonName(text(s, "kind"))
                    .orElseThrow(() -> new IllegalArgumentException("$.subjects: unknown kind"));
            subjects.add(new ApprovalStatement.Subject(kind, text(s, "id"), text(s, "version")));
        }
        JsonNode details = root.get("details");
        if (details == null || !details.isObject()) {
            throw new IllegalArgumentException("$.details: expected an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> detailMap = (Map<String, Object>) Canonical.plain(details, "$.details");
        return new LogEntry(seq.longValue(), text(root, "eventId"), text(root, "at"), type, who,
                root.has("changeSet") ? Optional.of(text(root, "changeSet")) : Optional.empty(), subjects, detailMap,
                text(root, "prev"), text(root, "hash"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("$." + field + ": expected a string");
        }
        return value.textValue();
    }
}
