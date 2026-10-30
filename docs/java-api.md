# Java API

This page explains how to embed RuleStone in a Java application: building an engine, passing inputs, reading results, handling errors and reloading rules. For complete signatures, see the Javadoc (`mvn javadoc:javadoc`, or the `-javadoc.jar` of each module).

- [Modules](#modules)
- [Building an engine](#building-an-engine)
- [Evaluating a decision](#evaluating-a-decision)
- [Timeouts](#timeouts)
- [Inputs: `Facts`](#inputs-facts)
- [Money, dates and the business date](#money-dates-and-the-business-date)
- [Results: `DecisionResult`](#results-decisionresult)
- [Decision records and auditing](#decision-records-and-auditing)
- [JSON facts and results: `FactsJson`](#json-facts-and-results-factsjson)
- [Errors](#errors)
- [Reloading rules](#reloading-rules)
- [Canary and shadow versions](#canary-and-shadow-versions)
- [Threading and performance](#threading-and-performance)
- [Using expressions directly](#using-expressions-directly)

## Modules

| Artifact | Package | Use |
|---|---|---|
| `rulestone-api` | `vn.io.dxa.rulestone.api` | Types your application code calls: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult` and the exceptions, and the model SPI, `ModelInvoker` and `ModelResult`. Depends on the JDK only. |
| `rulestone-core` | `vn.io.dxa.rulestone.core` | The embedded engine, decision tables and the `.stone.json` format. |
| `rulestone-cel` | `vn.io.dxa.rulestone.cel` | The expression language. Used by `rulestone-core`; use it directly only to evaluate stand-alone expressions. |
| `rulestone-audit-kafka` | `vn.io.dxa.rulestone.audit.kafka` | An audit sink that publishes decision records to Kafka; see [Publishing records to Kafka](#publishing-records-to-kafka). |
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
| `flow(DecisionFlow)` / `flows(Collection)` | Registers [decision flows](flows.md), read with `DecisionFlowJson.read(...)`. Register the tables they use with the same builder. |
| `maxIterations(int)` | Upper bound on iterations of any list function in any rule. Default 10,000. |
| `currencyScale(String, int)` | Number of digits after the decimal point that `money.round()` uses for a currency, overriding ISO 4217. Required for codes without minor units in ISO 4217, such as `XAU`. Throws `IllegalArgumentException` for a code that is not upper-case ISO 4217 or digits outside 0 to 18. |
| `auditSink(AuditSink)` | Receives the `DecisionRecord` of every evaluation, successful or not. May be called several times; sinks run in order. See [Decision records and auditing](#decision-records-and-auditing). |
| `factsHashKey(byte[])` | Makes `DecisionRecord.factsHash()` an HMAC-SHA256 with this key (at least 16 bytes; use 32 random bytes from your key store). Without it, the hash is plain SHA-256. |
| `recordIds(Supplier<String>)` | Source of record ids. Defaults to random UUIDs. |
| `timeout(Duration)` | Default timeout of every evaluation, for requests without their own; see [Timeouts](#timeouts). By default there is none. |
| `canary(String, String, double)` / `shadow(String, String)` | Routes a share of the requests for a decision to a new version, or evaluates a new version in shadow on every request; see [Canary and shadow versions](#canary-and-shadow-versions). |
| `shadowExecutor(Executor)` | Runs shadow evaluations on this executor instead of the caller's thread. |
| `modelInvoker(String, ModelInvoker)` | Registers the invoker that calls a model for the [model nodes](flows.md#model-nodes) of flows in `invoke` mode; see [Models in decision flows](#models-in-decision-flows). |
| `suppliedModelsOnly()` | Runs every model node in `supplied` mode, calling no model: for tests and replays, never for production. |
| `build()` | Compiles and type-checks every rule and flow, checks versions for duplicates and contradictory dates (see [Versions and effective dates](decision-tables.md#versions-and-effective-dates)) and that every canary and shadow names a registered version, and returns an immutable engine. Throws `InvalidDecisionException` listing every problem. |

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
| `DecisionRef.of("loan_eligibility").version("2")` | Exactly version `2`, regardless of dates: also a version that takes effect only later, or is no longer in force. For replays, investigations and trying a version before its effective date. |
| `….correlationId("LOAN-2026-000123")` | Adds your id for the business transaction to the decision record, so that it can be joined with your own data. It must not contain personal data. Combines with any of the above. |
| `….timeout(Duration.ofMillis(2))` | Fails the evaluation with a `DecisionTimeoutException` if it takes longer; see [Timeouts](#timeouts). Combines with any of the above. |

Use the business date of the transaction for `asOf`: the application date for a loan, the value date for a payment. Then re-evaluating the same case later gives the same answer. The same date reaches the rules as their implicit `businessDate` input; without `asOf` it is today according to the engine clock, also when you name a version.

## Timeouts

An evaluation usually takes microseconds, but a table can take unexpectedly long: a large list in the facts, a `filter` over a long history, a `COLLECT` table with many rules. When the caller has a deadline of its own, such as a payment that must be decided within its gRPC deadline, give the evaluation the time that is left:

```java
Duration left = Duration.between(clock.instant(), deadline);
if (left.isNegative() || left.isZero()) {
    return fallback.decide(payment);
}
try {
    DecisionResult result = engine.evaluate(
            DecisionRef.of("gate_decision").asOf(valueDate).correlationId(paymentId).timeout(left),
            facts);
    return Decision.from(result);
} catch (DecisionTimeoutException e) {
    return fallback.decide(payment);   // e.trace() lists the rules examined in time
}
```

- The timeout counts from the call to `evaluate`, on a monotonic clock (`System.nanoTime()`), never the engine clock: it bounds how long an evaluation takes and never changes its result.
- The engine checks the time before each rule. It needs no thread of its own, and it never interrupts a rule half-way, so an evaluation can overrun its timeout by the time one rule takes. `maxIterations` bounds that.
- An evaluation that runs out of time fails with a `DecisionTimeoutException`, a `DecisionEvaluationException`, whose `timeout()` and `trace()` say what the limit was and which rules were examined. Its decision record has the status `TIMEOUT` and shows the rules that matched until then. The record still goes to every audit sink, so the time the sinks take comes on top.
- `RuleStone.builder().timeout(...)` sets a default for requests without their own. A request's `timeout` replaces it, also when it is longer.

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

Facts can also carry the results of models that you ran yourself, for the [model nodes](flows.md#model-nodes) of a flow in `supplied` mode: `facts.withModelResult("fraud", result)`, or `withModelFailure("fraud", ModelStatus.TIMEOUT)` if the call failed. They are kept apart from the inputs, under the id of the node: `asMap()` returns the inputs only, and `models()` the model results. A decision that has no such node rejects them.

## Money, dates and the business date

`Money` (in `vn.io.dxa.rulestone.api`) is a record of a `BigDecimal` amount and an ISO 4217 currency code:

```java
Money price = Money.of("1500000.50", "VND");       // or Money.of(new BigDecimal("1500000.50"), "VND")
price.amount();                                     // 1500000.50
price.currency();                                   // "VND"
price.toString();                                   // "1500000.50 VND"
```

The constructor and factories reject a `null` amount and a currency that is not an upper-case ISO 4217 code. The valid codes and their minor units come from `Iso4217`, a fixed table (ISO 4217 up to amendment 180, plus withdrawn codes that Java has long accepted), not from `java.util.Currency`, whose codes differ between JDK versions. `Money` never rounds. Its `equals`, like `BigDecimal`'s, is scale-sensitive (`1.0 VND` is not equal to `1.00 VND`); compare amounts with `compareTo` when that matters. Inside rules, `==` compares numerically.

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
| `status` | `OK`; `ERROR` if the caller received an exception; `TIMEOUT` if the evaluation ran out of time (see [Timeouts](#timeouts)). A decision that matched no rule is `OK` with no reason codes. |
| `factsHash` | A hash of the declared inputs; see below. Left out if the inputs were invalid. |
| `output` | The outputs, written as by `FactsJson`. Empty unless the status is `OK`. |
| `reasonCodes` | Each reason code with the node that produced it. |
| `path` | Each node evaluated. For a decision table, the table with its version and the rules that matched; for a [decision flow](flows.md#decision-records), every node visited, in order, and for a model node, the model, its status and, on success, its outputs and ranked attributions (see [Model steps in the record](flows.md#model-steps-in-the-record)). When evaluation fails inside a table, it shows the rules that matched before the failure. |
| `error` | Present only when the status is not `OK`: the exception's message, which never contains input values. For exceptions that are not `RuleStoneException`s, only the exception type is recorded. |
| `routing` | Present only when a version other than the one in force was evaluated, as a canary or a shadow; see [Canary and shadow versions](#canary-and-shadow-versions). |

**No input values.** A record never contains input values. `factsHash` identifies the inputs instead: it is computed over the declared inputs in a canonical form, so equivalent inputs always give the same hash. In this canonical form:

- keys are sorted;
- decimals and money amounts have no trailing zeros, so `1.0` and `1.00` hash alike, just as rules treat them alike;
- undeclared facts and `businessDate` are left out.

To verify a decision later, recompute the hash from the inputs you stored and compare it with the record.

**Use a key.** Without `factsHashKey`, the hash is plain SHA-256. Someone who has a record could then test guesses of low-variety inputs, such as a birth date, against it. With a key, the hash is HMAC-SHA256 (`hmac-sha256:…`), and only holders of the key can do that. Use the same key wherever records are verified.

**Sinks must succeed.** Sinks run synchronously on the evaluating thread. If a sink throws, the evaluation fails with an `AuditException` and the caller gets no result: a decision is never returned without its record having been accepted. A sink that must not slow decisions down can hand records to its own queue. When an evaluation has already failed and a sink also fails, the caller receives the original exception, with the `AuditException` attached as suppressed.

### Publishing records to Kafka

`rulestone-audit-kafka` publishes records to a Kafka topic, written by `DecisionRecordJson` and keyed by correlation id, so that the records of one transaction stay in order on one partition. It is fast enough for an inline path: it hands each record to the producer's buffer and does not wait for the broker. Bring your own Kafka client; the module is compiled against `kafka-clients` 4.4 and does not redistribute it.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-audit-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.4.0</version>
</dependency>
```

```java
Producer<String, String> producer = new KafkaProducer<>(kafkaConfig, new StringSerializer(), new StringSerializer());

RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .auditSink(KafkaAuditSink.builder(producer, "rulestone.decisions")
                .failWhenBufferBelow(1 << 20)   // fail at once when less than 1 MiB of buffer is free
                .onDeliveryFailure((recordId, e) -> undelivered.add(recordId))
                .build())
        .build();
```

For an asynchronous sink, "accepted" means that the producer has taken the record:

- **The evaluation fails** with an `AuditException` when the producer refuses the record at once: its buffer is full, the topic's metadata cannot be fetched in time, or the producer is closed. `KafkaProducer` reports a full buffer through a failed `Future` rather than an exception; the sink checks for it, so such a record is never counted as accepted.
- **When the buffer fills up**, because the brokers cannot keep up or cannot be reached, the sink fails fast by default: it refuses records once less than `failWhenBufferBelow` bytes are free (1 MiB by default; use at least the producer's `batch.size`). `blockWhenBufferFull()` lets the producer wait for space instead, for up to its `max.block.ms`. On an inline path, failing fast is safer: your fallback decides instead of the payment waiting on the audit log. Either way, `max.block.ms` is the longest a write can block, also while the producer fetches the topic's metadata after it starts, so set it to what your callers can afford.
- **A record the producer took but could not deliver**, after its retries and `delivery.timeout.ms`, is reported to `onDeliveryFailure` with the record id, and counted by `deliveryFailures()`. By then the decision has been returned, so monitor both. Keep the producer's defaults `acks=all` and `enable.idempotence=true`.

The sink does not close the producer: close it when the application stops, after the engine is no longer used, so that buffered records are sent.

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
| `readModels(JsonNode json)` | a JSON `models` object of model node id to result or failure, as in [Supplied results](flows.md#supplied-results), to the `Map` that `Facts.withModels` takes |
| `readFacts(JsonNode facts, Map<String, ValueType> inputs, JsonNode models)` | inputs and model results together; problems in the model results are located under `models.` |
| `readValues(JsonNode json, Map<String, ValueType> types)` | a JSON object of named values to a `Map`, e.g. expected outputs with the types from `resultTypes(table)` |
| `readValue(JsonNode json, ValueType type)` | one value |
| `resultTypes(DecisionTable table)` | the types of the values in `DecisionResult.output()`: the declared outputs, each a list under `COLLECT` |
| `writeValue(Object value)`, `writeValues(Map<String, ?> values)` | Java values to a `JsonNode` or an `ObjectNode` |
| `write(Map<String, ?> values)` | Java values to compact JSON text |

## Models in decision flows

A decision flow can use machine-learning models through [model nodes](flows.md#model-nodes). The types are in `rulestone-api`, so `rulestone-core` depends on no model vendor; adapters for HTTP model servers, scorecards and ONNX are planned as separate modules.

| Type | Role |
|---|---|
| `ModelInvoker` | Calls a model: `ModelResult invoke(ModelCall call) throws ModelException`. Register one per model id with `RuleStone.builder().modelInvoker(modelId, invoker)`. Must be thread-safe and return within `call.timeout()`. |
| `ModelCall` | The model id and pinned version, the node's declared features as typed values, the time available, and the correlation id. |
| `ModelResult` | What the model returned: model id and version, outputs, an optional confidence, and attributions (`ModelResult.Attribution`: feature and contribution) with the name of the method that computed them. `ModelResult.of(id, version, outputs)`, then `withConfidence` and `withAttributions`. |
| `ModelException` | Thrown by an invoker that cannot answer. Like any exception from an invoker, it becomes a model failure of kind `ERROR`, never an exception for the caller. |
| `ModelStatus` | `OK`, or the kind of failure: `TIMEOUT`, `ERROR`, `CONTRACT_VIOLATION`, `INVALID_OUTPUT`, `LOW_CONFIDENCE`, `MISSING`. |
| `SuppliedModel` | A result or a failure that the caller passes with the facts, see `Facts.withModelResult`. |
| `DecisionRecord.ModelStep` | The model part of a record's path step: what the model was asked and answered, without feature values or contributions. |

`toString()` of `ModelCall` and `ModelResult` names features and outputs but never shows values. See [Model nodes](flows.md#model-nodes) for the contract a result must meet, the failure path and the reason codes.

## Errors

All exceptions extend `RuleStoneException`, an unchecked exception.

| Exception | When | What to do |
|---|---|---|
| `InvalidDecisionException` (core) | Loading a file or building an engine. `problems()` lists every problem with its location. | Fix the definition. At startup, fail fast; when reloading, keep the previous engine (see below). |
| `InvalidFactsException` (core) | Reading JSON facts or values with `FactsJson`. `problems()` lists every problem with its location. | Reject the request as invalid input, e.g. with HTTP 400 and the problems. |
| `DecisionNotFoundException` (api) | No decision with that id, no such version, or no version effective on the date. | Configuration error: check registered versions and their dates. |
| `AuditException` (api) | An audit sink threw while accepting a decision record; the sink's exception is the cause and `recordId()` names the record. | Treat as a system failure: the decision was not returned and must not be assumed. |
| `DecisionTimeoutException` (api) | The evaluation took longer than its timeout. A `DecisionEvaluationException`, so catch it first. `timeout()` and `trace()` say what the limit was and which rules were examined. | Apply your fallback for a decision that could not be made in time. |
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

## Canary and shadow versions

Before a new version of a decision takes over, you can run it on live traffic without changing a single answer (shadow), then let it answer for a small share of the requests (canary):

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)                     // versions 1, 2 and 3 of gate_decision
        .canary("gate_decision", "2", 0.05)    // 5% of correlation ids get version 2
        .shadow("gate_decision", "3")          // version 3 is evaluated on every request, only to be recorded
        .shadowExecutor(shadowPool)            // off the caller's thread
        .auditSink(auditSink)
        .build();
```

Routing applies to requests that do not name a version; a request with `DecisionRef.version` gets exactly that version. Neither the canary nor the shadow needs to be in force: a version whose `effectiveFrom` is still to come can be tried on today's traffic, with today's `businessDate`.

**Canary.** A request goes to the canary when its correlation id falls into the canary's share: the first 64 bits of the SHA-256 of `"<decision id>\n<correlation id>"`, as a fraction, are below the share. So a retried payment always gets the same version, and two decisions with canaries split their traffic independently. Requests without a correlation id always get the version in force. The caller receives the canary's result, and its record says why:

```json
"routing": { "kind": "CANARY", "champion": "1@sha256:9b1d…", "share": 0.05 }
```

`version` is the canary's version; `champion` is the version in force that the request would otherwise have used. Once the canary's `effectiveFrom` arrives it is in force for every request, and records no longer carry `routing`.

**Shadow.** After each request for the decision, the engine evaluates the shadow version on the same facts, with the same `businessDate`, and hands its record to the audit sinks. The caller never sees the shadow's result, and a shadow that fails, or whose record a sink rejects, never affects the request. The shadow's record links to the request's:

```json
"routing": { "kind": "SHADOW", "champion": "1@sha256:9b1d…", "shadowOf": "3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75" }
```

To compare champion and challenger, join the records of shadows on `shadowOf` with the records whose `recordId` it names, and compare `output` and `reasonCodes`. Both have the same `factsHash` when the two versions declare the same inputs. By default the shadow runs on the caller's thread after the request's own evaluation, which adds its time to every request; with `shadowExecutor` it runs on your executor instead, and a shadow the executor rejects is skipped. The facts must then not change after `evaluate` returns.

A shadow is skipped when the request names a version, when no version was found for it, and when the request already used the shadow version, for example as its canary. Change the share, or promote the canary, by building a new engine and swapping it in, as in [Reloading rules](#reloading-rules).

Canaries and shadows work the same way for [decision flows](flows.md): name the flow's id. Inside a flow, table nodes always use the table version in force, or the version they pin; canaries and shadows of those tables apply only when the tables are evaluated on their own.

## Threading and performance

- An engine is **immutable and thread-safe**. Build it once and share it.
- `build()` does all parsing and type-checking. `evaluate()` only converts inputs and runs pre-compiled expressions.
- Inputs are converted once per evaluation, then shared by all rules of the table.
- Evaluation does no I/O and holds no locks.

### Benchmark

`rulestone-benchmark` measures one call to `evaluate` with [JMH](https://github.com/openjdk/jmh), everything included: version selection, input conversion, the facts hash, the rules and the decision record, without an audit sink. Its tables have 10, 100 or 1,000 rules plus a catch-all, each comparing a `money` or a `decimal` field of a struct with a threshold, or scanning a list of 20 structs with `exists`; under `FIRST` half the rules are examined, under `COLLECT` all of them. See [its README](../rulestone-benchmark/README.md) for the details. Run it on your own hardware:

```sh
mvn -pl rulestone-benchmark -am package -DskipTests
java -jar rulestone-benchmark/target/benchmarks.jar -prof gc -t 4
```

Results on a shared cloud VM: 4 vCPUs (Intel Xeon, 2.8 GHz), OpenJDK 21.0.12, G1 with a 2 GB heap, one fork, 15 s warm-up and 10 s measurement per table. Latencies in µs; allocation in KB per evaluation.

| Hit policy | Input | Rules | p50, 1 thread | p99, 1 thread | p50, 4 threads | p99, 4 threads | p99.9, 4 threads | Allocated |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| `FIRST` | `MONEY` | 10 | 27.3 | 86.7 | 21.2 | 93.7 | 489 | 44 |
| `FIRST` | `MONEY` | 100 | 65.0 | 210 | 64.8 | 198 | 612 | 138 |
| `FIRST` | `MONEY` | 1,000 | 559 | 1,252 | 541 | 1,556 | 5,391 | 1,047 |
| `FIRST` | `DECIMAL` | 10 | 18.6 | 75.4 | 20.4 | 88.2 | 370 | 44 |
| `FIRST` | `DECIMAL` | 100 | 61.6 | 162 | 64.4 | 190 | 733 | 133 |
| `FIRST` | `DECIMAL` | 1,000 | 589 | 1,319 | 566 | 1,452 | 4,974 | 997 |
| `FIRST` | `LIST` | 10 | 182 | 480 | 189 | 517 | 3,148 | 313 |
| `FIRST` | `LIST` | 100 | 1,628 | 3,530 | 1,624 | 5,816 | 9,706 | 2,405 |
| `FIRST` | `LIST` | 1,000 | 15,876 | 31,642 | 19,235 | 43,062 | 67,809 | 27,543 |
| `COLLECT` | `MONEY` | 10 | 25.5 | 106 | 27.5 | 123 | 509 | 62 |
| `COLLECT` | `MONEY` | 100 | 146 | 389 | 152 | 412 | 3,261 | 343 |
| `COLLECT` | `MONEY` | 1,000 | 1,473 | 3,257 | 1,595 | 5,063 | 9,749 | 3,054 |
| `COLLECT` | `DECIMAL` | 10 | 25.1 | 102 | 32.7 | 145 | 663 | 63 |
| `COLLECT` | `DECIMAL` | 100 | 153 | 347 | 147 | 404 | 3,273 | 346 |
| `COLLECT` | `DECIMAL` | 1,000 | 1,585 | 3,664 | 1,647 | 5,751 | 9,870 | 3,007 |
| `COLLECT` | `LIST` | 10 | 334 | 741 | 371 | 897 | 4,361 | 512 |
| `COLLECT` | `LIST` | 100 | 3,342 | 7,872 | 3,043 | 6,802 | 10,845 | 4,799 |
| `COLLECT` | `LIST` | 1,000 | 36,635 | 79,299 | 34,341 | 69,615 | 84,482 | 52,833 |

What the figures say:

- **A small table takes about 20 µs**, most of it fixed cost: input conversion, the facts hash and the record. Each further rule comparing money or decimals adds about 1 µs, and about 1.5 µs under `COLLECT`, which also evaluates the outputs of the half that match.
- **List functions dominate.** `exists` over 20 structs costs about 30 µs per rule, roughly 1.5 µs per element. Keep comprehensions over long lists out of large tables, or compute the aggregate before the decision, and give inline callers a [timeout](#timeouts).
- **One engine scales across threads.** The median with 4 threads on one engine is within noise of the median with one thread: evaluation shares nothing mutable. The tails grow with 4 threads on 4 vCPUs, because the benchmark threads then compete with the JIT and the garbage collector for the same cores.
- **Allocation is the main cost of the tail.** An evaluation allocates about 44 KB, plus 2 to 3 KB per rule examined, and about 50 KB per rule that scans a list of 20. Size the young generation for the request rate, and prefer a low-pause collector (Generational ZGC) where p99.9 matters.

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
