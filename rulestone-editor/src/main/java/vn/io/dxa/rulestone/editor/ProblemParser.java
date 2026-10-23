//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns RuleStone's problem strings into structured {@link Problem}s.
 *
 * <p>RuleStone reports two shapes of problem:
 * <ul>
 *   <li>from {@code DecisionTableJson.read}, a JSON path: {@code $.rules[2].then.limit: expected a CEL expression string};</li>
 *   <li>from {@code RuleStone.builder().build()}, the table reference and a rule id:
 *       {@code loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'}.</li>
 * </ul>
 * The parser knows the table being checked, so it can map rule ids to positions and back.
 * A string in neither shape becomes a table-level problem with no field.
 */
final class ProblemParser {

    private static final Pattern INVALID_JSON = Pattern.compile("invalid JSON: .*\\(line (\\d+), column (\\d+)\\)",
            Pattern.DOTALL);
    private static final Pattern CEL_POSITION = Pattern.compile("^ERROR: <input>:(\\d+):(\\d+): ");
    private static final Pattern PATH_TOKEN = Pattern.compile("\\.([^.\\[]+)|\\[(\\d*)]");

    private final String ref;
    private final List<String> ruleIds = new ArrayList<>();
    private final Map<String, Integer> duplicatesSeen = new HashMap<>();

    /**
     * @param table the table JSON the problems are about; may be {@code null} or malformed
     */
    ProblemParser(JsonNode table) {
        String id = text(table, "id");
        String version = text(table, "version");
        this.ref = id + "@" + version;
        JsonNode rules = table == null ? null : table.get("rules");
        if (rules != null && rules.isArray()) {
            rules.forEach(rule -> ruleIds.add(text(rule, "id")));
        }
    }

    Problem parse(String problem) {
        if (problem.startsWith("$")) {
            return jsonPathProblem(problem);
        }
        if (problem.startsWith(ref + " ")) {
            return compileProblem(problem, problem.substring(ref.length() + 1));
        }
        Matcher json = INVALID_JSON.matcher(problem);
        if (json.matches()) {
            return new Problem(firstLine(problem), problem, new Location(
                    null, null, null, null, null, null, null,
                    Integer.valueOf(json.group(1)), Integer.valueOf(json.group(2))).build());
        }
        return new Problem(firstLine(problem), problem, new Location().build());
    }

    List<Problem> parseAll(List<String> problems) {
        return problems.stream().map(this::parse).toList();
    }

    /** {@code $.rules[2].then.limit: message} */
    private Problem jsonPathProblem(String problem) {
        int colon = problem.indexOf(": ");
        String path = colon < 0 ? problem : problem.substring(0, colon);
        String message = colon < 0 ? problem : problem.substring(colon + 2);
        List<String> tokens = new ArrayList<>();
        Matcher m = PATH_TOKEN.matcher(path.substring(1));
        while (m.find()) {
            tokens.add(m.group(1) != null ? m.group(1) : "[" + m.group(2) + "]");
        }
        Location location = new Location();
        location.path = path;
        if (!tokens.isEmpty()) {
            String field = tokens.get(0);
            if (field.equals("rules") && tokens.size() > 1 && tokens.get(1).matches("\\[\\d+]")) {
                int index = Integer.parseInt(tokens.get(1).substring(1, tokens.get(1).length() - 1));
                location.ruleIndex = index;
                location.rule = index < ruleIds.size() ? ruleIds.get(index) : null;
                if (tokens.size() > 2) {
                    location.field = tokens.get(2);
                    if (location.field.equals("then") && tokens.size() > 3) {
                        location.output = tokens.get(3);
                    }
                }
            } else {
                location.field = field;
                String rest = String.join(".", tokens.subList(1, tokens.size())).replace(".[", "[");
                if (field.equals("inputs") && !rest.isEmpty()) {
                    location.input = rest;
                } else if (field.equals("outputs") && !rest.isEmpty()) {
                    location.output = tokens.get(1);
                }
            }
        }
        return new Problem(firstLine(message), problem, location.build());
    }

    /** {@code rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'}, after the {@code id@version} prefix. */
    private Problem compileProblem(String problem, String rest) {
        Location location = new Location();
        String message = rest;
        if (rest.startsWith("rules[")) {
            String ruleId = matchRuleId(rest);
            if (ruleId != null) {
                String after = rest.substring("rules[".length() + ruleId.length() + 1);
                location.rule = ruleId;
                location.ruleIndex = ruleIndex(ruleId, after.startsWith(": duplicate rule id"));
                location.path = location.ruleIndex == null ? null : "$.rules[" + location.ruleIndex + "]";
                int colon = after.indexOf(": ");
                String target = colon < 0 ? after : after.substring(0, colon);
                message = colon < 0 ? after : after.substring(colon + 2);
                if (target.startsWith(".then.")) {
                    location.field = "then";
                    location.output = target.substring(".then.".length());
                } else if (target.startsWith(".")) {
                    location.field = target.substring(1);
                } else {
                    location.field = "id";
                }
                if (location.path != null && location.field != null) {
                    location.path += "." + location.field + (location.output == null ? "" : "." + location.output);
                }
            }
        } else {
            int colon = rest.indexOf(": ");
            if (colon > 0) {
                String target = rest.substring(0, colon);
                message = rest.substring(colon + 2);
                int dot = target.indexOf('.');
                location.field = dot < 0 ? target : target.substring(0, dot);
                location.path = "$." + target;
                if (dot > 0 && location.field.equals("outputs")) {
                    location.output = target.substring(dot + 1);
                } else if (dot > 0 && location.field.equals("inputs")) {
                    location.input = target.substring(dot + 1);
                }
            }
        }
        Matcher cel = CEL_POSITION.matcher(message);
        if (cel.find()) {
            location.line = Integer.valueOf(cel.group(1));
            location.column = Integer.valueOf(cel.group(2));
            message = message.substring(cel.end());
        }
        return new Problem(firstLine(message), problem, location.build());
    }

    /** The longest known rule id {@code x} such that {@code rest} starts with {@code rules[x]}. */
    private String matchRuleId(String rest) {
        String best = null;
        for (String id : ruleIds) {
            if (id != null && rest.startsWith("rules[" + id + "]") && (best == null || id.length() > best.length())) {
                best = id;
            }
        }
        if (best == null) {
            int close = rest.indexOf(']');
            best = close < 0 ? null : rest.substring("rules[".length(), close);
        }
        return best;
    }

    /** First position of the id; for a "duplicate rule id" problem, the next later position each time. */
    private Integer ruleIndex(String ruleId, boolean duplicate) {
        int skip = duplicate ? duplicatesSeen.merge(ruleId, 1, Integer::sum) : 0;
        for (int i = 0; i < ruleIds.size(); i++) {
            if (ruleId.equals(ruleIds.get(i)) && skip-- == 0) {
                return i;
            }
        }
        return null;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).strip();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** Mutable builder for {@link Problem.Location}. */
    private static final class Location {
        String path;
        String rule;
        Integer ruleIndex;
        String field;
        String output;
        String input;
        String file;
        Integer line;
        Integer column;

        Location() {}

        Location(String path, String rule, Integer ruleIndex, String field, String output, String input,
                 String file, Integer line, Integer column) {
            this.path = path;
            this.rule = rule;
            this.ruleIndex = ruleIndex;
            this.field = field;
            this.output = output;
            this.input = input;
            this.file = file;
            this.line = line;
            this.column = column;
        }

        Problem.Location build() {
            return new Problem.Location(path, rule, ruleIndex, field, output, input, file, line, column);
        }
    }
}
