# Examples

The [`examples/`](../examples/) directory holds complete decision tables for common banking problems. Each one is loaded and evaluated by the project's test suite, so the results quoted here are checked on every build. Thresholds, scores and rates are invented for illustration; amounts are in VND.

| File | Problem | Shows |
|---|---|---|
| [`loan_eligibility.v2.stone.json`](../examples/loan_eligibility.v2.stone.json), [`loan_eligibility.v3.stone.json`](../examples/loan_eligibility.v3.stone.json) | Unsecured loan eligibility | `FIRST`, reason codes, decimal ratios and rounding, two versions with effective dates |
| [`deposit_rate.stone.json`](../examples/deposit_rate.stone.json) | Term deposit rate grid | `UNIQUE` |
| [`transfer_fees.stone.json`](../examples/transfer_fees.stone.json) | Fees on a money transfer | `COLLECT`, `max`, rounding a percentage fee |
| [`card_fraud_signals.stone.json`](../examples/card_fraud_signals.stone.json), [`card_fraud_action.stone.json`](../examples/card_fraud_action.stone.json) | Card fraud screening | `COLLECT` for risk signals and `FIRST` for the action, chained by the application; `in` lists |
| [`consumer_loan_underwriting.stone.json`](../examples/consumer_loan_underwriting.stone.json), [`consumer_loan_conditions.stone.json`](../examples/consumer_loan_conditions.stone.json) | Consumer loan underwriting | `FIRST` with declines, referrals and risk grades; optional fields with `has()`; `min`, rounding modes; `COLLECT` for approval conditions |

## Loan eligibility: priorities and versions

Version 3 is walked through in [Getting started](getting-started.md). Its rules are checked in priority order: hard rejections (`UNDERAGE`, `BLACKLISTED`, `DTI_HIGH`), then approval, then a catch-all that refers the case for review (`AMOUNT_ABOVE_LIMIT`).

Version 2 was the policy before 1 November 2026:

| | Version 2 | Version 3 |
|---|---|---|
| In force | 2026-01-01 to 2026-10-31 | from 2026-11-01 |
| Minimum age | 20 | 18 |
| Blacklist check | — | yes |
| Debt-to-income check | — | rejects above 50% |
| Maximum amount | 6 × monthly income | 12 × monthly income, rounded down to the million |

Register both versions with the engine. The `asOf` date selects the one in force:

```java
Facts facts = Facts.of("applicant", new Applicant(19, new BigDecimal("30000000"),
                BigDecimal.ZERO, "RETAIL", false),
        "amount", new BigDecimal("1000000"));

engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 10, 31)), facts);
// version 2: {decision=REJECT}, reason UNDERAGE (minimum age was 20)

engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 11, 1)), facts);
// version 3: {decision=APPROVE, limit=360000000}
```

Note that `R3` divides by `monthlyIncome`. An applicant with zero income makes that division fail, and with it the whole decision. To decline such applicants with a reason instead, add a rule before `R3`:

```json
{ "id": "R2b", "when": "applicant.monthlyIncome <= 0", "then": { "decision": "'REJECT'" }, "reasonCode": "NO_INCOME" }
```

## Deposit rate: a grid with `UNIQUE`

```json
{ "id": "SHORT",    "when": "termMonths < 6",                                 "then": { "rate": "decimal('0.035')" } },
{ "id": "MID",      "when": "termMonths >= 6 && termMonths < 12",             "then": { "rate": "decimal('0.048')" } },
{ "id": "LONG",     "when": "termMonths >= 12 && balance < 5000000000",       "then": { "rate": "decimal('0.052')" } },
{ "id": "VIP_LONG", "when": "termMonths >= 12 && balance >= 5000000000",      "then": { "rate": "decimal('0.055')" } }
```

| Inputs | Matched | Rate |
|---|---|---|
| 9 months, 100,000,000 | `MID` | 0.048 |
| 12 months, 6,000,000,000 | `VIP_LONG` | 0.055 |

