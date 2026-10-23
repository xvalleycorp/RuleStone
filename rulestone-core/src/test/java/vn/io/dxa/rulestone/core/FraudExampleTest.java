//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * Evaluates examples/card_fraud_signals.stone.json and examples/card_fraud_action.stone.json.
 * The scenarios and the Java code quoted in docs/examples.md are checked here; update both together.
 */
class FraudExampleTest {

    record CardTransaction(BigDecimal amount, String currency, String merchantCategory, String country,
                           String channel, int hourOfDay, boolean cardPresent) {}

    record CardProfile(String homeCountry, BigDecimal averageAmount30d, int transactionsLastHour,
                       int distinctCountriesLast24h, boolean newDevice, int accountAgeDays) {}

    record Assessment(DecisionResult signals, long riskScore, DecisionResult action) {}

    private final RuleStoneEngine engine = RuleStone.builder()
            .decision(RuleStoneEngineTest.load("card_fraud_signals.stone.json"))
            .decision(RuleStoneEngineTest.load("card_fraud_action.stone.json"))
            .build();

    /** The application glue shown in docs/examples.md. */
    private Assessment assess(CardTransaction transaction, CardProfile profile) {
        DecisionResult signals = engine.evaluate(DecisionRef.of("card_fraud_signals"),
                Facts.of("transaction", transaction, "profile", profile));

        long riskScore = ((List<?>) signals.output().get("points")).stream()
                .mapToLong(points -> (Long) points)
                .sum();

        DecisionResult action = engine.evaluate(DecisionRef.of("card_fraud_action"),
                Facts.of("riskScore", riskScore, "transaction", transaction));

        return new Assessment(signals, riskScore, action);
    }

    // --- Documented scenarios ------------------------------------------------------------------

    @Test
    void everydayGroceryPurchaseIsApproved() {
        Assessment a = assess(
                new CardTransaction(new BigDecimal("450000"), "VND", "5411", "VN", "POS", 18, true),
                new CardProfile("VN", new BigDecimal("600000"), 1, 1, false, 800));

        assertThat(a.signals().output()).hasToString("{signal=[], points=[]}");
        assertThat(a.signals().matched()).isFalse();
        assertThat(a.riskScore()).isZero();
        assertThat(a.action().output()).hasToString("{action=APPROVE}");
        assertThat(a.action().reasonCodes()).hasToString("[]");
        assertThat(a.action().matchedRules()).hasToString("[APPROVE]");
    }

    @Test
    void foreignOnlinePurchaseFromNewDeviceIsChallenged() {
        Assessment a = assess(
                new CardTransaction(new BigDecimal("6000000"), "USD", "5732", "US", "ECOM", 23, false),
                new CardProfile("VN", new BigDecimal("1000000"), 1, 2, true, 400));

        assertThat(a.signals().output()).hasToString(
                "{signal=[AMOUNT_SPIKE, FOREIGN_COUNTRY, NEW_DEVICE_ECOM, FOREIGN_CURRENCY], points=[30, 10, 20, 5]}");
        assertThat(a.signals().reasonCodes()).hasToString(
                "[AMOUNT_SPIKE, FOREIGN_COUNTRY, NEW_DEVICE_ECOM, FOREIGN_CURRENCY]");
        assertThat(a.riskScore()).isEqualTo(65);
        assertThat(a.action().output()).hasToString("{action=CHALLENGE}");
        assertThat(a.action().reasonCodes()).hasToString("[RISK_SCORE_HIGH]");
        assertThat(a.action().matchedRules()).hasToString("[CHALLENGE_HIGH]");
    }

    @Test
    void thirdCountryInADayAtPosIsDeclinedBecauseItCannotBeChallenged() {
        Assessment a = assess(
                new CardTransaction(new BigDecimal("2000000"), "THB", "5812", "TH", "POS", 14, true),
                new CardProfile("VN", new BigDecimal("800000"), 2, 3, false, 900));

        assertThat(a.signals().output()).hasToString(
                "{signal=[FOREIGN_COUNTRY, MULTI_COUNTRY_24H, FOREIGN_CURRENCY], points=[10, 35, 5]}");
        assertThat(a.riskScore()).isEqualTo(50);
        assertThat(a.action().output()).hasToString("{action=DECLINE}");
        assertThat(a.action().reasonCodes()).hasToString("[STEP_UP_UNAVAILABLE]");
        assertThat(a.action().matchedRules()).hasToString("[DECLINE_NO_STEP_UP]");
    }

    @Test
    void largeOnlineMoneyTransferWithSomeRiskIsChallenged() {
        Assessment a = assess(
                new CardTransaction(new BigDecimal("25000000"), "VND", "4829", "VN", "ECOM", 10, false),
                new CardProfile("VN", new BigDecimal("8000000"), 0, 1, false, 600));

        assertThat(a.signals().output()).hasToString("{signal=[HIGH_RISK_MCC], points=[20]}");
        assertThat(a.riskScore()).isEqualTo(20);
        assertThat(a.action().output()).hasToString("{action=CHALLENGE}");
        assertThat(a.action().reasonCodes()).hasToString("[LARGE_AMOUNT_WITH_RISK]");
        assertThat(a.action().matchedRules()).hasToString("[CHALLENGE_LARGE_AMOUNT]");
    }

