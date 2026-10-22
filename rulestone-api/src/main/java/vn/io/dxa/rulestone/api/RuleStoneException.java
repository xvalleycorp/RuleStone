//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/** Base class of all exceptions thrown by RuleStone. */
public class RuleStoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RuleStoneException(String message) {
        super(message);
    }

    public RuleStoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
