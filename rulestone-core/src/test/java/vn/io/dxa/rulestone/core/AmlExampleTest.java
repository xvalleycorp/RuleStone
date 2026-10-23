//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * Evaluates examples/aml_name_screening.stone.json, examples/aml_transaction_monitoring.stone.json and
 * examples/aml_alert_action.stone.json. The scenarios, scores and Java code quoted in docs/examples.md are
 * checked here; update both together. All names are invented.
 */
class AmlExampleTest {

    private final RuleStoneEngine engine = RuleStone.builder()
            .decision(RuleStoneEngineTest.load("aml_name_screening.stone.json"))
            .decision(RuleStoneEngineTest.load("aml_transaction_monitoring.stone.json"))
            .decision(RuleStoneEngineTest.load("aml_alert_action.stone.json"))
            .build();

    private static Money vnd(String amount) {
        return Money.of(amount, "VND");
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... overrides) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        for (int i = 0; i < overrides.length; i += 2) {
            copy.put((String) overrides[i], overrides[i + 1]);
        }
        return copy;
    }

    private static Map<String, Object> without(Map<String, Object> base, String field) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.remove(field);
        return copy;
    }

    // ============================================================================================
    // Name screening
    // ============================================================================================

    @Nested
    class NameScreening {

        record Customer(String fullName, List<String> aliases, LocalDate dateOfBirth, String nationality) {}

        record WatchlistEntry(String name, List<String> aliases, LocalDate dateOfBirth, String listType) {}

        /** The invented watchlist used in docs/examples.md. The company has no date of birth. */
        static final List<WatchlistEntry> WATCHLIST = List.of(
                new WatchlistEntry("Trần Thị Bích Ngọc", List.of("Jade Tran"), LocalDate.of(1975, 3, 14), "SANCTIONS"),
                new WatchlistEntry("Mekong Phantom Trading Co., Ltd", List.of("MPT Trading"), null, "SANCTIONS"),
                new WatchlistEntry("Phạm Văn Quyết", List.of(), LocalDate.of(1959, 7, 20), "PEP"));

        private DecisionResult screen(Object customer) {
            return engine.evaluate(DecisionRef.of("aml_name_screening"),
                    Facts.of("customer", customer, "watchlist", WATCHLIST));
        }

        private DecisionResult screen(String fullName, LocalDate dateOfBirth) {
            return screen(new Customer(fullName, List.of(), dateOfBirth, "VN"));
        }

        // --- Documented scenarios --------------------------------------------------------------

        @Test
        void sameNameWithoutDiacriticsAndSameBirthDateIsBlocked() {
            DecisionResult result = screen("TRAN THI BICH NGOC", LocalDate.of(1975, 3, 14));

            assertThat(result.output()).hasToString("{result=BLOCK, bestScore=1.0000}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_MATCH]");
            assertThat(result.matchedRules()).hasToString("[SANCTIONS_MATCH]");
        }

        @Test
        void reorderedNameWithAnotherBirthDateIsReviewed() {
            DecisionResult result = screen("Ngoc Tran Thi Bich", LocalDate.of(1990, 5, 1));

            assertThat(result.output()).hasToString("{result=REVIEW, bestScore=1.0000}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_POSSIBLE_MATCH]");
            assertThat(result.matchedRules()).hasToString("[SANCTIONS_POSSIBLE_MATCH]");
        }

        @Test
        void exactNameWithoutBirthDateIsReviewed() {
            DecisionResult result = screen("Trần Thị Bích Ngọc", null);

            assertThat(result.output()).hasToString("{result=REVIEW, bestScore=1.0000}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_POSSIBLE_MATCH]");
        }

        @Test
        void customerAliasMatchingListedAliasWithSameBirthDateIsBlocked() {
            DecisionResult result = screen(new Customer("Lê Thị Hoa", List.of("Jade Tran"), LocalDate.of(1975, 3, 14), "VN"));

            // bestScore compares the full name with the primary names only, so it stays low.
            assertThat(result.output()).hasToString("{result=BLOCK, bestScore=0.6206}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_MATCH]");
        }

        @Test
        void differentPersonSharingFamilyAndMiddleNameIsAFalsePositive() {
            DecisionResult result = screen("Tran Thi Bich Hong", LocalDate.of(1992, 6, 30));

            assertThat(result.output()).hasToString("{result=REVIEW, bestScore=0.9660}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_POSSIBLE_MATCH]");
        }

        @Test
        void shortNameWithSameFamilyAndMiddleNameIsAWeakMatch() {
            DecisionResult result = screen("Tran Thi Mai", LocalDate.of(1988, 1, 9));

            assertThat(result.output()).hasToString("{result=REVIEW, bestScore=0.8778}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_WEAK_MATCH]");
            assertThat(result.matchedRules()).hasToString("[SANCTIONS_WEAK_MATCH]");
        }

        @Test
        void companyWithoutBirthDateCanOnlyBeAPossibleMatch() {
            DecisionResult result = screen("Mekong Phantom Trading Company Limited", null);

            assertThat(result.output()).hasToString("{result=REVIEW, bestScore=0.9526}");
            assertThat(result.reasonCodes()).hasToString("[SANCTIONS_POSSIBLE_MATCH]");
        }

        @Test
        void politicallyExposedPersonNeedsEnhancedDueDiligence() {
            DecisionResult result = screen("Pham Van Quyet", LocalDate.of(1959, 7, 20));

            assertThat(result.output()).hasToString("{result=ENHANCED_DUE_DILIGENCE, bestScore=1.0000}");
            assertThat(result.reasonCodes()).hasToString("[PEP_MATCH]");
            assertThat(result.matchedRules()).hasToString("[PEP_MATCH]");
        }

        @Test
        void unrelatedNameIsClear() {
            DecisionResult result = screen("Lê Thị Hoa", LocalDate.of(1990, 2, 2));

            assertThat(result.output()).hasToString("{result=CLEAR, bestScore=0.6206}");
            assertThat(result.reasonCodes()).hasToString("[]");
            assertThat(result.matchedRules()).hasToString("[CLEAR]");
        }

        @Test
        void blankNameIsReviewedWithoutScore() {
            DecisionResult result = screen(" - ", LocalDate.of(1990, 2, 2));

            assertThat(result.output()).hasToString("{result=REVIEW}");
            assertThat(result.reasonCodes()).hasToString("[NAME_MISSING]");
            assertThat(result.matchedRules()).hasToString("[NAME_MISSING]");
        }

        // --- Rules and thresholds --------------------------------------------------------------

        @Test
        void missingNameIsReviewed() {
            DecisionResult result = screen(Map.of("aliases", List.of()));

            assertThat(result.reasonCodes()).containsExactly("NAME_MISSING");
        }

        @Test
        void listedAliasAsFullNameWithSameBirthDateIsBlocked() {
            DecisionResult result = screen("Jade Tran", LocalDate.of(1975, 3, 14));

            assertThat(result.output()).hasToString("{result=BLOCK, bestScore=0.6429}");
        }

        @Test
        void listedAliasWithoutBirthDateIsReviewed() {
            assertThat(screen("MPT Trading", null).reasonCodes()).containsExactly("SANCTIONS_POSSIBLE_MATCH");
        }

        @Test
        void highThresholdIsInclusive() {
            DecisionResult atThreshold = screen("Tram Van Bich Ngoc", null);
            assertThat(atThreshold.output()).hasToString("{result=REVIEW, bestScore=0.9200}");
            assertThat(atThreshold.reasonCodes()).containsExactly("SANCTIONS_POSSIBLE_MATCH");

            DecisionResult below = screen("Tran Kim Bich Ngoc", null);
            assertThat(below.output()).hasToString("{result=REVIEW, bestScore=0.9181}");
            assertThat(below.reasonCodes()).containsExactly("SANCTIONS_WEAK_MATCH");
        }

        @Test
        void strongMatchNeedsTheHighThresholdEvenWithTheSameBirthDate() {
            DecisionResult result = screen("Tran Kim Bich Ngoc", LocalDate.of(1975, 3, 14));

            assertThat(result.reasonCodes()).containsExactly("SANCTIONS_WEAK_MATCH");
        }

        @Test
        void weakThresholdIsInclusive() {
            DecisionResult atThreshold = screen("Bui Thi Bich Hoa", null);
            assertThat(atThreshold.output()).hasToString("{result=REVIEW, bestScore=0.8500}");
            assertThat(atThreshold.reasonCodes()).containsExactly("SANCTIONS_WEAK_MATCH");

            DecisionResult below = screen("Ngo Thi Binh", null);
            assertThat(below.output()).hasToString("{result=CLEAR, bestScore=0.8498}");
            assertThat(below.matchedRules()).containsExactly("CLEAR");
        }

        @Test
        void pepUsesTheHighThreshold() {
            DecisionResult variant = screen("Pham Van Quyen", null);
            assertThat(variant.output()).hasToString("{result=ENHANCED_DUE_DILIGENCE, bestScore=0.9714}");
        }

        @Test
        void weakSimilarityToAPepRaisesNoAlert() {
            // There is no weak band for PEPs: 0.8961 against a PEP is not an alert.
            DecisionResult result = screen("Pham Duc Quyet", null);

            assertThat(result.output()).hasToString("{result=CLEAR, bestScore=0.8961}");
            assertThat(result.matchedRules()).containsExactly("CLEAR");
        }

        @Test
        void sanctionsTakePriorityOverPep() {
            List<WatchlistEntry> both = List.of(
                    new WatchlistEntry("Phạm Văn Quyết", List.of(), null, "PEP"),
                    new WatchlistEntry("Pham Van Quyet", List.of(), null, "SANCTIONS"));

            DecisionResult result = engine.evaluate(DecisionRef.of("aml_name_screening"), Facts.of(
                    "customer", new Customer("Phạm Văn Quyết", List.of(), null, "VN"), "watchlist", both));

            assertThat(result.reasonCodes()).containsExactly("SANCTIONS_POSSIBLE_MATCH");
        }

        @Test
        void birthDateOfAPepEntryNeverBlocks() {
            DecisionResult result = screen("Phạm Văn Quyết", LocalDate.of(1959, 7, 20));

            assertThat(result.reasonCodes()).containsExactly("PEP_MATCH");
        }

        @Test
        void blankCustomerAliasDoesNotMatchBlankListedAlias() {
            List<WatchlistEntry> withBlankAlias = List.of(
                    new WatchlistEntry("Trần Thị Bích Ngọc", List.of(""), LocalDate.of(1975, 3, 14), "SANCTIONS"));

            DecisionResult result = engine.evaluate(DecisionRef.of("aml_name_screening"), Facts.of(
                    "customer", new Customer("Lê Thị Hoa", List.of(" "), LocalDate.of(1975, 3, 14), "VN"),
                    "watchlist", withBlankAlias));

            assertThat(result.matchedRules()).containsExactly("CLEAR");
        }

        @Test
        void emptyWatchlistIsClearWithScoreZero() {
            DecisionResult result = engine.evaluate(DecisionRef.of("aml_name_screening"), Facts.of(
                    "customer", new Customer("Trần Thị Bích Ngọc", List.of(), null, "VN"), "watchlist", List.of()));

            assertThat(result.output()).hasToString("{result=CLEAR, bestScore=0.0000}");
        }

        @Test
        void missingAliasesFailTheEvaluation() {
            assertThatThrownBy(() -> screen(Map.of("fullName", "Lê Thị Hoa")))
                    .isInstanceOf(DecisionEvaluationException.class)
                    .hasMessageContaining("aliases")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Hoa"));
        }

        @Test
        void scoreIsADecimalWithFourPlaces() {
            Object score = screen("Tran Thi Mai", null).output().get("bestScore");

            assertThat(score).isEqualTo(new BigDecimal("0.8778"));
        }
    }

    // ============================================================================================
    // Transaction monitoring and alert action
    // ============================================================================================

    record Account(LocalDate openedOn, LocalDate lastActivityBeforeToday, String riskRating, boolean customerIsPep) {}

    record Transaction(Money amount, String type, String counterpartyCountry, LocalDate valueDate) {}

    record History(List<Money> cashDepositsLast7d, Money totalInLast24h, Money totalOutLast24h) {}

    record Assessment(DecisionResult alerts, long riskScore, boolean hasLargeCash, DecisionResult action) {}

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 2);

    /** The application glue shown in docs/examples.md. */
    private Assessment assess(LocalDate businessDate, Account account, Transaction transaction, History history) {
        DecisionResult alerts = engine.evaluate(DecisionRef.of("aml_transaction_monitoring").asOf(businessDate),
                Facts.of("account", account, "transaction", transaction, "history", history));

        long riskScore = ((List<?>) alerts.output().get("score")).stream()
                .mapToLong(score -> (Long) score)
                .sum();
        boolean hasLargeCash = ((List<?>) alerts.output().get("alert")).contains("LARGE_CASH");

        DecisionResult action = engine.evaluate(DecisionRef.of("aml_alert_action").asOf(businessDate),
                Facts.of("riskScore", riskScore, "hasLargeCash", hasLargeCash));

        return new Assessment(alerts, riskScore, hasLargeCash, action);
    }

    private static final Account ESTABLISHED = new Account(LocalDate.of(2019, 4, 10), LocalDate.of(2026, 10, 30), "LOW", false);

    // --- Documented scenarios ------------------------------------------------------------------

    @Test
    void salaryTransferRaisesNoAlert() {
        Assessment a = assess(BUSINESS_DATE, ESTABLISHED,
                new Transaction(vnd("15000000"), "TRANSFER_IN", "VN", BUSINESS_DATE),
                new History(List.of(), vnd("15000000"), vnd("2000000")));

        assertThat(a.alerts().output()).hasToString("{alert=[], score=[]}");
        assertThat(a.alerts().matched()).isFalse();
        assertThat(a.riskScore()).isZero();
        assertThat(a.action().output()).hasToString("{action=NO_ACTION, fileCtr=false}");
        assertThat(a.action().reasonCodes()).hasToString("[]");
        assertThat(a.action().matchedRules()).hasToString("[NO_ACTION]");
    }

    @Test
    void largeCashDepositIsReportedWithoutSuspicion() {
        Assessment a = assess(BUSINESS_DATE, ESTABLISHED,
                new Transaction(vnd("350000000"), "CASH_DEPOSIT", null, BUSINESS_DATE),
                new History(List.of(vnd("350000000")), vnd("350000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString("{alert=[LARGE_CASH], score=[10]}");
        assertThat(a.alerts().reasonCodes()).hasToString("[LARGE_CASH_TRANSACTION]");
        assertThat(a.riskScore()).isEqualTo(10);
        assertThat(a.hasLargeCash()).isTrue();
        assertThat(a.action().output()).hasToString("{action=FILE_CTR_ONLY, fileCtr=true}");
        assertThat(a.action().reasonCodes()).hasToString("[LARGE_CASH_REPORTABLE]");
        assertThat(a.action().matchedRules()).hasToString("[FILE_CTR_ONLY]");
    }

    @Test
    void depositsJustBelowTheThresholdAreReviewedAsStructuring() {
        Assessment a = assess(BUSINESS_DATE, ESTABLISHED,
                new Transaction(vnd("290000000"), "CASH_DEPOSIT", null, BUSINESS_DATE),
                new History(List.of(vnd("250000000"), vnd("270000000"), vnd("290000000")), vnd("290000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString("{alert=[STRUCTURING], score=[50]}");
        assertThat(a.alerts().reasonCodes()).hasToString("[POSSIBLE_STRUCTURING]");
        assertThat(a.riskScore()).isEqualTo(50);
        assertThat(a.action().output()).hasToString("{action=ANALYST_REVIEW, fileCtr=false}");
        assertThat(a.action().reasonCodes()).hasToString("[SUSPICION_SCORE_ELEVATED]");
        assertThat(a.action().matchedRules()).hasToString("[ANALYST_REVIEW]");
    }

    @Test
    void passThroughToHighRiskCountryLeadsToASuspiciousTransactionReport() {
        Assessment a = assess(BUSINESS_DATE, ESTABLISHED,
                new Transaction(vnd("950000000"), "TRANSFER_OUT", "XB", BUSINESS_DATE),
                new History(List.of(), vnd("1000000000"), vnd("950000000")));

        assertThat(a.alerts().output()).hasToString("{alert=[RAPID_IN_OUT, HIGH_RISK_COUNTRY], score=[40, 30]}");
        assertThat(a.alerts().reasonCodes()).hasToString("[RAPID_MOVEMENT_OF_FUNDS, HIGH_RISK_JURISDICTION]");
        assertThat(a.riskScore()).isEqualTo(70);
        assertThat(a.action().output()).hasToString("{action=FILE_STR, fileCtr=false}");
        assertThat(a.action().reasonCodes()).hasToString("[SUSPICION_SCORE_HIGH]");
        assertThat(a.action().matchedRules()).hasToString("[FILE_STR]");
    }

    @Test
    void dormantAccountReceivingLargeTransferIsReviewed() {
        Assessment a = assess(BUSINESS_DATE,
                new Account(LocalDate.of(2018, 6, 1), LocalDate.of(2025, 9, 1), "LOW", false),
                new Transaction(vnd("180000000"), "TRANSFER_IN", "VN", BUSINESS_DATE),
                new History(List.of(), vnd("180000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString("{alert=[DORMANT_ACCOUNT], score=[30]}");
        assertThat(a.alerts().reasonCodes()).hasToString("[DORMANT_ACCOUNT_REACTIVATED]");
        assertThat(a.riskScore()).isEqualTo(30);
        assertThat(a.action().output()).hasToString("{action=ANALYST_REVIEW, fileCtr=false}");
    }

    @Test
    void newAccountReceivingLargeTransferFromHighRiskCountryIsReviewed() {
        Assessment a = assess(BUSINESS_DATE,
                new Account(LocalDate.of(2026, 10, 20), null, "MEDIUM", false),
                new Transaction(vnd("250000000"), "TRANSFER_IN", "XA", BUSINESS_DATE),
                new History(List.of(), vnd("250000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString(
                "{alert=[HIGH_RISK_COUNTRY, NEW_ACCOUNT_LARGE_TRANSFER], score=[30, 25]}");
        assertThat(a.alerts().reasonCodes()).hasToString("[HIGH_RISK_JURISDICTION, NEW_ACCOUNT_LARGE_TRANSFER]");
        assertThat(a.riskScore()).isEqualTo(55);
        assertThat(a.action().output()).hasToString("{action=ANALYST_REVIEW, fileCtr=false}");
    }

    @Test
    void largeCashDepositByPepIsReviewedAndReported() {
        Assessment a = assess(BUSINESS_DATE,
                new Account(LocalDate.of(2015, 2, 3), LocalDate.of(2026, 10, 28), "HIGH", true),
                new Transaction(vnd("600000000"), "CASH_DEPOSIT", null, BUSINESS_DATE),
                new History(List.of(vnd("600000000")), vnd("600000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString("{alert=[LARGE_CASH, PEP_LARGE_TRANSACTION], score=[10, 20]}");
        assertThat(a.riskScore()).isEqualTo(30);
        assertThat(a.hasLargeCash()).isTrue();
        assertThat(a.action().output()).hasToString("{action=ANALYST_REVIEW, fileCtr=true}");
        assertThat(a.action().reasonCodes()).hasToString("[SUSPICION_SCORE_ELEVATED]");
    }

    @Test
    void pepStructuringThenLargeCashLeadsToBothReports() {
        Assessment a = assess(BUSINESS_DATE,
                new Account(LocalDate.of(2015, 2, 3), LocalDate.of(2026, 11, 1), "HIGH", true),
                new Transaction(vnd("520000000"), "CASH_DEPOSIT", null, BUSINESS_DATE),
                new History(List.of(vnd("245000000"), vnd("255000000"), vnd("265000000"), vnd("520000000")),
                        vnd("520000000"), vnd("0")));

        assertThat(a.alerts().output()).hasToString(
                "{alert=[LARGE_CASH, STRUCTURING, PEP_LARGE_TRANSACTION], score=[10, 50, 20]}");
        assertThat(a.riskScore()).isEqualTo(80);
        assertThat(a.action().output()).hasToString("{action=FILE_STR, fileCtr=true}");
        assertThat(a.action().reasonCodes()).hasToString("[SUSPICION_SCORE_HIGH]");
    }

    // --- Alerts, one rule at a time ------------------------------------------------------------
    //
    // The baseline is a domestic incoming transfer of 10,000,000 VND on an established, low-risk
    // account; it raises no alert. Each test changes one or two fields.

    private static final Map<String, Object> ACCOUNT = Map.of(
            "openedOn", LocalDate.of(2020, 1, 15), "lastActivityBeforeToday", LocalDate.of(2026, 10, 30),
            "riskRating", "LOW", "customerIsPep", false);

    private static final Map<String, Object> TRANSACTION = Map.of(
            "amount", vnd("10000000"), "type", "TRANSFER_IN", "counterpartyCountry", "VN", "valueDate", BUSINESS_DATE);

    private static final Map<String, Object> HISTORY = Map.of(
            "cashDepositsLast7d", List.of(), "totalInLast24h", vnd("10000000"), "totalOutLast24h", vnd("0"));

    private DecisionResult alerts(Map<String, Object> account, Map<String, Object> transaction,
                                  Map<String, Object> history) {
        return engine.evaluate(DecisionRef.of("aml_transaction_monitoring").asOf(BUSINESS_DATE),
                Facts.of("account", account, "transaction", transaction, "history", history));
    }

    private List<String> alertsFor(Map<String, Object> account, Map<String, Object> transaction,
                                   Map<String, Object> history) {
        return alerts(account, transaction, history).matchedRules();
    }

    private static Map<String, Object> cash(String amount) {
        return with(without(TRANSACTION, "counterpartyCountry"), "type", "CASH_DEPOSIT", "amount", vnd(amount));
    }

    @Test
    void baselineRaisesNoAlert() {
        DecisionResult result = alerts(ACCOUNT, TRANSACTION, HISTORY);

        assertThat(result.matched()).isFalse();
        assertThat(result.trace()).hasSize(7);
    }

    @Test
    void largeCashFromTheReportingThreshold() {
        assertThat(alertsFor(ACCOUNT, cash("299999999"), HISTORY)).isEmpty();
        assertThat(alertsFor(ACCOUNT, cash("300000000"), HISTORY)).containsExactly("LARGE_CASH");
        assertThat(alertsFor(ACCOUNT, with(cash("300000000"), "type", "CASH_WITHDRAWAL"), HISTORY))
                .containsExactly("LARGE_CASH");
    }

    @Test
    void largeCashIgnoresTransfers() {
        assertThat(alertsFor(ACCOUNT, with(TRANSACTION, "amount", vnd("300000000")), HISTORY)).isEmpty();
    }

    @Test
    void structuringNeedsThreeDepositsJustBelowTheThreshold() {
        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(HISTORY, "cashDepositsLast7d",
                List.of(vnd("240000000"), vnd("299999999"), vnd("260000000"))))).containsExactly("STRUCTURING");
        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(HISTORY, "cashDepositsLast7d",
                List.of(vnd("240000000"), vnd("299999999"))))).isEmpty();
    }

    @Test
    void structuringBandIsFrom80PercentToJustBelowTheThreshold() {
        // 239,999,999 is below 80% and 300,000,000 is a large cash deposit, not structuring.
        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(HISTORY, "cashDepositsLast7d",
                List.of(vnd("239999999"), vnd("250000000"), vnd("260000000"))))).isEmpty();
        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(HISTORY, "cashDepositsLast7d",
                List.of(vnd("300000000"), vnd("250000000"), vnd("260000000"))))).isEmpty();
    }

    @Test
    void rapidInOutFromNinetyPercentOfTheInflow() {
        Map<String, Object> inflow = with(HISTORY, "totalInLast24h", vnd("500000000"));

        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(inflow, "totalOutLast24h", vnd("450000000"))))
                .containsExactly("RAPID_IN_OUT");
        assertThat(alertsFor(ACCOUNT, TRANSACTION, with(inflow, "totalOutLast24h", vnd("449999999")))).isEmpty();
    }

    @Test
    void rapidInOutNeedsTheMinimumInflow() {
        Map<String, Object> below = with(HISTORY, "totalInLast24h", vnd("499999999"), "totalOutLast24h", vnd("499999999"));

        assertThat(alertsFor(ACCOUNT, TRANSACTION, below)).isEmpty();
    }

    @Test
    void rapidInOutMinimumIsLowerForHighRiskCustomers() {
        Map<String, Object> highRisk = with(ACCOUNT, "riskRating", "HIGH");

        assertThat(alertsFor(highRisk, TRANSACTION,
                with(HISTORY, "totalInLast24h", vnd("100000000"), "totalOutLast24h", vnd("90000000"))))
                .containsExactly("RAPID_IN_OUT");
        assertThat(alertsFor(highRisk, TRANSACTION,
                with(HISTORY, "totalInLast24h", vnd("99999999"), "totalOutLast24h", vnd("99999999")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XA", "XB", "XC"})
    void highRiskCountries(String country) {
        DecisionResult result = alerts(ACCOUNT, with(TRANSACTION, "counterpartyCountry", country), HISTORY);

        assertThat(result.output()).hasToString("{alert=[HIGH_RISK_COUNTRY], score=[30]}");
    }

    @Test
    void otherOrMissingCountryIsNotHighRisk() {
        assertThat(alertsFor(ACCOUNT, with(TRANSACTION, "counterpartyCountry", "SG"), HISTORY)).isEmpty();
        assertThat(alertsFor(ACCOUNT, without(TRANSACTION, "counterpartyCountry"), HISTORY)).isEmpty();
    }

    @Test
    void dormantFrom365DaysWithoutActivity() {
        Map<String, Object> inflow = with(TRANSACTION, "amount", vnd("100000000"));

        assertThat(alertsFor(with(ACCOUNT, "lastActivityBeforeToday", LocalDate.of(2025, 11, 2)), inflow, HISTORY))
                .containsExactly("DORMANT_REACTIVATED");
        assertThat(alertsFor(with(ACCOUNT, "lastActivityBeforeToday", LocalDate.of(2025, 11, 3)), inflow, HISTORY))
                .isEmpty();
    }

    @Test
    void dormantNeedsALargeInflow() {
        Map<String, Object> dormant = with(ACCOUNT, "lastActivityBeforeToday", LocalDate.of(2024, 1, 1));

        assertThat(alertsFor(dormant, with(TRANSACTION, "amount", vnd("99999999")), HISTORY)).isEmpty();
        assertThat(alertsFor(dormant, cash("100000000"), HISTORY)).containsExactly("DORMANT_REACTIVATED");
        assertThat(alertsFor(dormant, with(TRANSACTION, "amount", vnd("100000000"), "type", "TRANSFER_OUT"), HISTORY))
                .isEmpty();
    }

    @Test
    void accountNeverActiveIsDormantFromItsOpeningDate() {
        Map<String, Object> inflow = with(TRANSACTION, "amount", vnd("100000000"));

        assertThat(alertsFor(with(without(ACCOUNT, "lastActivityBeforeToday"), "openedOn", LocalDate.of(2025, 11, 2)),
                inflow, HISTORY)).containsExactly("DORMANT_REACTIVATED");
        assertThat(alertsFor(with(without(ACCOUNT, "lastActivityBeforeToday"), "openedOn", LocalDate.of(2025, 11, 3)),
                inflow, HISTORY)).isEmpty();
    }

    @Test
    void dormancyIsMeasuredToTheBusinessDate() {
        Map<String, Object> account = with(ACCOUNT, "lastActivityBeforeToday", LocalDate.of(2025, 11, 3));
        Map<String, Object> inflow = with(TRANSACTION, "amount", vnd("100000000"));

        DecisionResult nextDay = engine.evaluate(
                DecisionRef.of("aml_transaction_monitoring").asOf(BUSINESS_DATE.plusDays(1)),
                Facts.of("account", account, "transaction", inflow, "history", HISTORY));

        assertThat(nextDay.matchedRules()).containsExactly("DORMANT_REACTIVATED");
    }

    @Test
    void newAccountIsYoungerThanThirtyDaysOnTheValueDate() {
        Map<String, Object> transfer = with(TRANSACTION, "amount", vnd("200000000"));

        assertThat(alertsFor(with(ACCOUNT, "openedOn", LocalDate.of(2026, 10, 4)), transfer, HISTORY))
                .containsExactly("NEW_ACCOUNT_LARGE_TRANSFER");
        assertThat(alertsFor(with(ACCOUNT, "openedOn", LocalDate.of(2026, 10, 3)), transfer, HISTORY)).isEmpty();
    }

    @Test
    void newAccountNeedsALargeTransfer() {
        Map<String, Object> newAccount = with(ACCOUNT, "openedOn", LocalDate.of(2026, 10, 25));

        assertThat(alertsFor(newAccount, with(TRANSACTION, "amount", vnd("199999999")), HISTORY)).isEmpty();
        assertThat(alertsFor(newAccount, with(TRANSACTION, "amount", vnd("200000000"), "type", "TRANSFER_OUT"), HISTORY))
                .containsExactly("NEW_ACCOUNT_LARGE_TRANSFER");
        assertThat(alertsFor(newAccount, cash("200000000"), HISTORY)).isEmpty();
    }

    @Test
    void pepFromFiveHundredMillion() {
        Map<String, Object> pep = with(ACCOUNT, "customerIsPep", true);

        assertThat(alertsFor(pep, with(TRANSACTION, "amount", vnd("499999999")), HISTORY)).isEmpty();
        assertThat(alertsFor(pep, with(TRANSACTION, "amount", vnd("500000000")), HISTORY))
                .containsExactly("PEP_LARGE_TRANSACTION");
        assertThat(alertsFor(ACCOUNT, with(TRANSACTION, "amount", vnd("500000000")), HISTORY)).isEmpty();
    }

    @Test
    void everyMatchingAlertContributesInRuleOrder() {
        DecisionResult result = alerts(
                with(ACCOUNT, "openedOn", LocalDate.of(2025, 10, 1), "lastActivityBeforeToday", LocalDate.of(2025, 10, 1),
                        "riskRating", "HIGH", "customerIsPep", true),
                with(TRANSACTION, "type", "CASH_DEPOSIT", "amount", vnd("700000000"), "counterpartyCountry", "XC"),
                Map.of("cashDepositsLast7d", List.of(vnd("250000000"), vnd("260000000"), vnd("270000000")),
                        "totalInLast24h", vnd("700000000"), "totalOutLast24h", vnd("700000000")));

        assertThat(result.output()).hasToString("{alert=[LARGE_CASH, STRUCTURING, RAPID_IN_OUT, HIGH_RISK_COUNTRY, "
                + "DORMANT_ACCOUNT, PEP_LARGE_TRANSACTION], score=[10, 50, 40, 30, 30, 20]}");
        assertThat(result.reasonCodes()).containsExactly("LARGE_CASH_TRANSACTION", "POSSIBLE_STRUCTURING",
                "RAPID_MOVEMENT_OF_FUNDS", "HIGH_RISK_JURISDICTION", "DORMANT_ACCOUNT_REACTIVATED",
                "PEP_LARGE_TRANSACTION");
    }

    @Test
    void otherCurrenciesFailTheEvaluation() {
        Map<String, Object> usd = with(cash("300000000"), "amount", Money.of("20000", "USD"));

        assertThatThrownBy(() -> alerts(ACCOUNT, usd, HISTORY))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("money currencies differ")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("20000"));
    }

    // --- Action ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "score {0}, large cash {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            200 | false | FILE_STR       | false | FILE_STR       | [SUSPICION_SCORE_HIGH]
             70 | true  | FILE_STR       | true  | FILE_STR       | [SUSPICION_SCORE_HIGH]
             70 | false | FILE_STR       | false | FILE_STR       | [SUSPICION_SCORE_HIGH]
             69 | false | ANALYST_REVIEW | false | ANALYST_REVIEW | [SUSPICION_SCORE_ELEVATED]
             30 | true  | ANALYST_REVIEW | true  | ANALYST_REVIEW | [SUSPICION_SCORE_ELEVATED]
             30 | false | ANALYST_REVIEW | false | ANALYST_REVIEW | [SUSPICION_SCORE_ELEVATED]
             29 | true  | FILE_CTR_ONLY  | true  | FILE_CTR_ONLY  | [LARGE_CASH_REPORTABLE]
             10 | true  | FILE_CTR_ONLY  | true  | FILE_CTR_ONLY  | [LARGE_CASH_REPORTABLE]
             29 | false | NO_ACTION      | false | NO_ACTION      | []
              0 | false | NO_ACTION      | false | NO_ACTION      | []
            """)
    void actionFromRiskScore(long riskScore, boolean hasLargeCash, String action, boolean fileCtr, String rule,
                             String reasonCodes) {
        DecisionResult result = engine.evaluate(DecisionRef.of("aml_alert_action"),
                Facts.of("riskScore", riskScore, "hasLargeCash", hasLargeCash));

        assertThat(result.output()).hasToString("{action=" + action + ", fileCtr=" + fileCtr + "}");
        assertThat(result.matchedRules()).containsExactly(rule);
        assertThat(result.reasonCodes()).hasToString(reasonCodes);
    }
}