The rows are meant to partition every combination of term and balance, so the table uses `UNIQUE`. If someone later wrote `LONG` as just `termMonths >= 12`, a 12-month deposit of 6 billion would match both `LONG` and `VIP_LONG`. RuleStone would then fail the evaluation with `UNIQUE hit policy violated: rules [LONG, VIP_LONG] all matched` instead of silently paying one of the two rates.

## Transfer fees: adding up with `COLLECT`

Each rule adds one fee when it applies:

```json
{ "id": "INTERBANK", "when": "transfer.interbank",
  "then": { "feeCode": "'IBT'", "fee": "max(decimal(2000), (transfer.amount * decimal('0.0005')).round(0, 'HALF_UP'))" } },
{ "id": "EXPRESS",   "when": "transfer.express",            "then": { "feeCode": "'EXP'", "fee": "decimal(5000)" } },
{ "id": "COUNTER",   "when": "transfer.channel == 'COUNTER'", "then": { "feeCode": "'CTR'", "fee": "decimal(10000)" } }
```

An interbank transfer of 50,000,000 made at the counter:

```
output       {feeCode=[IBT, CTR], fee=[25000, 10000]}
matchedRules [INTERBANK, COUNTER]
```

The interbank fee is 0.05% of the amount, with a minimum of 2,000. Under `COLLECT`, each output is a list in rule order, so `feeCode` and `fee` line up position by position. Sum the fees in your application, or in a follow-up decision.

When no rule matches, every output is an empty list and `matched()` is `false`.

## Card fraud: signals with `COLLECT`, action with `FIRST`

A card authorization must be answered in milliseconds with one of three actions: approve it, **challenge** it (step-up authentication such as an OTP or 3-D Secure, which only works online), or decline it. Fraud analysts reason in *signals*: "far above this customer's usual amount", "third country today", "gambling merchant". Each signal is weak alone; together they are strong.

The example splits the problem into two tables, evaluated one after the other by the application:

1. [`card_fraud_signals.stone.json`](../examples/card_fraud_signals.stone.json) uses `COLLECT`. Every rule detects one signal independently and outputs its `signal` code and its `points`. Adding a signal means adding a row; no other rule changes.
2. [`card_fraud_action.stone.json`](../examples/card_fraud_action.stone.json) uses `FIRST`. It turns the total score into an action, in priority order.

The signals, with their invented weights:

| Rule | Fires when | Points |
|---|---|---|
| `AMOUNT_SPIKE` | amount is more than 5 × the 30-day average (and there is an average) | 30 |
| `HIGH_VELOCITY` | 5 or more transactions in the previous hour, not counting this one | 25 |
| `FOREIGN_COUNTRY` | merchant country differs from the home country | 10 |
| `MULTI_COUNTRY_24H` | 3 or more distinct countries in 24 hours, this one included (an "impossible travel" proxy) | 35 |
| `NIGHT_ECOM` | e-commerce between 00:00 and 04:59, cardholder's local time | 15 |
| `HIGH_RISK_MCC` | merchant category 7995 (gambling), 4829 (money transfer) or 6051 (quasi-cash, crypto) | 20 |
| `NEW_DEVICE_ECOM` | e-commerce from a device not seen before on this card | 20 |
| `NEW_ACCOUNT` | account opened less than 30 days ago | 15 |
| `KEYED_POS` | point-of-sale transaction without the card present (manual key entry) | 15 |
| `FOREIGN_CURRENCY` | original currency is not VND | 5 |

Each rule's reason code is its signal code, so `reasonCodes()` explains the score:

```json
{ "id": "AMOUNT_SPIKE",
  "when": "profile.averageAmount30d > 0 && transaction.amount > profile.averageAmount30d * 5",
  "then": { "signal": "'AMOUNT_SPIKE'", "points": "30" }, "reasonCode": "AMOUNT_SPIKE" },
{ "id": "HIGH_RISK_MCC",
  "when": "transaction.merchantCategory in ['7995', '4829', '6051']",
  "then": { "signal": "'HIGH_RISK_MCC'", "points": "20" }, "reasonCode": "HIGH_RISK_MCC" }
```

The action table, in evaluation order:

