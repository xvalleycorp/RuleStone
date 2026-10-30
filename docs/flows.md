# Decision flows

A decision flow chains decision tables and simple logic into one decision: policy knock-outs first, then segmentation, grading and a limit matrix, with a champion/challenger split where you want to test a new strategy. This page is the reference for the `.flow.json` format and for how flows are checked, evaluated and recorded.

Flows are step 2 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan). They contain no model nodes yet: a model score enters a flow as an ordinary input (see [Limitations](#limitations)). The Java engine runs flows; the browser engine of the [Editor SDK](editor-sdk.md) does not.

- [A first flow](#a-first-flow)
- [Running a flow](#running-a-flow)
- [File format](#file-format)
- [Nodes](#nodes)
- [Values between nodes](#values-between-nodes)
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
| `split` | Assigns a bucket by percentage | `taken`: the name of the bucket chosen | The bucket's node |
| `end` | Sets the flow's outputs | Nothing | Ends the flow |

The type names `model` and `flow` are reserved for model nodes and calls to other flows. They are not supported yet, and a file that uses them is rejected.

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

## Evaluation

The engine evaluates one node at a time from `start` and follows exactly one path to an `end` node. The same facts and the same business date always give the same path.

- **Business date.** The flow's business date (`DecisionRef.asOf`, else the engine clock) selects the flow version, the version of every table without a pinned `version`, and is the `businessDate` of every expression and table.
- **Table versions.** A `table` node without `version` uses the version of its table in force on the business date. Because that can be any registered version, the flow is checked against all of them: the facts must suit every version, and the flow can read only the outputs that every version declares with the same type. Pin `version` when a table's versions differ.
- **Reason codes.** The reason codes of every table node, in order. Each code appears once, with the node that first produced it.
- **Matched rules.** `DecisionResult.matchedRules()` and `trace()` list the rules of every table node, in order. The decision record says which node each one came from.
- **Timeouts.** The [timeout](java-api.md#timeouts) covers the whole flow. The engine checks the time before each node and before each rule.
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
- a `split` has duplicate bucket names, or percentages that do not add up to 100;
- a node id or `as` name is not an identifier, or is the name of an input;
- the flow's `id` is also the id of a table, or two versions are inconsistent, as for tables.

For example, if `referred` also read `limit.limit`, which only the approved path sets, the engine would refuse to build with:

```
invalid decision definition (1 problem):
  - card_origination@1 nodes.referred.set.limit: reads 'limit', which is not set on every path that reaches this node; a node may read only values that every path to it sets
```

## Testing flows

Test cases for flows are `.stone-test.json` files, like those for tables, and can also expect the path of nodes. Coverage then requires every node to be visited and every branch to be taken. See [Testing decision flows](testing.md#testing-decision-flows).

## Limitations

- **No model nodes yet.** Model nodes (`"type": "model"`), with their typed contract, failure paths and attributions, are step 3 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan). Until then, compute the score outside RuleStone and pass it as an input, as in [Pattern A](hybrid-ai.md#21-plain-input-works-today).
- **No sub-flows.** A node cannot call another flow yet.
- **Java engine only.** The browser engine of the [Editor SDK](editor-sdk.md) does not run flows, and its flow canvas only designs them. The [web editor](editor.md) and the [Temporal](temporal.md) and [Camunda](camunda.md) integrations work with decision tables only for now.
