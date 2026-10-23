# Hybrid AI decisioning

> Status: **design proposal**, partly implemented · Last updated: 2026-10-23
>
> Step 1 of the [implementation plan](#9-implementation-plan) is **available**: every evaluation of a decision table produces a decision record. See [Decision records and auditing](java-api.md#decision-records-and-auditing). Everything else on this page is a proposal for review and will change before it ships.

This page specifies how RuleStone combines machine-learning models with rules, and how every decision explains itself. It covers three things:

1. **Model output as rule input.** A model scores first; rules decide using the score.
2. **Models as nodes in a decision flow.** A flow chains policy rules, segmentation, one or more models, decision matrices and champion/challenger splits, in the style of credit strategy tools such as Strategy One.
3. **A decision record for every decision.** It is always produced, whatever the mix of rules and models, and it explains what was decided and why.

## Contents

1. [Principles](#1-principles)
2. [Pattern A: model output as rule input](#2-pattern-a-model-output-as-rule-input)
3. [Pattern B: models as nodes in a decision flow](#3-pattern-b-models-as-nodes-in-a-decision-flow)
4. [The decision record](#4-the-decision-record)
5. [Java API sketch](#5-java-api-sketch)
6. [Testing flows that use models](#6-testing-flows-that-use-models)
7. [Model adapters and licensing](#7-model-adapters-and-licensing)
8. [Regulatory mapping](#8-regulatory-mapping)
9. [Implementation plan](#9-implementation-plan)
10. [Open questions](#10-open-questions)

## 1. Principles

These hold for every pattern on this page.

1. **Rules decide, models inform.** A model can only produce typed values that later nodes read. The final output always comes from deterministic nodes. A model can never override a policy rule placed before it, such as a sanctions hit or an under-age applicant.
2. **Models enter through a typed contract.** A model node declares the features it sends, the outputs it returns with their types, and the ranges those outputs must fall in. Anything outside the contract counts as a model failure.
3. **Every model is pinned.** A model node names an exact model version. The model version is part of the flow's content hash, so changing a model means publishing a new flow version. This is the same rule as for decision tables: every version is set in stone.
4. **Every failure has a declared path.** A timeout, an error, an out-of-range output and low confidence each lead to a fallback that the flow states explicitly. A decision never fails silently and never uses a guessed value.
5. **Every decision produces a decision record.** The record is built for each evaluation, successful or not. There is no engine switch that turns it off.
6. **Decisions can be replayed.** The record stores what each model returned, so the decision can be re-run exactly from the record, even after the model has been retrained or retired.
7. **Data is minimised.** A model receives only its declared features. By default, the decision record contains no input values, only identifiers, versions, paths, reason codes and model outputs. This matches the existing rule that traces and error messages never contain input values.

## 2. Pattern A: model output as rule input

The model runs outside RuleStone, for example in a scoring service, a feature store pipeline or a fraud platform. Its output is passed in as a fact and rules decide on it. This is the simplest pattern, and it often fits existing bank architectures, where scoring already happens before rules are applied.

### 2.1 Plain input: works today

Declare the score as an ordinary typed input:

```json
{
  "id": "card_limit",
  "version": "1",
  "hitPolicy": "FIRST",
  "inputs":  { "pd": "decimal", "income": "money" },
  "outputs": { "decision": "string", "limit": "money" },
  "rules": [
    { "id": "HIGH_RISK", "when": "pd >= decimal('0.08')",
      "then": { "decision": "'DECLINE'", "limit": "money(0, income.currency())" }, "reasonCode": "PD_HIGH" },
    { "id": "STANDARD", "when": "true",
      "then": { "decision": "'APPROVE'", "limit": "(income * 3).round()" } }
  ]
}
```

This works with today's engine, but RuleStone cannot tell where the number came from. The decision record would show `pd` as an ordinary input, with no model id or version. Use it for prototypes, or when the scoring platform keeps its own audit trail and links it to the decision by a correlation id.

### 2.2 Attested input: recommended

Declare a **model node in `supplied` mode**. The caller passes the model result together with its provenance. RuleStone does not call the model, but it checks the result against the node's contract exactly as if it had:

```json
"pd": {
  "type": "model",
  "mode": "supplied",
  "model": { "id": "retail_pd_application", "version": "3.1.0" },
  "outputs": { "pd": "decimal" },
  "valid": "pd >= decimal(0) && pd <= decimal(1)",
  "onFailure": { "to": "refer", "reasonCode": "SCORE_INVALID" },
  "next": "limit"
}
```

The caller supplies a model result under the node id:

```json
{
  "facts": { "income": "30000000 VND" },
  "models": {
    "pd": {
      "modelId": "retail_pd_application",
      "modelVersion": "3.1.0",
      "outputs": { "pd": "0.0310" },
      "attributions": [
        { "feature": "utilisation", "contribution": "0.0120" },
        { "feature": "monthsOnBook", "contribution": "-0.0045" }
      ]
    }
  }
}
```

The engine checks that:

- the model id and version are the ones the flow pins;
- the outputs match the declared types, read with `FactsJson`, so decimals are exact and never `double`;
- the `valid` expression holds.

If any check fails, the engine follows `onFailure`. The decision record then shows the model id and version, the mode `supplied`, the outputs and the attributions. That record is the same as the one Pattern B produces, so later analysis can treat both alike.

Pattern A is Pattern B with the model call done by the caller. Everything below about contracts, failures and records applies to both.

## 3. Pattern B: models as nodes in a decision flow

### 3.1 From strategy-design concepts to RuleStone nodes

Credit strategy tools such as Strategy One model a decision as a **strategy tree**: segment the population, score each segment with the right model, map scores to grades, and look up actions and limits in matrices. Some branches are routed to challenger strategies so they can be tested. RuleStone adds a **decision flow**: a versioned, typed, directed acyclic graph of nodes that expresses the same ideas.

| Strategy-design concept | RuleStone node | Notes |
|---|---|---|
| Policy rules and knock-outs | `table` | Any existing decision table. Runs before models, so hard rules always apply. |
| Segmentation and tree splits | `switch` | The first matching `when` wins. A final branch with no `when` is required. |
| Derived characteristics | `derive` | Named CEL values computed once, such as debt-to-income ratio or months on book. |
| Scorecard or ML model | `model` | `invoke` mode calls a model; `supplied` mode takes the caller's result (Pattern A). |
| Decision matrix (grade × income band) | `table` | A table whose conditions test both dimensions. |
| Champion/challenger | `split` | A deterministic percentage split on a stable key, recorded in the decision record. |
| Reusable sub-strategy | `flow` | Calls another flow version as one node. |
| Assignment of final outputs | `end` | Sets the flow's declared outputs. A flow may have several `end` nodes. |

### 3.2 Example: credit card origination

```json
{
  "id": "card_origination",
  "version": "1",
  "inputs": {
    "applicant": { "id": "string", "age": "int", "income": "money", "existingCustomer": "bool" },
    "bureau": { "dpd30Count12m": "int", "utilisation": "decimal" }
  },
  "outputs": { "decision": "string", "limit": "money", "riskGrade": "string" },
  "start": "policy",
  "nodes": {
    "policy": {
      "type": "table", "decision": "card_policy",
      "next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "segment" } ]
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
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION" },
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
    "grade": {
      "type": "table", "decision": "pd_to_grade",
      "facts": { "pd": "score.pd" },
      "next": "limit_strategy"
    },
    "limit_strategy": {
      "type": "split", "key": "applicant.id", "salt": "card-limit-2026Q4",
      "buckets": [
        { "name": "champion", "percent": 90, "to": "limit_champion" },
        { "name": "challenger", "percent": 10, "to": "limit_challenger" }
      ]
    },
    "limit_champion":   { "type": "table", "decision": "card_limit_matrix",   "as": "offer", "next": "approved" },
    "limit_challenger": { "type": "table", "decision": "card_limit_matrix_b", "as": "offer", "next": "approved" },
    "approved": { "type": "end", "set": {
      "decision": "'APPROVE'", "riskGrade": "grade.riskGrade",
      "limit": "offer.limit" } },
    "rejected": { "type": "end", "set": {
      "decision": "'REJECT'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } },
    "referred": { "type": "end", "set": {
      "decision": "'REFER'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } }
  }
}
```

In this flow:

- Policy rules run first. A rejection there ends the flow before any model is called.
- Existing customers are scored by the behavioural model and new customers by the application model.
- If either model fails, the application is referred to a person. It is never approved or declined on a guess.
- 10% of applications, chosen deterministically by applicant id, get the challenger limit matrix. The bucket appears in the decision record.

### 3.3 Data between nodes

- **Names.** Each node's outputs are available to later nodes under the node id, for example `pd_behaviour.pd` or `grade.riskGrade`. Flow inputs keep their own names. `switch` and `split` nodes expose `taken`, which holds the branch or bucket that was chosen.
- **Facts for a table.** By default, a `table` node receives the flow values whose names match its declared inputs. `facts` maps them explicitly when names differ, as `grade` does.
- **Shared names.** Nodes on alternative branches can publish their outputs under one shared name with `as`, provided they declare identical outputs. Above, both models publish as `score`, so `grade` reads `score.pd` whichever model ran, and the two limit tables publish as `offer`.
- **Load-time typing.** All names are typed when the flow loads. A node may read a name only if **every** path that reaches it passes through a node that publishes that name. Reading a value that might not exist is a load-time error, not a runtime surprise. The record still shows which node produced the value.
- **Order.** Evaluation is sequential and follows exactly one path through the graph, so the same inputs and model results always give the same path.

### 3.4 The model node contract

| Field | Meaning |
|---|---|
| `model.id`, `model.version` | The exact model to use. Both are covered by the flow's content hash. |
| `mode` | `invoke`: the engine calls the model through a `ModelInvoker`. `supplied`: the caller passes the result (Pattern A). |
| `features` | Feature name to CEL expression. Only these values are sent to the model, which is the data-minimisation boundary. |
| `outputs` | Output name to type. Reuses table types: `decimal`, `int`, `string`, `bool`, `money`, `date`. |
| `valid` | Optional CEL condition over the outputs. If it is false, the output counts as a failure. |
| `minConfidence` | Optional. If the model reports a confidence below this, the output counts as a failure and the reason is `LOW_CONFIDENCE`. |
| `timeoutMs` | Hard limit for `invoke`. A timeout counts as a failure; there is no retry in the decision path. |
| `reasonCodes` | Feature name to reason code. Applied to the model's attributions; see [4.4](#44-reason-codes-and-human-readable-explanations). |
| `onFailure` | Where to go when the model fails, and with which reason code. It can lead to a fallback scorecard table, a manual-review end node, or a conservative default. It is required. |
| `shadow` | Optional list of other model versions to call for comparison only. Their outputs are recorded but never read by the flow. |

Failure kinds are recorded as `TIMEOUT`, `ERROR`, `CONTRACT_VIOLATION` (wrong id, version or types), `INVALID_OUTPUT` (the `valid` condition is false) and `LOW_CONFIDENCE`.

### 3.5 Champion/challenger and shadow models

- **`split`** assigns a bucket from `SHA-256(salt + key)`, so the same customer always lands in the same bucket for the same salt. Changing the salt or the percentages creates a new flow version. Bucket percentages must add up to 100.
- **Shadow models** let a new model version run on live traffic without influencing decisions. The engine calls a shadow model after the primary model, within the same timeout budget. A shadow failure never affects the decision. Shadow outputs go into the record only.
- Both feed the planned `rulestone-simulation` module, which compares outcomes per bucket and per model version.

### 3.6 LLM nodes

An LLM is a model node with `"kind": "llm"`, and stricter rules apply:

- Outputs must be typed values: an enumeration such as `string` with a `valid` condition like `category in ['A', 'B', 'C']`, a `bool`, or a number. Free text is never read by a later node.
- The prompt template is part of the node definition, so it is covered by the content hash. The provider must be configured for deterministic settings where it supports them.
- An LLM node always needs `onFailure`, and it must not be the only thing that separates an approval from a decline. This is checked at load time: every path from an LLM node to an `APPROVE`-style end must pass through a `table` node. Which end values count as approvals is configurable.

### 3.7 Load-time checks

A flow is rejected, with all problems listed together as for decision tables today, when:

- the graph has a cycle, an unreachable node, or a path that does not reach an `end` node;
- a name is unknown, has the wrong type, or may be unset on some path (the rule above);
- nodes sharing an `as` name declare different outputs, or can both run on the same path;
- a `table` node refers to a decision that is not registered, or its declared inputs cannot be satisfied;
- an `end` node does not set every declared output;
- a `model` node has no `onFailure`, has an unpinned version, or uses a feature expression that does not type-check;
- the `split` percentages do not add up to 100;
- an LLM node breaks the rule in [3.6](#36-llm-nodes).

## 4. The decision record

### 4.1 Always produced

Each call to `evaluate` builds a `DecisionRecord`, whether it runs a single table or a flow, and whether it succeeds or fails. The record is returned on `DecisionResult.record()` and handed to every configured `AuditSink`. *Available for decision tables.*

- **Embedded use:** the caller decides where records go. `rulestone-test` can assert on them.
- **`rulestone-server`, the Spring Boot starter, Temporal and Camunda:** startup fails if no audit sink is configured, unless it is turned off explicitly with a documented property, intended for local development only.
- **Errors:** when evaluation fails, the record is still written, with status `ERROR` and the failing node, before the exception is thrown.

### 4.2 Contents

```json
{
  "recordId": "01JB6Z9V4X3N7Q8R2T5W0YH1KM",
  "correlationId": "loan-app-2026-000123",
  "decision": "card_origination",
  "version": "1@sha256:9f2c…",
  "businessDate": "2026-11-01",
  "evaluatedAt": "2026-11-01T09:15:02.481Z",
  "engine": "rulestone 0.2.0",
  "status": "OK",
  "factsHash": "sha256:41d8…",
  "output": { "decision": "APPROVE", "limit": "60000000 VND", "riskGrade": "B" },
  "reasonCodes": [
    { "code": "HIGH_UTILISATION", "source": "pd_behaviour" }
  ],
  "path": [
    { "node": "policy", "type": "table", "version": "4@sha256:0b7e…", "matchedRules": ["PASS"] },
    { "node": "segment", "type": "switch", "taken": "pd_behaviour" },
    { "node": "pd_behaviour", "type": "model", "mode": "invoke",
      "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
      "status": "OK", "latencyMs": 23,
      "outputs": { "pd": "0.0310" },
      "attributions": [
        { "feature": "utilisation", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "HIGH_UTILISATION" },
        { "feature": "dpd30Count12m", "rank": 2, "direction": "DECREASES_RISK" }
      ] },
    { "node": "grade", "type": "table", "version": "2@sha256:77aa…", "matchedRules": ["B"] },
    { "node": "limit_strategy", "type": "split", "taken": "champion" },
    { "node": "limit_champion", "type": "table", "version": "5@sha256:c1d0…", "matchedRules": ["B_MID_INCOME"] },
    { "node": "approved", "type": "end" }
  ]
}
```

| Field | Purpose |
|---|---|
| `version` and per-node `version` | The exact content of every table and flow used, so the decision can be re-run with the same rules. |
| `factsHash` | SHA-256 of the canonical `FactsJson` form of the inputs. It proves which inputs were used, against the bank's own copy, without storing them. |
| `path` | Every node visited, in order, with what it decided: matched rules, the branch or bucket taken, the model status. |
| model `outputs` | What the model returned. These values let the decision be replayed exactly, and they show how much the model contributed. |
| `attributions` | Which features pushed the score and in which direction, by name and rank only. Raw contribution values and feature values are left out by default. |
| `reasonCodes` | The ordered, de-duplicated reasons, each with the node it came from. These are the source of truth for adverse-action notices. |

### 4.3 What is not in the record

By default the record contains **no input values and no feature values**. Two optional levels can be configured per deployment:

- `evidence: masked` adds the feature values sent to each model and the inputs each table read, passed through `DataMasker` (planned), which masks card numbers, national ID numbers and similar fields.
- `evidence: full` adds them unmasked. It is only for sinks that encrypt at rest and restrict access, and the server refuses to combine it with a plain log sink.

Keeping values out of the default record means it can be written to ordinary logs and shared with analysts safely. When a full investigation is needed, the evidence can be joined in from the bank's own data store using `correlationId` and checked against `factsHash`.

### 4.4 Reason codes and human-readable explanations

- **From rules:** the `reasonCode` of each matched rule, as today.
- **From models:** the node's `reasonCodes` map is applied to the model's attributions. The engine takes the top attributions that **increase** risk (three by default, configurable per node) and emits the mapped codes in rank order. A model that returns no attributions produces no model reason codes, and the record says so.
- **From failures:** the `onFailure` reason code, such as `MODEL_UNAVAILABLE`.
- **Text:** a reason-code catalog (`reason-codes.json`) holds a reviewed message for each code in each language:

  ```json
  {
    "HIGH_UTILISATION": {
      "en": "Your credit utilisation is high compared with your limits.",
      "vi": "Mức sử dụng hạn mức tín dụng của bạn đang cao so với hạn mức được cấp."
    }
  }
  ```

  Explanations for customers and staff are built from the catalog **deterministically**. An LLM may rephrase them later for tone, using the masked record as its only input, but the codes stay the source of truth. This is the existing "decision explanation" pattern in the [architecture](architecture.md#7-ai-integration-planned).

### 4.5 Replay and re-scoring

- **Replay** re-runs a decision from its record. The engine loads the recorded flow and table versions, and the caller supplies the original facts, which are checked against `factsHash`. Each model node runs in `supplied` mode using the recorded outputs. The result must match the record exactly. This is how an auditor reproduces a decision years later, after the models have changed.
- **Re-score** runs the same facts through a different model or flow version and reports the differences. It is used for model validation and backtesting, and it is never written back as the decision.

### 4.6 Where records go

| Integration | Behaviour |
|---|---|
| Embedded | `RuleStone.builder().auditSink(…)`; sinks are called synchronously after evaluation, and asynchronous sinks buffer on their own. *Available.* |
| `rulestone-audit-jdbc` / `-kafka` (planned) | An append-only store. Records are chained by hash in the governance module (planned), which makes tampering visible. |
| Temporal | The activity returns the record id. The record is written by the activity, never by workflow code, so replays do not write duplicates. |
| Camunda | The record id is stored as a process variable for traceability, and the record goes to the configured sink. |

## 5. Java API sketch

The SPIs live in `rulestone-api`, so `rulestone-core` stays free of model vendors. Adapters are separate modules.

```java
/** Calls a model. Implementations must be thread-safe and must honour the deadline. */
public interface ModelInvoker {
    ModelResult invoke(ModelCall call) throws ModelException;
}

public record ModelCall(
        String modelId,
        String modelVersion,
        Map<String, Object> features,   // typed values, exact decimals, never double
        Instant deadline,
        String correlationId) {}

public record ModelResult(
        String modelId,
        String modelVersion,             // the version that actually answered
        Map<String, Object> outputs,
        Optional<BigDecimal> confidence,
        List<Attribution> attributions) {}

public record Attribution(String feature, BigDecimal contribution) {}
```

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flows(flows)
        .models(ModelRegistry.builder()
                .invoker("retail_pd_behaviour", onnxInvoker)
                .invoker("retail_pd_application", httpInvoker)
                .build())
        .auditSink(record -> auditLog.append(record))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_origination").asOf(businessDate).correlationId(applicationId),
        facts);

result.output();          // {decision=APPROVE, limit=60000000 VND, riskGrade=B}
result.reasonCodes();     // [HIGH_UTILISATION]
result.record();          // the DecisionRecord shown in 4.2
```

For Pattern A, the caller passes a `ModelResult` with the facts: `Facts.of(...).withModelResult("pd", result)`. JSON callers use the `models` object shown in [2.2](#22-attested-input-recommended).

## 6. Testing flows that use models

`.stone-test.json` cases will accept a `models` object with the same shape as Pattern A. In tests, every model node then runs in `supplied` mode, so the tests are deterministic and need no model server:

```json
{
  "name": "existing customer with high utilisation is approved at grade B",
  "decision": "card_origination",
  "asOf": "2026-11-01",
  "facts": {
    "applicant": { "id": "C-1", "age": 35, "income": "30000000 VND", "existingCustomer": true },
    "bureau": { "dpd30Count12m": 0, "utilisation": "0.72" }
  },
  "models": { "pd_behaviour": { "modelId": "retail_pd_behaviour", "modelVersion": "2.4.0",
                                "outputs": { "pd": "0.0310" },
                                "attributions": [ { "feature": "utilisation", "contribution": "0.0120" } ] } },
  "expect": { "output": { "decision": "APPROVE", "riskGrade": "B" },
              "reasonCodes": ["HIGH_UTILISATION"],
              "path": ["policy", "segment", "pd_behaviour", "grade", "limit_strategy", "limit_champion", "approved"] }
}
```

Tests can also simulate a failure, for example `"pd_behaviour": { "failure": "TIMEOUT" }`. Coverage will extend from rules to flows: every node, every branch of each `switch`, and every `onFailure` path must be exercised.

## 7. Model adapters and licensing

Adapters are optional modules, and each one must pass the project's license allowlist (Apache-2.0, MIT, BSD).

| Adapter | Runs | Library and license (to be verified when the module is added) |
|---|---|---|
| `rulestone-model-http` | Any model behind HTTP, such as KServe or Seldon (Open Inference Protocol) or an in-house scoring service | JDK `HttpClient` only |
| `rulestone-model-onnx` | Models exported to ONNX, from scikit-learn, XGBoost, LightGBM or PyTorch, in-process | ONNX Runtime (MIT) |
| `rulestone-model-scorecard` | Points-based scorecards defined as JSON, with reason codes from the points lost | None; pure RuleStone |

PMML is deliberately absent. The common Java PMML evaluator is AGPL-licensed, which the allowlist excludes.

In-process adapters must convert exact decimals to the model's numeric type only at the adapter boundary, and must say so in their documentation. Model outputs are converted back to `decimal` with a declared scale.

## 8. Regulatory mapping

This table guides the design; it is not legal advice. The current texts must be checked.

| Expectation | Where it is met |
|---|---|
| Model risk management (SR 11-7 style): inventory, versioning, validation, monitoring | Pinned `model.id` and `model.version` in the hashed flow, shadow models, re-score, simulation |
| Record-keeping for high-risk AI (EU AI Act; credit scoring is high-risk) | The decision record is always produced, with model versions, outputs and path |
| Transparency and explanation to the person affected | Reason codes from rules and model attributions, and the reviewed text catalog in EN/VI |
| Human oversight | `onFailure` to manual review, LLM restrictions, and the rule that policy nodes come before models |
| Personal data protection (Vietnam's personal data protection law and decrees, GDPR where relevant) | Data minimisation by declared features, no values in the default record, evidence levels with masking |
| Reproducibility for audit | Replay from the record with `supplied` model outputs and `factsHash` verification |

## 9. Implementation plan

Each step is a separate, reviewable change with its own tests and documentation.

1. **Decision record for tables.** *Done.* Add `DecisionRecord`, `DecisionResult.record()`, `AuditSink` and `correlationId` for today's decision tables, with no models yet. This delivers the "always explain" obligation first.
2. **Flows without models.** Add the flow format, the `table`, `switch`, `derive`, `split` and `end` nodes, the load-time checks including the dominance rule, the executor, and `rulestone-test` support for paths and node coverage.
3. **Model nodes.** Add `ModelInvoker`, `ModelResult`, the `invoke` and `supplied` modes, failure handling, and reason codes from attributions. Add the `models` object to the test format and to `FactsJson`-based JSON callers (Temporal, Camunda, editor).
4. **Explanations.** Add the reason-code catalog with EN/VI messages and a deterministic explanation renderer.
5. **Replay and re-score.** Add these APIs, and extend the Temporal and Camunda integrations to carry record ids.
6. **Adapters.** Build `rulestone-model-http` first, then `rulestone-model-scorecard`, then `rulestone-model-onnx`.
7. **Shadow models, LLM nodes, and the flow canvas in the editor.**

## 10. Open questions

| Question | Current proposal |
|---|---|
| Should flows share the `.stone.json` extension? | Use `.flow.json`, with `id`/`version`/effective dating identical to tables, in the same repository. |
| Asynchronous model calls | Keep evaluation synchronous with deadlines first. Add a non-blocking API once the server module needs one. |
| How many model reason codes to emit by default | Three, configurable per node. Banks often need up to four for adverse-action notices. |
| Should `factsHash` be salted? | Decided: a plain hash of low-entropy inputs can be brute-forced, so `factsHashKey` makes it an HMAC-SHA256 with a bank-held key. Without a key it is plain SHA-256. |
| Attribution method | Up to the model provider (SHAP, scorecard points, etc.). The adapter must name the method, and the record stores that name. |