```json
{ "id": "DECLINE_VERY_HIGH",  "when": "riskScore >= 80",                                  "then": { "action": "'DECLINE'" },   "reasonCode": "RISK_SCORE_VERY_HIGH" },
{ "id": "DECLINE_NO_STEP_UP", "when": "riskScore >= 40 && transaction.channel != 'ECOM'", "then": { "action": "'DECLINE'" },   "reasonCode": "STEP_UP_UNAVAILABLE" },
{ "id": "CHALLENGE_HIGH",     "when": "riskScore >= 40",                                  "then": { "action": "'CHALLENGE'" }, "reasonCode": "RISK_SCORE_HIGH" },
{ "id": "CHALLENGE_LARGE_AMOUNT",
  "when": "transaction.channel == 'ECOM' && riskScore >= 20 && transaction.amount >= 20000000",
  "then": { "action": "'CHALLENGE'" }, "reasonCode": "LARGE_AMOUNT_WITH_RISK" },
{ "id": "APPROVE", "when": "true", "then": { "action": "'APPROVE'" } }
```

Under `COLLECT`, `points` is a list, so the application adds it up and passes the total to the second table. The same `transaction` object feeds both tables: the action table declares only the two fields it reads, and the other record components are ignored.

```java
record CardTransaction(BigDecimal amount, String currency, String merchantCategory, String country,
                       String channel, int hourOfDay, boolean cardPresent) {}

record CardProfile(String homeCountry, BigDecimal averageAmount30d, int transactionsLastHour,
                   int distinctCountriesLast24h, boolean newDevice, int accountAgeDays) {}

DecisionResult signals = engine.evaluate(DecisionRef.of("card_fraud_signals"),
        Facts.of("transaction", transaction, "profile", profile));

long riskScore = ((List<?>) signals.output().get("points")).stream()
        .mapToLong(points -> (Long) points)
        .sum();

DecisionResult action = engine.evaluate(DecisionRef.of("card_fraud_action"),
        Facts.of("riskScore", riskScore, "transaction", transaction));
```

Sample transactions by cardholders whose home country is VN (amounts in VND):

| Transaction | Profile | Signals (points) | Score | Action (rule, reason) |
|---|---|---|---|---|
| 450,000 VND, grocery 5411, VN, POS, 18:00, card present | average 600,000, 1 country, account 800 days old | none | 0 | `APPROVE` (`APPROVE`) |
| 6,000,000 billed for a USD purchase, electronics 5732, US, ECOM, 23:00 | average 1,000,000, 2 countries, new device | `AMOUNT_SPIKE`, `FOREIGN_COUNTRY`, `NEW_DEVICE_ECOM`, `FOREIGN_CURRENCY` (30, 10, 20, 5) | 65 | `CHALLENGE` (`CHALLENGE_HIGH`, `RISK_SCORE_HIGH`) |
| 2,000,000 billed for a THB purchase, restaurant 5812, TH, POS, 14:00, card present | average 800,000, 3 countries | `FOREIGN_COUNTRY`, `MULTI_COUNTRY_24H`, `FOREIGN_CURRENCY` (10, 35, 5) | 50 | `DECLINE` (`DECLINE_NO_STEP_UP`, `STEP_UP_UNAVAILABLE`) |
| 25,000,000 VND, money transfer 4829, VN, ECOM, 10:00 | average 8,000,000 | `HIGH_RISK_MCC` (20) | 20 | `CHALLENGE` (`CHALLENGE_LARGE_AMOUNT`, `LARGE_AMOUNT_WITH_RISK`) |
| 15,000,000 VND, crypto 6051, VN, ECOM, 02:00 | average 1,200,000, new device | `AMOUNT_SPIKE`, `NIGHT_ECOM`, `HIGH_RISK_MCC`, `NEW_DEVICE_ECOM` (30, 15, 20, 20) | 85 | `DECLINE` (`DECLINE_VERY_HIGH`, `RISK_SCORE_VERY_HIGH`) |

For the second transaction, `signals.output()` prints:

