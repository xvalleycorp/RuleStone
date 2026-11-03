# @dxa/rulestone-engine

RuleStone in TypeScript: decision tables and decision flows for JavaScript and TypeScript applications, such as Node.js services, edge workers and front ends, without Java. This package reads `.stone.json` tables and `.flow.json` flows strictly, type-checks their CEL expressions, evaluates them with exact `decimal`, `money` and `date` types, calls or receives machine-learning model results in flows, records every decision, and replays recorded decisions.

The TypeScript engine and the Java engine are equivalent implementations. Both give the same results on everything the conformance suite covers: rule versions, outputs, reason codes, traces, decision records, explanations, replays and error messages. The [known differences](#known-differences) are listed below.

> The package will be published to npm as `@dxa/rulestone-engine`; until then it is private and built from the repository.
>
> Entry points: `@dxa/rulestone-engine` (the engine), `@dxa/rulestone-engine/cel` (expressions and the `decimal`, `money` and `date` types alone, as Java's `rulestone-cel`) and `@dxa/rulestone-engine/testing` (`.stone-test.json` files, as Java's `rulestone-test`), so that production code does not load the test reader.

- No runtime dependencies. Numbers use the native `BigInt`.
- ESM, TypeScript types included, and safe to tree-shake (`sideEffects: false`).
- Runs in current browsers, Node.js 22 or later and edge runtimes, with no DOM. Hashing uses WebCrypto (`crypto.subtle`), which browsers provide only on secure (HTTPS or localhost) pages; timeouts use `performance.now()`.
- **Size** (`npm run size`), minified and minified + gzipped: the main entry point 178.9 KiB and 56.2 KiB, `/cel` 56.6 KiB and 19.0 KiB, `/testing` 59.4 KiB and 18.8 KiB. Approval and release verification is in the main entry point but bundled only by applications that create an `ApprovalVerifier`; `createEngine` alone grows by about 2 KiB. Flows, model nodes and replay are part of `createEngine`, so every application that creates an engine loads them; elements that only check expressions do not.

## Usage

```ts
import { createEngine, parseDecisionTable, outputJson, writeDecisionRecord } from '@dxa/rulestone-engine'

const engine = await createEngine({
  tables: [parseDecisionTable(cardFeeJson)],   // every version of every decision
  timeZone: 'Asia/Ho_Chi_Minh',                // "today" when a request has no asOf
})

const result = await engine.evaluateJson(
  { id: 'card_fee', asOf: '2026-11-01' },
  '{ "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }',
)

outputJson(result)                   // {"fee":"123457 VND","nextReview":"2027-05-01"}
result.ruleVersion                   // 1@sha256:e38b29d8…
result.reasonCodes                   // []
result.matchedRules                  // ['STANDARD']
writeDecisionRecord(result.record)   // one line of JSON, as DecisionRecordJson.write in Java
```

`createEngine` throws an `InvalidDecisionError` whose `problems` list every problem in every parameter set, table and flow, with the same paths and messages as Java, for example `loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'`. `parseDecisionTable` and `parseDecisionFlow` throw the same error for a malformed file.

### Decision flows

A [decision flow](../../../docs/flows.md) is registered with every table it uses, and evaluated by id like a table:

```ts
import { createEngine, parseDecisionFlow, parseDecisionTable, type ModelInvoker } from '@dxa/rulestone-engine'

const behaviourModel: ModelInvoker = async (call) => {
  // call.features: Map { 'utilisation' => Decimal 0.40, 'dpd30Count12m' => 0n }; call.timeoutMs: at most 80
  const response = await scoringClient.score(call.modelId, call.modelVersion, call.features, { signal: call.signal })
  return { modelId: call.modelId, modelVersion: call.modelVersion, outputs: { pd: response.pd },
           attributionMethod: 'SHAP', attributions: response.contributions }
}

const engine = await createEngine({
  tables: tableFiles.map(parseDecisionTable),
  flows: [parseDecisionFlow(cardOriginationScoredJson)],
  modelInvokers: { retail_pd_behaviour: behaviourModel, retail_pd_application: applicationModel },
  auditSinks: [(record) => auditLog.write(writeDecisionRecord(record))],
  timeoutMs: 500,
})

const result = await engine.evaluateJson(
  { id: 'card_origination_scored', asOf: '2026-12-01', correlationId: 'CARD-2026-000777' },
  facts,                                                   // JSON text or a parsed object
  '{ "fraud": { "modelId": "card_application_fraud", "modelVersion": "1.2.0", "outputs": { "score": "0.05" } } }',
)
result.record.path   // one step per node: table versions and matched rules, branches taken, model steps
```

Model nodes in `supplied` mode take their result from the third argument of `evaluate` or `evaluateJson`, by node id. Nodes in `invoke` mode call the `ModelInvoker` registered for their model id, which may be async: the engine waits for its answer, and an answer that comes after the node's `timeoutMs` (or the evaluation's own timeout, if that leaves less) is a `TIMEOUT`; a thrown error or rejected promise is an `ERROR`, recorded by the error's name only. The engine never interrupts an invoker, as in Java; `call.signal` is aborted when the time is up, for invokers that use `fetch`. Every failure takes the node's `onFailure` path. For tests, `suppliedModelsOnly: true` runs every model node in supplied mode.

