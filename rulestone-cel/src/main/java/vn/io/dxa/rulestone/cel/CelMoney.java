//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import java.math.BigDecimal;

/**
 * Runtime representation of a CEL {@code money}.
 *
 * <p>As with {@link CelDecimal}, equality is numeric ({@code 1.0 VND == 1.00 VND}); amounts in
 * different currencies are never equal. It never leaves this package: values are converted to
 * {@link vn.io.dxa.rulestone.api.Money} on the way out.
 */
final class CelMoney {

    final BigDecimal amount;
    final String currency;

    CelMoney(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CelMoney other && currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * currency.hashCode() + amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
