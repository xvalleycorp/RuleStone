//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FactsTest {

    @Test
    void bindsNamedValues() {
        Facts facts = Facts.of("age", 30, "amount", new BigDecimal("500000000"));

        assertThat(facts.get("age")).contains(30);
        assertThat(facts.get("amount")).contains(new BigDecimal("500000000"));
        assertThat(facts.get("missing")).isEmpty();
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> Facts.of("age", 30, "age", 31))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("age");
    }

    @Test
    void isImmutable() {
        Facts facts = Facts.of("age", 30);

        assertThatThrownBy(() -> facts.asMap().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toStringDoesNotLeakValues() {
        Facts facts = Facts.of("idNumber", "001099012345");

        assertThat(facts.toString()).contains("idNumber").doesNotContain("001099012345");
    }
}
