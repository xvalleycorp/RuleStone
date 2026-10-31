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
 * <p>{@code effectiveFrom} (inclusive) is the first day the version is in force, and the
 * optional {@code effectiveTo} (exclusive) the first day it no longer is. Without
 * {@code effectiveTo}, a version stays in force until the next version of the same decision
 * takes effect; see {@link DecisionVersions}. This is only the definition: structural and
 * type errors are reported when the table is registered with an engine.
 *
 * <p>{@code let} names local values: CEL expressions over the inputs (and other local values)
 * that every rule can read by name. Each is computed when a rule first reads it, at most once per
 * evaluation, and is not recorded.
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
        Map<String, String> let,
        List<Rule> rules) implements VersionedDefinition {

    /** A table without local values. */
    public DecisionTable(String id, String version, Optional<String> description, Optional<LocalDate> effectiveFrom,
                         Optional<LocalDate> effectiveTo, HitPolicy hitPolicy, Map<String, ValueType> inputs,
                         Map<String, ValueType> outputs, List<Rule> rules) {
        this(id, version, description, effectiveFrom, effectiveTo, hitPolicy, inputs, outputs, Map.of(), rules);
    }

    public DecisionTable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        let = Collections.unmodifiableMap(new LinkedHashMap<>(let));
        rules = List.copyOf(rules);
    }

    /**
     * Whether {@code date} falls within this table's own declared period. This ignores other
     * versions: a later version supersedes this one from its {@code effectiveFrom}. To find the
     * version in force, use {@link DecisionVersions#inForce}.
     */
    public boolean isEffectiveOn(LocalDate date) {
        return effectiveFrom.map(from -> !date.isBefore(from)).orElse(true)
                && effectiveTo.map(date::isBefore).orElse(true);
    }

    /** {@code id@version}, for messages. */
    @Override
    public String ref() {
        return id + "@" + version;
    }
}
