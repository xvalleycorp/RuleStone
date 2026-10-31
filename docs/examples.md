# Examples

The [`examples/`](../examples/) directory holds complete decision tables for common banking problems, and two decision flows that chain tables, one of them with machine-learning models. Each one is loaded and evaluated by the project's test suite, so the results quoted here are checked on every build. Thresholds, scores and rates are invented for illustration; amounts are in VND.

| File | Problem | Shows |
|---|---|---|
| [`loan_eligibility.v2.stone.json`](../examples/loan_eligibility.v2.stone.json), [`loan_eligibility.v3.stone.json`](../examples/loan_eligibility.v3.stone.json) | Unsecured loan eligibility | `FIRST`, reason codes, decimal ratios and rounding, two versions with effective dates |
| [`deposit_rate.stone.json`](../examples/deposit_rate.stone.json) | Term deposit rate grid | `UNIQUE` |
| [`transfer_fees.stone.json`](../examples/transfer_fees.stone.json) | Fees on a money transfer | `COLLECT`, `max`, rounding a percentage fee |
| [`card_fraud_signals.stone.json`](../examples/card_fraud_signals.stone.json), [`card_fraud_action.stone.json`](../examples/card_fraud_action.stone.json) | Card fraud screening | `COLLECT` for risk signals and `FIRST` for the action, chained by the application; `in` lists |
| [`consumer_loan_underwriting.stone.json`](../examples/consumer_loan_underwriting.stone.json), [`consumer_loan_conditions.stone.json`](../examples/consumer_loan_conditions.stone.json) | Consumer loan underwriting | `FIRST` with declines, referrals and risk grades; optional fields with `has()`; `min`, rounding modes; `COLLECT` for approval conditions |
| [`credit_limit_increase.stone.json`](../examples/credit_limit_increase.stone.json) | Credit card limit increase | [Local values](decision-tables.md#local-values) (`let`) shared by the rules, computed only when read; `sum`, `filter` and `map` over the customer's loans |
| [`aml_name_screening.stone.json`](../examples/aml_name_screening.stone.json), [`aml_transaction_monitoring.stone.json`](../examples/aml_transaction_monitoring.stone.json), [`aml_alert_action.stone.json`](../examples/aml_alert_action.stone.json) | Anti-money laundering: watchlist screening and transaction monitoring | Fuzzy name matching with aliases and dates of birth; `money` arithmetic and comparisons; date functions and `businessDate`; `COLLECT` alerts chained to a `FIRST` action |
| [`flows/card_origination.flow.json`](../examples/flows/card_origination.flow.json) and the tables in [`flows/`](../examples/flows/) | Credit card origination | A [decision flow](flows.md): policy knock-outs, segmentation with `switch`, a `derive` node, two grade tables sharing a name, and a champion/challenger `split` |
| [`flows/card_origination_scored.flow.json`](../examples/flows/card_origination_scored.flow.json) | Credit card origination with models | A flow with [model nodes](flows.md#model-nodes): a fraud score supplied by the caller, two invoked PD models sharing a name, and a referral whenever a model fails |

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

## Anti-money laundering: screening and transaction monitoring

Banks must know who their customers are and watch how money moves through their accounts. Two controls are at the heart of an anti-money laundering (AML) programme:

1. **Screening.** When a customer is onboarded, and again whenever the lists change, compare the customer with watchlists: sanctions lists (people and companies nobody may do business with) and lists of politically exposed persons (PEPs, who need enhanced due diligence).
2. **Transaction monitoring.** Look at each transaction together with the account's recent activity for patterns typical of money laundering, such as large cash, structuring or money that only passes through, and decide whether an analyst must look at it and whether a report must be filed with the authorities.

The example implements both with three tables.

> **Important.** This is an illustration of the rule format, not an AML system. All names are invented and refer to no real person, company or listed entity. All thresholds, bands and scores are invented and are **not** taken from any regulation: each institution must use the reporting thresholds, deadlines and obligations of the regulations that apply to it, and calibrate its own alert thresholds on its own data. The fuzzy functions are not a certified screening product; see [Fuzzy matching](fuzzy-matching.md#limitations).

### Screening a customer against a watchlist

[`aml_name_screening.stone.json`](../examples/aml_name_screening.stone.json) takes the customer and the watchlist, both with aliases and dates of birth:

```json
"inputs": {
  "customer":  { "fullName": "string", "aliases": ["string"], "dateOfBirth": "date", "nationality": "string" },
  "watchlist": [ { "name": "string", "aliases": ["string"], "dateOfBirth": "date", "listType": "string" } ]
},
"outputs": { "result": "string", "bestScore": "decimal" }
```

It uses `FIRST`, because the outcomes have a strict priority, from the most to the least severe:

| Order | Rule | `result` | Reason code | When |
|---|---|---|---|---|
| 1 | `NAME_MISSING` | `REVIEW` | `NAME_MISSING` | the name is missing or blank |
| 2 | `SANCTIONS_MATCH` | `BLOCK` | `SANCTIONS_MATCH` | a name or alias of the customer scores 0.92 or more against a name or alias of a sanctions entry, **and** both have the same date of birth |
| 3 | `SANCTIONS_POSSIBLE_MATCH` | `REVIEW` | `SANCTIONS_POSSIBLE_MATCH` | the same name test, without a matching date of birth |
| 4 | `SANCTIONS_WEAK_MATCH` | `REVIEW` | `SANCTIONS_WEAK_MATCH` | a name or alias scores from 0.85 to just below 0.92 against a sanctions entry |
| 5 | `PEP_MATCH` | `ENHANCED_DUE_DILIGENCE` | `PEP_MATCH` | a name or alias scores 0.92 or more against a PEP |
| 6 | `CLEAR` | `CLEAR` | — | anything else |

The order carries meaning:

- `NAME_MISSING` comes first because a blank name scores 0 against every entry and would otherwise pass as `CLEAR`.
- `SANCTIONS_MATCH` comes before `SANCTIONS_POSSIBLE_MATCH` because both match on the name; the strong rule adds the date of birth. A watchlist entry without a date of birth (a company, or a person whose date is unknown) can therefore never block, only be reviewed.
- `SANCTIONS_WEAK_MATCH` only tests `>= decimal('0.85')`: every score of 0.92 or more was already taken by the two rules above, so under `FIRST` the rule covers exactly the band from 0.85 to just below 0.92. Moving it up would turn strong matches into weak ones.
- Sanctions come before PEPs. A customer who is on both lists gets the sanctions outcome.

The name test compares every name of the customer (full name and aliases) with every name of the entry (name and aliases). `maxNameSimilarity` scores one name against a list, using `nameSimilarity`, which ignores diacritics, case, punctuation and word order:

```json
{ "id": "SANCTIONS_MATCH",
  "when": "has(customer.dateOfBirth) && watchlist.exists(w, w.listType == 'SANCTIONS' && has(w.dateOfBirth) && w.dateOfBirth == customer.dateOfBirth && ([customer.fullName] + customer.aliases).exists(n, normalizeName(n) != '' && maxNameSimilarity(n, [w.name] + w.aliases) >= decimal('0.92')))",
  "then": { "result": "'BLOCK'", "bestScore": "maxNameSimilarity(customer.fullName, watchlist.map(w, w.name))" },
  "reasonCode": "SANCTIONS_MATCH" },
{ "id": "SANCTIONS_WEAK_MATCH",
  "when": "watchlist.exists(w, w.listType == 'SANCTIONS' && ([customer.fullName] + customer.aliases).exists(n, normalizeName(n) != '' && maxNameSimilarity(n, [w.name] + w.aliases) >= decimal('0.85')))",
  "then": { "result": "'REVIEW'", "bestScore": "maxNameSimilarity(customer.fullName, watchlist.map(w, w.name))" },
  "reasonCode": "SANCTIONS_WEAK_MATCH" }
```

Dates of birth are optional on both sides and tested with `has()`. Aliases are required: pass an empty list when there are none. `normalizeName(n) != ''` skips a blank alias of the customer, which would otherwise score 1 against a blank alias on the list. `bestScore` is the best `nameSimilarity` of the customer's full name against the watchlist's primary names, so that the analyst who reviews the alert sees how close the name is. `nationality` is part of the customer record but not used by these rules.

In Java, records are the simplest way to pass the customer and the list; a `null` date of birth is left unset:

```java
record Customer(String fullName, List<String> aliases, LocalDate dateOfBirth, String nationality) {}

record WatchlistEntry(String name, List<String> aliases, LocalDate dateOfBirth, String listType) {}

List<WatchlistEntry> watchlist = List.of(
        new WatchlistEntry("Trần Thị Bích Ngọc", List.of("Jade Tran"), LocalDate.of(1975, 3, 14), "SANCTIONS"),
        new WatchlistEntry("Mekong Phantom Trading Co., Ltd", List.of("MPT Trading"), null, "SANCTIONS"),
        new WatchlistEntry("Phạm Văn Quyết", List.of(), LocalDate.of(1959, 7, 20), "PEP"));

DecisionResult screening = engine.evaluate(DecisionRef.of("aml_name_screening"),
        Facts.of("customer", new Customer("TRAN THI BICH NGOC", List.of(), LocalDate.of(1975, 3, 14), "VN"),
                "watchlist", watchlist));
// {result=BLOCK, bestScore=1.0000}, reason SANCTIONS_MATCH
```

Customers screened against that watchlist:

| Customer | Date of birth | `output()` | Rule and reason code |
|---|---|---|---|
| `TRAN THI BICH NGOC` | 1975-03-14 | `{result=BLOCK, bestScore=1.0000}` | `SANCTIONS_MATCH` |
| `Ngoc Tran Thi Bich` | 1990-05-01 | `{result=REVIEW, bestScore=1.0000}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Trần Thị Bích Ngọc` | none | `{result=REVIEW, bestScore=1.0000}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Lê Thị Hoa`, alias `Jade Tran` | 1975-03-14 | `{result=BLOCK, bestScore=0.6206}` | `SANCTIONS_MATCH` |
| `Tran Thi Bich Hong` | 1992-06-30 | `{result=REVIEW, bestScore=0.9660}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Tran Thi Mai` | 1988-01-09 | `{result=REVIEW, bestScore=0.8778}` | `SANCTIONS_WEAK_MATCH` |
| `Mekong Phantom Trading Company Limited` | none | `{result=REVIEW, bestScore=0.9526}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Pham Van Quyet` | 1959-07-20 | `{result=ENHANCED_DUE_DILIGENCE, bestScore=1.0000}` | `PEP_MATCH` |
| `Lê Thị Hoa` | 1990-02-02 | `{result=CLEAR, bestScore=0.6206}` | `CLEAR`, no reason code |
| ` - ` | 1990-02-02 | `{result=REVIEW}` | `NAME_MISSING` |

The first three rows are the listed name written in capitals without diacritics, in another word order, and with diacritics: all score `1.0000`, and only the date of birth decides between blocking and review. The fourth row matches through an alias, so its `bestScore`, computed on the full name, is low: read the reason code, not the score, to know what matched. The fifth and sixth rows are, by construction, different people: they share the very common family name Trần and middle name Thị (and in the fifth, Bích) with the listed person, which is enough to reach the thresholds. The company in the seventh row has no date of birth on the list, so it can only be reviewed.

### Monitoring transactions

[`aml_transaction_monitoring.stone.json`](../examples/aml_transaction_monitoring.stone.json) uses `COLLECT`: each rule detects one pattern independently and outputs an `alert` code and a `score`, like the [card fraud signals](#card-fraud-signals-with-collect-action-with-first). Amounts are `money` in VND, dates are `date`:

```json
"inputs": {
  "account":     { "openedOn": "date", "lastActivityBeforeToday": "date", "riskRating": "string", "customerIsPep": "bool" },
  "transaction": { "amount": "money", "type": "string", "counterpartyCountry": "string", "valueDate": "date" },
  "history":     { "cashDepositsLast7d": ["money"], "totalInLast24h": "money", "totalOutLast24h": "money" }
}
```

The alerts, with invented thresholds and scores. The cash reporting threshold is set at 300,000,000 VND for the example:

| Rule | `alert` | Fires when | Score | Reason code |
|---|---|---|---|---|
| `LARGE_CASH` | `LARGE_CASH` | cash deposit or withdrawal of 300,000,000 or more | 10 | `LARGE_CASH_TRANSACTION` |
| `STRUCTURING` | `STRUCTURING` | 3 or more cash deposits in 7 days, each from 80% of the threshold (240,000,000) to just below it | 50 | `POSSIBLE_STRUCTURING` |
| `RAPID_IN_OUT` | `RAPID_IN_OUT` | money out in 24 hours is 90% or more of money in, and money in is at least 500,000,000 (100,000,000 for customers rated `HIGH`) | 40 | `RAPID_MOVEMENT_OF_FUNDS` |
| `HIGH_RISK_COUNTRY` | `HIGH_RISK_COUNTRY` | counterparty country is `XA`, `XB` or `XC` | 30 | `HIGH_RISK_JURISDICTION` |
| `DORMANT_REACTIVATED` | `DORMANT_ACCOUNT` | no activity for 365 days or more before the business date (since opening, if never active), and an inflow of 100,000,000 or more | 30 | `DORMANT_ACCOUNT_REACTIVATED` |
| `NEW_ACCOUNT_LARGE_TRANSFER` | `NEW_ACCOUNT_LARGE_TRANSFER` | transfer in or out of 200,000,000 or more on an account less than 30 days old on the value date | 25 | `NEW_ACCOUNT_LARGE_TRANSFER` |
| `PEP_LARGE_TRANSACTION` | `PEP_LARGE_TRANSACTION` | the customer is a PEP and the amount is 500,000,000 or more | 20 | `PEP_LARGE_TRANSACTION` |

`XA`, `XB` and `XC` are codes that ISO 3166 leaves for private use; they stand for your institution's list of high-risk jurisdictions. Large cash alone scores only 10: it must be reported, but it is not suspicious in itself.

The rules compare `money` with `money`, never with plain numbers, and compute thresholds with money arithmetic. Dates come from the account and from the implicit `businessDate`:

```json
{ "id": "STRUCTURING",
  "when": "history.cashDepositsLast7d.filter(d, d >= money(300000000, 'VND') * decimal('0.8') && d < money(300000000, 'VND')).size() >= 3",
  "then": { "alert": "'STRUCTURING'", "score": "50" }, "reasonCode": "POSSIBLE_STRUCTURING" },
{ "id": "RAPID_IN_OUT",
  "when": "history.totalInLast24h >= (account.riskRating == 'HIGH' ? money(100000000, 'VND') : money(500000000, 'VND')) && history.totalOutLast24h >= history.totalInLast24h * decimal('0.9')",
  "then": { "alert": "'RAPID_IN_OUT'", "score": "40" }, "reasonCode": "RAPID_MOVEMENT_OF_FUNDS" },
{ "id": "DORMANT_REACTIVATED",
  "when": "daysBetween(has(account.lastActivityBeforeToday) ? account.lastActivityBeforeToday : account.openedOn, businessDate) >= 365 && transaction.type in ['CASH_DEPOSIT', 'TRANSFER_IN'] && transaction.amount >= money(100000000, 'VND')",
  "then": { "alert": "'DORMANT_ACCOUNT'", "score": "30" }, "reasonCode": "DORMANT_ACCOUNT_REACTIVATED" }
```

`RAPID_IN_OUT` multiplies the inflow by 90% instead of dividing the outflow by the inflow, so it cannot divide by zero. Had a ratio been needed, it would be written with the amounts as decimals, `history.totalOutLast24h.amount() / history.totalInLast24h.amount()`, because money divided by money is a type error.

### Choosing the action

[`aml_alert_action.stone.json`](../examples/aml_alert_action.stone.json) uses `FIRST` to turn the total score into a recommended action. Its second output, `fileCtr`, says whether a large cash transaction report is due, whatever the action:

| Order | Rule | `action` | `fileCtr` | Reason code | When |
|---|---|---|---|---|---|
| 1 | `FILE_STR` | `FILE_STR` | `hasLargeCash` | `SUSPICION_SCORE_HIGH` | score 70 or more |
| 2 | `ANALYST_REVIEW` | `ANALYST_REVIEW` | `hasLargeCash` | `SUSPICION_SCORE_ELEVATED` | score 30 or more |
| 3 | `FILE_CTR_ONLY` | `FILE_CTR_ONLY` | `true` | `LARGE_CASH_REPORTABLE` | large cash, nothing else suspicious |
| 4 | `NO_ACTION` | `NO_ACTION` | `false` | — | anything else |

`FILE_STR` means: prepare a suspicious transaction report for the compliance officer, who decides whether to file it.

As in the card fraud example, the application chains the two tables: it sums the scores and derives `hasLargeCash` from the alerts. Both evaluations use the same business date, so the dormancy rule and the version selection agree:

```java
record Account(LocalDate openedOn, LocalDate lastActivityBeforeToday, String riskRating, boolean customerIsPep) {}

record Transaction(Money amount, String type, String counterpartyCountry, LocalDate valueDate) {}

record History(List<Money> cashDepositsLast7d, Money totalInLast24h, Money totalOutLast24h) {}

DecisionResult alerts = engine.evaluate(DecisionRef.of("aml_transaction_monitoring").asOf(businessDate),
        Facts.of("account", account, "transaction", transaction, "history", history));

long riskScore = ((List<?>) alerts.output().get("score")).stream()
        .mapToLong(score -> (Long) score)
        .sum();
boolean hasLargeCash = ((List<?>) alerts.output().get("alert")).contains("LARGE_CASH");

DecisionResult action = engine.evaluate(DecisionRef.of("aml_alert_action").asOf(businessDate),
        Facts.of("riskScore", riskScore, "hasLargeCash", hasLargeCash));
```

Sample transactions with a business date of 2 November 2026, which is also the value date (amounts in VND; the history includes the transaction itself):

| Account | Transaction | History | Alerts (scores) | Score | `action.output()` (rule) |
|---|---|---|---|---|---|
| opened 2019, last active 2026-10-30, risk `LOW` | `TRANSFER_IN` 15,000,000 from VN | in 15,000,000, out 2,000,000 | none | 0 | `{action=NO_ACTION, fileCtr=false}` (`NO_ACTION`) |
| same | `CASH_DEPOSIT` 350,000,000 | cash deposits 350,000,000 | `LARGE_CASH` (10) | 10 | `{action=FILE_CTR_ONLY, fileCtr=true}` (`FILE_CTR_ONLY`) |
| same | `CASH_DEPOSIT` 290,000,000 | cash deposits 250,000,000, 270,000,000, 290,000,000 | `STRUCTURING` (50) | 50 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| same | `TRANSFER_OUT` 950,000,000 to XB | in 1,000,000,000, out 950,000,000 | `RAPID_IN_OUT`, `HIGH_RISK_COUNTRY` (40, 30) | 70 | `{action=FILE_STR, fileCtr=false}` (`FILE_STR`) |
| opened 2018, last active 2025-09-01 | `TRANSFER_IN` 180,000,000 from VN | in 180,000,000 | `DORMANT_ACCOUNT` (30) | 30 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| opened 2026-10-20, never active | `TRANSFER_IN` 250,000,000 from XA | in 250,000,000 | `HIGH_RISK_COUNTRY`, `NEW_ACCOUNT_LARGE_TRANSFER` (30, 25) | 55 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| PEP, risk `HIGH`, last active 2026-10-28 | `CASH_DEPOSIT` 600,000,000 | cash deposits 600,000,000 | `LARGE_CASH`, `PEP_LARGE_TRANSACTION` (10, 20) | 30 | `{action=ANALYST_REVIEW, fileCtr=true}` (`ANALYST_REVIEW`) |
| PEP, risk `HIGH`, last active 2026-11-01 | `CASH_DEPOSIT` 520,000,000 | cash deposits 245,000,000, 255,000,000, 265,000,000, 520,000,000 | `LARGE_CASH`, `STRUCTURING`, `PEP_LARGE_TRANSACTION` (10, 50, 20) | 80 | `{action=FILE_STR, fileCtr=true}` (`FILE_STR`) |

For the fourth transaction, `alerts.output()` prints:

```
{alert=[RAPID_IN_OUT, HIGH_RISK_COUNTRY], score=[40, 30]}
```

and `alerts.reasonCodes()` prints `[RAPID_MOVEMENT_OF_FUNDS, HIGH_RISK_JURISDICTION]`.

Pitfalls:

- **False positives.** Vietnamese names share a few family and middle names, so different people often score high: `Tran Thi Bich Hong` scores 0.9660 and `Tran Thi Mai` 0.8778 against `Trần Thị Bích Ngọc`. Every alert costs an analyst's time, and a flood of alerts hides the real ones. Corroborating attributes, such as the date of birth in `SANCTIONS_MATCH`, are what make a name match precise.
- **False negatives.** Missing a listed person is far more serious than raising an extra alert. Names in another script, transliterations, nicknames and titles are not matched (see [Fuzzy matching](fuzzy-matching.md#limitations)); record known spellings as aliases. In monitoring, a fixed amount threshold invites splitting payments just below it, which is what `STRUCTURING` looks for, and launderers adapt to any rule they can guess.
- **Tune thresholds on your own data.** The 0.92 and 0.85 bands, the amounts and the scores here are invented. Calibrate them on labelled samples of your own customers and transactions (see [Choosing a threshold](fuzzy-matching.md#choosing-a-threshold)), take the reporting thresholds from the regulations that apply to your institution, document the choices, and test both sides of every threshold, as `AmlExampleTest` does: `Tram Van Bich Ngoc` scores exactly 0.9200 and is a possible match, while `Tran Kim Bich Ngoc` scores 0.9181 and is only a weak one.
- **`bestScore` compares the full name with primary names.** A match found through an alias can show a low `bestScore`, as in the fourth screening row. The reason code says which rule matched; the analyst still needs to see which entry and which name matched, which the table does not output.
- **Not a certified screening system.** `CLEAR` means "no alert from these rules", not that the customer is cleared. `BLOCK` and `FILE_STR` are recommendations for the people who decide under your institution's AML policy.
- **Keep watchlists current.** Load the official lists and your internal lists from their source on every update, re-screen existing customers when they change, and record which list version each screening used, next to the `ruleVersion()` of the decision. RuleStone does not maintain lists.
- **Large lists.** `watchlist.exists(...)` is a comprehension, limited to 10,000 iterations by default (see [Limits](expressions.md#limits)). For large lists, measure the latency, raise the limit with `maxIterations`, or use a dedicated screening service.
- **Never log names.** Customer and watchlist names are personal data. Evaluation errors and traces never contain input values; keep it that way in your own logs, and store the reason codes, `bestScore` and `ruleVersion()` instead of the names.
- **One currency.** Every amount must be in VND. A transaction in another currency fails the evaluation with `money currencies differ` instead of silently escaping the thresholds: convert amounts before monitoring them, or write rules per currency.
- **Agree on the history.** `cashDepositsLast7d`, `totalInLast24h` and `totalOutLast24h` include the current transaction, and `lastActivityBeforeToday` excludes the business date. Write such conventions down next to the tables; the rules cannot check them.

## Card origination: a decision flow

[`examples/flows/card_origination.flow.json`](../examples/flows/card_origination.flow.json) chains four tables into one decision, and is explained step by step in [Decision flows](flows.md#a-first-flow). Every applicant below earns 20,000,000 a month and has no recent delinquency unless stated.

| Applicant | Path | Outcome |
|---|---|---|
| 17 years old | `policy`, `rejected` | `REJECT`, reason `UNDERAGE` |
| Existing customer, utilisation 10%, id `C-1002` | `policy`, `affordability`, `segment`, `grade_existing`, `limit_strategy` (champion), `limit`, `approved` | `APPROVE`, grade A, limit 120,000,000 |
| The same, id `C-1001` | as above, with the challenger bucket | `APPROVE`, grade A, limit 144,000,000 |
| Existing customer, utilisation 95% | `policy`, `affordability`, `segment`, `grade_existing`, `referred` | `REFER`, grade D, reason `HIGH_UTILISATION` |
| New customer, 19 years old | `policy`, `affordability`, `segment`, `grade_new`, `referred` | `REFER`, grade D, reason `THIN_CREDIT_FILE` |

The bucket depends only on the applicant id and the split's salt, so `C-1001` is in the challenger bucket on every evaluation. [`card_origination.stone-test.json`](../examples/flows/card_origination.stone-test.json) checks these paths and more, and covers every rule, node and branch.

[`card_origination_scored.flow.json`](../examples/flows/card_origination_scored.flow.json) makes the same decision with models, as explained in [Model nodes](flows.md#model-nodes). A fraud score comes with the facts; a probability of default (PD) comes from a behavioural model for existing customers and an application model for new ones, and the `card_pd_grade` table turns it into a grade. With a fraud score of 0.05 unless stated:

| Applicant | Models | Path | Outcome |
|---|---|---|---|
| Existing customer | PD 0.0150, `utilisation` the top attribution | `policy`, `fraud`, `segment`, `pd_behaviour`, `grade`, `limit`, `approved` | `APPROVE`, grade A, limit 120,000,000, reason `HIGH_UTILISATION` |
| Any | fraud score 0.92 | `policy`, `fraud`, `referred` | `REFER`, reasons `NEW_DEVICE`, `ADDRESS_MISMATCH` |
| Any | no fraud score | `policy`, `fraud`, `referred` | `REFER`, reason `FRAUD_SCORE_UNAVAILABLE` |
| Existing customer | behavioural model timed out | `policy`, `fraud`, `segment`, `pd_behaviour`, `referred` | `REFER`, reason `MODEL_UNAVAILABLE` |
| New customer | PD 0.0100 with confidence 0.50, below the 0.70 required | `policy`, `fraud`, `segment`, `pd_application`, `referred` | `REFER`, reason `MODEL_UNAVAILABLE` |

[`card_origination_scored.stone-test.json`](../examples/flows/card_origination_scored.stone-test.json) gives the model results of each case in `models`, and covers every rule, node and branch, including the failure path of every model node.
