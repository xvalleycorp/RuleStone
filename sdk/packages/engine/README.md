# @rulestone/engine

RuleStone decision tables in the browser. This package reads `.stone.json` tables, type-checks their CEL expressions and evaluates them with exact `decimal`, `money` and `date` types. On everything the conformance suite covers, it gives the same results as the Java engine: rule versions, outputs, reason codes, traces, decision records and error messages.

The Java engine remains the engine of record. Use this package for instant feedback, for example in an editor or a sandbox, and evaluate production decisions with the Java engine.

> The package is private while its npm name is decided. Its name, `@rulestone/engine`, is provisional.

- No runtime dependencies. Numbers use the native `BigInt`.
- ESM, TypeScript types included, and safe to tree-shake (`sideEffects: false`).
- Runs in current browsers and in Node.js 22 or later. Hashing uses WebCrypto (`crypto.subtle`), which browsers provide only on secure (HTTPS or localhost) pages.
- **Size:** 93.1 KiB minified, 30.3 KiB minified and gzipped, for the whole package (`npm run size`).

## Usage

```ts
import { createEngine, parseDecisionTable, outputJson, writeDecisionRecord } from '@rulestone/engine'

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

`createEngine` throws an `InvalidDecisionError` whose `problems` list every problem in every table, with the same paths and messages as Java, for example `loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'`. `parseDecisionTable` throws the same error for a malformed file.

## API

| Export | Purpose |
|---|---|
| `parseDecisionTable(text)` | Strict `.stone.json` reader, as `DecisionTableJson.read`. Unknown and duplicate keys are errors, and every problem is reported with its JSON path. |
| `writeDecisionTable(table)`, `ruleVersionOf(table)` | The canonical form, and the rule version `<version>@sha256:<hex>` computed over it, as in Java. |
| `createEngine(options)` | Compiles every table and checks versions for duplicates and contradictory dates (a version without `effectiveTo` is superseded by the next one). Options: `tables`, `maxIterations`, `currencyScales`, `clock`, `timeZone`, `recordIds`, `auditSinks`. |
| `engine.evaluate(ref, facts)` | Evaluates a decision with JavaScript values (see below). `ref` is a decision id or `{ id, version?, asOf?, correlationId? }`. |
| `engine.evaluateJson(ref, json)` | The same, with facts in their JSON forms, read with the inputs of the selected version (`FactsJson`). |
| `DecisionResult` | `output` (a `Map` in declaration order), `reasonCodes`, `trace`, `matched`, `matchedRules`, `ruleVersion`, `decisionId`, `record`. |
| `DecisionRecord`, `decisionRecordToJson`, `writeDecisionRecord` | The record of every evaluation, successful or not, in the JSON shape of Java's `DecisionRecordJson`. A failed evaluation's error carries its record as `error.record`. |
| `readFacts`, `readValues`, `readValue`, `resultTypes` | JSON to values with declared types: money `"1500000.50 VND"`, dates `"2026-11-01"`, decimals as numbers or numeric strings. JSON numbers never pass through floating point. |
| `valueToJson`, `valuesToJson`, `writeValues`, `outputJson` | Values to their JSON forms, with decimals as plain-notation strings. |
| `parseStoneTests`, `checkStoneTestCase` | Reads `.stone-test.json` files and checks their cases, as `StoneTestJson` and `StoneTestCase.check` do. |
| `ExpressionEnvironment` | Compiles and evaluates single expressions, as the Java class of that name. |
| `analyzeExpression(source, { variables, expected? })` | For editors: parses and type-checks an expression without throwing, and returns the typed syntax tree, every problem with its position (in code points and as a UTF-16 index, line and column) and the engine's message. Every node has its source span (`start`, `end`, including enclosing parentheses); selections also give `fieldPos`. `compile` uses the same analysis. |
| `localValueTypes(inputs, locals)` | For editors: the inferred types of a table's local values (`let`) that compile, to pass to `analyzeExpression` with the inputs. |
| `functionCatalog()`, `inputStructName(path)`, `codePointIndex(source, pos)` | The functions and methods rules can call, for autocomplete; the type name the checker gives a struct among the inputs (to tell which declared field a selection reads); converting code-point offsets to string indexes. |
| `Decimal`, `Money`, `LocalDate` | Exact values: `Decimal` keeps its scale like `BigDecimal`, `Money` is an amount and an ISO 4217 code, and `LocalDate` is a calendar date with no time zone. |
| `RuleStoneError` and its subclasses | `InvalidDecisionError`, `InvalidFactsError`, `DecisionNotFoundError`, `DecisionEvaluationError`, `AuditError`. `ExpressionError` and `InvalidValueError` come from `ExpressionEnvironment`. |

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

