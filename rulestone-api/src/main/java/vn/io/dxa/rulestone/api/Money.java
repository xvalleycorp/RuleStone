//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount in an ISO 4217 currency: the Java form of the rule type {@code money}.
 *
 * <p>The amount keeps whatever scale it was given; RuleStone never rounds money implicitly.
 * Like {@link BigDecimal#equals}, {@link #equals} is scale-sensitive: {@code 1.0 VND} and
 * {@code 1.00 VND} are different records. Compare amounts with {@link BigDecimal#compareTo}
 * when scale does not matter. Inside rules, {@code ==} compares numeric value.
 *
 * @param amount   the amount, of any sign and scale
 * @param currency an ISO 4217 alphabetic code in upper case, e.g. {@code VND}
 */
public record Money(BigDecimal amount, String currency) {

    /**
     * Validates both components.
     *
     * @throws NullPointerException     if either component is null
     * @throws IllegalArgumentException if {@code currency} is not an upper-case ISO 4217 code
     */
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!isCurrencyCode(currency)) {
            // The text is not quoted: it may come from customer data.
            throw new IllegalArgumentException("currency must be an ISO 4217 code in upper case, e.g. VND");
        }
    }

    /**
     * Creates {@code Money.of(new BigDecimal("1500000"), "VND")}.
     *
     * @throws IllegalArgumentException if {@code currency} is not an upper-case ISO 4217 code
     */
    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    /**
     * Creates {@code Money.of("1500000.50", "VND")}, parsing the amount exactly.
     *
     * @throws NumberFormatException    if {@code amount} is not a valid {@link BigDecimal}
     * @throws IllegalArgumentException if {@code currency} is not an upper-case ISO 4217 code
     */
    public static Money of(String amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal value;
        try {
            value = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            // BigDecimal's own message may quote characters of the text.
            throw new NumberFormatException("amount is not a valid decimal number");
        }
        return new Money(value, currency);
    }

    /** The amount in plain notation followed by the currency code, e.g. {@code 1500000.50 VND}. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private static boolean isCurrencyCode(String code) {
        if (code.length() != 3 || !code.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
