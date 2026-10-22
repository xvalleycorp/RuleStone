//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/** No version of the requested decision matches the {@link DecisionRef}. */
public final class DecisionNotFoundException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    public DecisionNotFoundException(String message) {
        super(message);
    }
}
