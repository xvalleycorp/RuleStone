//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

/**
 * A request the API refuses, answered as {@code {"error": {"code": ..., "message": ...}}}.
 * Messages name files, fields and limits, never values from the request body.
 */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    ApiException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
