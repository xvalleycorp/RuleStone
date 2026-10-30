# Parameters, aggregates and local values

> Status: **design proposal** · Last updated: 2026-10-30
>
> Nothing on this page is implemented yet. It is a proposal for review and will change before it ships.

Rules often need values that are not part of their logic: a debt-to-income limit, a cash reporting threshold, a minimum age. They also need totals over lists, and intermediate results that several rules share. This page proposes three additions:

| | Addition | Example |
|---|---|---|
| A | [Aggregate functions on lists](#a-aggregate-functions) | `loans.map(l, l.outstanding).sum()` |
| B | [Parameter sets](#b-parameter-sets): business values kept apart from the rules, versioned and dated | `params.maxDti` |
| C | [Local values in tables](#c-local-values-in-tables) | `"let": { "dti": "applicant.monthlyDebt / applicant.monthlyIncome" }` |

What is already possible, and what stays out of scope, is summarised [at the end](#what-exists-and-what-stays-out-of-scope).

## Principles

1. **The caller does not choose thresholds.** Values that a bank's policy fixes, such as limits and thresholds, must not arrive as inputs from the calling application, because then the application, not the approved policy, decides. They live in files that go through the same review as rules.
2. **Every decision can be reproduced.** The record names everything that determined the outcome: the rule version, and from now on the parameter set version.
3. **Errors are found when rules are loaded.** Names and types of parameters and local values are checked at build time, not on the first request that uses them.
4. **Both engines agree.** The Java engine and the browser engine implement every addition identically, checked by the conformance suite.

## A. Aggregate functions

Lists passed as inputs can already be counted and tested (`filter`, `exists`, `all`, `size()`), but not summed. Four functions are added on lists of `int`, `decimal` and `money`:

| Expression | Result |
|---|---|
| `list.sum()` | The total. `0` for an empty `list<int>`, `decimal('0')` for an empty `list<decimal>`. An empty `list<money>` has no currency, so it fails; use `sum(zero)`. |
| `list.sum(zero)` | The total, or `zero` when the list is empty. For money, `zero` also fixes the currency: every element must be in it. |
| `list.min()`, `list.max()` | The smallest and largest element. An empty list fails the evaluation. |
| `list.avg()` | The mean: a `decimal` for `int` and `decimal` lists, `money` for money lists, divided as `/` divides (34 significant digits, half-even). An empty list fails the evaluation. |

They combine with `map` and `filter`:

```
loans.map(l, l.outstanding).sum(money(0, 'VND')) > applicant.income * 12
transactions.filter(t, t.channel == 'ATM').map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')
```

Rules:

- Money elements in different currencies fail the evaluation, as `+` does. RuleStone never converts currencies.
- A sum of `int` that overflows 64 bits fails the evaluation.
- Each element counts towards the iteration limit, like other list functions (see [Limits](expressions.md#limits)).
- The functions are methods on the list, so they do not clash with the existing two-argument `min(a, b)` and `max(a, b)`.
- `count` is not added: `list.filter(x, cond).size()` already counts.

## B. Parameter sets

A parameter set is a separate file, `<id>.params.json`, holding named, typed business values:

```json
{
  "id": "retail_credit",
  "version": "7",
  "description": "Retail credit policy limits, approved by the Credit Committee on 2026-11-20",
  "effectiveFrom": "2026-12-01",
  "values": {
    "maxDti": { "type": "decimal", "value": "0.45" },
    "minAge": { "type": "int", "value": 18 },
    "maxUnsecured": { "type": "money", "value": "500000000 VND" },
    "blockedSegments": { "type": "list<string>", "value": ["SANCTIONED", "DORMANT"] }
  }
}
```

A table names the set and declares the parameters it reads, with their types, as it declares inputs:

```json
{
  "id": "loan_eligibility",
  "version": "4",
  "params": { "set": "retail_credit", "values": { "maxDti": "decimal", "minAge": "int" } },
  "rules": [
    { "id": "R1", "when": "applicant.age < params.minAge", "then": { "decision": "'REJECT'" } },
    { "id": "R3", "when": "applicant.monthlyDebt / applicant.monthlyIncome > params.maxDti", "then": { "decision": "'REJECT'" } }
  ]
}
```

### Versions and selection

Parameter sets are versioned and dated exactly as tables are, and use the same selection: the version in force on the business date is used, and a new version supersedes the previous one without editing it (see [Versions and effective dates](decision-tables.md#versions-and-effective-dates)). To change a limit from a date, add a version of the set; the table does not change.

A table can pin a version for special cases: `"params": { "set": "retail_credit", "version": "7", … }`.

### Checks at load time

`build()` fails if:

- the set is not registered, or no version of it declares a parameter the table reads, with the same type;
- a value does not match its declared type, such as `"0.45"` declared `int`, or an unknown currency;
- the table reads `params.x` without declaring `x`;
- the table has an input named `params` and also declares parameters.

The check covers **every registered version** of the set, as for table nodes in flows that are not pinned. A deployment that would fail on some business date therefore fails at start-up instead.

### Hashes and records

- A table's hash covers its `params` declaration (set id, names and types), not the values, so changing a value does not change any rule's hash.
- A parameter set has its own content hash, computed in the same canonical form as tables: `7@sha256:…`.
- The decision record gets a new field, `params`, listing each set used with its exact version:

```json
"params": [ { "set": "retail_credit", "version": "7@sha256:<64 hex digits>" } ]
```

To reproduce a decision, the replay names both the rule version and the parameter set version from the record. Parameter values never appear in the record; the version identifies them.

### Flows

A flow can declare `params` in the same way; its expressions (`when`, `derive`, `facts`, `end`) can then read `params.x`. A table node inside a flow uses the parameters that table declares, from the version in force on the same business date. The record lists every set used by the flow and its tables once.

### Changing values at run time

Engines are immutable. New parameter versions take effect when the application builds a new engine from its rule source, exactly as new rule versions do. With effective dates, a change can be deployed in advance and take effect at midnight without a restart. A later governance module (maker-checker) will approve parameter sets and rules alike.

## C. Local values in tables

A table can name intermediate results in `let`, and every rule can read them:

```json
"let": {
  "dti": "applicant.monthlyDebt / applicant.monthlyIncome",
  "annualIncome": "applicant.monthlyIncome * 12"
},
"rules": [
  { "id": "R3", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } },
  { "id": "R4", "when": "amount <= annualIncome", "then": { "decision": "'APPROVE'" } }
]
```

- **Types are inferred** from the expressions, which are type-checked when the table is loaded.
- **A local value can use another one.** The canonical form sorts keys, so order in the file cannot matter; dependencies are found from the expressions, and cycles are rejected.
- **Values are computed when first used**, at most once per evaluation. A local value that no rule reaches is never computed, so it cannot fail an evaluation, just as if its expression were written inside the rules.
- **Names** must be identifiers and must not clash with inputs, `params` or `businessDate`.
- Local values are not recorded: they follow from the inputs and the rule version.
- The table's hash covers `let`.

In a flow, `derive` nodes already do this between nodes; `let` is for calculations shared by the rules of one table.

## What exists and what stays out of scope

| Need | How |
|---|---|
| Values that change on every request | Inputs (facts), passed to every evaluation. |
| Calculations | CEL arithmetic in any expression; `let` in tables (proposed); `derive` nodes in [flows](flows.md). |
| Counting and testing list elements | `size()`, `filter`, `exists`, `all`, `exists_one`. |
| Totals, minimum, maximum and mean of a list | Aggregate functions (proposed). |
| Policy thresholds and limits | Parameter sets (proposed). |
| Data looked up during evaluation, such as a balance from core banking | Planned as `FactResolver`, together with replay, because every value fetched must be recorded to reproduce the decision. Until then, the caller fetches it and passes it as an input. |
| Aggregates over time windows, such as the total of transactions in 24 hours | **Out of scope.** RuleStone keeps no state between evaluations. Compute them in a stream processor or a store such as Flink or Valkey, and pass the result as an input. |

## Implementation plan

Each step is a separate change, implemented in both engines with conformance fixtures, documentation in English and Vietnamese, and tests.

1. **Aggregate functions.**
2. **Local values in tables**, including the browser editor's grid and condition builder, which must show `let` names.
3. **Parameter sets**: the file format, selection, load-time checks, the record's `params` field, `rulestone-test` support (test cases can name a parameter set version), and the editor SDK (a parameter set editor, and `params.` suggestions in the condition builder).
