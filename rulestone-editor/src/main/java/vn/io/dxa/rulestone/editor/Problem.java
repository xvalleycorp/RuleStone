//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One problem with a decision table, located precisely enough for the editor to highlight a cell.
 *
 * @param message  short, single-line explanation, e.g. {@code undefined field 'agee'}
 * @param detail   the full text as reported by RuleStone, including its location prefix and,
 *                 for expressions, the source line with a caret
 * @param location where the problem is; never {@code null}, but every part may be absent
 */
record Problem(String message, String detail, Location location) {

    /** A problem about the table as a whole or one of its top-level fields. */
    static Problem table(String field, String message) {
        return new Problem(message, message, new Location(field == null ? "$" : "$." + field,
                null, null, field, null, null, null, null, null));
    }

    ObjectNode toJson() {
        ObjectNode json = Json.object();
        json.put("message", message);
        json.put("detail", detail);
        json.set("location", location.toJson());
        return json;
    }

    /**
     * Where a problem is.
     *
     * @param path      JSON path in the table, e.g. {@code $.rules[2].then.limit}
     * @param rule      id of the rule concerned
     * @param ruleIndex zero-based position of that rule in {@code rules}
     * @param field     the field concerned: a rule field ({@code id}, {@code when}, {@code then},
     *                  {@code reasonCode}, ...) when {@code rule} is set, otherwise a table field
     *                  ({@code id}, {@code version}, {@code effectiveFrom}, {@code inputs}, ...)
     * @param output    the output concerned, for {@code then} and {@code outputs} problems
     * @param input     the dotted input path concerned, for {@code inputs} problems, e.g. {@code applicant.age}
     * @param file      another file in the rules directory involved, for version conflicts
     * @param line      one-based line within the expression, for expression errors
     * @param column    one-based column within the expression, for expression errors
     */
    record Location(String path, String rule, Integer ruleIndex, String field, String output, String input,
                    String file, Integer line, Integer column) {

        Location withFile(String otherFile) {
            return new Location(path, rule, ruleIndex, field, output, input, otherFile, line, column);
        }

        ObjectNode toJson() {
            ObjectNode json = Json.object();
            putIfPresent(json, "path", path);
            putIfPresent(json, "rule", rule);
            if (ruleIndex != null) {
                json.put("ruleIndex", ruleIndex);
            }
            putIfPresent(json, "field", field);
            putIfPresent(json, "output", output);
            putIfPresent(json, "input", input);
            putIfPresent(json, "file", file);
            if (line != null) {
                json.put("line", line);
                json.put("column", column);
            }
            return json;
        }

        private static void putIfPresent(ObjectNode json, String name, String value) {
            if (value != null) {
                json.put(name, value);
            }
        }
    }
}