```
{signal=[AMOUNT_SPIKE, FOREIGN_COUNTRY, NEW_DEVICE_ECOM, FOREIGN_CURRENCY], points=[30, 10, 20, 5]}
```

Pitfalls:

- **Agree on units and counting.** `amount` is the billing amount in VND, whatever the original `currency`, so that it can be compared with the 30-day average. `transactionsLastHour` excludes the current transaction while `distinctCountriesLast24h` includes it. `hourOfDay` is in the cardholder's local time and is computed by the application. Write these conventions down next to the table; the rules cannot check them.
- **Guard ratios against missing history.** Without the `averageAmount30d > 0` guard, a card with no history would raise `AMOUNT_SPIKE` on every purchase. New cards are handled by `NEW_ACCOUNT` instead.
- **Merchant category codes are text.** MCCs can start with a zero (`0742`), so they are declared `string` and listed as `'7995'`, not `7995`.
- **Order the action table by severity.** `DECLINE_NO_STEP_UP` must come before `CHALLENGE_HIGH`: a point-of-sale terminal cannot show an OTP, so a high score there declines instead of challenging.
- **Keep the lists aligned.** `signal`, `points` and `reasonCodes()` line up position by position. Sum or filter them together; never sort one of them on its own.
- **Audit both tables.** Store the `ruleVersion()` of both results with the authorization, so that a decline can be traced to the exact weights and score bands in force.

## Consumer loan underwriting: declines, referrals and risk grades

Underwriting a consumer loan, secured or not, answers four questions in turn: may we lend at all, does an underwriter need to look at it, how risky is it, and on what terms? [`consumer_loan_underwriting.stone.json`](../examples/consumer_loan_underwriting.stone.json) answers them with one `FIRST` table whose rules are grouped in that order:

| Order | Rule | Decision | Reason code | When |
|---|---|---|---|---|
| 1 | `DECLINE_AGE` | `DECLINE` | `AGE_OUT_OF_RANGE` | age below 20 or above 65 |
| 2 | `DECLINE_BAD_DEBT` | `DECLINE` | `BAD_DEBT_HISTORY` | bad debt in the last 5 years |
| 3 | `DECLINE_SCORE` | `DECLINE` | `CREDIT_SCORE_TOO_LOW` | credit score below 500 |
| 4 | `DECLINE_INCOME` | `DECLINE` | `INCOME_BELOW_MINIMUM` | monthly income below 5,000,000 |
| 5 | `DECLINE_TERM` | `DECLINE` | `TERM_OUT_OF_RANGE` | term outside 6–60 months (6–84 when secured) |
| 6 | `DECLINE_DTI` | `DECLINE` | `DTI_TOO_HIGH` | debt-to-income above 60% |
| 7 | `DECLINE_LTV` | `DECLINE` | `LTV_TOO_HIGH` | loan-to-value above 85% |
| 8 | `REFER_DTI` | `REFER` | `DTI_ELEVATED` | debt-to-income above 45% |
| 9 | `REFER_LTV` | `REFER` | `LTV_ELEVATED` | loan-to-value above 70% |
| 10 | `REFER_EMPLOYMENT` | `REFER` | `EMPLOYMENT_HISTORY_SHORT` | under 12 months in the job, or under 24 if self-employed |
| 11 | `REFER_PURPOSE` | `REFER` | `PURPOSE_NOT_STANDARD` | purpose other than `CONSUMER`, `EDUCATION`, `HOME_IMPROVEMENT` or `VEHICLE` |
| 12 | `APPROVE_A` | `APPROVE`, grade A | — | score 750 or more and debt-to-income at most 30% |
| 13 | `APPROVE_B` | `APPROVE`, grade B | — | score 680 or more |
| 14 | `APPROVE_C` | `APPROVE`, grade C | — | score 620 or more |
| 15 | `APPROVE_D_SECURED` | `APPROVE`, grade D | — | score 560 or more, secured |
| 16 | `REFER_D_UNSECURED` | `REFER`, grade D | `UNSECURED_GRADE_D` | score 560 or more, unsecured |
| 17 | `REFER_OTHER` | `REFER`, grade E | `CREDIT_SCORE_MARGINAL` | anything else; in practice a score of 500 to 559 |

