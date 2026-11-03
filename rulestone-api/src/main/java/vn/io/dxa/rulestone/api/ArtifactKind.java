//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Locale;
import java.util.Optional;

/** The kinds of versioned artifact that can change a decision, and so are governed by approvals. */
public enum ArtifactKind {
    /** A decision table, a {@code .stone.json} file. */
    TABLE,
    /** A decision flow, a {@code .flow.json} file. */
    FLOW,
    /** A parameter set, a {@code .params.json} file. */
    PARAMS,
    /** A reason-code catalog, a {@code reason-codes.json} file. */
    CATALOG;

    /** The kind as written in approval statements: {@code table}, {@code flow}, {@code params} or {@code catalog}. */
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind written as {@code name}, if any. */
    public static Optional<ArtifactKind> fromJsonName(String name) {
        for (ArtifactKind kind : values()) {
            if (kind.jsonName().equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
