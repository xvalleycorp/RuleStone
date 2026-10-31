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
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * The parameters a decision table or flow reads, as {@code params.<name>}, from a
 * {@link ParameterSet}: the {@code "params"} object of a {@code .stone.json} or {@code .flow.json} file.
 *
 * <pre>{@code
 * "params": { "set": "retail_credit", "values": { "maxDti": "decimal", "minAge": "int" } }
 * }</pre>
 *
 * <p>The table's content hash covers this declaration, not the values, so a new version of the set
 * changes no rule version. When the engine is built, every registered version of the set (or the
 * pinned one) must declare each parameter with the same type.
 *
 * @param set     the id of the parameter set
 * @param version a version of the set to use whatever the business date, for special cases; empty
 *                to use the version in force on the business date
 * @param values  the parameters read, by name, with their types, in file order
 */
public record ParamsDeclaration(String set, Optional<String> version, Map<String, ValueType> values) {

    public ParamsDeclaration {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(version, "version");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A declaration that uses the version of {@code set} in force on the business date. */
    public static ParamsDeclaration of(String set, Map<String, ValueType> values) {
        return new ParamsDeclaration(set, Optional.empty(), values);
    }
}
