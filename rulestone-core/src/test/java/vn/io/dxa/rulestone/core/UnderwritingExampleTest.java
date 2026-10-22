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
 * Evaluates examples/consumer_loan_underwriting.stone.json and examples/consumer_loan_conditions.stone.json.
 * The scenarios and the Java code quoted in docs/examples.md are checked here; update both together.
 */
class UnderwritingExampleTest {

    record Applicant(int age, int creditScore, BigDecimal monthlyIncome, BigDecimal existingMonthlyDebt,
                     int employmentMonths, String employmentType, boolean hasBadDebtLast5y) {}

    /** {@code collateralValue} is null for an unsecured loan. */
    record Loan(BigDecimal amount, int termMonths, BigDecimal collateralValue, String purpose) {}

    record Outcome(DecisionResult underwriting, List<?> conditions) {}

    private final RuleStoneEngine engine = RuleStone.builder()
            .decision(RuleStoneEngineTest.load("consumer_loan_underwriting.stone.json"))
            .decision(RuleStoneEngineTest.load("consumer_loan_conditions.stone.json"))
            .build();

    /** The application glue shown in docs/examples.md. */
    private Outcome underwrite(Applicant applicant, Loan loan) {
        DecisionResult underwriting = engine.evaluate(DecisionRef.of("consumer_loan_underwriting"),
                Facts.of("applicant", applicant, "loan", loan));

        List<?> conditions = List.of();
        if ("APPROVE".equals(underwriting.output().get("decision"))) {
            DecisionResult approval = engine.evaluate(DecisionRef.of("consumer_loan_conditions"),
                    Facts.of("riskGrade", underwriting.output().get("riskGrade"),
                            "applicant", applicant, "loan", loan));
            conditions = (List<?>) approval.output().get("condition");
        }

        return new Outcome(underwriting, conditions);
    }

    private static BigDecimal vnd(String amount) {
        return new BigDecimal(amount);
    }

    // --- Documented scenarios ------------------------------------------------------------------

    @Test
    void strongSalariedApplicantGetsGradeA() {
        Outcome o = underwrite(
                new Applicant(35, 780, vnd("60000000"), vnd("2000000"), 60, "SALARIED", false),
                new Loan(vnd("360000000"), 36, null, "CONSUMER"));

        assertThat(o.underwriting().output()).hasToString(
                "{decision=APPROVE, riskGrade=A, interestRate=0.0980, maxAmount=661000000}");
        assertThat(o.underwriting().reasonCodes()).hasToString("[]");
        assertThat(o.underwriting().matchedRules()).hasToString("[APPROVE_A]");
        assertThat(o.conditions()).hasToString("[CREDIT_LIFE_INSURANCE]");
    }

    @Test
    void securedVehicleLoanForSelfEmployedGetsGradeC() {
        Outcome o = underwrite(
                new Applicant(42, 640, vnd("35000000"), vnd("3000000"), 30, "SELF_EMPLOYED", false),
                new Loan(vnd("420000000"), 60, vnd("700000000"), "VEHICLE"));

        assertThat(o.underwriting().output()).hasToString(
                "{decision=APPROVE, riskGrade=C, interestRate=0.1303, maxAmount=478000000}");
        assertThat(o.underwriting().matchedRules()).hasToString("[APPROVE_C]");
        assertThat(o.conditions()).hasToString(
                "[TAX_RETURNS_2Y, CREDIT_LIFE_INSURANCE, COLLATERAL_INSURANCE, HOLD_VEHICLE_REGISTRATION]");

        // The limit is set by income (478,125,000) until 70% of the collateral is lower.
        Outcome lessCollateral = underwrite(
                new Applicant(42, 640, vnd("35000000"), vnd("3000000"), 30, "SELF_EMPLOYED", false),
                new Loan(vnd("420000000"), 60, vnd("680000000"), "VEHICLE"));
        assertThat(lessCollateral.underwriting().output()).containsEntry("maxAmount", vnd("476000000"));
    }

    @Test
    void heavyExistingDebtIsDeclined() {
        Outcome o = underwrite(
                new Applicant(28, 700, vnd("20000000"), vnd("6000000"), 24, "SALARIED", false),
                new Loan(vnd("240000000"), 24, null, "CONSUMER"));

        assertThat(o.underwriting().output()).hasToString("{decision=DECLINE}");
        assertThat(o.underwriting().reasonCodes()).hasToString("[DTI_TOO_HIGH]");
        assertThat(o.underwriting().matchedRules()).hasToString("[DECLINE_DTI]");
        assertThat(o.conditions()).isEmpty();
    }

