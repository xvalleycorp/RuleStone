# Examples

The [`examples/`](../examples/) directory holds complete decision tables for common banking problems. Each one is loaded and evaluated by the project's test suite, so the results quoted here are checked on every build.

| File | Problem | Shows |
|---|---|---|
| [`loan_eligibility.v2.stone.json`](../examples/loan_eligibility.v2.stone.json), [`loan_eligibility.v3.stone.json`](../examples/loan_eligibility.v3.stone.json) | Unsecured loan eligibility | `FIRST`, reason codes, decimal ratios and rounding, two versions with effective dates |
| [`deposit_rate.stone.json`](../examples/deposit_rate.stone.json) | Term deposit rate grid | `UNIQUE` |
| [`transfer_fees.stone.json`](../examples/transfer_fees.stone.json) | Fees on a money transfer | `COLLECT`, `max`, rounding a percentage fee |

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
