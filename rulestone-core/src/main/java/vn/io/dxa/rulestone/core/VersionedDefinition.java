//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A versioned, effective-dated definition: a {@link DecisionTable} or a {@link DecisionFlow},
 * which the engine evaluates by id, or a {@link ParameterSet}, which they read. All follow the
 * same rules, applied by {@link DecisionVersions}: {@code effectiveFrom} (inclusive) is the first
 * day a version is in force, and the optional {@code effectiveTo} (exclusive) the first day it no
 * longer is; without {@code effectiveTo}, a version stays in force until the next version of the
 * same id takes effect.
 */
public sealed interface VersionedDefinition permits DecisionTable, DecisionFlow, ParameterSet {

    /** The id, shared by all its versions. */
    String id();

    /** The version label, e.g. {@code 3}. */
    String version();

    Optional<LocalDate> effectiveFrom();

    Optional<LocalDate> effectiveTo();

    /** {@code id@version}, for messages. */
    default String ref() {
        return id() + "@" + version();
    }
}