    @Test
    void recentlySelfEmployedIsReferred() {
        Outcome o = underwrite(
                new Applicant(31, 720, vnd("40000000"), vnd("0"), 18, "SELF_EMPLOYED", false),
                new Loan(vnd("200000000"), 48, null, "HOME_IMPROVEMENT"));

        assertThat(o.underwriting().output()).hasToString("{decision=REFER}");
        assertThat(o.underwriting().reasonCodes()).hasToString("[EMPLOYMENT_HISTORY_SHORT]");
        assertThat(o.underwriting().matchedRules()).hasToString("[REFER_EMPLOYMENT]");
    }

    @Test
    void unsecuredGradeDIsReferred() {
        Outcome o = underwrite(
                new Applicant(26, 590, vnd("15000000"), vnd("1000000"), 36, "SALARIED", false),
                new Loan(vnd("60000000"), 24, null, "EDUCATION"));

        assertThat(o.underwriting().output()).hasToString("{decision=REFER, riskGrade=D}");
        assertThat(o.underwriting().reasonCodes()).hasToString("[UNSECURED_GRADE_D]");
        assertThat(o.underwriting().matchedRules()).hasToString("[REFER_D_UNSECURED]");
        assertThat(o.conditions()).isEmpty();
    }

    // --- Underwriting, one rule at a time ------------------------------------------------------
    //
    // The baseline is the grade A scenario above: debt-to-income 26% (2,000,000 existing debt plus an
    // estimated instalment of 360,000,000 * 1.36 / 36 = 13,600,000, over 60,000,000 income).

    private static final Map<String, Object> APPLICANT = Map.of(
            "age", 35, "creditScore", 780, "monthlyIncome", vnd("60000000"), "existingMonthlyDebt", vnd("2000000"),
            "employmentMonths", 60, "employmentType", "SALARIED", "hasBadDebtLast5y", false);

    private static final Map<String, Object> UNSECURED = Map.of(
            "amount", vnd("360000000"), "termMonths", 36, "purpose", "CONSUMER");

    /** Loan-to-value 70%. */
    private static final Map<String, Object> SECURED = with(UNSECURED,
            "amount", vnd("280000000"), "collateralValue", vnd("400000000"));