## Why not decimal.js

Java's `BigDecimal` keeps a scale: `1.50` and `1.5` are equal but print differently. That difference shows up in outputs, in `string(d)`, in decision records and in how division and rounding choose their result's scale. decimal.js normalizes the scale away, so it cannot reproduce those results. `Decimal` holds a `BigInt` unscaled value and a scale, and it implements Java's rules for addition, multiplication, `setScale` with every rounding mode, and division with `MathContext.DECIMAL128`, including the preferred scale of exact quotients. The conformance probes check about 280 generated operations against `BigDecimal`.

## Conformance

The tests in `test/` compare this engine with fixtures that the Java engine writes. The fixtures are written by `SdkFixturesTest` in `rulestone-test`, and the Maven build fails if they are stale:

| Fixture | Checked |
|---|---|
| `java-hashes.json` | The canonical form and rule version of every table in `examples/` and in `test/fixtures/tables/`. |
| `java-records.json` | The decision record and trace of every case of every `examples/*.stone-test.json` file. Each case also passes its own expectations. |
| `java-probes.json` | The result type and value, or the exact error, of every expression in `probes.json`. The probes cover operators, macros, the iteration budget shared by nested macros, `&&` and `\|\|` absorbing errors, error positions, list result types, decimal arithmetic, money, dates, fuzzy matching and value-free error messages. |
| `java-invalid-tables.json` | The problems reported for every invalid table in `invalid-tables.json`. |

`docs.test.ts` also checks every `` `expression` → result `` example in `docs/expressions.md`, and the worked examples in `docs/fuzzy-matching.md`, as Java's `ExpressionsDocTest` does.

To regenerate the fixtures after changing the engine, the examples or the probes, run this from the repository root:

```sh
mvn -pl rulestone-test -am test -Dtest=SdkFixturesTest -Dsurefire.failIfNoSpecifiedTests=false -Drulestone.sdk.updateFixtures=true
```

The ISO 4217 table (`src/iso4217.ts`) is generated from the table that the Java engine reads, `rulestone-api/src/main/resources/vn/io/dxa/rulestone/api/iso4217.txt`. Regenerate it with `npm run generate:iso4217`. A test fails if the two differ.

### Known differences from the Java engine

- **Not supported:** map literals (`{'a': 1}`), `bytes`, `type()`, `timestamp()`, `duration()` and `string(double)`. RuleStone rules do not use them, and Java's formatting of doubles depends on the JDK version. The browser engine rejects them at compile time with a message saying so; Java accepts them.
- **Syntax errors:** they are reported at the same position, but in other words than ANTLR's (for example `mismatched input '<EOF>'…`).
- **Regular expressions:** `matches()` translates RE2 syntax to JavaScript `RegExp` with the `u` flag. Common syntax behaves the same, including leading `(?i)` flags, `\pL` and `(?P<name>…)`. Exotic RE2 constructs may differ.
- **Unicode:** `normalizeName` and the fuzzy functions use the Unicode tables of the JavaScript runtime, and Java uses those of the JDK. They differ only for characters added in recent Unicode versions.
- **Input errors from JavaScript values:** messages name JavaScript types (`expected int but got string`) where Java names Java classes.
- **Records:** `engine` is `rulestone-js 0.1.0`, so a record shows which engine produced it. `evaluatedAt` has millisecond precision. `factsHash` is plain SHA-256; the Java engine's HMAC option (`factsHashKey`) is not offered.
- **Time zone:** "today" (no `asOf`) comes from `clock` and `timeZone`, the browser's own zone by default. Set `asOf` for reproducible results.

## Development

```sh
npm ci                  # in sdk/
npm run licenses        # npm license allowlist
npm run typecheck
npm run build
npm test
npm run size            # bundle size
```
