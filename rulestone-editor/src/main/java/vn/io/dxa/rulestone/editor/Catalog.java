//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.HitPolicy;

/**
 * What the editor offers rule authors: types, hit policies, and the expression functions and operators
 * used for autocomplete. Served by {@code GET /api/meta}.
 *
 * <p>Types and hit policies come from the engine ({@link ValueType.Primitive}, {@link HitPolicy}), so new
 * ones appear automatically. The function list is maintained here, in {@link #FUNCTIONS}: when a function
 * is added to the expression language, add it here too.
 */
final class Catalog {

    /**
     * A function, method, macro or operator of the expression language.
     *
     * @param name        what the author types, e.g. {@code round}
     * @param kind        {@code function} ({@code f(x)}), {@code method} ({@code x.f()}), {@code macro} or {@code operator}
     * @param signature   human-readable signature
     * @param insert      text inserted by autocomplete; {@code ${}} marks where the cursor goes
     * @param description one sentence for the autocomplete tooltip
     */
    record Function(String name, String kind, String signature, String insert, String description) {}

    /** The expression language as documented in docs/expressions.md. Keep in sync with it. */
    static final List<Function> FUNCTIONS = List.of(
            new Function("decimal", "function", "decimal(string) -> decimal", "decimal('${}')",
                    "Exact decimal from text in plain notation, e.g. decimal('0.05')."),
            new Function("decimal", "function", "decimal(int) -> decimal", "decimal(${})",
                    "Exact decimal from an integer, e.g. decimal(5)."),
            new Function("round", "method", "decimal.round(int) -> decimal", "round(${})",
                    "Rounds to the given number of decimal places, half-even (banker's rounding)."),
            new Function("round", "method", "decimal.round(int, string) -> decimal", "round(${0}, 'HALF_UP')",
                    "Rounds with a named mode: HALF_EVEN, HALF_UP, HALF_DOWN, UP, DOWN, CEILING or FLOOR."),
            new Function("abs", "method", "decimal.abs() -> decimal", "abs()", "Absolute value."),
            new Function("min", "function", "min(decimal, decimal) -> decimal", "min(${})", "The smaller of two decimals."),
            new Function("max", "function", "max(decimal, decimal) -> decimal", "max(${})", "The larger of two decimals."),
            new Function("string", "function", "string(x) -> string", "string(${})",
                    "Converts a decimal (plain notation) or an int to text."),
            new Function("int", "function", "int(string) -> int", "int(${})", "Parses an integer from text."),
            new Function("size", "function", "size(list | string) -> int", "size(${})",
                    "Number of elements of a list, or characters of a text."),
            new Function("size", "method", "list.size() -> int", "size()", "Number of elements of a list."),
            new Function("has", "macro", "has(struct.field) -> bool", "has(${})",
                    "Whether an optional struct field is set, e.g. has(applicant.segment)."),
            new Function("exists", "macro", "list.exists(x, condition) -> bool", "exists(x, ${})",
                    "Whether any element satisfies the condition."),
            new Function("all", "macro", "list.all(x, condition) -> bool", "all(x, ${})",
                    "Whether every element satisfies the condition."),
            new Function("exists_one", "macro", "list.exists_one(x, condition) -> bool", "exists_one(x, ${})",
                    "Whether exactly one element satisfies the condition."),
            new Function("filter", "macro", "list.filter(x, condition) -> list", "filter(x, ${})",
                    "The elements that satisfy the condition."),
            new Function("map", "macro", "list.map(x, expression) -> list", "map(x, ${})",
                    "The expression applied to each element."),
            new Function("startsWith", "method", "string.startsWith(string) -> bool", "startsWith('${}')",
                    "Whether the text starts with the given prefix."),
            new Function("endsWith", "method", "string.endsWith(string) -> bool", "endsWith('${}')",
                    "Whether the text ends with the given suffix."),
            new Function("contains", "method", "string.contains(string) -> bool", "contains('${}')",
                    "Whether the text contains the given text."),
            new Function("matches", "method", "string.matches(string) -> bool", "matches('${}')",
                    "Whether the text matches an RE2 regular expression."),
            new Function("==", "operator", "a == b", "== ", "Equal. Decimals compare by value: decimal('1.0') == decimal('1')."),
            new Function("!=", "operator", "a != b", "!= ", "Not equal."),
            new Function("<", "operator", "a < b", "< ", "Less than."),
            new Function("<=", "operator", "a <= b", "<= ", "Less than or equal."),
            new Function(">", "operator", "a > b", "> ", "Greater than."),
            new Function(">=", "operator", "a >= b", ">= ", "Greater than or equal."),
            new Function("&&", "operator", "a && b", "&& ", "And."),
            new Function("||", "operator", "a || b", "|| ", "Or."),
            new Function("!", "operator", "!a", "!", "Not."),
            new Function("in", "operator", "value in list", "in [${}]", "Membership, e.g. applicant.segment in ['RETAIL', 'SME']."),
            new Function("+", "operator", "a + b", "+ ", "Addition, or concatenation of texts and lists."),
            new Function("-", "operator", "a - b", "- ", "Subtraction or negation."),
            new Function("*", "operator", "a * b", "* ", "Multiplication."),
            new Function("/", "operator", "a / b", "/ ",
                    "Division. Integer division truncates; decimal division keeps 34 significant digits."),
            new Function("%", "operator", "a % b", "% ", "Remainder of integer division."),
            new Function("? :", "operator", "condition ? a : b", "? ${} : ", "Conditional value."));

