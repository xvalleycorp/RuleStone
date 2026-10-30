# Decision flows

A decision flow chains decision tables and simple logic into one decision: policy knock-outs first, then segmentation, grading and a limit matrix, with a champion/challenger split where you want to test a new strategy. This page is the reference for the `.flow.json` format and for how flows are checked, evaluated and recorded.

Flows are steps 2 and 3 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan). A flow can also use machine-learning models: a [model node](#model-nodes) either calls the model or takes the result the caller passes, and checks it against a typed contract. The Java engine runs flows; the browser engine of the [Editor SDK](editor-sdk.md) does not.

- [A first flow](#a-first-flow)
- [Running a flow](#running-a-flow)
- [File format](#file-format)
- [Nodes](#nodes)
- [Values between nodes](#values-between-nodes)
- [Model nodes](#model-nodes)
- [Evaluation](#evaluation)
- [Decision records](#decision-records)
- [Versions and content hashes](#versions-and-content-hashes)
- [Validation](#validation)
- [Limitations](#limitations)

## A first flow

The flow below, [`examples/flows/card_origination.flow.json`](../examples/flows/card_origination.flow.json), decides on a credit card application. It uses four decision tables from the same folder: `card_policy`, `card_grade_existing`, `card_grade_new` and `card_limit`.

```json
{
  "id": "card_origination",
  "version": "1",
  "description": "Credit card origination: knock-out policy, segmentation, risk grade, and a champion/challenger limit strategy",
  "effectiveFrom": "2026-11-01",
  "inputs": {
    "applicant": { "id": "string", "age": "int", "income": "money", "existingCustomer": "bool" },
    "bureau": { "dpd30Count12m": "int", "utilisation": "decimal" }
  },
  "outputs": { "decision": "string", "limit": "money", "riskGrade": "string" },
  "start": "policy",
  "nodes": {
    "policy": {
      "type": "table", "decision": "card_policy",
      "next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "affordability" } ]
    },
    "affordability": {
      "type": "derive",
      "outputs": { "annualIncome": "money" },
      "set": { "annualIncome": "applicant.income * 12" },
      "next": "segment"
    },
    "segment": {
      "type": "switch",
      "cases": [ { "when": "applicant.existingCustomer", "to": "grade_existing" }, { "to": "grade_new" } ]
    },
    "grade_existing": {
      "type": "table", "decision": "card_grade_existing", "as": "grade",
      "next": [ { "when": "grade.riskGrade == 'D'", "to": "referred" }, { "to": "limit_strategy" } ]
    },
    "grade_new": {
      "type": "table", "decision": "card_grade_new", "as": "grade",
      "next": [ { "when": "grade.riskGrade == 'D'", "to": "referred" }, { "to": "limit_strategy" } ]
    },
    "limit_strategy": {
      "type": "split", "key": "applicant.id", "salt": "card-limit-2026Q4",
      "buckets": [
        { "name": "champion", "percent": 90, "to": "limit" },
        { "name": "challenger", "percent": 10, "to": "limit" }
      ]
    },
    "limit": {
      "type": "table", "decision": "card_limit",
      "facts": {
        "riskGrade": "grade.riskGrade",
        "annualIncome": "affordability.annualIncome",
        "strategy": "limit_strategy.taken"
      },
      "next": "approved"
    },
    "approved": { "type": "end", "set": {
      "decision": "'APPROVE'", "riskGrade": "grade.riskGrade", "limit": "limit.limit" } },
    "referred": { "type": "end", "set": {
      "decision": "'REFER'", "riskGrade": "grade.riskGrade", "limit": "money(0, applicant.income.currency())" } },
    "rejected": { "type": "end", "set": {
      "decision": "'REJECT'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } }
  }
}
```

In this flow:

- The policy table runs first. A rejection there ends the flow before anything else is evaluated.
- `affordability` computes the annual income once, for the limit table to use later.
- Existing customers are graded by one table and new customers by another. Both publish their result under the shared name `grade`, so later nodes read `grade.riskGrade` whichever table ran.
- Grade `D` is referred to a person, never declined automatically.
- 10% of applicants, chosen by applicant id, get the challenger limit strategy. The limit table receives the chosen bucket as its `strategy` input.

## Running a flow

Register the flow together with every table it uses, and evaluate it by id like a table:

```java
List<DecisionTable> tables = Stream.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit")
        .map(id -> DecisionTableJson.read(Path.of("rules/" + id + ".stone.json")))
        .toList();
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decisions(tables)
        .flow(DecisionFlowJson.read(Path.of("rules/card_origination.flow.json")))
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_origination").asOf(LocalDate.of(2026, 12, 1)).correlationId("CARD-2026-000777"),
        Facts.of("applicant", Map.of("id", "C-1001", "age", 35, "income", Money.of("20000000", "VND"),
                                     "existingCustomer", true),
                 "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.10"))));

result.output();         // {decision=APPROVE, limit=144000000 VND, riskGrade=A}
result.matchedRules();   // [PASS, A, A_CHALLENGER]: the matched rules of every table node, in order
```

`build()` checks the flow against the registered tables and throws `InvalidDecisionException` listing every problem (see [Validation](#validation)). Flows share the `id` namespace of tables, so a flow and a table cannot have the same `id`. Everything else in the [Java API](java-api.md) applies to flows as it does to tables: `DecisionRef`, `Facts`, `FactsJson`, timeouts, audit sinks, and canary and shadow versions.

## File format

A flow is a JSON object. Files end in `.flow.json`. Reading is strict, as for tables: unknown or duplicate keys are errors.

| Field | Required | Description |
|---|---|---|
| `id`, `version`, `description`, `effectiveFrom`, `effectiveTo` | as for a [decision table](decision-tables.md#file-format) | The same rules and meaning. Versions of a flow are selected as versions of a table are; see [Versions and effective dates](decision-tables.md#versions-and-effective-dates). |
| `inputs` | yes | Object mapping each input name to its [type](decision-tables.md#types), as for a table. Like a table, a flow also receives `businessDate` implicitly. |
| `outputs` | yes | Object mapping each output name to its type. Every `end` node sets all of them. As for tables, outputs cannot be structs. |
| `start` | yes | Id of the first node. |
| `nodes` | yes | Object mapping each node id to a node. A node id names the node's values in expressions, so it must be an identifier (letters, digits and `_`, not starting with a digit) and differ from every input name. |

## Nodes

Every node has a `type` and an optional `description`.

| `type` | Does | Publishes | Goes to |
|---|---|---|---|
| `table` | Evaluates a decision table | The table's outputs | `next` |
| `switch` | Chooses a branch | `taken`: the id of the node chosen | The first case whose `when` holds |
| `derive` | Computes named values once | The values | `next` |
| `model` | Obtains a model's result and checks it | The model's outputs | `next`, or `onFailure` when the model fails |
| `split` | Assigns a bucket by percentage | `taken`: the name of the bucket chosen | The bucket's node |
| `end` | Sets the flow's outputs | Nothing | Ends the flow |

The type name `flow` is reserved for calls to other flows. It is not supported yet, and a file that uses it is rejected.

### Branches: `next` and `cases`

`next` is either a node id, or an array of alternatives. `cases` of a `switch` is always an array:

```json
"next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "affordability" } ]
```

The alternatives are tried in order and the first whose `when` holds is taken. Only the last alternative has no `when`, and it must have none, so that one always applies. A `when` is a CEL condition, written as in a [table rule](expressions.md). In the `next` of a `table` or `derive` node, it can read the node's own values.

### `table`

| Field | Required | Description |
|---|---|---|
| `decision` | yes | Id of a registered decision table. |
| `version` | no | Always use this version of the table. Without it, the node uses the version in force on the business date. |
| `facts` | no | Object mapping a table input to a CEL expression over the flow's values. Inputs not listed receive the flow value of the same name. |
| `as` | no | A name to publish the table's outputs under instead of the node id. |
| `next` | yes | Where to go next. |

The table's outputs are published as one value with a field per output: `policy.decision`. Under `COLLECT`, each field is a list, as in the table's own result.

### `switch`

| Field | Required | Description |
|---|---|---|
| `cases` | yes | Array of alternatives, as described above. |

### `derive`

| Field | Required | Description |
|---|---|---|
| `outputs` | yes | Object mapping each value name to its type, as for table outputs. |
| `set` | yes | Object mapping each value name to a CEL expression of that type. Every value in `outputs` is set, and nothing else. |
| `as` | no | A name to publish the values under instead of the node id. |
| `next` | yes | Where to go next. |

The expressions of one `derive` node cannot read each other's values. To compute a value from another derived value, use a second `derive` node.

### `model`

Calls a machine-learning model, or takes the result the caller passes, and checks it. See [Model nodes](#model-nodes).

### `split`

| Field | Required | Description |
|---|---|---|
| `key` | yes | CEL expression producing a `string`, such as `applicant.id`. Use `string(applicant.number)` for an `int`. |
| `salt` | yes | Any text. Use a new salt for a new experiment, so that its buckets are independent of earlier ones. |
| `buckets` | yes | Array of `{ "name", "percent", "to" }`. Names are unique; percentages are whole numbers from 1 to 100 and add up to 100. |

The bucket depends only on the salt and the key: the first 64 bits of the SHA-256 of `"<salt>\n<key>"`, read as a fraction and multiplied by 100, fall into the buckets in order. The same customer therefore always gets the same bucket for the same salt, on every server and every day. Changing the salt or the percentages changes the flow's content hash, as any edit does.

### `end`

| Field | Required | Description |
|---|---|---|
| `set` | yes | Object mapping every output of the flow to a CEL expression of its type. |

## Values between nodes

Expressions in a node can read:

- the flow's inputs and `businessDate`, by name;
- the values of the nodes before it, under the node id or its `as` name: `policy.decision`, `affordability.annualIncome`, `segment.taken`.

**Only values set on every path.** A node can read a value only if every path from `start` to the node passes through a node that sets it. In the example, `referred` can read `grade.riskGrade` because both paths to it pass through a node that publishes `grade`; it could not read `affordability.annualIncome` if some path to it skipped `affordability`. This is checked when the engine is built, so a flow never reads a value that might not exist.

**Shared names.** Nodes on alternative branches can publish under the same `as` name if they publish the same values with the same types, as `grade_existing` and `grade_new` do. Two nodes on one path cannot share a name.

**Facts for a table.** A `table` node passes each of the table's inputs the flow value of the same name, which must have the same type, or, for a struct, at least the fields that the table declares. List an input in `facts` to pass it something else. A struct input can only be passed a value by name, for example `"customer": "applicant"`. In the example, `limit` passes `grade.riskGrade` as the table's `riskGrade` input and the chosen bucket as its `strategy`.

**Outputs that no rule set.** When no rule of a table matches, or the rule that matched does not set an output, that output is absent. Reading it fails the evaluation; test for it with `has()`, as in a table rule: `has(policy.decision) && policy.decision == 'REJECT'`.

## Model nodes

A `model` node brings a machine-learning model into a flow: a fraud score, a probability of default, a scorecard. It checks the model's result against a typed contract before any later node reads it, and it always has a declared path for when the model fails. The rules around it stay in charge: a model only produces typed values that later nodes read.

The example [`examples/flows/card_origination_scored.flow.json`](../examples/flows/card_origination_scored.flow.json) is the card origination flow with models. It uses the tables `card_policy`, `card_pd_grade` and `card_limit`, and has three model nodes:

```json
    "fraud": {
      "type": "model", "mode": "supplied",
      "model": { "id": "card_application_fraud", "version": "1.2.0" },
      "outputs": { "score": "decimal" },
      "valid": "score >= decimal(0) && score <= decimal(1)",
      "reasonCodes": { "deviceAgeDays": "NEW_DEVICE", "addressMismatch": "ADDRESS_MISMATCH" },
      "onFailure": { "to": "referred", "reasonCode": "FRAUD_SCORE_UNAVAILABLE" },
      "next": [ { "when": "fraud.score >= decimal('0.80')", "to": "referred" }, { "to": "segment" } ]
    },
    "segment": {
      "type": "switch",
      "cases": [ { "when": "applicant.existingCustomer", "to": "pd_behaviour" }, { "to": "pd_application" } ]
    },
    "pd_behaviour": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
      "features": { "utilisation": "bureau.utilisation", "dpd30Count12m": "bureau.dpd30Count12m" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION", "dpd30Count12m": "RECENT_DELINQUENCY" },
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
    "pd_application": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_application", "version": "3.1.0" },
      "features": { "age": "applicant.age", "utilisation": "bureau.utilisation" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "minConfidence": "0.70",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION", "age": "THIN_CREDIT_FILE" },
      "maxReasonCodes": 1,
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
```

In this flow:

- The policy table still runs first, so a knock-out never depends on a model.
- `fraud` is in `supplied` mode: the caller's fraud platform scores the application and passes the result with the facts. A score of 0.80 or more is referred to a person.
- `pd_behaviour` and `pd_application` are in `invoke` mode: the engine calls the model itself, with only the declared features. Both publish their output as `score`, so the `grade` table reads `score.pd` whichever model ran.
- Whenever a model fails, the application is referred, with a reason code that says why. It is never approved or declined on a guessed score.

### Fields

| Field | Required | Description |
|---|---|---|
| `mode` | yes | `invoke`: the engine calls the model through the `ModelInvoker` registered for its id. `supplied`: the caller passes the model's result with the facts. |
| `model` | yes | `{ "id", "version" }`: the model and the one exact version the flow uses, e.g. `"2.4.0"`. A range, a wildcard such as `2.x`, and `latest` are rejected. Both are part of the flow's content hash, so a new model version is a new flow version. |
| `features` | in `invoke` mode | Object mapping each feature name to a CEL expression over the flow's values. The model receives these values and nothing else. Each expression must produce a `bool`, `int`, `string`, `decimal`, `money` or `date`, or a list of them. Not allowed in `supplied` mode. |
| `outputs` | yes | Object mapping each output name to its type, as for table outputs. The model's result must have exactly these outputs. Output names are identifiers, because `valid` reads them by name. |
| `valid` | no | A CEL condition over the outputs, by name, e.g. `pd >= decimal(0) && pd <= decimal(1)`. If it is false, the result is invalid. |
| `minConfidence` | no | A decimal from 0 to 1, e.g. `"0.70"`. The model must then report a confidence, and a lower one is a failure. |
| `timeoutMs` | in `invoke` mode | How long the model may take, in milliseconds. Not allowed in `supplied` mode. |
| `reasonCodes` | no | Object mapping a feature name to the reason code to give when that feature is among the model's main reasons; see [Reason codes from attributions](#reason-codes-from-attributions). |
| `maxReasonCodes` | no | How many reason codes the node gives at most, from 1 to 10. The default is 3. |
| `onFailure` | yes | `{ "to", "reasonCode" }`: the node to go to when the model fails, and the reason code to give. |
| `as` | no | A name to publish the outputs under instead of the node id, as for a `table` node. |
| `next` | yes | Where to go when the result meets the contract. Conditions can read the outputs, e.g. `fraud.score`. |

On success, the node publishes its outputs under its id or `as` name, as a `table` node does: `fraud.score`, `score.pd`. On failure it publishes nothing. A node after `onFailure` therefore cannot read the model's outputs, and the engine refuses to build if one tries; this is the rule that a node may read only values that [every path to it sets](#values-between-nodes).

### Supplied results

In `supplied` mode, the caller passes the model's result with the facts, under the id of the node:

```java
Facts facts = Facts.of("applicant", applicant, "bureau", bureau)
        .withModelResult("fraud", ModelResult.of("card_application_fraud", "1.2.0",
                Map.of("score", new BigDecimal("0.05"))));
```

If the caller's own call of the model failed, it says so instead: `withModelFailure("fraud", ModelStatus.TIMEOUT)` (or `ERROR`). JSON callers write the same as a `models` object next to the facts, which `FactsJson.readModels` reads, or `FactsJson.readFacts(facts, inputs, models)` together with the facts:

```json
{
  "fraud": {
    "modelId": "card_application_fraud",
    "modelVersion": "1.2.0",
    "outputs": { "score": "0.05" },
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "deviceAgeDays", "contribution": "0.30" },
      { "feature": "addressMismatch", "contribution": "0.10" }
    ]
  },
  "pd_behaviour": { "failure": "TIMEOUT" }
}
```

`confidence`, `attributionMethod` and `attributions` are optional. Outputs are read with the types the node declares, through the [JSON forms](java-api.md#json-facts-and-results-factsjson): a decimal can be `"0.05"` or `0.05`, and money is `"1500000 VND"`. In Java, `BigDecimal`, `Long`, `Money` and `LocalDate` work, and so do their JSON text forms; `Double` and `Float` break the contract, since they cannot hold an exact decimal.

The engine checks a supplied result exactly as if it had called the model. A supplied result for a node that is not a `model` node, or that is in `invoke` mode, fails the evaluation, so a misspelt node id cannot go unnoticed. A model node in `supplied` mode without a result fails with `MISSING` and takes its `onFailure` path.

### Invoked models

In `invoke` mode, the engine calls the `ModelInvoker` registered for the model's id:

```java
ModelInvoker behaviourModel = call -> {
    // call.features(): {utilisation=0.40, dpd30Count12m=0}; call.timeout(): at most 80 ms
    ScoringResponse response = scoringClient.score(call.modelId(), call.modelVersion(), call.features(), call.timeout());
    return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", response.pd()))
            .withAttributions("SHAP", response.contributions());
};

RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flow(DecisionFlowJson.read(Path.of("rules/card_origination_scored.flow.json")))
        .modelInvoker("retail_pd_behaviour", behaviourModel)
        .modelInvoker("retail_pd_application", applicationModel)
        .build();
```

- **The call.** `ModelCall` holds the model id and pinned version, the declared features as typed values (exact decimals, never `double`), the time available and the request's correlation id. Its `toString()` names the features but never shows their values.
- **The time available** is the node's `timeoutMs`, or less if the evaluation's own [timeout](java-api.md#timeouts) leaves less. The invoker runs on the evaluating thread and must return in time: the engine does not interrupt it, and treats a later result as a `TIMEOUT`. If the evaluation's own timeout passes during the call, the whole evaluation times out instead.
- **No retries.** A failed call is a model failure, handled by `onFailure`. Retrying belongs in the invoker, within its time.
- **Exceptions.** A `ModelException` or any other exception from the invoker is a failure of kind `ERROR`. The record names the exception type only, never its message, which could contain feature values.
- **Registration.** `build()` fails if a model node in `invoke` mode has no invoker for its model id. One invoker can serve every version of a model, since each call names the version.

For tests and replays, `RuleStone.builder().suppliedModelsOnly()` runs every model node in `supplied` mode: the engine calls no model and needs no invoker, and `invoke` nodes take their results from the facts too. Their features are still computed, so that an expression error shows up in tests. Do not use it in production.

### Failures

Every failure takes the node's `onFailure` path and adds its reason code. A model failure never fails the evaluation; the record shows the kind of failure in the step's `status`.

| `status` | When |
|---|---|
| `TIMEOUT` | The invoker did not answer within the time available, or the caller supplied `{ "failure": "TIMEOUT" }`. |
| `ERROR` | The invoker threw an exception or returned nothing, or the caller supplied `{ "failure": "ERROR" }`. |
| `CONTRACT_VIOLATION` | Another model id or version answered; an output is missing, undeclared or not of its type; or the node sets `minConfidence` and no confidence was reported. |
| `INVALID_OUTPUT` | The `valid` condition is false, or cannot be evaluated. |
| `LOW_CONFIDENCE` | The confidence is below `minConfidence`. |
| `MISSING` | The node is in `supplied` mode and no result was supplied. |

The checks run in this order, and the first that fails decides the status. The step's `detail` says what failed, such as `output 'pd' is missing` or `the invoker threw vn.io.dxa.rulestone.api.ModelException`, and never shows a value.

### Reason codes from attributions

A model can return attributions: how much each feature moved its result, computed for example with SHAP or from scorecard points. A positive contribution increases risk; for a score where higher means less risk, the invoker negates the contributions. The node turns them into reason codes:

1. The attributions are ranked by the size of their contribution, in either direction.
2. Going down the ranks, each feature that increases risk and has a code in `reasonCodes` gives that code, until the node has given `maxReasonCodes` codes (three by default). A code is given once, even if two features map to it. Features without a code are skipped.

In the test case of the example where `pd_application` returns `age` with 0.0100 and `utilisation` with 0.0200, `utilisation` ranks first, and with `maxReasonCodes` 1 the node gives only `HIGH_UTILISATION`. A model that returns no attributions gives no reason codes, and its step in the record has an empty `attributions` list.

The codes join the flow's reason codes in the order the nodes ran, with the node as their source. A failed model gives only its `onFailure` reason code.

### Determinism and replay

- **`supplied` mode is deterministic.** The same facts, model results and business date always give the same path, outputs, reason codes and record (apart from `recordId` and `evaluatedAt`).
- **`invoke` mode** depends on what the model answers, and when. The record therefore keeps everything that later nodes used: the outputs, the confidence, and the ranks, directions and reason codes of the attributions. Replay, step 5 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan), will rebuild each model's result from its step and run the flow with `suppliedModelsOnly()`; the ranks and directions are enough to give the same reason codes. A failed step keeps its `status`, which replays as the same failure. Replay is not available yet.

### Model steps in the record

Each model node adds a step of type `model` to the record's `path`. For an existing customer whose fraud score is supplied and whose behavioural model answers in 23 ms, the two model steps are:

```json
[
  { "node": "fraud", "type": "model", "mode": "supplied",
    "model": { "id": "card_application_fraud", "version": "1.2.0" },
    "status": "OK",
    "outputs": { "score": "0.05" },
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "deviceAgeDays", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "NEW_DEVICE" },
      { "feature": "addressMismatch", "rank": 2, "direction": "INCREASES_RISK", "reasonCode": "ADDRESS_MISMATCH" }
    ] },
  { "node": "pd_behaviour", "type": "model", "mode": "invoke",
    "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
    "status": "OK", "latencyMs": 23,
    "outputs": { "pd": "0.0150" },
    "confidence": "0.95",
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "utilisation", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "HIGH_UTILISATION" },
      { "feature": "dpd30Count12m", "rank": 2, "direction": "DECREASES_RISK" }
    ] }
]
```

If the behavioural model had timed out, its step would be `{ "node": "pd_behaviour", "type": "model", "mode": "invoke", "model": { … }, "status": "TIMEOUT", "detail": "no result within 80 ms", "latencyMs": 81 }`, followed by the `referred` step.

| Step field | Content |
|---|---|
| `mode` | How the result was obtained. An `invoke` node of an engine built with `suppliedModelsOnly()` shows `supplied`. |
| `model` | The model's id and the version the node pins. |
| `status` | `OK`, or the [kind of failure](#failures). |
| `detail` | For a failure, what failed, without values. |
| `latencyMs` | For a model the engine called, how long the call took. |
| `outputs` | For `OK`, the outputs later nodes read, in the [JSON forms](java-api.md#json-facts-and-results-factsjson). |
| `confidence`, `attributionMethod` | For `OK`, as the model reported them, if it did. |
| `attributions` | For `OK`, every attribution by rank, with its direction and, if the node gave one for it, its reason code. Contribution values are left out. |

The record never contains the features sent to the model, nor the raw contributions: only identifiers, the outputs the flow used, and ranks. This keeps records safe to write to ordinary logs, as for tables.

## Evaluation

The engine evaluates one node at a time from `start` and follows exactly one path to an `end` node. The same facts and the same business date always give the same path.

- **Business date.** The flow's business date (`DecisionRef.asOf`, else the engine clock) selects the flow version, the version of every table without a pinned `version`, and is the `businessDate` of every expression and table.
- **Table versions.** A `table` node without `version` uses the version of its table in force on the business date. Because that can be any registered version, the flow is checked against all of them: the facts must suit every version, and the flow can read only the outputs that every version declares with the same type. Pin `version` when a table's versions differ.
- **Reason codes.** The reason codes of every table and model node, in order. Each code appears once, with the node that first produced it.
- **Matched rules.** `DecisionResult.matchedRules()` and `trace()` list the rules of every table node, in order. The decision record says which node each one came from.
- **Timeouts.** The [timeout](java-api.md#timeouts) covers the whole flow, model calls included. The engine checks the time before each node and before each rule, and gives a model no more time than the evaluation has left.
- **Errors.** When a node fails, for example a division by zero or a `UNIQUE` table whose rules all match, the evaluation fails with a `DecisionEvaluationException` for the flow, whose message names the node. As always, the message contains no input values.
- **Canary and shadow versions** work for flows as for tables: name the flow id in `canary` or `shadow` (see [Canary and shadow versions](java-api.md#canary-and-shadow-versions)). Inside a flow, table nodes always use the version in force or their pinned version; canaries and shadows of those tables do not apply.

## Decision records

A flow's [decision record](java-api.md#decision-records-and-auditing) has one step in `path` for each node visited, in order. For the evaluation in [Running a flow](#running-a-flow), it is:

```json
{
  "recordId": "6b0e2c51-3f7a-4d8e-9a14-c2d5f8e1b037",
  "correlationId": "CARD-2026-000777",
  "decision": "card_origination",
  "version": "1@sha256:986ffd80e5fedf4282e123e5c45f00da428234a6b2fbffb874571d3c056d924c",
  "businessDate": "2026-12-01",
  "evaluatedAt": "2026-12-01T02:15:04.218Z",
  "engine": "rulestone 0.1.0",
  "status": "OK",
  "factsHash": "sha256:899ae821198f33a92c369a3b18ea9f0cee2aff1da7830743b6753ffeb63ab790",
  "output": { "decision": "APPROVE", "limit": "144000000 VND", "riskGrade": "A" },
  "reasonCodes": [],
  "path": [
    { "node": "policy", "type": "table", "decision": "card_policy",
      "version": "1@sha256:54e99ef7d8be5c18c3d490cbe6dc2facd60e32bce51f7769a486af9a0854adcb", "matchedRules": ["PASS"] },
    { "node": "affordability", "type": "derive" },
    { "node": "segment", "type": "switch", "taken": "grade_existing" },
    { "node": "grade_existing", "type": "table", "decision": "card_grade_existing",
      "version": "1@sha256:63cd7b62f4208a03bfd914f30d0ee45e4dcfcce038dccfb0e62964dc1af6fa7b", "matchedRules": ["A"] },
    { "node": "limit_strategy", "type": "split", "taken": "challenger" },
    { "node": "limit", "type": "table", "decision": "card_limit",
      "version": "1@sha256:af5200a630dbf793caa4d3d72f7a4547ee675eb5d03e5baaf74d27259408d31a", "matchedRules": ["A_CHALLENGER"] },
    { "node": "approved", "type": "end" }
  ]
}
```

| Step field | Present for | Content |
|---|---|---|
| `node`, `type` | every step | The node id and its type. |
| `decision`, `version` | `table` | The table evaluated and its exact version, so that the decision can be reproduced even after the table changes. |
| `matchedRules` | `table` | The rules that matched, in order. |
| `taken` | `switch`, `split` | The node the switch chose, or the bucket the split chose. |
| `mode`, `model`, `status`, `detail`, `latencyMs`, `outputs`, `confidence`, `attributionMethod`, `attributions` | `model` | What the model was asked and answered; see [Model steps in the record](#model-steps-in-the-record). |

The record's own `version` is the flow's version. When the evaluation fails inside a node, that node is the last step of the path, and a `table` step shows the rules matched before the failure.

## Versions and content hashes

A flow's version is its `version` label plus the SHA-256 of its canonical form, as for a table: `1@sha256:…`. The hash covers the flow file only. It does not cover the tables the flow uses, because a table node without `version` uses whichever version of the table is in force; the decision record names the exact table version of every table step instead. To fix a table version as part of the flow's content, set `version` on the node.

The canonical form writes a single unconditional `next` as the node id, so `"next": "segment"` and `"next": [ { "to": "segment" } ]` are the same content.

## Validation

`RuleStone.builder().build()` rejects a flow, listing every problem with its location, when:

- the graph has a cycle, a node that `start` cannot reach, or a `to` that names no node;
- a `next` or `cases` array has a `when` on its last alternative, or lacks one on another;
- an expression does not compile or has the wrong type, or reads a value that some path to its node does not set;
- nodes sharing an `as` name publish different values, or can both run on one path;
- a `table` node names an unregistered table or version, a flow, or a table input that it cannot satisfy;
- an `end` node does not set every output, or sets one that is not declared;
- a `model` node pins no exact model version; has no `features` or `timeoutMs` in `invoke` mode, or has them in `supplied` mode; has a feature or `valid` condition that does not type-check, an output with fields, a `minConfidence` outside 0 to 1, or a reason code that is not `UPPER_SNAKE_CASE`; or is in `invoke` mode and no invoker is registered for its model (unless the engine is built with `suppliedModelsOnly()`). `onFailure` is required when the file is read;
- a `split` has duplicate bucket names, or percentages that do not add up to 100;
- a node id or `as` name is not an identifier, or is the name of an input;
- the flow's `id` is also the id of a table, or two versions are inconsistent, as for tables.

For example, if `referred` also read `limit.limit`, which only the approved path sets, the engine would refuse to build with:

```
invalid decision definition (1 problem):
  - card_origination@1 nodes.referred.set.limit: reads 'limit', which is not set on every path that reaches this node; a node may read only values that every path to it sets
```

## Testing flows

Test cases for flows are `.stone-test.json` files, like those for tables, and can also expect the path of nodes and give model results in `models`. Coverage then requires every node to be visited and every branch to be taken, including the `onFailure` path of every model node. See [Testing decision flows](testing.md#testing-decision-flows).

## Limitations

- **No replay yet.** Re-running a recorded decision with its recorded model results is step 5 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan).
- **No shadow models or LLM nodes yet.** A model node with `shadow`, `kind` or `prompt` is rejected as not supported yet; they are step 7.
- **No sub-flows.** A node cannot call another flow yet.
- **Java engine only.** The browser engine of the [Editor SDK](editor-sdk.md) does not run flows, and its flow canvas only designs them. The [web editor](editor.md) and the [Temporal](temporal.md) and [Camunda](camunda.md) integrations evaluate decision tables only for now. They already read supplied model results in JSON, in the same `models` form as above, for when they can evaluate flows; until then a decision table rejects them.
