# Replay and re-score

A [decision record](java-api.md#decision-records-and-auditing) names everything a decision used: the exact decision version, the business date, the parameter set versions, the version of every table of a flow, and what every model returned. It holds no input values. With the record and the original facts, RuleStone can make the decision again:

- **Replay** re-runs a recorded decision exactly as it was made and checks that the outcome is the same. An auditor uses it to reproduce a decision years later, after the rules, the parameters and the models have changed.
- **Re-score** runs the same facts through another version, the version in force today, other parameters or today's models, and reports what would change. It is used to investigate complaints and to measure the impact of a change to rules or models. It is never written back as the decision.

Replay and re-score are step 5 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan). They are available in the Java engine.

- [Example](#example)
- [Storing records](#storing-records)
- [Replaying a decision](#replaying-a-decision)
- [Re-scoring a decision](#re-scoring-a-decision)
- [The comparison](#the-comparison)
- [Versions that are not registered](#versions-that-are-not-registered)
- [Reading records back](#reading-records-back)
- [Golden records in tests](#golden-records-in-tests)
- [Workflows](#workflows)
- [Limits](#limits)

## Example

The [loan_eligibility v4](examples.md#loan-eligibility-priorities-and-versions) table reads its limits from the `retail_credit` parameter set. Version 1 of the set allows a debt-to-income ratio of up to 0.50; version 2, in force from 1 July 2027, only 0.45. An applicant with a ratio of 0.48 is approved on 30 June 2027:

```java
RuleStoneEngine engine = RuleStone.builder()
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v1.params.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v2.params.json")))
        .factsHashKey(factsHashKey)
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2027, 6, 30)).correlationId("LOAN-2027-000042"),
        facts);   // decision APPROVE
```

Later, the record is read back from the audit store and replayed with the facts from the bank's own data store:

```java
DecisionRecord record = DecisionRecordJson.read(stored);

DecisionComparison replay = engine.replay(record, facts);
replay.matches();   // true: the same outputs, reason codes and path, from the same facts
```

The customer complains in August. Would the decision be the same under the current policy?

```java
DecisionComparison rescore = engine.rescore(record, facts,
        DecisionRef.of("loan_eligibility").asOf(record.businessDate()).params("retail_credit", "2"),
        ModelSource.RECORDED);
rescore.differences();
```

```text
PARAMS: params: recorded retail_credit 1@sha256:…, now retail_credit 2@sha256:…
OUTPUT: output 'decision' differs
OUTPUT: output 'limit': recorded, not set now
REASON_CODES: reasonCodes: recorded [], now [DTI_HIGH from loan_eligibility]
PATH: path step 1 (loan_eligibility): recorded matched rules [R5], now [R4]
```

Under version 2 the application would be rejected by rule `R4` with `DTI_HIGH`. `rescore.evaluated()` is the record that evaluation would have produced, and `rescore.result()` its result, with the new outputs.

## Storing records

Replay needs two things that the engine does not keep:

- **The record**, written by an [audit sink](java-api.md#decision-records-and-auditing), for example with `DecisionRecordJson.write` to an append-only store or with the [Kafka sink](java-api.md#publishing-records-to-kafka). Keep the `recordId` and the `correlationId` with the business case, so that the record can be found.
- **The facts**, in your own data store, under your normal data-protection controls. The record proves which facts were used with its `factsHash`; give the engine the same `factsHashKey` when you replay.

Keep every decision version, table version and parameter set version registered with the engine that replays, as you already do for [versions and effective dates](decision-tables.md#versions-and-effective-dates): replay uses exactly the versions the record names.

## Replaying a decision

`engine.replay(record, facts)` evaluates the facts with exactly what the record names, whatever is in force today:

| From the record | How it is used |
|---|---|
| `version` | The exact decision version, table or flow, with its content hash. |
| `businessDate` | The business date, which rules read as `businessDate`. |
| `params` | The exact version of each parameter set. |
| Table steps of a flow's `path` | The exact version each `table` node used, also if a version registered later would now be in force. |
| Model steps of the `path` | Each `model` node's result: the outputs, the confidence, and attributions rebuilt from their ranks and directions, which give the same reason codes. A recorded failure is the same failure again. **No model is called**, so the decision replays after the model has been retrained or retired. |
| `correlationId` | Copied to the new record. |

The replay checks the facts against the record's `factsHash`, computed with the engine's `factsHashKey`. It writes **nothing to the audit sinks**, uses no canary or shadow version and no timeout, and reads the engine clock only to date its own record. The facts must not carry model results; they come from the record.

A record of a failed evaluation replays too: the replay must fail with the same error. A record with the status `TIMEOUT` was cut short, so its replay runs to the end and reports the status as a difference.

## Re-scoring a decision

`engine.rescore(record, facts, target, models)` evaluates the facts with the decision `target` selects, exactly as `evaluate` would, and compares the outcome with the record:

| `target` | Evaluates |
|---|---|
| `DecisionRef.of(id).asOf(record.businessDate())` | The version in force on the original business date, with the parameter set versions in force then, as the engine knows them now. |
| `DecisionRef.of(id)` | Today's version, on today's business date, with today's parameters. |
| `DecisionRef.of(id).version("5").asOf(record.businessDate())` | A named version, for example a candidate before its effective date. |
| `….params("retail_credit", "2")` | A named parameter set version. |

`target` must name the recorded decision. Without a correlation id of its own, it takes the record's. No canary or shadow version is used, and nothing is written to the audit sinks.

Before it evaluates, re-score checks the facts against the record's `factsHash` with the recorded version, which must therefore still be registered. Facts that do not match give a `FACTS` difference.

`models` says where model nodes get their results, and is always explicit:

| `ModelSource` | Model nodes get | Use it to measure |
|---|---|---|
| `RECORDED` | The result supplied with the facts for the node, if any; otherwise the result the record shows for the node, if it names the same model id and version; otherwise none, so the node fails with `MISSING` and takes its `onFailure` path. No model is called. | The impact of a change to rules or parameters alone, or of a challenger model whose scores you computed offline and supply with `facts.withModelResult`. |
| `LIVE` | What `evaluate` would give them: `invoke` nodes call their invokers, `supplied` nodes read the facts. | The impact of today's models. The models are called; their latency counts against the request's timeout. |

## The comparison

Both methods return a `DecisionComparison`:

| Method | Content |
|---|---|
| `recorded()` | The record you passed. |
| `evaluated()` | The record of the new evaluation, with a new record id and the instant of the evaluation. It went to no audit sink; do not store it as a decision. |
| `result()` | The result of the new evaluation; empty if it failed. |
| `differences()` | What differs, in the order of the record's fields. |
| `matches()` | Whether there is no difference at all. |

Each `Difference` has a `kind` and a `description`. The descriptions name outputs, nodes, rules, versions, dates and reason codes, but **never input or output values**, so they can be logged like the record. Compare the values of `recorded()` and `evaluated()` where you need them.

| Kind | Differs |
|---|---|
| `FACTS` | The facts do not give the record's `factsHash`: other facts, or another `factsHashKey`. |
| `STATUS` | The status, or the error message of a failed evaluation. |
| `VERSION` | The exact decision version. |
| `BUSINESS_DATE` | The business date. |
| `PARAMS` | The parameter set versions. |
| `OUTPUT` | An output's value, or whether it is set. Values are compared in their JSON forms, so `"0.0980"` read back from a record equals the decimal `0.0980`. |
| `REASON_CODES` | The reason codes, their sources or their order. |
| `PATH` | A step of the path: the node, a table version, the matched rules, the branch taken, or a model's id, version, status, outputs, confidence or attributions. A model's mode, latency and failure detail say how a result was obtained, not what it was, and are not compared. |

## Versions that are not registered

If a version the record names is not registered with exactly the recorded content hash, `replay` and `rescore` fail with a `DecisionNotFoundException` that names the version, never a value:

```text
decision 'loan_eligibility' has no version 4@sha256:9f2c…: version '4' is registered with other content, 4@sha256:77ba…; register the content the record names
parameter set 'retail_credit' has no version 1@sha256:41d8…
```

The first message means that someone changed the rules of version `4` without giving them a new version label. Register the original content again; publish changes under a new label.

## Reading records back

`DecisionRecordJson.read` reads a record that `DecisionRecordJson.write` wrote, from a `String`, an `InputStream`, a `Path` or a `JsonNode`. It is strict: unknown and duplicate fields, missing fields and malformed values are reported together in an `InvalidRecordException`, whose `problems()` give the location and the expected form, never the value.

A record carries no types, so output values read back are the Java forms of their JSON: a `String` for a decimal, money or date such as `"0.0980"`, a `Long` for an integer, a `Boolean`, a `List` or a `Map`. Writing the record again gives the same JSON.

## Golden records in tests

`ReplayCheck` in `rulestone-test` asserts that recorded decisions still replay, for example a few reviewed records kept with the rules, so that a build fails when a release would no longer reproduce them:

```java
ReplayCheck.assertReplays(engine, Path.of("golden/loan-2027-000042.record.json"), facts);
```

It throws an `AssertionError` that lists the differences.

## Workflows

- **Temporal.** `DecisionOutcome.recordId()` names the record of a decision, and `correlationId()` its correlation id, which the activity takes from `DecisionRequest.withCorrelationId`. In-workflow evaluation records the decision record in workflow history and replays it on every later workflow task, so the audit sinks receive it once and the outcome names the same record every time. See [Temporal integration](temporal.md#decision-records).
- **Camunda.** The result variable holds `recordId`, and `correlationId` when the task names a `correlationIdVariable`. See [Camunda integration](camunda.md#decision-records).

## Limits

- The TypeScript engine of the [Editor SDK](editor-sdk.md) replays and re-scores records too (`engine.replay`, `engine.rescore`, `readDecisionRecord`), with the same comparison, and replays records of the Java engine.
- Data looked up during evaluation is not recorded, because RuleStone looks nothing up: everything a decision reads is in the facts.
- A re-score with `ModelSource.LIVE` calls today's models, which are not deterministic in general; its outcome is a measurement, not a decision.