### Replay and re-score

```ts
import { readDecisionRecord } from '@dxa/rulestone-engine'

const record = readDecisionRecord(line)                      // a record of either engine, from an audit log
const replayed = await engine.replay(record, facts)          // the recorded versions, date, parameters and model results
replayed.matches                                             // true when the decision is reproduced
const rescored = await engine.rescore(record, facts, { id: record.decision, asOf: '2027-01-01' }, 'RECORDED')
rescored.differences                                         // [{ kind: 'OUTPUT', description: "output 'limit' differs" }, …]
```

`replay` and `rescore` behave as in Java ([Replaying decisions](../../../docs/replay.md)): nothing is written to the audit sinks, a version that is not registered with exactly the recorded content hash is a `DecisionNotFoundError`, and differences are described with Java's words, never with values.

### Approvals

An engine created with `requireApproved` loads only content covered by signed [approval statements](../../../docs/approvals.md), verified with WebCrypto (`EdDSA` with Ed25519, or `ES256`) and checked for four-eyes and a minimum number of approvers, as in Java:

```ts
import { ApprovalVerifier, createEngine } from '@dxa/rulestone-engine'

const engine = await createEngine({
  tables, paramSets, reasonCodeCatalog,
  approvals: [{ name: 'CHG-2026-0042.approval.jws', jws: approvalText }],   // or bare strings
  requireApproved: new ApprovalVerifier({ trustedKeys: { 'gov-2026': publicKey }, minApprovers: 1 }),
})
```

A statement that does not verify, or an artifact that no acceptable statement covers, is a problem of the `InvalidDecisionError`, with Java's words. `ApprovalIssuer` in `@dxa/rulestone-engine/testing` signs statements for tests.

### Releases

With `requireRelease`, the engine loads exactly the versions of a signed [release](../../../docs/releases.md) of its target, refuses a release older than `lastAcceptedRelease`, and checks its canary and shadow versions, as in Java. Content, approvals and the release can come from a `RuleRepository`, an asynchronous interface that runs in browsers and edge runtimes, and `RepositoryReloader` builds a new engine when a newer release appears:

```ts
import { InMemoryRuleRepository, MemoryReleaseStore, RepositoryReloader, createEngine } from '@dxa/rulestone-engine'

const engine = await createEngine({
  snapshot: await repository.load('retail-credit-prod'),   // the release, the content it lists, the approvals
  requireApproved: verifier,
  requireRelease: 'retail-credit-prod',
})

const reloader = new RepositoryReloader({
  repository, target: 'retail-credit-prod', verifier, store: new MemoryReleaseStore(),
  onReload: (next) => { current = next },
})
await reloader.reloadIfNewer()
```

