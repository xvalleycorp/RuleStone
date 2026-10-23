# Java API

This page explains how to embed RuleStone in a Java application: building an engine, passing inputs, reading results, handling errors and reloading rules. For complete signatures, see the Javadoc (`mvn javadoc:javadoc`, or the `-javadoc.jar` of each module).

- [Modules](#modules)
- [Building an engine](#building-an-engine)
- [Evaluating a decision](#evaluating-a-decision)
- [Inputs: `Facts`](#inputs-facts)
- [Money, dates and the business date](#money-dates-and-the-business-date)
- [Results: `DecisionResult`](#results-decisionresult)
- [Decision records and auditing](#decision-records-and-auditing)
- [JSON facts and results: `FactsJson`](#json-facts-and-results-factsjson)
- [Errors](#errors)
- [Reloading rules](#reloading-rules)
- [Threading and performance](#threading-and-performance)
- [Using expressions directly](#using-expressions-directly)

## Modules

| Artifact | Package | Use |
|---|---|---|
| `rulestone-api` | `vn.io.dxa.rulestone.api` | Types your application code calls: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult` and the exceptions. Depends on the JDK only. |
| `rulestone-core` | `vn.io.dxa.rulestone.core` | The embedded engine, decision tables and the `.stone.json` format. |
| `rulestone-cel` | `vn.io.dxa.rulestone.cel` | The expression language. Used by `rulestone-core`; use it directly only to evaluate stand-alone expressions. |
| `rulestone-bom` | — | Import to align versions. |

Depend on `rulestone-core`; it brings in the other two. Code that only *evaluates* decisions needs nothing beyond the `api` types, so you can keep `core` out of most of your codebase:

```java
class LoanService {
    private final RuleStoneEngine rules;   // from vn.io.dxa.rulestone.api

    LoanService(RuleStoneEngine rules) {
        this.rules = rules;
    }
}
```

## Building an engine

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")))
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")))
        .decisions(otherTables)
        .maxIterations(10_000)
        .currencyScale("XAU", 4)
        .build();
```

| Method | Purpose |
|---|---|
| `clock(Clock)` | Determines "today" when a request has no `asOf` date. Defaults to the JVM's default time zone; **set it explicitly** to your bank's business time zone. |
| `decision(DecisionTable)` / `decisions(Collection)` | Registers tables. Register every version of a decision; the engine selects one per request. |
| `maxIterations(int)` | Upper bound on iterations of any list function in any rule. Default 10,000. |
| `currencyScale(String, int)` | Number of digits after the decimal point that `money.round()` uses for a currency, overriding ISO 4217. Required for codes without minor units in ISO 4217, such as `XAU`. Throws `IllegalArgumentException` for a code that is not upper-case ISO 4217 or digits outside 0 to 18. |
| `auditSink(AuditSink)` | Receives the `DecisionRecord` of every evaluation, successful or not. May be called several times; sinks run in order. See [Decision records and auditing](#decision-records-and-auditing). |
| `factsHashKey(byte[])` | Makes `DecisionRecord.factsHash()` an HMAC-SHA256 with this key (at least 16 bytes; use 32 random bytes from your key store). Without it, the hash is plain SHA-256. |
| `recordIds(Supplier<String>)` | Source of record ids. Defaults to random UUIDs. |
| `build()` | Compiles and type-checks every rule, checks versions for duplicates and overlaps, and returns an immutable engine. Throws `InvalidDecisionException` listing every problem. |

`DecisionTableJson.read(...)` accepts a `String`, an `InputStream` (read as UTF-8) or a `Path`, and throws `InvalidDecisionException` for any problem in the file. Load files from wherever you keep them: the classpath, a directory, a database column or a configuration service.

You can also build a `DecisionTable` in Java, for example in tests:

```java
DecisionTable table = new DecisionTable(
        "risk_band", "1", Optional.empty(), Optional.empty(), Optional.empty(),
        HitPolicy.FIRST,
        Map.of("score", ValueType.INT),
        Map.of("band", ValueType.STRING),
        List.of(
                Rule.of("HIGH", "score < 500", Map.of("band", "'HIGH'"), "LOW_SCORE"),
                Rule.of("NORMAL", "true", Map.of("band", "'NORMAL'"))));
```

## Evaluating a decision

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility").asOf(applicationDate),
        Facts.of("applicant", applicant, "amount", amount));
```

`DecisionRef` selects the decision and version:

| Expression | Version used |
|---|---|
| `DecisionRef.of("loan_eligibility")` | Effective today, according to the engine clock. |
| `DecisionRef.of("loan_eligibility").asOf(date)` | Effective on `date`. **Recommended**: the result does not depend on when the code runs. |
| `DecisionRef.of("loan_eligibility").version("2")` | Exactly version `2`, regardless of dates. For replays and investigations. |
| `….correlationId("LOAN-2026-000123")` | Adds your id for the business transaction to the decision record, so that it can be joined with your own data. It must not contain personal data. Combines with any of the above. |

Use the business date of the transaction for `asOf`: the application date for a loan, the value date for a payment. Then re-evaluating the same case later gives the same answer. The same date reaches the rules as their implicit `businessDate` input; without `asOf` it is today according to the engine clock, also when you name a version.

## Inputs: `Facts`

`Facts` is an immutable set of named inputs.

```java
Facts.of("termMonths", 12, "balance", new BigDecimal("6000000000"));
Facts.from(Map.of("applicant", applicant, "amount", amount));
Facts.empty().with("applicant", applicant).with("amount", amount);
```

Names must be unique and values must not be `null`. Facts the decision does not declare are ignored, so you can pass a shared set of facts to several decisions. The exception is `businessDate`, which is reserved: a fact with that name fails the evaluation with `DecisionEvaluationException`. Pass the date with `DecisionRef.asOf` instead.

Values are converted to the types declared in the table's `inputs`:

| Declared type | Accepted Java values |
|---|---|
| `bool` | `Boolean` |
| `int` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` within 64-bit range |
| `string` | `String`, any `enum` (passed as its `name()`) |
| `decimal` | `BigDecimal`, or an integral number. **`Double` and `Float` are rejected.** |
| `money` | `Money` only. `BigDecimal` and numbers are rejected, because they have no currency. |
| `date` | `LocalDate` only. Strings, `LocalDateTime`, `Instant` and other types are rejected. |
| list `["…"]` | any `Collection`, each element converted |
| struct `{…}` | a `Map` with `String` keys, or a `record`; each declared field converted |

For structs, only declared fields are read; other map entries and record components are ignored. A declared field that is absent from a `Map`, or `null` in a `Map` or a record, is left unset (see [missing fields](expressions.md#structs-lists-and-missing-fields)).

A record must have a component for **every** declared field. A record without one, for example because of a misspelt name, fails the evaluation with `record Applicant has no component named 'monthlyIncome'`, instead of silently leaving the field unset. Use a `Map`, or a record component that may be `null`, for fields that are genuinely optional.

Records are the simplest way to pass domain objects:

```java
record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                 String segment, boolean blacklisted) {}
```

For other classes, such as JavaBeans or JPA entities, convert to a `Map` or a record at the boundary. This keeps lazy-loading proxies and unrelated fields out of your decisions.

## Money, dates and the business date

`Money` (in `vn.io.dxa.rulestone.api`) is a record of a `BigDecimal` amount and an ISO 4217 currency code:

```java
Money price = Money.of("1500000.50", "VND");       // or Money.of(new BigDecimal("1500000.50"), "VND")
price.amount();                                     // 1500000.50
price.currency();                                   // "VND"
price.toString();                                   // "1500000.50 VND"
```

The constructor and factories reject a `null` amount and a currency that is not an upper-case ISO 4217 code. `Money` never rounds. Its `equals`, like `BigDecimal`'s, is scale-sensitive (`1.0 VND` is not equal to `1.00 VND`); compare amounts with `compareTo` when that matters. Inside rules, `==` compares numerically.

Dates are `java.time.LocalDate`, both as inputs and as outputs. Every rule can also read `businessDate`, the date of the request (see [The business date](expressions.md#the-business-date)). For example, this table charges an annual card fee of 0.1% of the year's spending, waived for customers under 25:

```json
{
  "id": "card_fee",
  "version": "1",
  "hitPolicy": "FIRST",
  "inputs":  { "customer": { "birthDate": "date" }, "spend": "money" },
  "outputs": { "fee": "money", "nextReview": "date" },
  "rules": [
    { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
      "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)" },
      "reasonCode": "YOUTH_WAIVER" },
    { "id": "STANDARD", "when": "true",
      "then": { "fee": "(spend * decimal('0.001')).round()", "nextReview": "businessDate.plusMonths(6)" } }
  ]
}
```

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)),
        Facts.of("customer", Map.of("birthDate", LocalDate.of(1990, 5, 20)),
                 "spend", Money.of("123456789", "VND")));

result.output();   // {fee=123457 VND, nextReview=2027-05-01}
```

## Results: `DecisionResult`

| Method | Content |
|---|---|
| `output()` | `Map<String, Object>` of outputs, in the order the table declares them. Values are `Boolean`, `Long`, `String`, `BigDecimal`, `Money`, `LocalDate` or `List`. Under `FIRST` and `UNIQUE` it holds only the outputs set by the matching rule; under `COLLECT` every output is a list. |
| `reasonCodes()` | Reason codes of the matching rule(s). |
| `matched()` | Whether any rule matched. |
| `matchedRules()` | Ids of the matching rules, in order. |
| `trace()` | Each evaluated rule with whether it matched, as `TraceStep(ruleId, matched)`. |
| `ruleVersion()` | `<version>@sha256:<hash>`, identifying the exact rule content used. |
| `decisionId()` | The decision's id. |
| `record()` | The `DecisionRecord` of this evaluation, the same one every audit sink received. See below. |

Store the decision record with each business decision, or configure an audit sink that does. Together with the inputs, it lets you explain and reproduce the decision. It contains no input values, so it is safe to log; the inputs themselves need your normal data-protection handling.

Under `FIRST` or `UNIQUE`, always handle "no rule matched":

```java
if (!result.matched()) {
    // the table has no rule for this case
}
```

or end the table with a `"when": "true"` rule so that every case gets an answer.

## Decision records and auditing

Every evaluation produces a `DecisionRecord`, whether it succeeds or fails. The record says what was decided, with exactly which rules, and why. The engine hands it to every audit sink before it returns the result or throws, and a successful result also carries it as `result.record()`.

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .factsHashKey(factsHashKey)   // 32 random bytes from the bank's key store
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)).correlationId("CARD-2026-000123"),
        Facts.of("customer", Map.of("birthDate", LocalDate.of(2002, 1, 1)),
                 "spend", Money.of("123456789", "VND")));
```

`auditLog` stands for wherever your records go: a log file, a database table or a Kafka topic. `DecisionRecordJson.write` turns the record into one line of JSON. For the `card_fee` table from [Money, dates and the business date](#money-dates-and-the-business-date), the record of this evaluation is:

```json
{
  "recordId": "3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75",
  "correlationId": "CARD-2026-000123",
  "decision": "card_fee",
  "version": "1@sha256:e38b29d8a5086a1e73ed2f1b54bba1a3fc32167000eebab77ca579516e4a198e",
  "businessDate": "2026-11-01",
  "evaluatedAt": "2026-11-01T02:15:04.218Z",
  "engine": "rulestone 0.1.0",
  "status": "OK",
  "factsHash": "hmac-sha256:46a89c481ab66d5224883eabd26d39e01df82bc0a44d1eed8aa82dfd9f505459",
  "output": { "fee": "0 VND", "nextReview": "2027-11-01" },
  "reasonCodes": [ { "code": "YOUTH_WAIVER", "source": "card_fee" } ],
  "path": [
    {
      "node": "card_fee",
      "type": "table",
      "version": "1@sha256:e38b29d8a5086a1e73ed2f1b54bba1a3fc32167000eebab77ca579516e4a198e",
      "matchedRules": ["YOUTH"]
    }
  ]
}
```

| Field | Content |
|---|---|
| `recordId` | A unique id, a random UUID unless you set `recordIds`. |
| `correlationId` | Your id for the business transaction, from `DecisionRef.correlationId`. Left out if you gave none. |
| `decision`, `version` | The decision requested and the exact version used. `version` is left out if no version was found. |
| `businessDate`, `evaluatedAt` | The date the decision was made for, and the instant evaluation started, both from the engine clock unless `asOf` gave the date. |
| `engine` | The RuleStone version, from the jar's manifest; `rulestone dev` when run from unpackaged classes. |
| `status` | `OK`, or `ERROR` if the caller received an exception. A decision that matched no rule is `OK` with no reason codes. |
| `factsHash` | A hash of the declared inputs; see below. Left out if the inputs were invalid. |
| `output` | The outputs, written as by `FactsJson`. Empty when the status is `ERROR`. |
| `reasonCodes` | Each reason code with the node that produced it. |
| `path` | Each node evaluated: today, the decision table with its version and the rules that matched. When evaluation fails inside a table, it shows the rules that matched before the failure. |
| `error` | Present only when the status is `ERROR`: the exception's message, which never contains input values. For exceptions that are not `RuleStoneException`s, only the exception type is recorded. |

**No input values.** A record never contains input values. `factsHash` identifies the inputs instead: it is computed over the declared inputs in a canonical form, so equivalent inputs always give the same hash. In this canonical form:

- keys are sorted;
- decimals and money amounts have no trailing zeros, so `1.0` and `1.00` hash alike, just as rules treat them alike;
- undeclared facts and `businessDate` are left out.

To verify a decision later, recompute the hash from the inputs you stored and compare it with the record.

**Use a key.** Without `factsHashKey`, the hash is plain SHA-256. Someone who has a record could then test guesses of low-variety inputs, such as a birth date, against it. With a key, the hash is HMAC-SHA256 (`hmac-sha256:…`), and only holders of the key can do that. Use the same key wherever records are verified.

**Sinks must succeed.** Sinks run synchronously on the evaluating thread. If a sink throws, the evaluation fails with an `AuditException` and the caller gets no result: a decision is never returned without its record having been accepted. A sink that must not slow decisions down can hand records to its own queue. When an evaluation has already failed and a sink also fails, the caller receives the original exception, with the `AuditException` attached as suppressed.

## JSON facts and results: `FactsJson`

When facts arrive as JSON, for example from a REST request, a workflow engine or a rule editor, `FactsJson` (core) converts them to the Java values a decision takes, guided by the types the table declares. JSON has no money, date or exact decimal type, so it uses these forms:

| Type | JSON | Java |
|---|---|---|
| `bool` | `true`, `false` | `Boolean` |
| `int` | an integer, e.g. `30` | `Long` |
| `string` | a string | `String` |
| `decimal` | a number, e.g. `25750000.50`, or a string in plain notation, e.g. `"0.0980"` | `BigDecimal`, with the written scale |
| `money` | `"<amount> <CCY>"`, e.g. `"1500000.50 VND"`, the form of `Money.toString()` | `Money` |
| `date` | `"yyyy-MM-dd"`, e.g. `"2026-11-01"` | `LocalDate` |
| list | an array | `List` |
| struct | an object | `Map` |

With the `card_fee` table above:

```java
DecisionTable cardFee = DecisionTableJson.read(Path.of("rules/card_fee.stone.json"));

Facts facts = FactsJson.readFacts("""
        { "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }""", cardFee.inputs());
DecisionResult result = engine.evaluate(DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)), facts);

String json = FactsJson.write(result.output());   // {"fee":"123457 VND","nextReview":"2027-05-01"}
```

Reading is strict. Every problem is reported at once in an `InvalidFactsException`, whose `problems()` give the location and the expected form but never the value, for example `customer.birthDate: expected a date as "yyyy-MM-dd"` or `spend: expected money as "<amount> <CCY>", e.g. "1500000 VND"`. Inputs and struct fields that the table does not declare are problems too. A struct field that is absent, or `null`, is left unset, so rules see it with `has()`; an absent input is left to the engine, which reports it as missing. Numbers never pass through floating point: `readFacts(String, …)` parses decimals exactly, and a `JsonNode` you parse yourself must be read with `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, otherwise its floating-point numbers are rejected.

Writing produces the same forms, with decimals as strings in plain notation (`"0.0980"`, never `9.8E-2`), so values read back unchanged:

| Method | Converts |
|---|---|
| `readFacts(String json, Map<String, ValueType> inputs)`, `readFacts(JsonNode, Map<String, ValueType>)`, `readFacts(JsonNode, DecisionTable)` | a JSON object of inputs to `Facts` |
| `readValues(JsonNode json, Map<String, ValueType> types)` | a JSON object of named values to a `Map`, e.g. expected outputs with the types from `resultTypes(table)` |
| `readValue(JsonNode json, ValueType type)` | one value |
| `resultTypes(DecisionTable table)` | the types of the values in `DecisionResult.output()`: the declared outputs, each a list under `COLLECT` |
| `writeValue(Object value)`, `writeValues(Map<String, ?> values)` | Java values to a `JsonNode` or an `ObjectNode` |
| `write(Map<String, ?> values)` | Java values to compact JSON text |

## Errors

All exceptions extend `RuleStoneException`, an unchecked exception.

| Exception | When | What to do |
|---|---|---|
| `InvalidDecisionException` (core) | Loading a file or building an engine. `problems()` lists every problem with its location. | Fix the definition. At startup, fail fast; when reloading, keep the previous engine (see below). |
| `InvalidFactsException` (core) | Reading JSON facts or values with `FactsJson`. `problems()` lists every problem with its location. | Reject the request as invalid input, e.g. with HTTP 400 and the problems. |
| `DecisionNotFoundException` (api) | No decision with that id, no such version, or no version effective on the date. | Configuration error: check registered versions and their dates. |
| `AuditException` (api) | An audit sink threw while accepting a decision record; the sink's exception is the cause and `recordId()` names the record. | Treat as a system failure: the decision was not returned and must not be assumed. |
| `DecisionEvaluationException` (api) | Invalid input (missing, wrong type, floating point for a decimal, a fact named `businessDate`), an expression failing at run time, or a `UNIQUE` table with several matches. `decisionId()` and `ruleVersion()` identify the decision. | Treat as a system failure for this case, e.g. route it to manual review. **Do not substitute a default decision.** |

Exception messages name the decision, rule, input or field involved, never input values. Log them as you would any error.

## Reloading rules

An engine is immutable. To change rules at run time, build a new engine and swap it in atomically:

```java
private final AtomicReference<RuleStoneEngine> engine = new AtomicReference<>(loadRules());

void reload() {
    try {
        engine.set(loadRules());          // new requests use the new rules
    } catch (InvalidDecisionException e) {
        log.error("Rules not reloaded; still using the previous version:\n{}", e.getMessage());
    }
}

DecisionResult evaluate(DecisionRef ref, Facts facts) {
    return engine.get().evaluate(ref, facts);
}
```

Because `build()` validates everything first, an invalid change never replaces working rules, and a request never sees a half-loaded rule set. A request that started on the old engine finishes on it.

## Threading and performance

- An engine is **immutable and thread-safe**. Build it once and share it.
- `build()` does all parsing and type-checking. `evaluate()` only converts inputs and runs pre-compiled expressions.
- Inputs are converted once per evaluation, then shared by all rules of the table.
- Evaluation does no I/O and holds no locks.

## Using expressions directly

`rulestone-cel` evaluates single expressions without a decision table, for example to validate a formula entered in an admin screen:

```java
ExpressionEnvironment env = ExpressionEnvironment.builder()
        .variable("amount", ValueType.DECIMAL)
        .variable("tenorMonths", ValueType.INT)
        .build();

CelExpression fee = env.compile("(amount * decimal('0.01') / tenorMonths).round(0)", ValueType.DECIMAL);

Bindings inputs = env.bind(Map.of("amount", new BigDecimal("12000000"), "tenorMonths", 12));
BigDecimal value = (BigDecimal) fee.evaluate(inputs);   // 10000
```

`compile` throws `ExpressionException` for any syntax or type error. `bind` throws `InvalidValueException`, whose `path()` names the offending input. Compiled expressions and environments are immutable and thread-safe.

An `ExpressionEnvironment` has no implicit `businessDate`; declare a `ValueType.DATE` variable if an expression needs one. `ExpressionEnvironment.builder().currencyScale("XAU", 4)` configures minor units for `money.round()`, as `RuleStone.builder().currencyScale` does for decision tables. Expressions of type `ValueType.MONEY` evaluate to `Money`, and of type `ValueType.DATE` to `LocalDate`.
