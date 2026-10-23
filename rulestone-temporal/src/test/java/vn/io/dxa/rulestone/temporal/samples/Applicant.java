//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import java.math.BigDecimal;

/** The applicant struct of {@code loan_eligibility}, as workflow data. */
public record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt, String segment,
        boolean blacklisted) {
}
