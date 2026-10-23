//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class Iso4217Test {

    @Test
    void knowsCurrentCodesAndTheirMinorUnits() {
        assertThat(Iso4217.minorUnits("VND")).isEqualTo(OptionalInt.of(0));
        assertThat(Iso4217.minorUnits("JPY")).isEqualTo(OptionalInt.of(0));
        assertThat(Iso4217.minorUnits("USD")).isEqualTo(OptionalInt.of(2));
        assertThat(Iso4217.minorUnits("KWD")).isEqualTo(OptionalInt.of(3));
        assertThat(Iso4217.minorUnits("CLF")).isEqualTo(OptionalInt.of(4));
        assertThat(Iso4217.minorUnits("XAU")).isEmpty();
        assertThat(Iso4217.minorUnits("XDR")).isEmpty();
    }

    @Test
    void isTheSameOnEveryJdk() {
        // XCG (amendment 176) is unknown to java.util.Currency before JDK 22 and ZWG
        // (amendment 177) before JDK 23; RuleStone accepts both on every JDK.
        assertThat(Iso4217.isCurrencyCode("XCG")).isTrue();
        assertThat(Iso4217.minorUnits("XCG")).isEqualTo(OptionalInt.of(2));
        assertThat(Iso4217.isCurrencyCode("ZWG")).isTrue();
        assertThat(Iso4217.minorUnits("ZWG")).isEqualTo(OptionalInt.of(2));
        assertThat(Money.of("1", "XCG")).hasToString("1 XCG");
        // Withdrawn codes that java.util.Currency has long accepted stay valid.
        assertThat(Iso4217.minorUnits("DEM")).isEqualTo(OptionalInt.of(2));
        assertThat(Iso4217.table()).hasSize(232).containsEntry("VND", 0).containsEntry("XAU", -1);
        assertThat(Iso4217.AMENDMENT).isEqualTo(177);
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(Iso4217.isCurrencyCode("ZZZ")).isFalse();
        assertThat(Iso4217.isCurrencyCode("vnd")).isFalse();
        assertThat(Iso4217.isCurrencyCode("VND ")).isFalse();
        assertThat(Iso4217.isCurrencyCode("")).isFalse();
        assertThat(Iso4217.isCurrencyCode(null)).isFalse();
        assertThatThrownBy(() -> Iso4217.minorUnits("SECRET"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    }
}