    @Test
    void nightCryptoPurchaseFromNewDeviceIsDeclined() {
        Assessment a = assess(
                new CardTransaction(new BigDecimal("15000000"), "VND", "6051", "VN", "ECOM", 2, false),
                new CardProfile("VN", new BigDecimal("1200000"), 2, 1, true, 400));

        assertThat(a.signals().output()).hasToString(
                "{signal=[AMOUNT_SPIKE, NIGHT_ECOM, HIGH_RISK_MCC, NEW_DEVICE_ECOM], points=[30, 15, 20, 20]}");
        assertThat(a.riskScore()).isEqualTo(85);
        assertThat(a.action().output()).hasToString("{action=DECLINE}");
        assertThat(a.action().reasonCodes()).hasToString("[RISK_SCORE_VERY_HIGH]");
        assertThat(a.action().matchedRules()).hasToString("[DECLINE_VERY_HIGH]");
    }

    // --- Signals, one rule at a time -----------------------------------------------------------
    //
    // The baseline is a domestic, card-present grocery purchase by an established cardholder;
    // it raises no signal. Each test changes one or two fields.

    private static final Map<String, Object> TRANSACTION = Map.of(
            "amount", new BigDecimal("1000000"), "currency", "VND", "merchantCategory", "5411",
            "country", "VN", "channel", "POS", "hourOfDay", 14, "cardPresent", true);

    private static final Map<String, Object> PROFILE = Map.of(
            "homeCountry", "VN", "averageAmount30d", new BigDecimal("1000000"), "transactionsLastHour", 0,
            "distinctCountriesLast24h", 1, "newDevice", false, "accountAgeDays", 365);

