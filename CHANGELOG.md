# Changelog

All notable changes to RuleStone are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, minor versions may contain breaking changes; they are called out under **Changed**.

## [Unreleased]

### Added

- Decision tables with `FIRST`, `UNIQUE` and `COLLECT` hit policies.
- The `.stone.json` file format, read strictly: unknown and duplicate keys are errors.
- Rule versions identified by version and SHA-256 content hash, e.g. `3@sha256:…`.
- Effective dating with `effectiveFrom` and an optional `effectiveTo`. A version without `effectiveTo` stays in force until the next version takes effect, so adding a version never changes the previous version's file or hash ([#6](https://github.com/xvalleycorp/RuleStone/issues/6)). Versions that take effect on the same day, and an `effectiveTo` after the next version's start, are rejected. `DecisionVersions` applies these rules for the engine and every integration.
- Expression language based on CEL with typed inputs (structs and lists) and an exact `decimal` type. Floating-point numbers are rejected.
- `decimal` arithmetic, comparison, `round` with any rounding mode, `abs`, `min`, `max`, `string`.
- Bounded comprehensions (`maxIterations`).
- Embedded, immutable, thread-safe engine (`RuleStone.builder()`).
- Evaluation traces that record rule ids only, never input values.
- All definition problems reported together with their location.
- Apache-2.0 license, license headers, third-party license allowlist, CycloneDX SBOM, sources and Javadoc jars.
- Website at https://rulestone.dxa.io.vn, generated from `docs/` and served by Cloudflare.
- GitHub Actions CI on Java 17, 21 and 25; Dependabot.
- `money` type with ISO 4217 minor units and `HALF_EVEN` rounding by default, and a `date` type with an implicit `businessDate` input.
- Fuzzy name matching for AML/KYC screening: `normalizeName`, `similarity` (Jaro-Winkler), `nameSimilarity`, `maxNameSimilarity` and `levenshtein`.
- `FactsJson`: schema-aware conversion between JSON and facts or outputs, with exact decimals and error messages that never contain values.
- `rulestone-test`: JSON test cases (`.stone-test.json`), JUnit 5 dynamic tests, rule coverage and fluent assertions.
- `rulestone-temporal`: decisions as Temporal activities, or evaluated inside workflow code with rule versions pinned across replays.
- `rulestone-camunda8` and `rulestone-camunda7`: a Camunda 8 job worker with an element template, and a Camunda 7 delegate and expression bean.
- `rulestone-editor`: a local web editor for decision tables that can try tables on sample facts.
- Examples for card fraud, consumer loan underwriting and AML, with JSON tests covering every rule.
- Bilingual website (English and Vietnamese) with checks that keep translations and their code blocks in step with the English pages.
- Design proposal for hybrid AI decisioning: model outputs as rule inputs, models as nodes in decision flows, and a decision record for every decision.
- Decision records: every evaluation, successful or not, produces a `DecisionRecord` with the exact version, path, matched rules, reason codes and a hash of the inputs (SHA-256, or HMAC-SHA256 with `factsHashKey`), but no input values. Records go to `AuditSink`s, are returned on `DecisionResult.record()`, and are written as JSON by `DecisionRecordJson`. `DecisionRef.correlationId` links them to the business transaction. A sink that throws fails the evaluation with `AuditException`, so a decision is never returned unrecorded.
- Browser SDK (`sdk/`): `@rulestone/engine` (provisional name), a TypeScript engine with no runtime dependencies that reads `.stone.json` tables, type-checks and evaluates CEL with exact `decimal`, `money` and `date` types, selects versions by effective date and produces decision records. A conformance suite checks it against fixtures written by the Java engine: rule versions, every example test case with its decision record, the expressions in the documentation, and several hundred expression probes. CI runs it on Node 24.
- Browser SDK: `@rulestone/editor` (provisional name), embeddable Web Components for editing decision tables and decision flows visually: `<rulestone-table-editor>`, `<rulestone-rules-grid>` with drag and drop and per-cell type checking, `<rulestone-condition-builder>` with nested AND/OR blocks that round-trip exactly to CEL, `<rulestone-schema-designer>` whose renames update rule expressions through the syntax tree, `<rulestone-flow-canvas>` (design only) and `<rulestone-try-it>`, which runs rules in the browser. English and Vietnamese, keyboard alternatives for every drag and drop, themable, and working under a strict CSP. The engine exports `analyzeExpression` and `functionCatalog` for editors.
- `Iso4217`: the fixed table of ISO 4217 codes and minor units that both engines use.
- Timeouts: `DecisionRef.timeout(Duration)` per request and `RuleStone.builder().timeout(Duration)` per engine. The engine checks the time before each rule, on a monotonic clock, and an evaluation that runs out of time fails with `DecisionTimeoutException` (a `DecisionEvaluationException`) listing the rules examined; its decision record has the new status `TIMEOUT` ([#7](https://github.com/xvalleycorp/RuleStone/issues/7)).
- Canary and shadow versions (champion/challenger on live traffic): `RuleStone.builder().canary(decision, version, share)` routes a share of correlation ids, chosen by hash so that a retry gets the same version, to a new version; `shadow(decision, version)` evaluates a version on the same facts as every request and records it without returning it, optionally on a `shadowExecutor`. Records say so in the new `DecisionRecord.routing()` ([#9](https://github.com/xvalleycorp/RuleStone/issues/9)).
- `rulestone-audit-kafka`: `KafkaAuditSink` publishes decision records to Kafka as JSON, keyed by correlation id, without waiting for the broker. It fails the evaluation at once when the producer's buffer is (almost) full or the producer refuses a record, or optionally blocks for up to `max.block.ms`, and reports records that were accepted but not delivered ([#10](https://github.com/xvalleycorp/RuleStone/issues/10)).
- `rulestone-benchmark`: JMH benchmarks of `evaluate()` (`FIRST` and `COLLECT`, 10 to 1,000 rules, money, decimal and list inputs), with latency and allocation figures in the Java API guide. Not published ([#8](https://github.com/xvalleycorp/RuleStone/issues/8)).
- Decision flows (step 2 of hybrid AI decisioning): `.flow.json` files, read by `DecisionFlowJson` and registered with `RuleStone.builder().flow(…)`, chain decision tables with `table`, `switch`, `derive`, `split` and `end` nodes, and are evaluated by id like tables. Flows are versioned and effective-dated like tables, with a content hash over the flow file; every problem is reported when the engine is built, including cycles, unreachable nodes, unsatisfied table inputs, end nodes that miss an output, splits that do not add up to 100, and any value read by a node that some path to it does not set. Their decision records list every node visited, with each table's exact version and matched rules and the branch or bucket taken. Timeouts, canary and shadow versions apply to flows. Model and sub-flow nodes are not supported yet. Example: `examples/flows/card_origination.flow.json`.
- `rulestone-test` for flows: test cases can expect the `path` of nodes, `DecisionCheck.expectPath`, and `RuleCoverage.of(tables, flows)` also requires every flow node to be visited and every branch to be taken.
- Aggregate functions on lists of `int`, `decimal` and `money`, in both engines: `list.sum()`, `list.sum(zero)`, `list.min()`, `list.max()` and `list.avg()`. Money in different currencies fails, as `+` does; every element counts towards the iteration limit (step 1 of [Parameters, aggregates and local values](docs/parameters.md)).
- Local values in decision tables (step 2): `"let": { "dti": "…" }` names expressions that every rule can read, in both engines. Types are inferred; local values can read each other, and cycles, name clashes with inputs, `businessDate` or `params`, and type errors are reported when the table is loaded. Each is computed when a rule first reads it, at most once per evaluation, so one that no evaluated rule reads cannot fail the evaluation. The content hash covers `let`; tables without it keep their hash. `DecisionTable` has a `let` component (the previous constructor remains), and `ExpressionEnvironment` has `compileLocal`, `references` and `Builder.local`. The browser editor's rules grid, condition builder and a new Local values tab of the table editor show and offer them. New example: `credit_limit_increase`.

### Changed

- `DecisionRecord.PathStep` has `decision` and `taken` components for flows, and its `version` is now an `Optional`, present for table steps only; the previous constructor remains. In record JSON, steps other than table steps have no `version` or `matchedRules`. `DecisionVersions` works on `VersionedDefinition`, which both `DecisionTable` and `DecisionFlow` implement. `RuleCoverage` counts the rules of each table step of a result's decision record.
- `DecisionRef` has a `timeout` component and `DecisionRecord` a `routing` component; the previous constructors remain. `DecisionEvaluationException` is no longer final, and a `DecisionRecord` with the status `TIMEOUT`, like `ERROR`, carries an `error` and no output.
- Money accepts the same currency codes on every JDK: a fixed ISO 4217 table (amendment 180, plus withdrawn codes that Java has long accepted) replaces `java.util.Currency`, whose codes differ between JDK versions. `XCG`, `ZWG` and `XAD` are now accepted on Java 17 and 21 too.
- Errors of CEL's standard conversions, list indexing and `matches()` no longer quote the argument: `int('x')` fails with `int() argument is not a valid integer` instead of `For input string: "x"`, and an index out of range with `list index out of bounds` instead of `Index out of bounds: 5`.
