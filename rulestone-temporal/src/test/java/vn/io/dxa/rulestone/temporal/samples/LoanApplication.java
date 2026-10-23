//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Workflow input: a loan application. Typed {@code BigDecimal} fields keep their exact value in payloads. */
public record LoanApplication(String applicationId, LocalDate applicationDate, Applicant applicant,
        BigDecimal amount) {
}
