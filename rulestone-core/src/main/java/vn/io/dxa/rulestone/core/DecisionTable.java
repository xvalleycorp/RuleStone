//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * A versioned decision table: typed inputs and outputs plus an ordered list of rules.
 *
 * <p>A table is effective from {@code effectiveFrom} (inclusive) until {@code effectiveTo}
 * (exclusive); an empty bound is open-ended. This is only the definition: structural and
 * type errors are reported when the table is registered with an engine.
 */
public record DecisionTable(
        String id,
        String version,
        Optional<String> description,
        Optional<LocalDate> effectiveFrom,
        Optional<LocalDate> effectiveTo,
        HitPolicy hitPolicy,
        Map<String, ValueType> inputs,
        Map<String, ValueType> outputs,
        List<Rule> rules) {

    public DecisionTable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        rules = List.copyOf(rules);
    }

    /** Whether this table is in force on {@code date}. */
    public boolean isEffectiveOn(LocalDate date) {
        return effectiveFrom.map(from -> !date.isBefore(from)).orElse(true)
                && effectiveTo.map(date::isBefore).orElse(true);
    }

    /** {@code id@version}, for messages. */
    public String ref() {
        return id + "@" + version;
    }
}
