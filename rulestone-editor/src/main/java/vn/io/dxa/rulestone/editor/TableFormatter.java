//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a decision table as a stable, human-diffable {@code .stone.json} file.
 *
 * <ul>
 *   <li>Fixed key order. Table: {@code id, version, description, effectiveFrom, effectiveTo, hitPolicy,
 *       inputs, outputs, rules}. Rule: {@code id, description, when, then, reasonCode}. Inputs, outputs,
 *       struct fields and {@code then} keep the author's order, because it is meaningful (outputs are
 *       reported in declaration order). Unknown keys, which only a forced save can contain, follow the
 *       known ones in their original order.</li>
 *   <li>Two-space indentation, one key per line, a final newline, UTF-8 without escaping non-ASCII text.</li>
 *   <li>Short arrays of scalars and objects with a single scalar entry stay on one line, as in
 *       {@code "then": { "decision": "'REJECT'" }} and {@code ["decimal"]}.</li>
 * </ul>
 *
 * <p>Formatting never changes the content hash: the hash is computed over the canonical form
 * (compact, keys sorted), which ignores whitespace and key order.
 */
final class TableFormatter {

    static final List<String> TABLE_KEYS = List.of(
            "id", "version", "description", "effectiveFrom", "effectiveTo", "hitPolicy", "inputs", "outputs", "rules");
    static final List<String> RULE_KEYS = List.of("id", "description", "when", "then", "reasonCode");

    private static final int MAX_INLINE_WIDTH = 100;

    private TableFormatter() {}

    /** Formats a table; {@code table} need not be valid, but must be a JSON object. */
    static String format(JsonNode table) {
        StringBuilder out = new StringBuilder();
        write(out, table, 0, Kind.TABLE);
        return out.append('\n').toString();
    }

    private enum Kind { TABLE, RULES, RULE, OTHER }

    private static void write(StringBuilder out, JsonNode node, int indent, Kind kind) {
        if (node.isObject()) {
            writeObject(out, node, indent, kind);
        } else if (node.isArray()) {
            writeArray(out, node, indent, kind == Kind.RULES ? Kind.RULE : Kind.OTHER);
        } else {
            out.append(scalar(node));
        }
    }

    private static void writeObject(StringBuilder out, JsonNode node, int indent, Kind kind) {
        Map<String, JsonNode> entries = ordered(node, kind);
        if (entries.isEmpty()) {
            out.append("{}");
            return;
        }
        if (kind == Kind.OTHER && entries.size() == 1 && entries.values().iterator().next().isValueNode()) {
            Map.Entry<String, JsonNode> e = entries.entrySet().iterator().next();
            String inline = "{ " + Json.quote(e.getKey()) + ": " + scalar(e.getValue()) + " }";
            if (fits(out, inline)) {
                out.append(inline);
                return;
            }
        }
        out.append("{\n");
        Iterator<Map.Entry<String, JsonNode>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            pad(out, indent + 1).append(Json.quote(e.getKey())).append(": ");
            Kind child = kind == Kind.TABLE && e.getKey().equals("rules") ? Kind.RULES : Kind.OTHER;
            write(out, e.getValue(), indent + 1, child);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        pad(out, indent).append('}');
    }

    private static void writeArray(StringBuilder out, JsonNode node, int indent, Kind elementKind) {
        if (node.isEmpty()) {
            out.append("[]");
            return;
        }
        boolean scalars = true;
        for (JsonNode element : node) {
            scalars &= element.isValueNode();
        }
        if (scalars && elementKind == Kind.OTHER) {
            List<String> parts = new ArrayList<>();
            node.forEach(element -> parts.add(scalar(element)));
            String inline = "[" + String.join(", ", parts) + "]";
            if (fits(out, inline)) {
                out.append(inline);
                return;
            }
        }
        out.append("[\n");
        for (int i = 0; i < node.size(); i++) {
            pad(out, indent + 1);
            write(out, node.get(i), indent + 1, elementKind);
            out.append(i + 1 < node.size() ? ",\n" : "\n");
        }
        pad(out, indent).append(']');
    }

    private static Map<String, JsonNode> ordered(JsonNode node, Kind kind) {
        List<String> known = switch (kind) {
            case TABLE -> TABLE_KEYS;
            case RULE -> RULE_KEYS;
            default -> List.of();
        };
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        for (String key : known) {
            if (node.has(key)) {
                entries.put(key, node.get(key));
            }
        }
        node.properties().forEach(e -> entries.putIfAbsent(e.getKey(), e.getValue()));
        return entries;
    }

    private static boolean fits(StringBuilder out, String inline) {
        int lineStart = out.lastIndexOf("\n") + 1;
        return out.length() - lineStart + inline.length() <= MAX_INLINE_WIDTH;
    }

    private static String scalar(JsonNode node) {
        if (node.isTextual()) {
            return Json.quote(node.asText());
        }
        return node.toString();
    }

    private static StringBuilder pad(StringBuilder out, int indent) {
        return out.append("  ".repeat(indent));
    }
}
