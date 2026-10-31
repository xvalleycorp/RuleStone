# Decision tables

A decision table turns typed inputs into typed outputs using an ordered list of rules. This page is the reference for the `.stone.json` file format and for how tables are versioned, selected and evaluated.

- [File format](#file-format)
- [Types](#types)
- [Rules](#rules)
- [Local values](#local-values)
- [Parameters](#parameters)
- [Hit policies](#hit-policies)
- [Versions and effective dates](#versions-and-effective-dates)
- [Validation](#validation)

## File format

A table is a JSON object. Files conventionally end in `.stone.json`.

```json
{
  "id": "deposit_rate",
  "version": "1",
  "description": "Term deposit interest rate by term and balance",
  "effectiveFrom": "2026-01-01",
  "effectiveTo": "2027-01-01",
  "hitPolicy": "UNIQUE",
  "inputs":  { "termMonths": "int", "balance": "decimal" },
  "outputs": { "rate": "decimal" },
  "rules": [
    { "id": "SHORT", "when": "termMonths < 6", "then": { "rate": "decimal('0.035')" } }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `id` | yes | Name of the decision, used in `DecisionRef.of(id)`. Starts with a letter; letters, digits, `_`, `.` and `-`. All versions of a decision share the same `id`. |
| `version` | yes | Version label, e.g. `"3"` or `"2026.11"`. Letters, digits, `_`, `.` and `-`. Unique per `id`. |
| `description` | no | Free text for readers. Part of the content hash. |
| `effectiveFrom` | no | First day this version is in force, `yyyy-MM-dd`, inclusive. Omitted means "since forever". |
| `effectiveTo` | no | First day this version is **no longer** in force, `yyyy-MM-dd`, exclusive. Omitted means "until the next version takes effect". Must be after `effectiveFrom`. |
| `hitPolicy` | yes | `FIRST`, `UNIQUE` or `COLLECT`. See [Hit policies](#hit-policies). |
| `inputs` | yes | Object mapping each input name to its [type](#types). May be empty. |
| `outputs` | yes | Object mapping each output name to its [type](#types). May be empty, e.g. for a table that only produces reason codes. |
| `params` | no | The parameter set the rules read business values from, and the parameters they read with their types. See [Parameters](#parameters). |
| `let` | no | Object mapping names to expressions that every rule can read by name. See [Local values](#local-values). |
| `rules` | yes | Non-empty array of [rules](#rules), in evaluation order. |

Reading is **strict**:

- Unknown fields are errors. A misspelt `"efectiveFrom"` cannot silently put a version in force.
- Duplicate keys in any object are errors.
- Every problem in the file is reported at once, each with its JSON path, e.g. `$.rules[2].then.limit`.

Input and output names, and struct field names, must be valid identifiers: a letter or `_`, followed by letters, digits or `_`. CEL reserved words (`in`, `if`, `true`, `null`, …) are not allowed. No input may be named `businessDate`: every decision receives it implicitly (see [The business date](expressions.md#the-business-date)).

## Types

| Type | JSON | Java input accepted | Java output |
|---|---|---|---|
| Boolean | `"bool"` | `Boolean` | `Boolean` |
| Integer, 64-bit | `"int"` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` within range | `Long` |
| Text | `"string"` | `String`, any `enum` (its `name()`) | `String` |
| Exact decimal | `"decimal"` | `BigDecimal`; integral numbers are converted | `BigDecimal` |
| Money | `"money"` | `Money` (`vn.io.dxa.rulestone.api`) only | `Money` |
| Date | `"date"` | `LocalDate` only; strings and other `java.time` types are rejected | `LocalDate` |
| List | `["<type>"]`, e.g. `["decimal"]` | any `Collection` | `List` |
| Struct | `{ "field": "<type>", … }` | `Map` with `String` keys, or a `record` | — (inputs only) |

There is **no floating-point type**. Rates and ratios are `decimal`; amounts are `money` when their currency matters, otherwise `decimal`. Declaring `"double"`, `"float"` or `"number"` is an error, and passing a `Double` or `Float` where a `decimal` is expected is rejected at evaluation time.

Types nest. A customer with a list of accounts:

```json
"inputs": {
  "customer": {
    "segment": "string",
    "accounts": [ { "type": "string", "balance": "decimal" } ]
  }
}
```

```
customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= decimal(100000000))
```

**Struct fields are optional at evaluation time.** A field that is missing from a `Map` input, or `null`, is left unset. (A Java record must still declare every field as a component; see [Java API](java-api.md#inputs-facts).) Test for it with `has(customer.segment)`; reading an unset field fails the evaluation. **Top-level inputs are required**: a missing input fails the evaluation.

Outputs must be `bool`, `int`, `string`, `decimal`, `money`, `date`, or lists of these.

A table using money and dates:

```json
"inputs":  { "transfer": { "amount": "money", "valueDate": "date" } },
"outputs": { "fee": "money", "settles": "date" }
```

```
max(money(2000, transfer.amount.currency()), (transfer.amount * decimal('0.0005')).round())
transfer.valueDate.plusDays(1)
```

Besides its declared inputs, every rule can read `businessDate`, the date the decision is made for. See [The business date](expressions.md#the-business-date).

## Rules

| Field | Required | Description |
|---|---|---|
| `id` | yes | Unique within the table. Recorded in traces and audit logs, so keep it stable across versions when the rule's meaning does not change. |
| `description` | no | Free text for readers. |
| `when` | yes | [Expression](expressions.md) that must produce `bool`. The rule *matches* when it is `true`. Use `"true"` for a catch-all rule. |
| `then` | no | Object mapping output names to expressions. Each expression must produce the output's declared type. A rule may set some, all or none of the outputs. |
| `reasonCode` | no | Reported when the rule matches. `UPPER_SNAKE_CASE`, e.g. `DTI_HIGH`. |

`then` values are expressions, not literals. Text needs inner quotes:

```json
"then": { "decision": "'APPROVE'", "limit": "applicant.monthlyIncome * 12", "manual": "false" }
```

Reason codes are the stable vocabulary you show customers and auditors ("why was I declined?"). Keep them stable across versions and document them for your organization.

## Local values

`let` names intermediate results that several rules share, so that a calculation is written once:

```json
"let": {
  "monthlyDebt": "loans.map(l, l.monthlyPayment).sum(money(0, 'VND'))",
  "dti": "monthlyDebt.amount() / applicant.monthlyIncome.amount()"
},
"rules": [
  { "id": "DTI_HIGH", "when": "dti > decimal('0.5')", "then": { "decision": "'REJECT'" } },
  { "id": "LOW_DEBT", "when": "monthlyDebt < money(5000000, 'VND')", "then": { "decision": "'APPROVE'" } }
]
```

- **Types are inferred** from the expressions, which are type-checked when the table is loaded. A local value can be a `bool`, `int`, `string`, `decimal`, `money`, `date`, a list or a struct, for example `loans[0]`.
- **A local value can read inputs, `businessDate` and other local values.** The order in the file does not matter; a local value that reads itself, directly or through others, is rejected.
- **Values are computed when first read**, at most once per evaluation. A local value that no evaluated rule reads is never computed, so it cannot fail the evaluation. In [credit_limit_increase](../examples/credit_limit_increase.stone.json), `dti` divides by the income, but a customer with missed payments is rejected by the first rule before `dti` is read, even without income.
- **Errors behave as if the expression were written in the rule.** `||` and `&&` absorb them as usual. When a rule fails because of a local value, the message names it: `rule DTI_HIGH condition failed: local value dti failed: decimal division by zero in: …`. Each local value has its own [iteration limit](expressions.md#limits).
- **Names** are identifiers that do not clash with an input, `businessDate` or `params` (reserved for parameter sets). An output may have the same name as a local value.
- Local values are not recorded: they follow from the inputs and the rule version. The [content hash](#content-hash) covers `let`; a table without `let`, or with an empty one, has the hash it had before `let` existed.

In a [flow](flows.md), `derive` nodes compute values between nodes; `let` is for calculations shared by the rules of one table.

## Parameters

Values that the bank's policy fixes, such as a debt-to-income limit or a minimum age, belong in a **parameter set**: a separate `.params.json` file, reviewed and versioned like the rules, that the rules read as `params.<name>`. The caller never passes them; the version of the set in force on the business date is used. The design and its reasons are in [Parameters, aggregates and local values](parameters.md#b-parameter-sets).

A table declares the set and the parameters it reads, with their types, as it declares inputs:

```json
"params": {
  "set": "retail_credit",
  "values": { "minAge": "int", "maxDti": "decimal", "incomeMultiple": "int", "blockedSegments": ["string"] }
},
"let": {
  "dti": "applicant.monthlyDebt / applicant.monthlyIncome",
  "maxAmount": "applicant.monthlyIncome * params.incomeMultiple"
},
"rules": [
  { "id": "R1", "when": "applicant.age < params.minAge", "then": { "decision": "'REJECT'" } },
  { "id": "R4", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } }
]
```

| Field | Required | Description |
|---|---|---|
| `set` | yes | The id of the parameter set. |
| `version` | no | A version of the set to use whatever the business date, for special cases. Without it, the version in force on the business date is used. |
| `values` | yes | Object mapping each parameter the table reads to its type: `bool`, `int`, `string`, `decimal`, `money`, `date` or a list of them. At least one. |

Rules and local values read `params.<name>`; reading a parameter that `values` does not declare is an error. The [content hash](#content-hash) covers the declaration, not the values: a new version of the set changes no rule version. A table without `params` has the hash it had before parameter sets existed.

### Parameter set files

A parameter set is a `.params.json` file, one per version:

```json
{
  "id": "retail_credit",
  "version": "2",
  "description": "Tighter debt-to-income limit and lower income multiple, approved by the Credit Committee on 2027-06-10",
  "effectiveFrom": "2027-07-01",
  "values": {
    "minAge": { "type": "int", "value": 18 },
    "maxDti": { "type": "decimal", "value": "0.45" },
    "incomeMultiple": { "type": "int", "value": 10 },
    "blockedSegments": { "type": ["string"], "value": ["DORMANT", "SANCTIONED"] }
  }
}
```

| Field | Required | Description |
|---|---|---|
| `id` | yes | The set's id, shared by all its versions. Parameter set ids are a namespace of their own: a set may have the id of a decision. |
| `version` | yes | Version label, as for tables. |
| `description` | no | Free text, e.g. who approved the values. |
| `effectiveFrom`, `effectiveTo` | no | As for tables: a version stays in force until the next version takes effect. See [Versions and effective dates](#versions-and-effective-dates). |
| `values` | yes | Object mapping each parameter name to `{ "type": …, "value": … }`. Types are written as for inputs; parameters have no fields, so struct types are not allowed. Values use the JSON forms of facts: money as `"500000000 VND"`, decimals as strings or numbers, read exactly, dates as `"yyyy-MM-dd"`. |

Reading is strict, as for tables: unknown fields, duplicate keys and values that do not match their type are errors, each reported with its JSON path, e.g. `$.values.maxDti.value`. Each version has its own content hash, `2@sha256:…`, computed in the same canonical form as a table's.

To change a limit from a date, add a version of the set with a later `effectiveFrom`: the table does not change. Register every version with `RuleStone.builder().paramSet(...)` (see [Java API](java-api.md#parameter-sets)); each decision record lists the set versions used, and `DecisionRef.params(set, version)` replays with them.

## Hit policies

The hit policy decides how matching rules combine. The names follow [DMN](https://www.omg.org/dmn/).

### `FIRST`

Rules are evaluated in order. The first rule whose `when` is `true` produces the result; later rules are **not evaluated**.

- `output` contains the outputs set by that rule.
- `reasonCodes` contains that rule's reason code, if it has one.
- If no rule matches, `output` and `reasonCodes` are empty and `matched()` is `false`. End the table with a `"when": "true"` rule if every request must get an answer.

Use `FIRST` for eligibility checks and anything with a natural priority, such as "reject reasons first, then approvals, then refer to a human".

### `UNIQUE`

Every rule is evaluated. **At most one may match.** If two or more match, the evaluation fails with a `DecisionEvaluationException` naming the rules, and no result is returned.

- With one match, `output` and `reasonCodes` come from that rule.
- With no match, both are empty.

Use `UNIQUE` for tables whose rows should partition the input space, such as rate grids. A double match means the table has a gap in its logic, and failing is safer than picking one. See [`examples/deposit_rate.stone.json`](../examples/deposit_rate.stone.json).

### `COLLECT`

Every rule is evaluated and **every match contributes**.

- Every declared output appears in `output` as a list, holding the values from the matched rules that set it, in rule order. An output that no matched rule sets is an empty list.
- `reasonCodes` lists the reason codes of all matched rules, in rule order.

Use `COLLECT` to accumulate things, such as the fees that apply to a transfer. See [`examples/transfer_fees.stone.json`](../examples/transfer_fees.stone.json).

### Trace

Whatever the hit policy, `DecisionResult.trace()` lists each rule that was evaluated and whether it matched, in order. Under `FIRST` it stops at the first match. The trace never contains input values, so it is safe to store in audit logs.

## Versions and effective dates

A decision usually has several versions over its life. Register all of them with the engine; it chooses one per request:

1. If the `DecisionRef` names a version (`.version("2")`), that version is used, regardless of dates. Use this to replay or investigate a past decision.
2. Otherwise the engine uses the version **effective on** the business date: `DecisionRef.asOf(date)` if given, else today according to the engine's clock.
3. If no version is effective on that date, evaluation fails with `DecisionNotFoundException`.

Rules see the same date as their implicit `businessDate` input: `asOf(date)` if given, else today according to the clock, also when a version is named.

**A new version supersedes the previous one.** Versions of a decision are ordered by `effectiveFrom`; a version without `effectiveFrom` starts at the beginning of time. Each version stays in force until the earlier of:

- its own `effectiveTo`, if it has one;
- the `effectiveFrom` of the next version.

To change a policy from a date, add a file with the new version and that `effectiveFrom`. **Never edit the previous version.** Its file, and therefore its content hash, stays exactly as published, so every decision recorded under it can still be matched to it:

```
v3  effectiveFrom 2026-11-01
v4  effectiveFrom 2027-03-01
```

On 28 February 2027 v3 applies; on 1 March 2027 v4 applies. Both ends are exclusive, so there is no gap and no overlap.

**`effectiveTo` is for a fixed end.** Set it when a version must stop on a date whether or not a successor exists, for example a promotion that ends on 31 December. If the next version starts later, no version is in force in between, and evaluation on those dates fails with `DecisionNotFoundException`. An `effectiveTo` equal to the next version's `effectiveFrom` is also valid: the example `loan_eligibility.v2` ends on the day v3 starts.

**Contradictions are rejected.** The engine refuses to build when:

- two versions of the same decision take effect on the same day, or neither has `effectiveFrom`, because neither supersedes the other;
- a version's `effectiveTo` is after the next version's `effectiveFrom`, because the two files disagree about which one applies.

Selection is therefore never ambiguous.

**Deploy every version together.** Because a version stays in force until its successor takes effect, a deployment that leaves out a newer version keeps applying the older one, without an error. Load all versions of a decision from the same source, such as one directory or one repository.

**Replays should name the version.** A version added later with an `effectiveFrom` in the past changes which version a replay by date (`asOf`) selects. To reproduce a recorded decision exactly, name the version from its record: `DecisionRef.of(id).version(...)`. The [decision record](java-api.md#decision-records-and-auditing) holds the exact version of every evaluation.

### Content hash

Every result reports `ruleVersion()` as `<version>@sha256:<hash>`, where the hash is 64 lowercase hexadecimal digits:

```
3@sha256:<64 hex digits>
```

The hash is computed over the table's canonical form: compact JSON with sorted keys and absent optional fields omitted. Therefore:

- reformatting the file or reordering its keys does **not** change the hash;
- any change to meaning, including a description, an expression or a date, **does** change it.

Store `ruleVersion()` with each decision. It proves which rule text produced the decision, even if someone later edits a file without changing its `version` label. `DecisionTableJson.write(table)` produces the canonical form.

## Validation

`RuleStone.builder().build()` validates every registered table and throws a single `InvalidDecisionException` listing **all** problems. Each problem is prefixed with `id@version` and its location, for example `loan_eligibility@3 rules[R1].when: …`.

The checks are:

| Category | Rejected |
|---|---|
| File | Invalid JSON, unknown fields, duplicate keys, missing required fields, wrong JSON types, unknown types, unknown hit policy, invalid dates. |
| Table | Invalid `id` or `version`; `effectiveTo` not after `effectiveFrom`; no rules; struct-typed outputs; invalid input or field names; an input named `businessDate`. |
| Parameters | A `params` that is not an object with `set` and `values`; an unregistered set, or a pinned version it does not have; a declared parameter that some version the table may use (every registered version, or the pinned one) does not have, or has with another type; parameter names that are not identifiers; struct types; an input named `params`; an expression that reads `params.x` when `x` is not declared, or reads `params` in a table that declares no parameters. |
| Parameter sets | Every problem of a `.params.json` file, as for tables; invalid `id` or `version`; values that do not match their types; versions of one set that are registered twice, take effect on the same day or overlap, as for tables. Prefixed with `parameter set id@version`. |
| Local values | A `let` that is not an object of expressions; names that are not identifiers or that clash with an input, `businessDate` or `params`; cycles, reported once as `let.a: cycle: a -> b -> a`; expressions with syntax or type errors, or whose type is not a RuleStone type (such as `double`). A rule that reads a rejected local value is not checked again. |
| Rules | Duplicate rule ids; reason codes that are not `UPPER_SNAKE_CASE`; `then` setting an undeclared output. |
| Expressions | Syntax errors; unknown variables or fields; type errors, including any mix of `decimal` and floating point, or of `money` and plain numbers; `when` not producing `bool`; `then` not producing the output's type. |
| Versions | The same `id@version` registered twice; two versions taking effect on the same day, or both without `effectiveFrom`; an `effectiveTo` after the next version's `effectiveFrom`. |

What validation cannot catch is a rule that is well-formed but wrong for the business: for example, `UNIQUE` rows that overlap only for some inputs. Those are found by tests. Write unit tests for your decision tables just as you would for code.