    static final List<String> ROUNDING_MODES = List.of("HALF_EVEN", "HALF_UP", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR");

    private static final Map<String, String> TYPE_DESCRIPTIONS = Map.of(
            "bool", "true or false",
            "int", "64-bit integer",
            "string", "text",
            "decimal", "exact decimal number, for amounts and rates",
            "money", "amount with a currency",
            "date", "calendar date (yyyy-MM-dd)");

    private static final Map<String, String> HIT_POLICY_DESCRIPTIONS = Map.of(
            "UNIQUE", "At most one rule may match; several matches fail the evaluation.",
            "FIRST", "Rules are tried in order; the first match wins.",
            "COLLECT", "Every matching rule contributes; each output becomes a list.");

    private Catalog() {}

    /** The {@code GET /api/meta} document. */
    static ObjectNode meta(String editorVersion, long maxBodyBytes) {
        ObjectNode meta = Json.object();
        meta.put("editorVersion", editorVersion);
        ArrayNode types = meta.putArray("types");
        for (ValueType.Primitive primitive : ValueType.Primitive.values()) {
            ObjectNode type = types.addObject();
            type.put("name", primitive.typeName());
            type.put("description", TYPE_DESCRIPTIONS.getOrDefault(primitive.typeName(), primitive.typeName()));
        }
        ArrayNode policies = meta.putArray("hitPolicies");
        for (HitPolicy policy : HitPolicy.values()) {
            ObjectNode p = policies.addObject();
            p.put("name", policy.name());
            p.put("description", HIT_POLICY_DESCRIPTIONS.getOrDefault(policy.name(), ""));
        }
        ArrayNode functions = meta.putArray("functions");
        for (Function f : FUNCTIONS) {
            ObjectNode fn = functions.addObject();
            fn.put("name", f.name());
            fn.put("kind", f.kind());
            fn.put("signature", f.signature());
            fn.put("insert", f.insert());
            fn.put("description", f.description());
        }
        ArrayNode modes = meta.putArray("roundingModes");
        ROUNDING_MODES.forEach(modes::add);
        ObjectNode limits = meta.putObject("limits");
        limits.put("maxBodyBytes", maxBodyBytes);
        limits.put("maxFileBytes", RulesDirectory.MAX_FILE_BYTES);
        meta.put("fileSuffix", RulesDirectory.SUFFIX);
        return meta;
    }

    /** The editor's version, from the build ({@code editor.properties}), or {@code "dev"}. */
    static String editorVersion() {
        try (InputStream in = Catalog.class.getResourceAsStream("editor.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version", "");
                if (!version.isBlank() && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return "dev";
    }
}
