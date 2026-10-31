//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

/**
 * Thrown while resolving a local value whose expression failed. CEL treats it as the error of the
 * identifier that read the value, so {@code ||} and {@code &&} absorb it exactly as they would
 * absorb the error of the expression written in place; when it is not absorbed,
 * {@link CelExpression#evaluate} reports the local value's own failure.
 */
final class LocalValueException extends RuntimeException {

    LocalValueException(String name, String message, Throwable cause) {
        super("local value " + name + " failed: " + message, cause);
    }

    /** The failure of a local value among the causes of {@code e}, or null. */
    static LocalValueException in(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LocalValueException local) {
                return local;
            }
        }
        return null;
    }
}
