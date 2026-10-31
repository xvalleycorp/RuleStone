//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * A versioned set of named, typed business values, such as limits and thresholds, kept apart from
 * the rules that read them: a {@code .params.json} file. Tables and flows declare the parameters
 * they read with a {@link ParamsDeclaration} and read them as {@code params.<name>}.
 *
 * <p>Parameter sets are versioned and effective-dated exactly as tables are, and the engine selects
 * the version in force on the business date in the same way; see {@link DecisionVersions}. To change
 * a limit from a date, add a version of the set: the tables do not change. Parameter set ids are a
 * namespace of their own: a set may have the id of a decision.
 *
 * <p>This is only the definition: problems are reported when the set is registered with an engine.
 *
 * @param id            the set id, shared by all its versions
 * @param version       the version label, e.g. {@code 7}
 * @param description   free text, e.g. who approved the values
 * @param effectiveFrom first day this version is in force (inclusive)
 * @param effectiveTo   first day this version is no longer in force (exclusive)
 * @param values        the parameters by name, in file order
 */
public record ParameterSet(
        String id,
        String version,
        Optional<String> description,
        Optional<LocalDate> effectiveFrom,
        Optional<LocalDate> effectiveTo,
        Map<String, Parameter> values) implements VersionedDefinition {

    /**
     * One parameter.
     *
     * @param type  its type: bool, int, string, decimal, money, date or a list of them
     * @param value its value, as {@link FactsJson} reads it: {@code Boolean}, {@code Long},
     *              {@code String}, {@code BigDecimal}, {@link vn.io.dxa.rulestone.api.Money},
     *              {@code LocalDate} or a {@code List} of them
     */
    public record Parameter(ValueType type, Object value) {

        public Parameter {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    public ParameterSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        values.forEach((name, value) -> Objects.requireNonNull(value, () -> "parameter " + name));
    }
}
