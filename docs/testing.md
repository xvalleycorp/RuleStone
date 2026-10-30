# Testing decision tables

A decision table is code: a changed threshold or a reordered rule changes who gets a loan. This page explains how to test tables, and the [decision flows](flows.md) built from them, with the `rulestone-test` module, so that every rule is checked by a test before it reaches production.

The module offers three tools:

| Tool | Use it to |
|---|---|
| [`.stone-test.json` files](#test-cases-in-json) | Write test cases as data, without Java. Business analysts can write and review them next to the table. |
| [`StoneTests`](#running-test-files-with-junit-5) and [`RuleCoverage`](#rule-coverage) | Run those files in any JUnit 5 build, one test per case, and fail the build if a rule is never matched by any test, or a flow node or branch never exercised. |
| [`DecisionCheck`](#fluent-assertions-in-java) | Write test cases in Java with fluent assertions, in any test framework. |

- [Adding the module](#adding-the-module)
- [Test cases in JSON](#test-cases-in-json)
- [Running test files with JUnit 5](#running-test-files-with-junit-5)
- [Rule coverage](#rule-coverage)
- [Testing decision flows](#testing-decision-flows)
- [Fluent assertions in Java](#fluent-assertions-in-java)
- [Recommended practice for banks](#recommended-practice-for-banks)

## Adding the module

Add `rulestone-test` with `test` scope, next to `rulestone-core`:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

`StoneTests` needs JUnit 5 (`org.junit.jupiter:junit-jupiter-api`), which `rulestone-test` declares with `provided` scope: it uses the JUnit version your project already has and does not bring its own. `DecisionCheck`, `StoneTestJson` and `RuleCoverage` do not need JUnit and work with any test framework.

## Test cases in JSON

Keep test cases in a file named `<name>.stone-test.json`, next to the table it tests. Every table in [`examples/`](../examples/) has one, for example [`examples/loan_eligibility.v3.stone-test.json`](../examples/loan_eligibility.v3.stone-test.json). Two of its cases:

```json
{
  "decision": "loan_eligibility",
  "cases": [
    {
      "name": "adult with low debt is approved",
      "asOf": "2026-12-01",
      "facts": {
        "applicant": { "age": 30, "monthlyIncome": 25750000.50, "monthlyDebt": 5000000, "segment": "RETAIL", "blacklisted": false },
        "amount": 300000000
      },
      "expect": {
        "output": { "decision": "APPROVE", "limit": 309000000 },
        "exactOutput": true,
        "reasonCodes": [],
        "matchedRules": ["R4"],
        "ruleVersion": "3"
      }
    },
    {
      "name": "zero income fails the evaluation",
      "description": "R3 divides by monthlyIncome",
      "asOf": "2026-12-01",
      "facts": {
        "applicant": { "age": 30, "monthlyIncome": 0, "monthlyDebt": 1000, "segment": "RETAIL", "blacklisted": false },
        "amount": 1000
      },
      "expect": { "error": "division by zero" }
    }
  ]
}
```

Each case evaluates the decision once with its `facts` and compares the result with `expect`. Only the expectations written down are checked: a case without `matchedRules` accepts any matched rules.

### File

| Field | Required | Content |
|---|---|---|
| `decision` | yes | Id of the decision under test, as in the table's or flow's `id`. |
| `description` | no | Free text, e.g. the policy document the cases come from. |
| `cases` | yes | Non-empty array of cases. |

### Case

| Field | Required | Content |
|---|---|---|
| `name` | yes | Unique within the file. Shown as the test name and in failure messages, so describe the situation: `"debt just above half of income is rejected"`. |
| `description` | no | Free text, e.g. the policy clause or ticket the case checks. |
| `asOf` | no | ISO date (`yyyy-MM-dd`) that selects the version in force, as in `DecisionRef.asOf`. **Set it for every decision with effective dates**; without it, the engine's clock decides and the result depends on the day the test runs. |
| `version` | no | Evaluates exactly this version, regardless of dates, as in `DecisionRef.version`. |
| `facts` | yes | Object of input names to values, as for `Facts`. Objects become `Map`s and arrays `List`s. `null` is not allowed: leave a field out to test a missing value. |
| `expect` | yes | Object with the expectations below. `{}` only checks that the evaluation succeeds. |

### Expectations

| Field | Checks |
|---|---|
| `output` | Each listed output equals the given value. Outputs not listed are not checked. The value `null` means the output must be absent, as when a `FIRST` rule does not set it. |
| `exactOutput` | With `true`, the decision must produce no output besides those listed in `output`. Requires `output`. |
| `reasonCodes` | Exactly these reason codes, in this order. `[]` means none. |
| `matchedRules` | Exactly these rules matched, in evaluation order. `[]` means none. For a flow, the rules of every table node, in order. |
| `path` | For a flow: exactly these nodes were visited, in order, from the start node to the end node. For a table, the path is the decision id alone. |
| `ruleVersion` | The version used: `"3"` accepts any content of version 3; `"3@sha256:…"` (the full `ruleVersion` of a result) accepts only that exact content. |
| `error` | The evaluation fails with a `RuleStoneException` whose message contains this text, e.g. `"division by zero"`, `"balance: missing input"` or `"no version of decision 'loan_eligibility' is effective on 2025-12-31"`. Cannot be combined with the other fields. |

### Values

When the test files are run with the tables (see [Rule coverage](#rule-coverage), or pass the tables to `StoneTests.dynamicTests` or `StoneTestJson.read`), `facts` and `expect.output` are read with the types the table declares, with the same JSON forms as [`FactsJson`](java-api.md#json-facts-and-results-factsjson):

| Type | Written as | Example |
|---|---|---|
| `bool` | `true` or `false` | `"blacklisted": false` |
| `int` | an integer | `"age": 30` |
| `string` | a string | `"segment": "RETAIL"` |
| `decimal` | a number, or a string in plain notation | `"monthlyIncome": 25750000.50`, `"rate": "0.0980"` |
| `money` | a string, the amount, a space and the ISO 4217 code | `"amount": "1500000.50 VND"` |
| `date` | a string, `yyyy-MM-dd` | `"valueDate": "2026-11-02"` |
| list | an array | `"cashDepositsLast7d": ["250000000 VND", "270000000 VND"]` |
| struct | an object | `"account": { "openedOn": "2019-04-10", "riskRating": "LOW" }` |

A case from [`examples/aml_transaction_monitoring.stone-test.json`](../examples/aml_transaction_monitoring.stone-test.json) with money and dates:

```json
{
  "name": "large cash deposit is a large cash transaction",
  "asOf": "2026-11-02",
  "facts": {
    "account": { "openedOn": "2019-04-10", "lastActivityBeforeToday": "2026-10-30", "riskRating": "LOW", "customerIsPep": false },
    "transaction": { "amount": "350000000 VND", "type": "CASH_DEPOSIT", "valueDate": "2026-11-02" },
    "history": { "cashDepositsLast7d": ["350000000 VND"], "totalInLast24h": "350000000 VND", "totalOutLast24h": "0 VND" }
  },
  "expect": {
    "output": { "alert": ["LARGE_CASH"], "score": [10] },
    "reasonCodes": ["LARGE_CASH_TRANSACTION"],
    "matchedRules": ["LARGE_CASH"]
  }
}
```

Read with types, a value that does not match its type is a problem in the file, reported with its path, such as `$.cases[3].facts.transaction.amount: expected money as "<amount> <CCY>", e.g. "1500000 VND"`. So are inputs, struct fields and outputs that the table does not declare. A struct field that is left out stays unset, so rules see it with `has()`. Under `COLLECT`, every expected output is a list. The types come from the version the case evaluates: the one named by `version`, or the one in force on `asOf`. Without either, all versions of the decision must declare the same inputs and outputs. Cases for a decision that is not among the tables are read without types.

Without the tables, values keep their JSON types and the decision converts them, so `money` and `date` inputs cannot be written: the decision rejects a string where it expects a `Money` or a `LocalDate`.

Numbers are read exactly and never pass through floating point: integers become `Long` (`BigInteger` beyond 64 bits) and all other numbers `BigDecimal`, keeping the written scale, so `25750000.50` is passed to the decision as `25750000.50`.

Expected numbers are compared **by value**: `309000000`, `309000000.0` and `309000000.00` all match a limit of `309000000`. So are amounts of money in the same currency: `"25000.00 VND"` matches `25000 VND`. Lists are compared element by element, in order: `"fee": [25000, 10000]` matches the `COLLECT` output `[25000, 10000]`.

### Strict reading

As for decision tables, unknown and duplicate keys are errors, so that a misspelt expectation cannot silently turn a check off. Every problem in a file is reported at once with its JSON path:

```
invalid test file loan_eligibility.v3.stone-test.json (1 problem):
  - $.cases[0].expect.matchedRule: unknown field (allowed: error, exactOutput, matchedRules, output, path, reasonCodes, ruleVersion)
```

## Running test files with JUnit 5

`StoneTests.dynamicTests` turns test files into JUnit 5 dynamic tests, one per case. Pass a file, or a directory to search recursively for `*.stone-test.json` files:

```java
class DecisionTableTest {

    private final RuleStoneEngine engine = RuleStone.builder()
            .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")))
            .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")))
            .build();

    @TestFactory
    Stream<DynamicTest> decisionTables() {
        return StoneTests.dynamicTests(engine, Path.of("rules"));
    }
}
```

Each test is named `<file>: <case name>`, for example `loan_eligibility.v3.stone-test.json: adult with low debt is approved`. A failing case lists every expectation that was not met. If someone changed `R4` to lend at most 10 monthly incomes instead of 12, the case above would fail with:

```
loan_eligibility.v3.stone-test.json, case "adult with low debt is approved", evaluated with loan_eligibility@3:
  - output "decision": expected "APPROVE" but was "REVIEW"
  - output "limit": expected 309000000 but the decision produced no such output
  - reason codes: expected [] but was [AMOUNT_ABOVE_LIMIT]
  - matched rules: expected [R4] but was [R5]
```

Messages show expected and actual outputs, never the input facts. A file that cannot be read becomes a single failing test, named after the file, that lists its problems; the other files still run. A path that does not exist, or contains no test file, fails the test factory, so a wrong path cannot pass unnoticed.

To read values with their declared types, so that money and dates can be written (see [Values](#values)), pass the tables as well: `StoneTests.dynamicTests(engine, path, tables)`. The overload with a `RuleCoverage` below uses its tables.

To read test files without JUnit, use `StoneTestJson.read(path, tables)` (or `StoneTestJson.read(path)`, without types) and call `check(engine)` on each `StoneTestCase`. It throws an `AssertionError` listing the mismatches.

## Rule coverage

A rule that no test matches is a rule nobody has checked. `RuleCoverage` tracks, for each decision and version, which rules have been matched by a passing test. Pass the tables to `dynamicTests` to add a final test named `rule coverage`:

```java
@TestFactory
Stream<DynamicTest> decisionTables() {
    List<DecisionTable> tables = List.of(
            DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")),
            DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")));
    RuleStoneEngine engine = RuleStone.builder().decisions(tables).build();
    return StoneTests.dynamicTests(engine, Path.of("rules"), RuleCoverage.of(tables));
}
```

The `rule coverage` test fails unless every rule of every table is matched by at least one passing case. If `examples/loan_eligibility.v2.stone-test.json` were deleted, the coverage test for `examples/` would fail with:

```
rule coverage: 12 of 15 rules matched by a passing test
  deposit_rate@1: 4 of 4 rules
  loan_eligibility@2: 0 of 3 rules, never matched: R1, R2, R3
  loan_eligibility@3: 5 of 5 rules
  transfer_fees@1: 3 of 3 rules
```

Only passing cases that expect success count. A rule matched only in a failing case, or in a case that expects an `error`, is not covered. Coverage counts each version separately: tests of version 3 do not cover version 2. The coverage test evaluates every case itself, so its result does not depend on the order in which JUnit runs the tests.

Outside JUnit, record results yourself and check at the end:

```java
RuleCoverage coverage = RuleCoverage.of(loanEligibilityV3);
coverage.record(result);            // the DecisionResult of each passing test; here one that matched R4
coverage.uncoveredRules();          // {loan_eligibility@3=[R1, R2, R3, R5]}
coverage.report();                  // a summary in the format shown above
coverage.assertComplete();          // throws AssertionError with the report
```

Rule coverage shows that every rule *can* fire as intended. It does not show that the boundaries between rules are right; for that, add cases on both sides of every threshold (see below).

## Testing decision flows

A test file for a [decision flow](flows.md) names the flow in `decision`. Its cases are written as for a table, and can also expect the `path` of nodes the evaluation visits. This case, from [`examples/flows/card_origination.stone-test.json`](../examples/flows/card_origination.stone-test.json), checks that a grade `D` customer is referred:

```json
{
  "name": "an existing customer at grade D is referred, not declined",
  "facts": {
    "applicant": { "id": "C-1005", "age": 35, "income": "20000000 VND", "existingCustomer": true },
    "bureau": { "dpd30Count12m": 0, "utilisation": "0.95" }
  },
  "expect": {
    "output": { "decision": "REFER", "riskGrade": "D", "limit": "0 VND" },
    "reasonCodes": ["HIGH_UTILISATION"],
    "path": ["policy", "affordability", "segment", "grade_existing", "referred"]
  }
}
```

Pass the flows with the tables, so that facts and outputs are read with the flow's types, and so that coverage includes the flows:

```java
@TestFactory
Stream<DynamicTest> cardOrigination() {
    List<DecisionTable> tables = Stream.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit")
            .map(id -> DecisionTableJson.read(Path.of("rules/" + id + ".stone.json")))
            .toList();
    List<DecisionFlow> flows = List.of(DecisionFlowJson.read(Path.of("rules/card_origination.flow.json")));
    RuleStoneEngine engine = RuleStone.builder().decisions(tables).flows(flows).build();
    return StoneTests.dynamicTests(engine, Path.of("rules"), RuleCoverage.of(tables, flows));
}
```

The final test is then named `rule and node coverage`. Besides every rule of every table, including the rules that table nodes match inside flows, it requires every node of every flow to be visited and every **branch** to be taken by a passing case. A branch is a way out of a node that has several: each distinct target of a `switch` or of a conditional `next`, written `segment -> grade_new`, and each bucket of a `split`, written `limit_strategy[challenger]`. Without the challenger case in the example file, the report is:

```
rule coverage: 14 of 15 rules matched by a passing test
  card_grade_existing@1: 4 of 4 rules
  card_grade_new@1: 4 of 4 rules
  card_limit@1: 3 of 4 rules, never matched: A_CHALLENGER
  card_policy@1: 3 of 3 rules
node coverage: 10 of 10 nodes and 9 of 10 branches exercised by a passing test
  card_origination@1: 10 of 10 nodes, 9 of 10 branches, never taken: limit_strategy[challenger]
```

To test a `split` bucket, choose a key that falls into it: the bucket of a key never changes for the same salt. `RuleCoverage.uncoveredNodes()` and `uncoveredBranches()` return what is missing, as `uncoveredRules()` does for rules. Without JUnit, read the files with `StoneTestJson.read(path, tables, flows)`.

## Fluent assertions in Java

`DecisionCheck` checks one evaluation with a fluent API. It throws `AssertionError`, so it works with JUnit, TestNG or any other framework:

```java
DecisionCheck.of(engine)
        .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
        .given("applicant", applicant).given("amount", new BigDecimal("300000000"))
        .expectOutput("decision", "APPROVE")
        .expectOutput("limit", new BigDecimal("309000000"))   // compared by value
        .expectReasonCodes()                                   // exactly none
        .expectMatchedRules("R4");
```

The request methods `decision`, `version`, `asOf` and `given` set up the evaluation. The first expectation evaluates the decision; after that the request cannot be changed.

| Method | Checks |
|---|---|
| `expectOutput(name, value)` | Output `name` equals `value`. Numbers are compared by value. `Double` and `Float` are rejected: write `new BigDecimal("0.048")`. |
| `expectNoOutput(name)` | The decision did not produce output `name`. |
| `expectExactOutput(Map)` | Exactly these outputs, with these values. |
| `expectReasonCodes(codes…)` | Exactly these reason codes, in order. No arguments: none. |
| `expectMatchedRules(ids…)` | Exactly these rules matched, in order. No arguments: none. |
| `expectPath(nodes…)` | For a flow, exactly these nodes were visited, in order. |
| `expectRuleVersion(version)` | `"3"`, or the full `"3@sha256:…"`. |
| `expectFailure(type)` | The evaluation threw this exception type, e.g. `DecisionEvaluationException.class`. |
| `expectFailureMessage(text)` | The evaluation threw a `RuleStoneException` whose message contains `text`. |
| `result()` | Returns the `DecisionResult` for any other check. |

A failed expectation names the decision, the version used and the request:

```
loan_eligibility@3 (asOf 2026-12-01): output "limit": expected 300000000 but was 309000000
```

To expect an evaluation to fail:

```java
DecisionCheck.of(engine)
        .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
        .given("applicant", zeroIncomeApplicant).given("amount", new BigDecimal("1000"))
        .expectFailure(DecisionEvaluationException.class)
        .expectFailureMessage("division by zero");
```

Prefer `.stone-test.json` files for cases that business analysts should read or write. Use `DecisionCheck` when a test needs Java, for example to build facts from domain objects or to evaluate several decisions in sequence.

## Recommended practice for banks

- **Cover every rule.** Run the coverage check in every build. A table change that adds a rule without a test case fails the build.
- **Test both sides of every threshold.** For `applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')`, test a ratio of exactly 0.5 and one just above it, as `examples/loan_eligibility.v3.stone-test.json` does. Most rule defects are off-by-one boundaries (`>` instead of `>=`) that coverage alone does not find.
- **Write the expected results from the policy, not from the engine.** The person who writes a case should derive the expected outcome from the credit policy or product sheet, not copy what the engine returns. Otherwise the test only confirms the current behaviour, including its mistakes.
- **Review test cases with the rule change.** Keep each table and its `.stone-test.json` file in the same repository and change them in the same pull request. The reviewer, for example a second analyst under a four-eyes principle, approves the rule change and the cases that prove it together. A changed expectation in a test file is a visible statement that the policy changed.
- **Keep one test file per table version.** When a new version takes effect, add a new file for it and keep the old one: the old version is still used to replay past decisions. Set `asOf` in every case, as in the examples, so that each file tests the version it was written for, whatever the date of the build.
- **Test the failures you know about.** If a table cannot handle an input (such as zero income in the example), add an `error` case. It documents the limitation and fails if it changes.
- **Use invented data.** Test files are shared and kept in version control. Never copy real customer data into them.
- **Run the tests in continuous integration.** Rules should reach production only through a build where every case passes and coverage is complete. The test files, the build logs and the `ruleVersion` of each table together show auditors which rules were tested, with which cases, before release.
