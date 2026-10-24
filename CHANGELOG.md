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

### Changed

- Money accepts the same currency codes on every JDK: a fixed ISO 4217 table (amendment 177, plus withdrawn codes that Java has long accepted) replaces `java.util.Currency`, whose codes differ between JDK versions. `XCG` and `ZWG` are now accepted on Java 17 and 21 too.
- Errors of CEL's standard conversions, list indexing and `matches()` no longer quote the argument: `int('x')` fails with `int() argument is not a valid integer` instead of `For input string: "x"`, and an index out of range with `list index out of bounds` instead of `Index out of bounds: 5`.
