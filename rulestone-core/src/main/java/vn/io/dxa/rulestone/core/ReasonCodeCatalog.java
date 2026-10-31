//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The reviewed messages of reason codes, in each language the bank explains decisions in: a
 * {@code reason-codes.json} file, read with {@link ReasonCodeCatalogJson}.
 *
 * <p>Register it with {@link RuleStone.Builder#reasonCodeCatalog} so that the engine checks, when it
 * is built, that every reason code of every table and flow has a message, and can explain records
 * with {@link vn.io.dxa.rulestone.api.RuleStoneEngine#explain}; or explain records without an
 * engine with {@link Explanations#explain}.
 *
 * @param version     the version label, e.g. {@code 3}; the exact version adds the content hash,
 *                    e.g. {@code 3@sha256:…}, see {@link ReasonCodeCatalogJson#exactVersion}
 * @param description free text, e.g. who reviewed the messages
 * @param locales     the languages every code has a message in, e.g. {@code [en, vi]}, in file order
 * @param codes       reason code to locale to message, in file order; every code must have a
 *                    message in exactly the declared locales, or the constructor throws
 *                    {@link IllegalArgumentException}
 */
public record ReasonCodeCatalog(
        String version,
        Optional<String> description,
        List<String> locales,
        Map<String, Map<String, String>> codes) {

    public ReasonCodeCatalog {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        locales = List.copyOf(locales);
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : codes.entrySet()) {
            Map<String, String> messages = Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue()));
            if (!messages.keySet().equals(Set.copyOf(locales))) {
                throw new IllegalArgumentException("reason code " + e.getKey() + " must have a message in exactly "
                        + "the locales " + locales + " but has " + messages.keySet());
            }
            messages.values().forEach(m -> Objects.requireNonNull(m, "message"));
            copy.put(Objects.requireNonNull(e.getKey(), "code"), messages);
        }
        codes = Collections.unmodifiableMap(copy);
    }

    /** The message of {@code code} in {@code locale}; empty if the catalog has no such code or locale. */
    public Optional<String> message(String code, String locale) {
        Map<String, String> messages = codes.get(code);
        return messages == null ? Optional.empty() : Optional.ofNullable(messages.get(locale));
    }
}
