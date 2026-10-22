# Expression language

The `when` and `then` fields of a rule are expressions in **[CEL](https://cel.dev)**, the Common Expression Language, used by Kubernetes, Envoy and Google Cloud for policies. RuleStone adds typed inputs and an exact `decimal` type.

CEL was chosen because it is **safe by construction**:

- No statements, loops, assignments, I/O or access to Java classes: an expression can only compute a value from its inputs.
- Every expression terminates. List operations are bounded (see [Limits](#limits)).
- Expressions are type-checked when rules are loaded, so most mistakes never reach production.

This page covers what rule authors need. The full language is described in the [CEL language definition](https://github.com/google/cel-spec/blob/master/doc/langdef.md).

- [Values and operators](#values-and-operators)
- [Decimals](#decimals)
- [Structs, lists and missing fields](#structs-lists-and-missing-fields)
- [Text](#text)
- [Fuzzy matching](#fuzzy-matching)
- [Errors](#errors)
- [Limits](#limits)
- [Quick reference](#quick-reference)

## Values and operators

| Kind | Examples |
|---|---|
| Literals | `true`, `false`, `42`, `-7`, `'RETAIL'`, `"RETAIL"`, `[1, 2, 3]`, `decimal('0.05')` |
| Inputs | `amount`, `applicant.age`, `customer.accounts[0].balance` |
| Comparison | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| Logic | `&&`, `\|\|`, `!` |
| Arithmetic | `+`, `-`, `*`, `/`, `%` |
| Conditional | `applicant.age >= 18 ? 'ADULT' : 'MINOR'` |
| Membership | `applicant.segment in ['RETAIL', 'SME']` |

Inside a JSON file, an expression is a JSON string, so text literals use single quotes: `"when": "applicant.segment == 'SME'"`.

**Integers** are 64-bit. Integer division truncates (`7 / 2` is `3`) and overflow is an error, never a silent wrap-around. For amounts, rates and anything fractional, use `decimal`.

**There are no floating-point numbers in rules.** A literal such as `0.5` is a CEL `double`, and RuleStone rejects any expression that combines a `double` with a `decimal`. Write `decimal('0.5')` instead.

## Decimals

`decimal` is an exact base-10 number backed by Java's `BigDecimal`. `0.1 + 0.2` is exactly `0.3`.

### Creating decimals

| Expression | Value |
|---|---|
| `decimal('1234.50')` | 1234.50 |
| `decimal(5)` | 5 |
| an input declared `"decimal"` | the `BigDecimal` passed in |

Write decimal literals as strings in plain notation. An unparsable string, such as `decimal('abc')`, fails the evaluation.

### Arithmetic and comparison

`+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=` work between two decimals, and between a decimal and an `int`:

```
amount <= applicant.monthlyIncome * 12
fee + 2000
balance > 0
-amount
```

`==` and `!=` require **both sides to be decimal**. `income == 5` is a type error; write `income == decimal(5)`. Equality compares numeric value, ignoring scale: `decimal('1.0') == decimal('1.00')` is `true`, and the same holds for `in` and list functions.

### Division

`/` keeps 34 significant digits and rounds half-even (IEEE 754 *decimal128*):

```
decimal(1) / 3     // 0.3333333333333333333333333333333333
```

Division by zero fails the evaluation. Because division can produce long fractions, **round explicitly** before presenting or storing an amount.

### Rounding

| Expression | Rounding | Example → result |
|---|---|---|
| `x.round(scale)` | HALF_EVEN (banker's rounding) | `decimal('2.345').round(2)` → 2.34 |
| `x.round(scale, mode)` | the named mode | `decimal('2.345').round(2, 'HALF_UP')` → 2.35 |

`scale` is the number of digits after the decimal point. It may be negative: `x.round(-3)` rounds to thousands. The result then has a negative scale, so in Java `toString()` prints it in scientific notation (`1.535E+6`); use `toPlainString()` (`1535000`) for display. `mode` is one of `HALF_EVEN`, `HALF_UP`, `HALF_DOWN`, `UP`, `DOWN`, `CEILING`, `FLOOR`, in any letter case. An unknown mode fails the evaluation.

Rounding to a multiple, e.g. down to the nearest million:

```
(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000
```

### Other functions

| Expression | Result |
|---|---|
| `x.abs()` | absolute value |
| `min(a, b)`, `max(a, b)` | smaller / larger of two decimals |
| `string(x)` | plain-notation text, e.g. `'1500.50'` |

## Structs, lists and missing fields

Inputs declared as structs are accessed with `.`; lists with `[index]` and the list functions below.

```
applicant.age >= 18
customer.accounts[0].balance
customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= decimal(100000000))
customer.accounts.all(a, a.balance >= 0)
customer.accounts.filter(a, a.type == 'LOAN').size() > 2
customer.accounts.map(a, a.type)
```

| Function | Result |
|---|---|
| `list.exists(x, cond)` | `true` if any element satisfies `cond` |
| `list.all(x, cond)` | `true` if every element satisfies `cond` |
| `list.exists_one(x, cond)` | `true` if exactly one element satisfies `cond` |
| `list.filter(x, cond)` | elements satisfying `cond` |
| `list.map(x, expr)` | `expr` applied to each element |
| `size(list)` or `list.size()` | number of elements |
| `value in list` | membership |

Field names are checked against the declared type when the rule is loaded: `applicant.agee` is reported as `undefined field 'agee'`.

**Missing fields.** The caller may omit a struct field or pass `null`. The field is then *unset*. Reading an unset field fails the evaluation, so when a field is optional, guard it with `has()`:

```
has(applicant.segment) && applicant.segment == 'VIP'
```

`has()` takes a field selection (`has(applicant.segment)`), not a top-level input name. Top-level inputs are always required.

## Text

| Expression | Result |
|---|---|
| `s.startsWith('VN')`, `s.endsWith('X')`, `s.contains('abc')` | `bool` |
| `s.matches('^[0-9]{12}$')` | `bool`; [RE2 syntax](https://github.com/google/re2/wiki/Syntax) |
| `size(s)` | number of characters |
| `a + b` | concatenation |
| `string(42)`, `int('42')` | conversion |

Enum values passed from Java arrive as their `name()`, so compare with text: `applicant.segment == 'RETAIL'`.

## Fuzzy matching

For name screening, RuleStone provides `normalizeName(s)`, `similarity(a, b)`, `nameSimilarity(a, b)`, `levenshtein(a, b)` and `maxNameSimilarity(name, names)`. Scores are `decimal` values between 0 and 1 with four decimal places; names are compared without accents (including Vietnamese `đ`), case or punctuation.

```
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
```

See [Fuzzy matching](fuzzy-matching.md) for the algorithms, thresholds, limits and limitations.

## Errors

RuleStone separates problems found when **loading** rules from problems found when **evaluating** them.

Found when loading, and reported by `RuleStone.builder().build()`:

- syntax errors;
- unknown inputs and fields;
- type errors, such as `decimal` compared with a `double` or a `string` compared with an `int`;
- a `when` that is not `bool`, or a `then` that does not match its output type.

Found when evaluating, and reported as `DecisionEvaluationException` with the rule id:

- division by zero;
- integer overflow;
- reading an unset field;
- an unparsable `decimal('…')` string or unknown rounding mode;
- exceeding the iteration limit.

An evaluation error **fails the whole decision**. RuleStone does not skip the rule and carry on, because a skipped rejection rule could turn into an approval. Treat the failure as you would any other system error, for example by routing the case to manual review.

## Limits

To protect the engine from a runaway rule, list functions (`exists`, `all`, `map`, …) may perform at most **10,000 iterations** per call by default. Exceeding this fails the evaluation. Change it with `RuleStone.builder().maxIterations(n)`.

## Quick reference

```
// comparison and logic
applicant.age >= 18 && !applicant.blacklisted
applicant.segment in ['RETAIL', 'SME']

// decimals
amount <= applicant.monthlyIncome * 12
applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')
(transfer.amount * decimal('0.0005')).round(0, 'HALF_UP')
max(decimal(2000), fee)

// optional field
has(applicant.segment) && applicant.segment == 'VIP'

// lists
customer.accounts.exists(a, a.balance < 0)
customer.accounts.filter(a, a.type == 'LOAN').size()

// text
applicant.idNumber.matches('^[0-9]{12}$')

// fuzzy name matching
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))

// conditional output
amount > decimal(1000000000) ? 'SENIOR_APPROVAL' : 'STANDARD'
```
