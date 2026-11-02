//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads and writes reason-code catalogs, conventionally a file named {@code reason-codes.json}.
 *
 * <pre>{@code
 * {
 *   "version": "3",
 *   "description": "Reviewed by Compliance on 2026-11-20",
 *   "locales": ["en", "vi"],
 *   "codes": {
 *     "DTI_HIGH": {
 *       "en": "Your monthly debt payments are high compared with your income.",
 *       "vi": "Tổng số tiền trả nợ hằng tháng của quý khách cao so với thu nhập."
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Every code has a message in exactly the declared locales. Codes are UPPER_SNAKE_CASE, as in
 * tables and flows; locales are language tags such as {@code en}, {@code vi} or {@code en-GB}. A
 * message is one line of plain text, without leading or trailing spaces and without the characters
 * {@code {} and {@code }}, which are reserved for placeholders: messages never contain values.
 * Reading is strict: unknown or duplicate keys and every other problem are errors, each reported
 * with its location, all together.
 */
public final class ReasonCodeCatalogJson {

    /** The conventional file name of a catalog. */
    public static final String FILE_NAME = "reason-codes.json";

    static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Set<String> CATALOG_FIELDS = Set.of("version", "description", "locales", "codes");

    private ReasonCodeCatalogJson() {}

    /** @throws InvalidDecisionException listing every problem found */
    public static ReasonCodeCatalog read(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidDecisionException(List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return catalog(root);
    }

    public static ReasonCodeCatalog read(InputStream in) {
        try {
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ReasonCodeCatalog read(Path file) {
        try {
            return read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the canonical form: compact, keys sorted, the locales in their declared order, no
     * description when there is none. The content hash of {@link #exactVersion} is computed from it.
     */
    public static String write(ReasonCodeCatalog catalog) {
        Map<String, Object> json = new TreeMap<>();
        json.put("version", catalog.version());
        catalog.description().ifPresent(d -> json.put("description", d));
        json.put("locales", catalog.locales());
        Map<String, Object> codes = new TreeMap<>();
        catalog.codes().forEach((code, messages) -> codes.put(code, new TreeMap<>(messages)));
        json.put("codes", codes);
        return DecisionTableJson.writeCanonical(json);
    }

    /** The catalog's exact version: {@code <version>@sha256:<hash of the canonical form>}. */
    public static String exactVersion(ReasonCodeCatalog catalog) {
        return catalog.version() + "@sha256:" + CompiledDecision.sha256(write(catalog));
    }

    private static ReasonCodeCatalog catalog(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidDecisionException(List.of("$: expected a JSON object"));
        }
        DecisionTableJson.Reader reader = new DecisionTableJson.Reader();
        reader.unknownFields(root, CATALOG_FIELDS, "$");
        String version = reader.requiredText(root, "version", "$");
        if (!version.isEmpty() && !CompiledDecision.ID.matcher(version).matches()
                && !version.matches("[0-9][A-Za-z0-9_.-]*")) {
            reader.problem("$.version", "must be letters, digits, '.', '_' or '-'");
        }
        Optional<String> description = reader.optionalText(root, "description", "$");
        List<String> locales = locales(root.get("locales"), reader);
        Map<String, Map<String, String>> codes = new LinkedHashMap<>();
        JsonNode node = root.get("codes");
        if (node == null || !node.isObject()) {
            reader.problem("$.codes", "required object of reason code to { locale: message }");
        } else {
            node.properties().forEach(e -> {
                Map<String, String> messages = messages(e.getKey(), e.getValue(), locales, reader);
                if (messages != null) {
                    codes.put(e.getKey(), messages);
                }
            });
        }
        if (!reader.problems.isEmpty()) {
            throw new InvalidDecisionException(reader.problems);
        }
        return new ReasonCodeCatalog(version, description, locales, codes);
    }

    private static List<String> locales(JsonNode node, DecisionTableJson.Reader reader) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            reader.problem("$.locales", "required non-empty array of language tags, e.g. [\"en\", \"vi\"]");
            return List.of();
        }
        Set<String> locales = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String path = "$.locales[" + i + "]";
            if (!item.isTextual() || !LOCALE.matcher(item.asText()).matches()) {
                reader.problem(path, "must be a language tag such as en, vi or en-GB");
            } else if (!locales.add(item.asText())) {
                reader.problem(path, "duplicate locale '" + item.asText() + "'");
            }
        }
        return new ArrayList<>(locales);
    }

    private static Map<String, String> messages(String code, JsonNode node, List<String> locales,
                                                DecisionTableJson.Reader reader) {
        String path = "$.codes." + code;
        if (!CompiledDecision.REASON_CODE.matcher(code).matches()) {
            reader.problem(path, "must be UPPER_SNAKE_CASE, e.g. DTI_HIGH");
        }
        if (!node.isObject()) {
            reader.problem(path, "expected an object of locale to message");
            return null;
        }
        Map<String, String> messages = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            String at = path + "." + e.getKey();
            if (!locales.contains(e.getKey())) {
                reader.problem(at, "locale not declared in $.locales");
                return;
            }
            if (!e.getValue().isTextual()) {
                reader.problem(at, "expected a string");
                return;
            }
            String problem = messageProblem(e.getValue().asText());
            if (problem != null) {
                reader.problem(at, problem);
                return;
            }
            messages.put(e.getKey(), e.getValue().asText());
        });
        for (String locale : locales) {
            if (!node.has(locale)) {
                reader.problem(path + "." + locale, "required: the message in " + locale);
            }
        }
        return messages;
    }

    /** Why a message is not allowed, or {@code null}. */
    static String messageProblem(String message) {
        if (message.chars().allMatch(c -> c == ' ')) {
            return "must not be blank";
        }
        if (message.startsWith(" ") || message.endsWith(" ")) {
            return "must not start or end with spaces";
        }
        if (message.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "must be one line of text, without control characters";
        }
        if (message.indexOf('{') >= 0 || message.indexOf('}') >= 0) {
            return "must not contain '{' or '}', which are reserved for placeholders";
        }
        return null;
    }
}
