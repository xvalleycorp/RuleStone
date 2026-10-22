//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.math.BigDecimal;

/**
 * Runtime representation of a CEL {@code decimal}.
 *
 * <p>CEL compares values of custom types with {@link Object#equals}, but
 * {@link BigDecimal#equals} is scale-sensitive ({@code 1.0 != 1.00}). This wrapper gives
 * decimals numeric equality, so {@code ==}, {@code in} and map keys behave as rule
 * authors expect. It never leaves this package: values are unwrapped on the way out.
 */
final class CelDecimal implements Comparable<CelDecimal> {

    final BigDecimal value;

    CelDecimal(BigDecimal value) {
        this.value = value;
    }

    @Override
    public int compareTo(CelDecimal other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CelDecimal other && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