`FIRST` makes the priority explicit: a declined applicant is never referred, and a referred one is never approved. The catch-all at the end guarantees that every application gets an answer, and when in doubt, a person decides.

The table computes, in `decimal` arithmetic throughout:

| Quantity | Formula |
|---|---|
| Estimated monthly instalment | amount × (1 + 1% × termMonths) / termMonths: a flat 1% a month on the original principal, a deliberately conservative approximation for illustration |
| Debt-to-income (DTI) | (existingMonthlyDebt + instalment) / monthlyIncome |
| Loan-to-value (LTV) | amount / collateralValue, secured loans only |
| `interestRate` | grade base rate (A 8.5%, B 10.5%, C 13%, D 16%) + 5% × DTI, minus 2% when secured, rounded half-up to 4 decimal places |
| `maxAmount` | the largest amount that keeps DTI at 45%, (45% × monthlyIncome − existingMonthlyDebt) × termMonths / (1 + 1% × termMonths), and when secured at most 70% of the collateral; rounded down to the million |

Because referrals come before approvals, every approved request is within `maxAmount` before rounding; the difference is what the bank could additionally offer.

An unsecured loan omits `collateralValue` (`null` in a Java record), and the rules test for it with `has()`. Thresholds compare by multiplying rather than dividing, so these rules cannot divide by zero:

```json
{ "id": "DECLINE_DTI",
  "when": "applicant.existingMonthlyDebt + loan.amount * (decimal(1) + decimal('0.01') * loan.termMonths) / loan.termMonths > applicant.monthlyIncome * decimal('0.6')",
  "then": { "decision": "'DECLINE'" }, "reasonCode": "DTI_TOO_HIGH" },
{ "id": "DECLINE_LTV",
  "when": "has(loan.collateralValue) && loan.amount > loan.collateralValue * decimal('0.85')",
  "then": { "decision": "'DECLINE'" }, "reasonCode": "LTV_TOO_HIGH" },
{ "id": "REFER_EMPLOYMENT",
  "when": "applicant.employmentMonths < (applicant.employmentType == 'SELF_EMPLOYED' ? 24 : 12)",
  "then": { "decision": "'REFER'" }, "reasonCode": "EMPLOYMENT_HISTORY_SHORT" }
```

An approval comes with conditions. They accumulate, so they live in a second table, [`consumer_loan_conditions.stone.json`](../examples/consumer_loan_conditions.stone.json), which uses `COLLECT` and takes the `riskGrade` from the first result:

| Rule | When | `condition` |
|---|---|---|
| `SALARY_ASSIGNMENT` | salaried, grade C or D | `SALARY_ASSIGNMENT` |
| `TAX_RETURNS` | self-employed | `TAX_RETURNS_2Y` |
| `GUARANTOR` | grade D, or unsecured and above 500,000,000 | `GUARANTOR` |
| `CREDIT_LIFE_INSURANCE` | 100,000,000 or more, or longer than 36 months | `CREDIT_LIFE_INSURANCE` |
| `COLLATERAL_INSURANCE` | secured | `COLLATERAL_INSURANCE` |
| `HOLD_VEHICLE_REGISTRATION` | secured vehicle loan | `HOLD_VEHICLE_REGISTRATION` |
| `DISBURSE_TO_SCHOOL` | education loan | `DISBURSE_TO_SCHOOL` |

The application evaluates the conditions for approvals only:

```java
record Applicant(int age, int creditScore, BigDecimal monthlyIncome, BigDecimal existingMonthlyDebt,
                 int employmentMonths, String employmentType, boolean hasBadDebtLast5y) {}

record Loan(BigDecimal amount, int termMonths, BigDecimal collateralValue, String purpose) {}

DecisionResult underwriting = engine.evaluate(DecisionRef.of("consumer_loan_underwriting"),
        Facts.of("applicant", applicant, "loan", loan));

List<?> conditions = List.of();
if ("APPROVE".equals(underwriting.output().get("decision"))) {
    DecisionResult approval = engine.evaluate(DecisionRef.of("consumer_loan_conditions"),
            Facts.of("riskGrade", underwriting.output().get("riskGrade"),
                    "applicant", applicant, "loan", loan));
    conditions = (List<?>) approval.output().get("condition");
}
```

