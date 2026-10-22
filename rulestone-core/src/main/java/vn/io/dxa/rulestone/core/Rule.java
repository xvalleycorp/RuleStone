//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of a decision table.
 *
 * @param id          stable identifier, recorded in traces and audit logs
 * @param description optional explanation for business users
 * @param when        CEL condition; the rule matches when it evaluates to {@code true}
 * @param then        output name to CEL expression producing its value
 * @param reasonCode  optional machine-readable reason reported when the rule matches, e.g. {@code DTI_HIGH}
 */
public record Rule(
        String id,
        Optional<String> description,
        String when,
        Map<String, String> then,
        Optional<String> reasonCode) {

    public Rule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(then, "then");
        Objects.requireNonNull(reasonCode, "reasonCode");
        then = Collections.unmodifiableMap(new LinkedHashMap<>(then));
    }

    public static Rule of(String id, String when, Map<String, String> then) {
        return new Rule(id, Optional.empty(), when, then, Optional.empty());
    }

    public static Rule of(String id, String when, Map<String, String> then, String reasonCode) {
        return new Rule(id, Optional.empty(), when, then, Optional.of(reasonCode));
    }
}