    private static Map<String, Object> with(Map<String, Object> base, Object... overrides) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        for (int i = 0; i < overrides.length; i += 2) {
            copy.put((String) overrides[i], overrides[i + 1]);
        }
        return copy;
    }

    private DecisionResult signals(Map<String, Object> transaction, Map<String, Object> profile) {
        return engine.evaluate(DecisionRef.of("card_fraud_signals"),
                Facts.of("transaction", transaction, "profile", profile));
    }

    private List<String> signalsFor(Map<String, Object> transaction, Map<String, Object> profile) {
        return signals(transaction, profile).matchedRules();
    }

    @Test
    void baselineRaisesNoSignal() {
        DecisionResult result = signals(TRANSACTION, PROFILE);

        assertThat(result.matched()).isFalse();
        assertThat(result.output()).hasToString("{signal=[], points=[]}");
        assertThat(result.trace()).hasSize(10);
    }

    @Test
    void amountSpikeIsStrictlyAboveFiveTimesTheAverage() {
        assertThat(signalsFor(with(TRANSACTION, "amount", new BigDecimal("5000000")), PROFILE)).isEmpty();
        assertThat(signalsFor(with(TRANSACTION, "amount", new BigDecimal("5000000.01")), PROFILE))
                .containsExactly("AMOUNT_SPIKE");
    }

    @Test
    void amountSpikeNeedsHistory() {
        assertThat(signalsFor(with(TRANSACTION, "amount", new BigDecimal("50000000")),
                with(PROFILE, "averageAmount30d", BigDecimal.ZERO))).isEmpty();
    }

    @Test
    void highVelocityFromFiveTransactionsInTheLastHour() {
        assertThat(signalsFor(TRANSACTION, with(PROFILE, "transactionsLastHour", 4))).isEmpty();
        DecisionResult result = signals(TRANSACTION, with(PROFILE, "transactionsLastHour", 5));
        assertThat(result.output()).hasToString("{signal=[HIGH_VELOCITY], points=[25]}");
        assertThat(result.reasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void foreignCountryComparesWithHomeCountry() {
        assertThat(signalsFor(with(TRANSACTION, "country", "SG"), PROFILE)).containsExactly("FOREIGN_COUNTRY");
        assertThat(signalsFor(with(TRANSACTION, "country", "SG"), with(PROFILE, "homeCountry", "SG"))).isEmpty();
    }

    @Test
    void multiCountryFromThreeCountriesInADay() {
        assertThat(signalsFor(TRANSACTION, with(PROFILE, "distinctCountriesLast24h", 2))).isEmpty();
        assertThat(signalsFor(TRANSACTION, with(PROFILE, "distinctCountriesLast24h", 3)))
                .containsExactly("MULTI_COUNTRY_24H");
    }

    @Test
    void nightEcomIsBeforeFiveInTheMorningOnline() {
        Map<String, Object> online = with(TRANSACTION, "channel", "ECOM", "cardPresent", false);

        assertThat(signalsFor(with(online, "hourOfDay", 0), PROFILE)).containsExactly("NIGHT_ECOM");
        assertThat(signalsFor(with(online, "hourOfDay", 4), PROFILE)).containsExactly("NIGHT_ECOM");
        assertThat(signalsFor(with(online, "hourOfDay", 5), PROFILE)).isEmpty();
        assertThat(signalsFor(with(TRANSACTION, "hourOfDay", 2), PROFILE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7995", "4829", "6051"})
    void highRiskMerchantCategories(String mcc) {
        DecisionResult result = signals(with(TRANSACTION, "merchantCategory", mcc), PROFILE);

        assertThat(result.output()).hasToString("{signal=[HIGH_RISK_MCC], points=[20]}");
    }

    @Test
    void newDeviceCountsOnlyOnline() {
        Map<String, Object> newDevice = with(PROFILE, "newDevice", true);

        assertThat(signalsFor(TRANSACTION, newDevice)).isEmpty();
        assertThat(signalsFor(with(TRANSACTION, "channel", "ECOM", "cardPresent", false), newDevice))
                .containsExactly("NEW_DEVICE_ECOM");
    }

    @Test
    void newAccountIsYoungerThanThirtyDays() {
        assertThat(signalsFor(TRANSACTION, with(PROFILE, "accountAgeDays", 29))).containsExactly("NEW_ACCOUNT");
        assertThat(signalsFor(TRANSACTION, with(PROFILE, "accountAgeDays", 30))).isEmpty();
    }

    @Test
    void keyedPosIsPointOfSaleWithoutTheCard() {
        assertThat(signalsFor(with(TRANSACTION, "cardPresent", false), PROFILE)).containsExactly("KEYED_POS");
        assertThat(signalsFor(with(TRANSACTION, "channel", "ECOM", "cardPresent", false), PROFILE)).isEmpty();
        assertThat(signalsFor(with(TRANSACTION, "channel", "ATM", "cardPresent", false), PROFILE)).isEmpty();
    }

    @Test
    void foreignCurrencyIsAnyCurrencyButVnd() {
        assertThat(signalsFor(with(TRANSACTION, "currency", "USD"), PROFILE)).containsExactly("FOREIGN_CURRENCY");
    }

    @Test
    void everyMatchingSignalContributesInRuleOrder() {
        DecisionResult result = signals(
                with(TRANSACTION, "amount", new BigDecimal("90000000"), "currency", "EUR", "merchantCategory", "7995",
                        "country", "MT", "channel", "ECOM", "hourOfDay", 3, "cardPresent", false),
                with(PROFILE, "transactionsLastHour", 7, "distinctCountriesLast24h", 4, "newDevice", true,
                        "accountAgeDays", 10));

        assertThat(result.output()).hasToString("{signal=[AMOUNT_SPIKE, HIGH_VELOCITY, FOREIGN_COUNTRY, "
                + "MULTI_COUNTRY_24H, NIGHT_ECOM, HIGH_RISK_MCC, NEW_DEVICE_ECOM, NEW_ACCOUNT, FOREIGN_CURRENCY], "
                + "points=[30, 25, 10, 35, 15, 20, 20, 15, 5]}");
        assertThat(result.reasonCodes()).isEqualTo(result.output().get("signal"));
    }

    // --- Action ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "score {0}, {1}, amount {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            100 | ECOM | 1000000  | DECLINE   | DECLINE_VERY_HIGH      | [RISK_SCORE_VERY_HIGH]
             80 | ECOM | 1000000  | DECLINE   | DECLINE_VERY_HIGH      | [RISK_SCORE_VERY_HIGH]
             79 | POS  | 1000000  | DECLINE   | DECLINE_NO_STEP_UP     | [STEP_UP_UNAVAILABLE]
             40 | ATM  | 1000000  | DECLINE   | DECLINE_NO_STEP_UP     | [STEP_UP_UNAVAILABLE]
             79 | ECOM | 1000000  | CHALLENGE | CHALLENGE_HIGH         | [RISK_SCORE_HIGH]
             40 | ECOM | 1000000  | CHALLENGE | CHALLENGE_HIGH         | [RISK_SCORE_HIGH]
             39 | ECOM | 20000000 | CHALLENGE | CHALLENGE_LARGE_AMOUNT | [LARGE_AMOUNT_WITH_RISK]
             20 | ECOM | 20000000 | CHALLENGE | CHALLENGE_LARGE_AMOUNT | [LARGE_AMOUNT_WITH_RISK]
             19 | ECOM | 20000000 | APPROVE   | APPROVE                | []
             20 | ECOM | 19999999 | APPROVE   | APPROVE                | []
             39 | POS  | 50000000 | APPROVE   | APPROVE                | []
             39 | ECOM | 1000000  | APPROVE   | APPROVE                | []
              0 | ECOM | 1000000  | APPROVE   | APPROVE                | []
            """)
    void actionFromRiskScore(long riskScore, String channel, String amount, String action, String rule,
                             String reasonCodes) {
        DecisionResult result = engine.evaluate(DecisionRef.of("card_fraud_action"), Facts.of(
                "riskScore", riskScore,
                "transaction", Map.of("amount", new BigDecimal(amount), "channel", channel)));

        assertThat(result.output()).hasToString("{action=" + action + "}");
        assertThat(result.matchedRules()).containsExactly(rule);
        assertThat(result.reasonCodes()).hasToString(reasonCodes);
    }
}
