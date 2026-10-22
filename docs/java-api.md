# Java API

This page explains how to embed RuleStone in a Java application: building an engine, passing inputs, reading results, handling errors and reloading rules. For complete signatures, see the Javadoc (`mvn javadoc:javadoc`, or the `-javadoc.jar` of each module).

- [Modules](#modules)
- [Building an engine](#building-an-engine)
- [Evaluating a decision](#evaluating-a-decision)
- [Inputs: `Facts`](#inputs-facts)
- [Results: `DecisionResult`](#results-decisionresult)
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
        .build();
```

| Method | Purpose |
|---|---|
| `clock(Clock)` | Determines "today" when a request has no `asOf` date. Defaults to the JVM's default time zone; **set it explicitly** to your bank's business time zone. |
| `decision(DecisionTable)` / `decisions(Collection)` | Registers tables. Register every version of a decision; the engine selects one per request. |
| `maxIterations(int)` | Upper bound on iterations of any list function in any rule. Default 10,000. |
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

Use the business date of the transaction for `asOf`: the application date for a loan, the value date for a payment. Then re-evaluating the same case later gives the same answer.

## Inputs: `Facts`

`Facts` is an immutable set of named inputs.

```java
Facts.of("termMonths", 12, "balance", new BigDecimal("6000000000"));
Facts.from(Map.of("applicant", applicant, "amount", amount));
Facts.empty().with("applicant", applicant).with("amount", amount);
```

Names must be unique and values must not be `null`. Facts the decision does not declare are ignored, so you can pass a shared set of facts to several decisions.

Values are converted to the types declared in the table's `inputs`:

| Declared type | Accepted Java values |
|---|---|
| `bool` | `Boolean` |
| `int` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` within 64-bit range |
| `string` | `String`, any `enum` (passed as its `name()`) |
| `decimal` | `BigDecimal`, or an integral number. **`Double` and `Float` are rejected.** |
| list `["…"]` | any `Collection`, each element converted |
| struct `{…}` | a `Map` with `String` keys, or a `record`; each declared field converted |

For structs, only declared fields are read; other map entries and record components are ignored. A declared field that is absent or `null` is left unset (see [missing fields](expressions.md#structs-lists-and-missing-fields)).

Records are the simplest way to pass domain objects:

```java
record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                 String segment, boolean blacklisted) {}
```

For other classes, such as JavaBeans or JPA entities, convert to a `Map` or a record at the boundary. This keeps lazy-loading proxies and unrelated fields out of your decisions.

## Results: `DecisionResult`

| Method | Content |
|---|---|
| `output()` | `Map<String, Object>` of outputs, in the order the table declares them. Values are `Boolean`, `Long`, `String`, `BigDecimal` or `List`. Under `FIRST` and `UNIQUE` it holds only the outputs set by the matching rule; under `COLLECT` every output is a list. |
| `reasonCodes()` | Reason codes of the matching rule(s). |
| `matched()` | Whether any rule matched. |
| `matchedRules()` | Ids of the matching rules, in order. |
| `trace()` | Each evaluated rule with whether it matched, as `TraceStep(ruleId, matched)`. |
| `ruleVersion()` | `<version>@sha256:<hash>`, identifying the exact rule content used. |
| `decisionId()` | The decision's id. |

Store at least `decisionId`, `ruleVersion`, `output`, `reasonCodes` and `trace` with each business decision. Together with the inputs, they let you explain and reproduce it. None of these contain input values, so they are safe to log; the inputs themselves need your normal data-protection handling.

Under `FIRST` or `UNIQUE`, always handle "no rule matched":

```java
if (!result.matched()) {
    // the table has no rule for this case
}
```

or end the table with a `"when": "true"` rule so that every case gets an answer.

## Errors

All exceptions extend `RuleStoneException`, an unchecked exception.

| Exception | When | What to do |
|---|---|---|
| `InvalidDecisionException` (core) | Loading a file or building an engine. `problems()` lists every problem with its location. | Fix the definition. At startup, fail fast; when reloading, keep the previous engine (see below). |
| `DecisionNotFoundException` (api) | No decision with that id, no such version, or no version effective on the date. | Configuration error: check registered versions and their dates. |
| `DecisionEvaluationException` (api) | Invalid input (missing, wrong type, floating point for a decimal), an expression failing at run time, or a `UNIQUE` table with several matches. `decisionId()` and `ruleVersion()` identify the decision. | Treat as a system failure for this case, e.g. route it to manual review. **Do not substitute a default decision.** |

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
