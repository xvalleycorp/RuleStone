//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void printsPlainAmountAndCurrency() {
        assertThat(Money.of("1500000", "VND")).hasToString("1500000 VND");
        assertThat(Money.of("1500000.50", "VND")).hasToString("1500000.50 VND");
        assertThat(Money.of(new BigDecimal("1E+3"), "USD")).hasToString("1000 USD");
    }

    @Test
    void equalityIsScaleSensitiveLikeBigDecimal() {
        assertThat(Money.of("1.0", "USD")).isEqualTo(Money.of(new BigDecimal("1.0"), "USD"));
        assertThat(Money.of("1.0", "USD")).isNotEqualTo(Money.of("1.00", "USD"));
        assertThat(Money.of("1", "USD")).isNotEqualTo(Money.of("1", "EUR"));
    }

    @Test
    void requiresAnUpperCaseIsoCurrency() {
        assertThatThrownBy(() -> Money.of("1", "vnd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency must be an ISO 4217 code in upper case, e.g. VND");
        assertThatThrownBy(() -> Money.of("1", "ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1", "SECRET-CODE"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
        assertThatThrownBy(() -> Money.of((BigDecimal) null, "VND")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidAmountTextIsNotQuoted() {
        assertThatThrownBy(() -> Money.of("12x345", "VND"))
                .isInstanceOf(NumberFormatException.class)
                .hasMessage("amount is not a valid decimal number");
    }
}
