# Examples

Complete decision tables, and a decision flow, for common banking problems. They are explained in [docs/examples.md](../docs/examples.md) and evaluated by the test suite on every build.

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
| `aml_name_screening.stone.json` | `FIRST` | AML screening of a customer against a sanctions and PEP watchlist |
| `aml_transaction_monitoring.stone.json` | `COLLECT` | AML alerts and their scores for a transaction and the account's recent activity |
| `aml_alert_action.stone.json` | `FIRST` | AML action (suspicious transaction report, analyst review, large cash report) from the alert score |

[`flows/`](flows/) holds a decision flow, `card_origination.flow.json`, with the four tables it uses and its tests. It is explained in [docs/flows.md](../docs/flows.md). The browser engine, which does not run flows, skips this folder.

These files illustrate the format. Their thresholds, scores and rates are invented and are not anyone's actual policy. The names in the AML examples are invented and refer to no real person, company or listed entity.
