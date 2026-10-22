//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

/** How a decision table combines matching rules, following DMN semantics. */
public enum HitPolicy {

    /** At most one rule may match; several matches are an error that blocks the decision. */
    UNIQUE,

    /** Rules are tried in order; the first match wins and later rules are not evaluated. */
    FIRST,

    /** Every matching rule contributes; each output becomes the list of values from matched rules, in order. */
    COLLECT
}
