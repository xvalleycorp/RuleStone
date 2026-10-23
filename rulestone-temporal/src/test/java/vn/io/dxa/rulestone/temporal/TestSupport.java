//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.temporal.samples.Applicant;
import vn.io.dxa.rulestone.temporal.samples.LoanApplication;

/** Rules, engines and Temporal test environments shared by the tests. */
final class TestSupport {

    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** A value that must never appear in a failure message. */
    static final String SECRET_DEBT = "98765432.17";

    static final String CARD_FEE = """
            {
              "id": "card_fee",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "customer": { "birthDate": "date" }, "spend": "money", "rate": "decimal" },
              "outputs": { "fee": "money", "nextReview": "date", "tier": "string" },
              "rules": [
                { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
                  "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)",
                            "tier": "'YOUTH'" },
                  "reasonCode": "YOUTH_WAIVER" },
                { "id": "STANDARD", "when": "true",
                  "then": { "fee": "spend * rate", "nextReview": "businessDate.plusMonths(6)",
                            "tier": "'STANDARD'" } }
              ]
            }
            """;

    private TestSupport() {
    }

    static String exampleText(String file) {
        try {
            return Files.readString(Path.of("..", "examples", file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static DecisionTable loanV2() {
        return DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v2.stone.json"));
    }

    static DecisionTable loanV3() {
        return DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v3.stone.json"));
    }

    /** Version 3 with a changed rule but the same version label. */
    static DecisionTable loanV3Edited() {
        String text = exampleText("loan_eligibility.v3.stone.json");
        String edited = text.replace("decimal('0.5')", "decimal('0.6')");
        if (edited.equals(text)) {
            throw new IllegalStateException("the example changed; update loanV3Edited");
        }
        return DecisionTableJson.read(edited);
    }

    static DecisionTable cardFee() {
        return DecisionTableJson.read(CARD_FEE);
    }

    static List<DecisionTable> allTables() {
        return List.of(loanV2(), loanV3(), cardFee());
    }

    static RuleStoneEngine engine(List<DecisionTable> tables) {
        return RuleStone.builder().clock(Clock.system(HCM)).decisions(tables).build();
    }

    static Clock clockAt(String isoDate) {
        return Clock.fixed(LocalDate.parse(isoDate).atStartOfDay(HCM).plusHours(9).toInstant(), HCM);
    }

    static Applicant adult(String monthlyIncome, String monthlyDebt) {
        return new Applicant(30, new BigDecimal(monthlyIncome), new BigDecimal(monthlyDebt), "RETAIL", false);
    }

    static LoanApplication application(Applicant applicant, String amount, String applicationDate) {
        return new LoanApplication("APP-1", LocalDate.parse(applicationDate), applicant, new BigDecimal(amount));
    }

    /** A test environment whose workflow time starts at {@code initialTime}, with the search attributes. */
    static TestWorkflowEnvironment environment(String initialTime) {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setInitialTime(Instant.parse(initialTime))
                .build());
        env.registerSearchAttribute("RuleStoneDecision", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
        env.registerSearchAttribute("RuleStoneRuleVersion", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
        env.registerSearchAttribute("RuleStoneOutcome", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
        env.registerSearchAttribute("RuleStoneReasonCodes", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD_LIST);
        return env;
    }

    /** Every message and string form in a failure's cause chain, for checks on what it reveals. */
    static String failureText(Throwable failure) {
        List<String> parts = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            parts.add(String.valueOf(t));
            parts.add(String.valueOf(t.getMessage()));
        }
        return String.join("\n", parts);
    }

    /** The first throwable of type {@code type} in the cause chain, or null. */
    static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