## API

| Export | Purpose |
|---|---|
| `parseDecisionTable(text)` | Strict `.stone.json` reader, as `DecisionTableJson.read`. Unknown and duplicate keys are errors, and every problem is reported with its JSON path. |
| `writeDecisionTable(table)`, `ruleVersionOf(table)` | The canonical form, and the rule version `<version>@sha256:<hex>` computed over it, as in Java. |
| `parseDecisionFlow(text)`, `writeDecisionFlow(flow)`, `flowVersionOf(flow)` | The strict `.flow.json` reader, as `DecisionFlowJson.read`, the canonical form and the flow's rule version, as in Java. |
| `createEngine(options)` | Compiles every parameter set, table and flow, and checks versions for duplicates and contradictory dates (a version without `effectiveTo` is superseded by the next one). Options: `tables`, `flows`, `paramSets`, `modelInvokers`, `suppliedModelsOnly`, `maxIterations`, `currencyScales`, `clock`, `timeZone`, `recordIds`, `auditSinks`, `factsHashKey`, `timeoutMs`, `reasonCodeCatalog`, `reasonCodeCatalogId`, `requireReasonCodeCatalog`, `requireApproved`, `approvals`, `requireRelease`, `release`, `lastAcceptedRelease`, `snapshot`. |
| `parseParameterSet(text)`, `writeParameterSet(set)`, `parameterSetVersionOf(set)` | The strict `.params.json` reader, the canonical form and the version `<version>@sha256:<hex>` of a [parameter set](../../../docs/decision-tables.md#parameters), as `ParameterSetJson` in Java. Pass every version to `createEngine` as `paramSets`: tables and flows that declare `params` are checked against them with Java's messages and read the version in force on the business date as `params`; the record lists it in `params`. |
| `engine.evaluate(ref, facts, models?)` | Evaluates a table or a flow with JavaScript values (see below). `ref` is a decision id or `{ id, version?, asOf?, correlationId?, params?, timeoutMs? }`; `params` forces parameter set versions, e.g. `{ retail_credit: '1' }`. `models` are supplied model results by node id. |
| `engine.evaluateJson(ref, json, models?)` | The same, with facts and model results in their JSON forms, read with the inputs of the selected version (`FactsJson.readFacts(facts, inputs, models)`). |
| `engine.replay(record, facts)`, `engine.rescore(record, facts, target, source, models?)` | Replays a recorded decision, or re-scores it with another target and the recorded (`'RECORDED'`) or live (`'LIVE'`) models, and compares, as `RuleStoneEngine.replay` and `rescore`. |
| `readDecisionRecord(json)`, `InvalidRecordError` | Reads a record back from its JSON, strictly, as `DecisionRecordJson.read`. |
| `readModels(json)` | Supplied model results in JSON, by node id, as `FactsJson.readModels`. |
| `ModelInvoker`, `ModelCall`, `ModelResult`, `SuppliedModel` | The model API of flows, as in Java: an invoker receives the model id, the pinned version, the features, the time available, the correlation id and an abort signal. |
| `DecisionResult` | `output` (a `Map` in declaration order), `reasonCodes`, `trace`, `matched`, `matchedRules`, `ruleVersion`, `decisionId`, `record`. For a flow, the trace and matched rules cover every table node, in order. |
| `DecisionRecord`, `decisionRecordToJson`, `writeDecisionRecord` | The record of every evaluation, successful or not, in the JSON shape of Java's `DecisionRecordJson`, with flow and model steps. A failed evaluation's error carries its record as `error.record`. |
| `readFacts`, `readValues`, `readValue`, `resultTypes` | JSON to values with declared types: money `"1500000.50 VND"`, dates `"2026-11-01"`, decimals as numbers or numeric strings. JSON numbers never pass through floating point. |
| `valueToJson`, `valuesToJson`, `writeValues`, `outputJson` | Values to their JSON forms, with decimals as plain-notation strings. |
| `parseReasonCodeCatalog`, `writeReasonCodeCatalog`, `reasonCodeCatalogVersionOf` | The strict `reason-codes.json` reader, the canonical form and the exact version of a [reason-code catalog](../../../docs/explanations.md), as `ReasonCodeCatalogJson`. Given to `createEngine` as `reasonCodeCatalog`, it is checked against the reason codes of every table and model node with Java's messages. |
| `engine.explain(record, locale)`, `explainDecision`, `explanationText`, `writeExplanation` | The [explanation](../../../docs/explanations.md) of a record in one locale, as text and JSON, byte for byte as Java's `Explanations` writes them, for tables and flows. |
| `ApprovalVerifier`, `parseApprovalStatement`, `writeApprovalStatement` | Verifies signed approval statements for `requireApproved`, as Java's `ApprovalVerifier`; reads and writes statements in canonical form, as `ApprovalStatementJson`. |
| `parseRelease`, `writeRelease`, `releaseHash`, `verifier.verifyRelease(jws)` | Reads and writes [releases](../../../docs/releases.md) in canonical form, as `ReleaseJson`; the hash that the next release names as `previous`; verifies a signed release. |
| `RuleRepository`, `InMemoryRuleRepository`, `RepositoryReloader`, `ReleaseStore`, `MemoryReleaseStore` | The read-only repository interface, asynchronous; a repository in memory; the reload helper with a store of the last accepted release, as Java's `RuleRepository` and `RepositoryReloader`. |
| `ApprovalIssuer`, `approvalSubjects`, `releaseEntries` (from `@dxa/rulestone-engine/testing`) | Signs approval statements and releases (`issueRelease`) with a generated or given key, and lists the exact versions of tables, flows, parameter sets and a catalog, for tests, as Java's `ApprovalIssuer`. |
| `parseStoneTests`, `checkStoneTestCase` (from `@dxa/rulestone-engine/testing`) | Reads `.stone-test.json` files, with flows' `models` and expected `path`, and checks their cases, as `StoneTestJson` and `StoneTestCase.check` do. |
| `ExpressionEnvironment` | Compiles and evaluates single expressions, as the Java class of that name. |
| `analyzeExpression(source, { variables, expected? })` | For editors: parses and type-checks an expression without throwing, and returns the typed syntax tree, every problem with its position (in code points and as a UTF-16 index, line and column) and the engine's message. Every node has its source span (`start`, `end`, including enclosing parentheses); selections also give `fieldPos`. `compile` uses the same analysis. |
| `localValueTypes(inputs, locals, params?)` | For editors: the inferred types of a table's local values (`let`) that compile, to pass to `analyzeExpression` with the inputs; `params` are the declared parameters, which local values read as `params.<name>`. |
| `functionCatalog()`, `inputStructName(path)`, `codePointIndex(source, pos)` | The functions and methods rules can call, for autocomplete; the type name the checker gives a struct among the inputs (to tell which declared field a selection reads); converting code-point offsets to string indexes. |
| `Decimal`, `Money`, `LocalDate` | Exact values: `Decimal` keeps its scale like `BigDecimal`, `Money` is an amount and an ISO 4217 code, and `LocalDate` is a calendar date with no time zone. |
| `RuleStoneError` and its subclasses | `InvalidDecisionError`, `InvalidFactsError`, `DecisionNotFoundError`, `DecisionEvaluationError`, `DecisionTimeoutError`, `AuditError`, `InvalidRecordError`. `ExpressionError` and `InvalidValueError` come from `ExpressionEnvironment`. |

### Values

| Declared type | JavaScript value accepted by `evaluate` | Value in results |
|---|---|---|
| `bool` | `boolean` | `boolean` |
| `int` | `bigint`, or a safe-integer `number` | `bigint` |
| `string` | `string` | `string` |
| `decimal` | `Decimal`, `bigint` or a safe-integer `number`. Other numbers are rejected, because there is no floating point. | `Decimal` |
| `money` | `Money` | `Money` |
| `date` | `LocalDate` | `LocalDate` |
| list | an array | a frozen array |
| struct | a `Map` or a plain object; absent, `null` or `undefined` fields are unset | (inputs only) |

Model outputs, from an invoker or supplied, are read through their JSON forms with the types the node declares: a decimal can be a `Decimal`, a `bigint` or a string such as `"0.05"`, and money a `Money` or `"1500000 VND"`. A JavaScript number that is not a safe integer breaks the contract, as a `double` does in Java.

## Runtime

- **Audit sinks** (`auditSinks`) receive the record of every evaluation, in order, before `evaluate` returns or throws; a sink that throws or rejects fails the evaluation with an `AuditError`, as in Java.
- **Record and correlation ids:** `recordIds` supplies record ids (random UUIDs by default); a request's `correlationId` is copied into its record and passed to model invokers.
- **Facts hash key:** with `factsHashKey` (at least 16 bytes), the record's `factsHash` is `hmac-sha256:…`, the same value the Java engine computes with the same key.
- **Timeouts:** `timeoutMs`, or a request's own `timeoutMs`, covers the whole evaluation. The engine checks the time before each node and each rule, and gives a model no more time than is left; an evaluation that runs out fails with a `DecisionTimeoutError` and a record with status `TIMEOUT`.

## Why not decimal.js

Java's `BigDecimal` keeps a scale: `1.50` and `1.5` are equal but print differently. That difference shows up in outputs, in `string(d)`, in decision records and in how division and rounding choose their result's scale. decimal.js normalizes the scale away, so it cannot reproduce those results. `Decimal` holds a `BigInt` unscaled value and a scale, and it implements Java's rules for addition, multiplication, `setScale` with every rounding mode, and division with `MathContext.DECIMAL128`, including the preferred scale of exact quotients. The conformance probes check about 280 generated operations against `BigDecimal`.

## Conformance

The tests in `test/` compare this engine with fixtures that the Java engine writes. The fixtures are written by `SdkFixturesTest` in `rulestone-test`, and the Maven build fails if they are stale:

| Fixture | Checked |
|---|---|
| `java-hashes.json` | The canonical form and rule version of every table in `examples/` and in `test/fixtures/tables/`. |
| `java-records.json` | The decision record and trace of every case of every `examples/*.stone-test.json` file. Each case also passes its own expectations, and its Java record replays with no differences. |
| `java-flow-hashes.json` | The canonical form and rule version of every flow in `examples/flows/` and of the tables it uses. |
| `java-flow-records.json` | The decision record and trace of every case of every `examples/flows/*.stone-test.json` file, with the cases' model results. Each case also passes its own expectations, its explanation is Java's, and its Java record replays with no differences. |
| `java-probes.json` | The result type and value, or the exact error, of every expression in `probes.json`. The probes cover operators, macros, the iteration budget shared by nested macros, `&&` and `\|\|` absorbing errors, error positions, list result types, decimal arithmetic, money, dates, fuzzy matching and value-free error messages. |
| `java-invalid-tables.json` | The problems reported for every invalid table in `invalid-tables.json`. |
| `java-invalid-flows.json` | The problems reported, when it is read and when an engine is created, for every flow in `invalid-flows.json`: malformed files, graphs, names, scopes, table nodes, expressions, parameters, model contracts and reason codes. |
| `java-explanations.json` | The explanation, in English and Vietnamese, of the records of the example tests. |
| `java-approvals.json` | Signed approval statements with the trusted keys (EdDSA and ES256), and the problems of an engine created with `requireApproved`: valid statements, a tampered hash or payload, a wrong or unknown key, `alg: none`, forbidden header parameters, a wrong `typ`, payloads that are not canonical or not valid, a maker who approves, too few approvers and a missing approval. |
| `java-releases.json` | Signed releases with the release accepted before, and the problems of an engine created with `requireRelease`: valid releases with retired, shadow and canary versions, a rollback, the same sequence with other content, a broken chain, a tampered payload, a wrong or unknown key, a statement given as a release and the reverse, another target, unlisted, missing and changed versions, routing to unlisted, retired or unapproved versions, and invalid or non-canonical payloads. |

`docs.test.ts` also checks every `` `expression` → result `` example in `docs/expressions.md`, and the worked examples in `docs/fuzzy-matching.md`, as Java's `ExpressionsDocTest` does. Invoke mode, timeouts and the facts hash key are tested against the behaviour of Java's `ModelNodeTest`, `TimeoutTest` and `DecisionRecordTest`.

To regenerate the fixtures after changing the engine, the examples or the probes, run this from the repository root:

```sh
mvn -pl rulestone-test -am test -Dtest=SdkFixturesTest -Dsurefire.failIfNoSpecifiedTests=false -Drulestone.sdk.updateFixtures=true
```

The ISO 4217 table (`src/iso4217.ts`) is generated from the table that the Java engine reads, `rulestone-api/src/main/resources/vn/io/dxa/rulestone/api/iso4217.txt`. Regenerate it with `npm run generate:iso4217`. A test fails if the two differ.

### Known differences

- **Not supported:** map literals (`{'a': 1}`), `bytes`, `type()`, `timestamp()`, `duration()` and `string(double)`. RuleStone rules do not use them, and Java's formatting of doubles depends on the JDK version. The TypeScript engine rejects them at compile time with a message saying so; Java accepts them.
- **Canary and shadow versions** (`RuleStone.builder().canary(…)` and `shadow(…)` in Java) are not offered. Records with `routing` from the Java engine are read and replayed. A release's routing is verified as in Java, but not applied.
- **Repositories:** there is no files repository, so that the main entry point uses no Node.js API; implement `RuleRepository` over HTTP, or put snapshots into an `InMemoryRuleRepository`. The reloader's store of the last accepted release is a `ReleaseStore` rather than a file.
- **Approval files:** the engine reads no files; give `approvals` the text of the `.approval.jws` files, which Java's `RuleStone.builder().approvals(dir)` reads itself. Keys are WebCrypto `CryptoKey`s.
- **Syntax errors:** they are reported at the same position, but in other words than ANTLR's (for example `mismatched input '<EOF>'…`).
- **Regular expressions:** `matches()` translates RE2 syntax to JavaScript `RegExp` with the `u` flag. Common syntax behaves the same, including leading `(?i)` flags, `\pL` and `(?P<name>…)`. Exotic RE2 constructs may differ.
- **Unicode:** `normalizeName` and the fuzzy functions use the Unicode tables of the JavaScript runtime, and Java uses those of the JDK. They differ only for characters added in recent Unicode versions.
- **Messages that name the API:** input errors from JavaScript values name JavaScript types (`expected int but got string`) where Java names Java classes; a model node without an invoker, and supplied results for a node in invoke mode, are reported with this package's option names (`modelInvokers`, `suppliedModelsOnly`) instead of the Java builder's; an invoker's error is recorded by its `name` (`the invoker threw TypeError`) where Java gives the exception's class name.
- **Records:** `engine` is `rulestone-js 0.1.0`, so a record shows which engine produced it. `evaluatedAt` has millisecond precision. Model latencies are measured with `performance.now()`.
- **Time zone:** "today" (no `asOf`) comes from `clock` and `timeZone`, the runtime's own zone by default. Set `asOf` for reproducible results.

## Development

```sh
npm ci                  # in sdk/
npm run licenses        # npm license allowlist
npm run typecheck
npm run build
npm test
npm run size            # bundle size
```
