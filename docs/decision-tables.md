# Decision tables

A decision table turns typed inputs into typed outputs using an ordered list of rules. This page is the reference for the `.stone.json` file format and for how tables are versioned, selected and evaluated.

- [File format](#file-format)
- [Types](#types)
- [Rules](#rules)
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
| `effectiveTo` | no | First day this version is **no longer** in force, `yyyy-MM-dd`, exclusive. Omitted means "until further notice". Must be after `effectiveFrom`. |
| `hitPolicy` | yes | `FIRST`, `UNIQUE` or `COLLECT`. See [Hit policies](#hit-policies). |
| `inputs` | yes | Object mapping each input name to its [type](#types). May be empty. |
| `outputs` | yes | Object mapping each output name to its [type](#types). May be empty, e.g. for a table that only produces reason codes. |
| `rules` | yes | Non-empty array of [rules](#rules), in evaluation order. |

Reading is **strict**:

- Unknown fields are errors. A misspelt `"efectiveFrom"` cannot silently put a version in force.
- Duplicate keys in any object are errors.
- Every problem in the file is reported at once, each with its JSON path, e.g. `$.rules[2].then.limit`.

Input and output names, and struct field names, must be valid identifiers: a letter or `_`, followed by letters, digits or `_`. CEL reserved words (`in`, `if`, `true`, `null`, …) are not allowed.

## Types

| Type | JSON | Java input accepted | Java output |
|---|---|---|---|
| Boolean | `"bool"` | `Boolean` | `Boolean` |
| Integer, 64-bit | `"int"` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` within range | `Long` |
| Text | `"string"` | `String`, any `enum` (its `name()`) | `String` |
| Exact decimal | `"decimal"` | `BigDecimal`; integral numbers are converted | `BigDecimal` |
| List | `["<type>"]`, e.g. `["decimal"]` | any `Collection` | `List` |
| Struct | `{ "field": "<type>", … }` | `Map` with `String` keys, or a `record` | — (inputs only) |

There is **no floating-point type**. Money, rates and ratios are `decimal`. Declaring `"double"`, `"float"` or `"number"` is an error, and passing a `Double` or `Float` where a `decimal` is expected is rejected at evaluation time.

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

**Struct fields are optional at evaluation time.** A field that is missing or `null` in the input is left unset. Test for it with `has(customer.segment)`; reading an unset field fails the evaluation. **Top-level inputs are required**: a missing input fails the evaluation.

Outputs must be `bool`, `int`, `string`, `decimal`, or lists of these.

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

A version is effective on date `d` when `effectiveFrom <= d < effectiveTo`. Because the end is exclusive, consecutive versions share a boundary date:

```
v2  effectiveFrom 2026-01-01  effectiveTo 2026-11-01
v3  effectiveFrom 2026-11-01
```

On 31 October 2026 v2 applies; on 1 November 2026 v3 applies. There is no gap and no overlap.

**Overlaps are rejected.** If two versions of the same decision could both be in force on some date, the engine refuses to build. Selection is therefore never ambiguous.

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
| Table | Invalid `id` or `version`; `effectiveTo` not after `effectiveFrom`; no rules; struct-typed outputs; invalid input or field names. |
| Rules | Duplicate rule ids; reason codes that are not `UPPER_SNAKE_CASE`; `then` setting an undeclared output. |
| Expressions | Syntax errors; unknown variables or fields; type errors, including any mix of `decimal` and floating point; `when` not producing `bool`; `then` not producing the output's type. |
| Versions | The same `id@version` registered twice; overlapping effective periods. |

What validation cannot catch is a rule that is well-formed but wrong for the business: for example, `UNIQUE` rows that overlap only for some inputs. Those are found by tests. Write unit tests for your decision tables just as you would for code.
