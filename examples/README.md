# Examples

Complete decision tables for common banking problems. They are explained in [docs/examples.md](../docs/examples.md) and evaluated by the test suite on every build.

| File | Hit policy | Problem |
|---|---|---|
| `loan_eligibility.v2.stone.json` | `FIRST` | Loan eligibility, policy until 31 October 2026 |
| `loan_eligibility.v3.stone.json` | `FIRST` | Loan eligibility, policy from 1 November 2026 |
| `deposit_rate.stone.json` | `UNIQUE` | Term deposit rate grid |
| `transfer_fees.stone.json` | `COLLECT` | Fees on a money transfer |
| `card_fraud_signals.stone.json` | `COLLECT` | Card fraud risk signals and their points |
| `card_fraud_action.stone.json` | `FIRST` | Card authorization action (approve, challenge, decline) from the risk score |
| `consumer_loan_underwriting.stone.json` | `FIRST` | Consumer loan decision, risk grade, interest rate and maximum amount |
| `consumer_loan_conditions.stone.json` | `COLLECT` | Conditions attached to an approved consumer loan |

These files illustrate the format. Their thresholds, scores and rates are invented and are not anyone's actual policy.