Sample applications (amounts in VND):

| Applicant | Loan | `output()` | Rule, reason | Conditions |
|---|---|---|---|---|
| age 35, score 780, income 60,000,000, debt 2,000,000, salaried 60 months | 360,000,000 unsecured, 36 months, `CONSUMER` | `{decision=APPROVE, riskGrade=A, interestRate=0.0980, maxAmount=661000000}` | `APPROVE_A` | `[CREDIT_LIFE_INSURANCE]` |
| age 42, score 640, income 35,000,000, debt 3,000,000, self-employed 30 months | 420,000,000, 60 months, `VEHICLE` worth 700,000,000 | `{decision=APPROVE, riskGrade=C, interestRate=0.1303, maxAmount=478000000}` | `APPROVE_C` | `[TAX_RETURNS_2Y, CREDIT_LIFE_INSURANCE, COLLATERAL_INSURANCE, HOLD_VEHICLE_REGISTRATION]` |
| age 28, score 700, income 20,000,000, debt 6,000,000, salaried 24 months | 240,000,000 unsecured, 24 months, `CONSUMER` | `{decision=DECLINE}` | `DECLINE_DTI`, `DTI_TOO_HIGH` | not evaluated |
| age 31, score 720, income 40,000,000, no debt, self-employed 18 months | 200,000,000 unsecured, 48 months, `HOME_IMPROVEMENT` | `{decision=REFER}` | `REFER_EMPLOYMENT`, `EMPLOYMENT_HISTORY_SHORT` | not evaluated |
| age 26, score 590, income 15,000,000, debt 1,000,000, salaried 36 months | 60,000,000 unsecured, 24 months, `EDUCATION` | `{decision=REFER, riskGrade=D}` | `REFER_D_UNSECURED`, `UNSECURED_GRADE_D` | not evaluated |

In the first application the instalment is 360,000,000 × 1.36 / 36 = 13,600,000, so DTI is (2,000,000 + 13,600,000) / 60,000,000 = 26% and the rate is 8.5% + 5% × 26% = 9.80%. In the second, DTI is 14,200,000 / 35,000,000 ≈ 40.57% and LTV is 60%, so the rate is 13% + 2.03% − 2% ≈ 13.03%, and `maxAmount` is limited by income (478,125,000) rather than by collateral (490,000,000).

Pitfalls:

- **Guard before you divide.** `DECLINE_INCOME` and `DECLINE_TERM` come before every rule that divides by income or term, and the DTI and LTV thresholds multiply instead of dividing. A zero income or a zero collateral value is declined with a reason instead of failing the evaluation.
- **Unsecured means absent, not zero.** Omit `collateralValue` for an unsecured loan. A value of `0` means a secured loan with worthless collateral, which `DECLINE_LTV` declines.
- **The instalment formula is repeated.** A table cannot name an intermediate value, so the same expression appears in both DTI rules, in `APPROVE_A` and in every rate. Change them together; the tests at each threshold (DTI of exactly 30%, 45% and 60%) catch a copy that drifts. Computing the instalment in the application and passing it as an input would shorten the rules, but would hide the formula from the table's readers.
- **Name the rounding mode.** The rate rounds `HALF_UP`: a grade B applicant with a DTI of exactly 30.1% gets 12.005%, shown as `0.1201`; the default `HALF_EVEN` would give `0.1200`. `maxAmount` rounds `FLOOR`, so the bank never offers more than the policy allows.
- **Declines and referrals set fewer outputs.** `{decision=DECLINE}` has no `riskGrade`, `interestRate` or `maxAmount`. Check the decision before reading them.
- **`interestRate` keeps its scale.** It prints as `0.0980`, not `0.098`. Compare `BigDecimal` values with `compareTo`, not `equals`.
