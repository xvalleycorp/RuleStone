//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

/** Raised when a value does not match its declared {@link ValueType}. */
public final class InvalidValueException extends ExpressionException {

    private static final long serialVersionUID = 1L;

    private final String path;

    InvalidValueException(String path, String problem) {
        super(path + ": " + problem, null);
        this.path = path;
    }

    /** Location of the offending value, e.g. {@code applicant.accounts[2].balance}. */
    public String path() {
        return path;
    }
}
