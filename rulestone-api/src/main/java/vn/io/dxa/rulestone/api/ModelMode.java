//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Locale;

/** How a {@code model} node obtains the model's result. */
public enum ModelMode {
    /** The engine calls the model through the {@link ModelInvoker} registered for its id. */
    INVOKE,
    /**
     * The caller computed the result and passes it with the facts (see
     * {@link Facts#withModelResult}). The engine checks it exactly as if it had called the model.
     */
    SUPPLIED;

    /** The mode as written in flow files and records: {@code invoke} or {@code supplied}. */
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
