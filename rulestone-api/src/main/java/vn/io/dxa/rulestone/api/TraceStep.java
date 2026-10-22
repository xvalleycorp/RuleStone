//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Objects;

/**
 * One rule examined during evaluation. Only rule identity and outcome are recorded,
 * never input values, so traces are safe to store in audit logs.
 */
public record TraceStep(String ruleId, boolean matched) {

    public TraceStep {
        Objects.requireNonNull(ruleId, "ruleId");
    }
}
