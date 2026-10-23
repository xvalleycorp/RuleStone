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
            new Function("abs", "method", "decimal.abs() | money.abs()", "abs()", "Absolute value."),
            new Function("min", "function", "min(a, b) -> decimal | money", "min(${})",
                    "The smaller of two decimals, or of two amounts in the same currency."),
            new Function("max", "function", "max(a, b) -> decimal | money", "max(${})",
                    "The larger of two decimals, or of two amounts in the same currency."),
            new Function("money", "function", "money(string, string) -> money", "money('${}', 'VND')",
                    "Exact amount in an ISO 4217 currency, e.g. money('1500000', 'VND')."),
            new Function("money", "function", "money(int | decimal, string) -> money", "money(${}, 'VND')",
                    "Amount from a number and a currency code, e.g. money(2000, 'VND')."),
            new Function("round", "method", "money.round() -> money", "round()",
                    "Rounds to the currency's minor units (0 for VND, 2 for USD), half-even."),
            new Function("round", "method", "money.round(string) -> money", "round('HALF_UP')",
                    "Rounds to the currency's minor units with a named mode."),
            new Function("amount", "method", "money.amount() -> decimal", "amount()", "The amount, as a decimal."),
            new Function("currency", "method", "money.currency() -> string", "currency()",
                    "The ISO 4217 currency code, e.g. 'VND'."),
            new Function("date", "function", "date(string) -> date", "date('${}')",
                    "Calendar date in ISO format, e.g. date('2026-11-01')."),
            new Function("plusDays", "method", "date.plusDays(int) -> date", "plusDays(${})",
                    "The date n days later; n may be negative."),
            new Function("plusMonths", "method", "date.plusMonths(int) -> date", "plusMonths(${})",
                    "The date n months later, clamped to the end of the month."),
            new Function("plusYears", "method", "date.plusYears(int) -> date", "plusYears(${})",
                    "The date n years later, clamped to the end of the month."),
            new Function("year", "method", "date.year() -> int", "year()", "The year."),
            new Function("month", "method", "date.month() -> int", "month()", "The month, 1 to 12."),
            new Function("day", "method", "date.day() -> int", "day()", "The day of the month."),
            new Function("dayOfWeek", "method", "date.dayOfWeek() -> int", "dayOfWeek()",
                    "Day of the week, 1 = Monday ... 7 = Sunday."),
            new Function("daysBetween", "function", "daysBetween(date, date) -> int", "daysBetween(${})",
                    "Whole days from the first date to the second."),
            new Function("monthsBetween", "function", "monthsBetween(date, date) -> int", "monthsBetween(${})",
                    "Complete months from the first date to the second."),
            new Function("yearsBetween", "function", "yearsBetween(date, date) -> int", "yearsBetween(${})",
                    "Complete years from the first date to the second, e.g. an age."),
            new Function("normalizeName", "function", "normalizeName(string) -> string", "normalizeName(${})",
                    "The name without accents, case or punctuation."),
            new Function("similarity", "function", "similarity(string, string) -> decimal", "similarity(${})",
                    "Jaro-Winkler similarity of two normalized names, from 0 to 1."),
            new Function("nameSimilarity", "function", "nameSimilarity(string, string) -> decimal",
                    "nameSimilarity(${})", "Name similarity that also ignores word order, from 0 to 1."),
            new Function("levenshtein", "function", "levenshtein(string, string) -> int", "levenshtein(${})",
                    "Edit distance of two normalized names."),
            new Function("maxNameSimilarity", "function", "maxNameSimilarity(string, list<string>) -> decimal",
                    "maxNameSimilarity(${})", "The highest nameSimilarity of a name against a list of names."),
            new Function("string", "function", "string(x) -> string", "string(${})",
                    "Converts a decimal (plain notation), an int, money or a date to text."),
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

    /** Inputs every rule can read without declaring them (see "The business date" in docs/expressions.md). */
    static final Map<String, String> IMPLICIT_INPUTS = Map.of("businessDate", "date");

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
        ArrayNode implicit = meta.putArray("implicitInputs");
        IMPLICIT_INPUTS.forEach((name, type) -> implicit.addObject().put("name", name).put("type", type));
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