    private static Map<String, Object> with(Map<String, Object> base, Object... overrides) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        for (int i = 0; i < overrides.length; i += 2) {
            copy.put((String) overrides[i], overrides[i + 1]);
        }
        return copy;
    }

    private DecisionResult evaluate(Map<String, Object> applicant, Map<String, Object> loan) {
        return engine.evaluate(DecisionRef.of("consumer_loan_underwriting"),
                Facts.of("applicant", applicant, "loan", loan));
    }

    private String rule(Map<String, Object> applicant, Map<String, Object> loan) {
        List<String> matched = evaluate(applicant, loan).matchedRules();
        assertThat(matched).hasSize(1);
        return matched.get(0);
    }

    @Test
    void baselineIsGradeA() {
        assertThat(evaluate(APPLICANT, UNSECURED).output()).hasToString(
                "{decision=APPROVE, riskGrade=A, interestRate=0.0980, maxAmount=661000000}");
    }

    @ParameterizedTest(name = "age {0} -> {1}")
    @CsvSource({"19, DECLINE_AGE", "20, APPROVE_A", "65, APPROVE_A", "66, DECLINE_AGE"})
    void ageMustBeBetween20And65(int age, String rule) {
        assertThat(rule(with(APPLICANT, "age", age), UNSECURED)).isEqualTo(rule);
    }

    @Test
    void declinesWithReasonCodeAndNoOtherOutput() {
        DecisionResult result = evaluate(with(APPLICANT, "age", 17), UNSECURED);

        assertThat(result.output()).hasToString("{decision=DECLINE}");
        assertThat(result.reasonCodes()).containsExactly("AGE_OUT_OF_RANGE");
        assertThat(result.trace()).hasSize(1);
    }

    @Test
    void badDebtIsDeclined() {
        DecisionResult result = evaluate(with(APPLICANT, "hasBadDebtLast5y", true), UNSECURED);

        assertThat(result.matchedRules()).containsExactly("DECLINE_BAD_DEBT");
        assertThat(result.reasonCodes()).containsExactly("BAD_DEBT_HISTORY");
    }

    @Test
    void creditScoreBelow500IsDeclined() {
        DecisionResult declined = evaluate(with(APPLICANT, "creditScore", 499), UNSECURED);
        assertThat(declined.matchedRules()).containsExactly("DECLINE_SCORE");
        assertThat(declined.reasonCodes()).containsExactly("CREDIT_SCORE_TOO_LOW");

        DecisionResult marginal = evaluate(with(APPLICANT, "creditScore", 500), UNSECURED);
        assertThat(marginal.output()).hasToString("{decision=REFER, riskGrade=E}");
        assertThat(marginal.reasonCodes()).containsExactly("CREDIT_SCORE_MARGINAL");
        assertThat(marginal.matchedRules()).containsExactly("REFER_OTHER");
    }

    @Test
    void incomeBelowMinimumIsDeclined() {
        Map<String, Object> small = with(UNSECURED, "amount", vnd("12000000"), "termMonths", 12);
        Map<String, Object> noDebt = with(APPLICANT, "existingMonthlyDebt", BigDecimal.ZERO);

        DecisionResult declined = evaluate(with(noDebt, "monthlyIncome", vnd("4999999.99")), small);
        assertThat(declined.matchedRules()).containsExactly("DECLINE_INCOME");
        assertThat(declined.reasonCodes()).containsExactly("INCOME_BELOW_MINIMUM");

        // 12,000,000 * 1.12 / 12 = 1,120,000 a month: debt-to-income 22.4%
        assertThat(evaluate(with(noDebt, "monthlyIncome", vnd("5000000")), small).output()).hasToString(
                "{decision=APPROVE, riskGrade=A, interestRate=0.0962, maxAmount=24000000}");
    }

    @Test
    void zeroIncomeIsDeclinedInsteadOfDividingByZero() {
        assertThat(rule(with(APPLICANT, "monthlyIncome", BigDecimal.ZERO), UNSECURED)).isEqualTo("DECLINE_INCOME");
    }

    @ParameterizedTest(name = "{0} months, collateral {1} -> {2}")
    @CsvSource({
            "0, , DECLINE_TERM", "5, , DECLINE_TERM", "6, , APPROVE_A", "60, , APPROVE_A", "61, , DECLINE_TERM",
            "5, 400000000, DECLINE_TERM", "84, 400000000, APPROVE_A", "85, 400000000, DECLINE_TERM"})
    void termIsLongerWhenSecured(int termMonths, BigDecimal collateralValue, String rule) {
        Map<String, Object> loan = with(UNSECURED, "amount", vnd("60000000"), "termMonths", termMonths);
        if (collateralValue != null) {
            loan.put("collateralValue", collateralValue);
        }
        DecisionResult result = evaluate(APPLICANT, loan);

        assertThat(result.matchedRules()).containsExactly(rule);
        if (rule.equals("DECLINE_TERM")) {
            assertThat(result.reasonCodes()).containsExactly("TERM_OUT_OF_RANGE");
        }
    }

    @ParameterizedTest(name = "existing debt {0} -> {1}")
    @CsvSource({
            "4400000,  APPROVE_A,  ''",
            "4400001,  APPROVE_B,  ''",
            "13400000, APPROVE_B,  ''",
            "13400001, REFER_DTI,  DTI_ELEVATED",
            "22400000, REFER_DTI,  DTI_ELEVATED",
            "22400001, DECLINE_DTI, DTI_TOO_HIGH"})
    void debtToIncomeThresholds(BigDecimal existingMonthlyDebt, String rule, String reasonCode) {
        // Instalment 13,600,000 on 60,000,000 income: total debt 18,000,000 is 30%, 27,000,000 is 45%,
        // 36,000,000 is 60%.
        DecisionResult result = evaluate(with(APPLICANT, "existingMonthlyDebt", existingMonthlyDebt), UNSECURED);

        assertThat(result.matchedRules()).containsExactly(rule);
        assertThat(String.join(",", result.reasonCodes())).isEqualTo(reasonCode);
    }

    @ParameterizedTest(name = "amount {0} on 400,000,000 collateral -> {1}")
    @CsvSource({
            "280000000, APPROVE_A,   ''",
            "280000001, REFER_LTV,   LTV_ELEVATED",
            "340000000, REFER_LTV,   LTV_ELEVATED",
            "340000001, DECLINE_LTV, LTV_TOO_HIGH"})
    void loanToValueThresholds(BigDecimal amount, String rule, String reasonCode) {
        DecisionResult result = evaluate(APPLICANT, with(SECURED, "amount", amount));

        assertThat(result.matchedRules()).containsExactly(rule);
        assertThat(String.join(",", result.reasonCodes())).isEqualTo(reasonCode);
    }

    @Test
    void zeroCollateralIsDeclinedInsteadOfDividingByZero() {
        assertThat(rule(APPLICANT, with(SECURED, "collateralValue", BigDecimal.ZERO))).isEqualTo("DECLINE_LTV");
    }

    @ParameterizedTest(name = "{0} for {1} months -> {2}")
    @CsvSource({
            "SALARIED, 11, REFER_EMPLOYMENT", "SALARIED, 12, APPROVE_A",
            "CONTRACT, 11, REFER_EMPLOYMENT", "CONTRACT, 12, APPROVE_A",
            "SELF_EMPLOYED, 23, REFER_EMPLOYMENT", "SELF_EMPLOYED, 24, APPROVE_A"})
    void employmentHistory(String employmentType, int employmentMonths, String rule) {
        DecisionResult result = evaluate(
                with(APPLICANT, "employmentType", employmentType, "employmentMonths", employmentMonths), UNSECURED);

        assertThat(result.matchedRules()).containsExactly(rule);
        if (rule.equals("REFER_EMPLOYMENT")) {
            assertThat(result.output()).hasToString("{decision=REFER}");
            assertThat(result.reasonCodes()).containsExactly("EMPLOYMENT_HISTORY_SHORT");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONSUMER", "EDUCATION", "HOME_IMPROVEMENT", "VEHICLE"})
    void standardPurposesAreApproved(String purpose) {
        assertThat(rule(APPLICANT, with(UNSECURED, "purpose", purpose))).isEqualTo("APPROVE_A");
    }

    @Test
    void otherPurposesAreReferred() {
        DecisionResult result = evaluate(APPLICANT, with(UNSECURED, "purpose", "TRAVEL"));

        assertThat(result.matchedRules()).containsExactly("REFER_PURPOSE");
        assertThat(result.reasonCodes()).containsExactly("PURPOSE_NOT_STANDARD");
    }

    @ParameterizedTest(name = "score {0}, secured {1} -> {2}")
    @CsvSource({
            "850, false, APPROVE_A", "750, false, APPROVE_A", "749, false, APPROVE_B", "680, false, APPROVE_B",
            "679, false, APPROVE_C", "620, false, APPROVE_C", "619, false, REFER_D_UNSECURED",
            "560, false, REFER_D_UNSECURED", "559, false, REFER_OTHER", "500, false, REFER_OTHER",
            "750, true, APPROVE_A", "620, true, APPROVE_C", "619, true, APPROVE_D_SECURED",
            "560, true, APPROVE_D_SECURED", "559, true, REFER_OTHER"})
    void gradeFollowsCreditScore(int creditScore, boolean secured, String rule) {
        assertThat(rule(with(APPLICANT, "creditScore", creditScore), secured ? SECURED : UNSECURED))
                .isEqualTo(rule);
    }

    @Test
    void securedLoanGetsRateDiscountAndLimitCappedByCollateral() {
        // Instalment 280,000,000 * 1.36 / 36 = 10,577,777.78; debt-to-income 20.96%;
        // rate 8.5% + 5% * 20.96% - 2% = 7.548%
        assertThat(evaluate(APPLICANT, SECURED).output()).hasToString(
                "{decision=APPROVE, riskGrade=A, interestRate=0.0755, maxAmount=280000000}");
    }

    @Test
    void gradeDSecuredLoan() {
        assertThat(evaluate(with(APPLICANT, "creditScore", 580), SECURED).output()).hasToString(
                "{decision=APPROVE, riskGrade=D, interestRate=0.1505, maxAmount=280000000}");
    }

    @Test
    void gradeBAndCRates() {
        assertThat(evaluate(with(APPLICANT, "creditScore", 700), UNSECURED).output()).hasToString(
                "{decision=APPROVE, riskGrade=B, interestRate=0.1180, maxAmount=661000000}");
        assertThat(evaluate(with(APPLICANT, "creditScore", 650), UNSECURED).output()).hasToString(
                "{decision=APPROVE, riskGrade=C, interestRate=0.1430, maxAmount=661000000}");
    }

    @Test
    void interestRateRoundsHalfUp() {
        // Debt-to-income exactly 30.1%: 10.5% + 5% * 30.1% = 12.005%, rounded half-up to 12.01%
        DecisionResult result = evaluate(with(APPLICANT, "existingMonthlyDebt", vnd("4460000")), UNSECURED);

        assertThat(result.output()).hasToString(
                "{decision=APPROVE, riskGrade=B, interestRate=0.1201, maxAmount=596000000}");
    }

    // --- Conditions ----------------------------------------------------------------------------

    private List<String> conditions(String riskGrade, String employmentType, Map<String, Object> loan) {
        DecisionResult result = engine.evaluate(DecisionRef.of("consumer_loan_conditions"), Facts.of(
                "riskGrade", riskGrade, "applicant", Map.of("employmentType", employmentType), "loan", loan));
        assertThat(result.reasonCodes()).isEmpty();
        return result.matchedRules();
    }

    private static final Map<String, Object> SMALL_LOAN = Map.of(
            "amount", vnd("50000000"), "termMonths", 24, "purpose", "CONSUMER");

    @Test
    void smallUnsecuredGradeALoanHasNoCondition() {
        DecisionResult result = engine.evaluate(DecisionRef.of("consumer_loan_conditions"), Facts.of(
                "riskGrade", "A", "applicant", Map.of("employmentType", "SALARIED"), "loan", SMALL_LOAN));

        assertThat(result.matched()).isFalse();
        assertThat(result.output()).hasToString("{condition=[]}");
    }

    @Test
    void salaryAssignmentForLowerGradesOfSalariedApplicants() {
        assertThat(conditions("B", "SALARIED", SMALL_LOAN)).isEmpty();
        assertThat(conditions("C", "SALARIED", SMALL_LOAN)).containsExactly("SALARY_ASSIGNMENT");
        assertThat(conditions("C", "CONTRACT", SMALL_LOAN)).isEmpty();
    }

    @Test
    void taxReturnsForSelfEmployed() {
        assertThat(conditions("A", "SELF_EMPLOYED", SMALL_LOAN)).containsExactly("TAX_RETURNS");
    }

    @Test
    void guarantorForGradeDOrLargeUnsecuredLoans() {
        assertThat(conditions("D", "CONTRACT", SMALL_LOAN)).containsExactly("GUARANTOR");
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "amount", vnd("500000000"))))
                .containsExactly("CREDIT_LIFE_INSURANCE");
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "amount", vnd("500000001"))))
                .containsExactly("GUARANTOR", "CREDIT_LIFE_INSURANCE");
        assertThat(conditions("A", "SALARIED",
                with(SMALL_LOAN, "amount", vnd("500000001"), "collateralValue", vnd("900000000"))))
                .containsExactly("CREDIT_LIFE_INSURANCE", "COLLATERAL_INSURANCE");
    }

    @Test
    void creditLifeInsuranceFrom100MillionOrBeyond36Months() {
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "amount", vnd("99999999"), "termMonths", 36)))
                .isEmpty();
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "amount", vnd("100000000"))))
                .containsExactly("CREDIT_LIFE_INSURANCE");
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "termMonths", 37)))
                .containsExactly("CREDIT_LIFE_INSURANCE");
    }

    @Test
    void securedVehicleLoanHoldsTheRegistration() {
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "purpose", "VEHICLE"))).isEmpty();
        assertThat(conditions("A", "SALARIED",
                with(SMALL_LOAN, "purpose", "VEHICLE", "collateralValue", vnd("80000000"))))
                .containsExactly("COLLATERAL_INSURANCE", "HOLD_VEHICLE_REGISTRATION");
    }

    @Test
    void educationLoanIsPaidToTheSchool() {
        assertThat(conditions("A", "SALARIED", with(SMALL_LOAN, "purpose", "EDUCATION")))
                .containsExactly("DISBURSE_TO_SCHOOL");
    }
}
