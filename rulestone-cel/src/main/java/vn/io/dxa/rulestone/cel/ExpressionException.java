//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

/** Raised when a rule expression cannot be compiled or evaluated. */
public class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
