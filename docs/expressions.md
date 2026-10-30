# Expression language

The `when` and `then` fields of a rule are expressions in **[CEL](https://cel.dev)**, the Common Expression Language, used by Kubernetes, Envoy and Google Cloud for policies. RuleStone adds typed inputs, exact `decimal` and `money` types, a `date` type and the implicit `businessDate` input.

CEL was chosen because it is **safe by construction**:

- No statements, loops, assignments, I/O or access to Java classes: an expression can only compute a value from its inputs.
- Every expression terminates. List operations are bounded (see [Limits](#limits)).
- Expressions are type-checked when rules are loaded, so most mistakes never reach production.

This page covers what rule authors need. The full language is described in the [CEL language definition](https://github.com/google/cel-spec/blob/master/doc/langdef.md).

- [Values and operators](#values-and-operators)
- [Decimals](#decimals)
- [Money](#money)
- [Dates](#dates)
- [The business date](#the-business-date)
- [Structs, lists and missing fields](#structs-lists-and-missing-fields)
- [Aggregate functions](#aggregate-functions)
- [Text](#text)
- [Fuzzy matching](#fuzzy-matching)
- [Errors](#errors)
- [Limits](#limits)
- [Quick reference](#quick-reference)

## Values and operators

| Kind | Examples |
|---|---|
| Literals | `true`, `false`, `42`, `-7`, `'RETAIL'`, `"RETAIL"`, `[1, 2, 3]`, `decimal('0.05')`, `money('1500000', 'VND')`, `date('2026-11-01')` |
| Inputs | `amount`, `applicant.age`, `customer.accounts[0].balance` |
| Comparison | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| Logic | `&&`, `\|\|`, `!` |
| Arithmetic | `+`, `-`, `*`, `/`, `%` |
| Conditional | `applicant.age >= 18 ? 'ADULT' : 'MINOR'` |
| Membership | `applicant.segment in ['RETAIL', 'SME']` |

Inside a JSON file, an expression is a JSON string, so text literals use single quotes: `"when": "applicant.segment == 'SME'"`.

**Integers** are 64-bit. Integer division truncates (`7 / 2` is `3`) and overflow is an error, never a silent wrap-around. For rates and anything fractional, use `decimal`; for amounts in a currency, use `money`.

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

## Money

`money` is an exact amount in an [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) currency: a `decimal` amount plus a currency code. In Java it is `vn.io.dxa.rulestone.api.Money`. Use it when an amount's currency matters, so that a rule can never add dong to dollars by accident.

### Creating money

| Expression | Value |
|---|---|
| `money('1500000.50', 'VND')` | 1500000.50 VND |
| `money(decimal('12.5'), 'USD')` | 12.5 USD |
| `money(2000, 'VND')` | 2000 VND |
| an input declared `"money"` | the `Money` passed in |

The currency is an upper-case ISO 4217 code. An unknown code or an unparsable amount fails the evaluation.

RuleStone accepts a fixed list of codes, so every Java version accepts and rounds the same codes: the current codes of ISO 4217 up to amendment 180 (September 2025), and withdrawn codes that Java has long accepted, such as `DEM`, or that were withdrawn recently, such as `BGN` when Bulgaria adopted the euro. In Java the list is `vn.io.dxa.rulestone.api.Iso4217`.

### Arithmetic and comparison

| Expression | Result |
|---|---|
| `a + b`, `a - b` | money; both sides in the same currency |
| `m * decimal('0.015')`, `m * 2`, `2 * m` | money; multiplied by a decimal or an int, on either side |
| `m / 3`, `m / decimal('0.5')` | money; divided by a decimal or an int |
| `-m` | money with the opposite sign |
| `a < b`, `a <= b`, `a > b`, `a >= b` | bool; both sides in the same currency |
| `a == b`, `a != b` | bool; equal when the currency is the same and the amounts are numerically equal |

**Currencies are never mixed.** `+`, `-`, the comparisons `< <= > >=`, `min` and `max` fail the evaluation when the two sides have different currencies, with an error such as `money currencies differ: VND and USD`. RuleStone does not convert currencies; pass amounts already converted, or write separate rules per currency. Equality does not fail: amounts in different currencies are simply not equal.

Money and plain numbers do not mix either: `m + 2000` and `m > 1000000` are type errors. Write `m + money(2000, m.currency())` or `m > money(1000000, 'VND')`. Money times money is also a type error.

Equality ignores scale, as for decimals: `money('1.0', 'USD') == money('1.00', 'USD')` is `true`.

### Rounding

Arithmetic **never rounds** money: results keep full precision, and division keeps 34 significant digits as for decimals.

```
money('100', 'VND') / 3     // 33.33333333333333333333333333333333 VND
```

Round explicitly, to the currency's minor units, before presenting or storing an amount:

| Expression | Rounding | Example → result |
|---|---|---|
| `m.round()` | to the currency's minor units, HALF_EVEN | `money('1234.565', 'USD').round()` → 1234.56 USD |
| `m.round(mode)` | to the currency's minor units, the named mode | `money('1234.565', 'USD').round('HALF_UP')` → 1234.57 USD |

`mode` takes the same names as for [decimals](#rounding). The minor units come from ISO 4217: 0 digits for `VND` and `JPY`, 2 for `USD` and `EUR`, 3 for `KWD`, so `money('2.5', 'VND').round()` is `2 VND`. Some codes, such as `XAU` (gold), have no minor unit in ISO 4217; rounding them fails unless you configure one. To configure or override the minor units of a currency, use `RuleStone.builder().currencyScale("XAU", 4)` (see the [Java API](java-api.md#building-an-engine)).

A payment fee of 0.05% of `payment.amount`, rounded to whole dong, at least 2,000 dong:

```
max(money(2000, 'VND'), (payment.amount * decimal('0.0005')).round())
```

### Other functions

| Expression | Result |
|---|---|
| `m.amount()` | the amount, a `decimal` |
| `m.currency()` | the currency code, a `string`, e.g. `'VND'` |
| `m.abs()` | absolute value |
| `min(a, b)`, `max(a, b)` | smaller / larger of two amounts in the same currency |
| `string(m)` | the amount in plain notation and the code, e.g. `'1500000 VND'` |

## Dates

`date` is a calendar date with no time of day and no time zone, such as a business date or a date of birth. In Java it is `java.time.LocalDate`. (CEL's `timestamp` type is not used for dates, so a time zone can never shift a result by a day.)

| Expression | Value |
|---|---|
| `date('2026-11-01')` | 1 November 2026 |
| an input declared `"date"` | the `LocalDate` passed in |

Write dates in ISO format, `yyyy-MM-dd`. Anything else, such as `date('01/11/2026')`, or an impossible date such as `date('2026-02-30')`, fails the evaluation.

Dates compare with `==`, `!=`, `<`, `<=`, `>` and `>=`; a later date is greater.

| Expression | Result | Example → result |
|---|---|---|
| `d.plusDays(n)` | date `n` days later; `n` may be negative | `date('2026-11-01').plusDays(30)` → 2026-12-01 |
| `d.plusMonths(n)` | date `n` months later, clamped to the end of the month | `date('2026-01-31').plusMonths(1)` → 2026-02-28 |
| `d.plusYears(n)` | date `n` years later, clamped to the end of the month | `date('2028-02-29').plusYears(1)` → 2029-02-28 |
| `d.year()`, `d.month()`, `d.day()` | `int`; month 1 to 12 | `date('2026-11-01').month()` → 11 |
| `d.dayOfWeek()` | `int`, 1 = Monday … 7 = Sunday | `date('2026-11-01').dayOfWeek()` → 7 |
| `daysBetween(a, b)` | whole days from `a` to `b` | `daysBetween(date('2026-10-01'), date('2026-11-01'))` → 31 |
| `monthsBetween(a, b)` | complete months from `a` to `b` | `monthsBetween(date('2026-01-31'), date('2026-02-28'))` → 0 |
| `yearsBetween(a, b)` | complete years from `a` to `b` | `yearsBetween(date('2008-11-02'), date('2026-11-01'))` → 17 |
| `string(d)` | ISO text | `string(date('2026-11-01'))` → `'2026-11-01'` |

The `…Between` functions count only complete units, like `java.time.temporal.ChronoUnit.between`, and are negative when `b` is before `a`. That makes `yearsBetween(birthDate, day)` a person's age on `day`.

## The business date

Every decision has an implicit input, `businessDate`, of type `date`. It is the date the decision is made for:

- `DecisionRef.asOf(date)` when the request gives one;
- otherwise today according to the engine's clock (`RuleStone.builder().clock(...)`), also when the request names a version.

It is the same date that [selects the rule version](decision-tables.md#versions-and-effective-dates). Use it instead of a date passed in by the caller whenever a rule needs "today":

```
yearsBetween(applicant.birthDate, businessDate) >= 18
businessDate.dayOfWeek() >= 6                       // Saturday or Sunday
loan.maturityDate <= businessDate.plusMonths(3)
```

Expressions cannot read the clock themselves, so a decision evaluated with `asOf` gives the same result whenever it is replayed.

The name is reserved. A table that declares an input named `businessDate` is rejected when it is loaded, and passing a fact named `businessDate` fails the evaluation, so a caller cannot make the rules and the version selection disagree about the date. The implicit input is not part of the table, so it does not affect the [content hash](decision-tables.md#content-hash).

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

## Aggregate functions

Lists of `int`, `decimal` and `money` can be summed, and their smallest, largest and mean element found. The functions are methods on the list:

| Expression | Result | Example → result |
|---|---|---|
| `list.sum()` | The total. `0` for an empty `list<int>`, `decimal('0')` for an empty `list<decimal>`. An empty `list<money>` has no currency, so it fails the evaluation: use `sum(zero)`. | `[3, 1, 2].sum()` → 6 |
| `list.sum(zero)` | The total, or `zero` when the list is empty. `zero` has the element type; for money it also fixes the currency, and every element must be in it. | `[1].filter(x, x > 5).sum(0)` → 0 |
| `list.min()`, `list.max()` | The smallest and the largest element; of equal elements, the first. An empty list fails the evaluation. | `[money(100, 'VND'), money(50, 'VND')].min()` → 50 VND |
| `list.avg()` | The mean: a `decimal` for `int` and `decimal` lists, `money` for money lists, divided as `/` divides (34 significant digits, half-even). An empty list fails the evaluation. | `[1, 2].avg()` → 1.5 |

They combine with `map` and `filter`, so a list of structs is aggregated by first mapping it to the field:

```
loans.map(l, l.outstanding).sum(money(0, 'VND')) > money(500000000, 'VND')
transactions.filter(t, t.channel == 'ATM').map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')
```

`max()` fails on an empty list, so guard it when the list may be empty, for example with `transactions.exists(t, t.channel == 'ATM') && …`. `sum(zero)` never needs a guard.

- **Currencies are never mixed.** Money elements in different currencies fail the evaluation, as `+` does, with an error such as `money currencies differ: VND and USD`.
- **Integer totals are exact.** A sum of `int` fails only if the total does not fit in 64 bits. `avg()` of an `int` list never overflows.
- **Each element counts towards the [iteration limit](#limits)**, like the other list functions.
- The two-argument `min(a, b)` and `max(a, b)` are unchanged: `min(a, b)` compares two values, `list.min()` the elements of a list.
- There is no `count`: `list.filter(x, cond).size()` already counts.

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
- type errors, such as `decimal` compared with a `double`, a `string` compared with an `int`, or `money` added to a `decimal`;
- a `when` that is not `bool`, or a `then` that does not match its output type.

Found when evaluating, and reported as `DecisionEvaluationException` with the rule id:

- division by zero;
- integer overflow;
- reading an unset field;
- an unparsable `decimal('…')`, `money('…', '…')` or `date('…')` argument, an unknown currency code or an unknown rounding mode;
- money in two different currencies combined with `+`, `-`, `<`, `<=`, `>`, `>=`, `min` or `max`, or in one list passed to `sum`, `min`, `max` or `avg`;
- `min()`, `max()` or `avg()` of an empty list, `sum()` of an empty money list, and an `int` sum beyond 64 bits;
- rounding money in a currency that has no minor unit and no configured `currencyScale`;
- date arithmetic beyond the supported range of years;
- exceeding the iteration limit.

Error messages name inputs, fields, rules and currency codes, never input values or amounts. This includes the standard functions: `int('x')` fails with `int() argument is not a valid integer`, and an index beyond the end of a list with `list index out of bounds`.

An evaluation error **fails the whole decision**. RuleStone does not skip the rule and carry on, because a skipped rejection rule could turn into an approval. Treat the failure as you would any other system error, for example by routing the case to manual review.

## Limits

To protect the engine from a runaway rule, list functions (`exists`, `all`, `map`, `sum`, …) may perform at most **10,000 iterations** by default. Each element a list function visits counts as one iteration, and the count is shared by every list function of the expression: `loans.map(l, l.outstanding).sum()` takes two iterations per loan. Exceeding this fails the evaluation. Change it with `RuleStone.builder().maxIterations(n)`.

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

// money
payment.amount > money(1000000000, 'VND')
(payment.amount * decimal('0.0005')).round()
money('1500000', 'VND') + money(2000, 'VND')
loan.balance.amount() > decimal(0)

// dates and the business date
yearsBetween(applicant.birthDate, businessDate) >= 18
businessDate >= date('2026-11-01')
businessDate.plusDays(30)
daysBetween(invoice.dueDate, businessDate) > 90

// optional field
has(applicant.segment) && applicant.segment == 'VIP'

// lists
customer.accounts.exists(a, a.balance < 0)
customer.accounts.filter(a, a.type == 'LOAN').size()

// totals, minimum, maximum and mean
loans.map(l, l.outstanding).sum(money(0, 'VND'))
transactions.map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')

// text
applicant.idNumber.matches('^[0-9]{12}$')

// fuzzy name matching
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))

// conditional output
amount > decimal(1000000000) ? 'SENIOR_APPROVAL' : 'STANDARD'
```
