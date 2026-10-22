# Getting started

This guide takes you from an empty project to evaluating your first decision in about ten minutes. It assumes you know Java and Maven. No prior knowledge of rule engines is needed.

You will:

1. add RuleStone to a project;
2. write a decision table for unsecured loan eligibility;
3. evaluate it from Java and read the result;
4. see what happens when a rule is wrong.

## 1. Add RuleStone to your project

RuleStone requires **Java 17 or newer**.

> **Pre-release.** RuleStone is not yet published to Maven Central. Until it is, build it from source and install it into your local Maven repository:
>
> ```sh
> git clone <repository-url> rulestone
> cd rulestone
> mvn install
> ```

Then add the dependency. Maven:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL; add `mavenLocal()` to your repositories while using a local build):

```kotlin
implementation("vn.io.dxa.rulestone:rulestone-core:0.1.0-SNAPSHOT")
```

If you use several RuleStone modules, import `rulestone-bom` in `<dependencyManagement>` to keep their versions aligned.

## 2. Write a decision table

A *decision table* is a list of rules that turns typed inputs into typed outputs. Save the following as `rules/loan_eligibility.stone.json`. It is the same file as [`examples/loan_eligibility.v3.stone.json`](../examples/loan_eligibility.v3.stone.json).

```json
{
  "id": "loan_eligibility",
  "version": "3",
  "description": "Unsecured consumer loan eligibility",
  "effectiveFrom": "2026-11-01",
  "hitPolicy": "FIRST",
  "inputs": {
    "applicant": {
      "age": "int",
      "monthlyIncome": "decimal",
      "monthlyDebt": "decimal",
      "segment": "string",
      "blacklisted": "bool"
    },
    "amount": "decimal"
  },
  "outputs": {
    "decision": "string",
    "limit": "decimal"
  },
  "rules": [
    {
      "id": "R1",
      "description": "Applicants must be adults",
      "when": "applicant.age < 18",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "UNDERAGE"
    },
    {
      "id": "R2",
      "when": "applicant.blacklisted",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "BLACKLISTED"
    },
    {
      "id": "R3",
      "description": "Debt-to-income above 50%",
      "when": "applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "DTI_HIGH"
    },
    {
      "id": "R4",
      "description": "Up to 12 months of income, rounded down to the nearest million",
      "when": "amount <= applicant.monthlyIncome * 12",
      "then": {
        "decision": "'APPROVE'",
        "limit": "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000"
      }
    },
    {
      "id": "R5",
      "when": "true",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "AMOUNT_ABOVE_LIMIT"
    }
  ]
}
```

What this says:

- **`inputs`** declares what the caller must provide, with types. `applicant` is a *struct* with five typed fields; `amount` is a `decimal`, an exact number suitable for money.
- **`outputs`** declares what the rules can produce.
- **`hitPolicy: FIRST`** means rules are tried top to bottom and the first one whose `when` is true decides. `R5` matches everything, so it catches whatever the earlier rules did not.
- **`when`** and the values in **`then`** are expressions in [CEL](expressions.md). Text values are quoted inside the JSON string: `"'REJECT'"`.
- **`reasonCode`** is a stable code you can show to a customer or store for audit.
- **`effectiveFrom`** says this version applies from 1 November 2026.

## 3. Evaluate it

```java
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

public class LoanCheck {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                     String segment, boolean blacklisted) {}

    public static void main(String[] args) {
        RuleStoneEngine engine = RuleStone.builder()
                .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
                .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.stone.json")))
                .build();

        Applicant applicant = new Applicant(30, new BigDecimal("25750000.50"),
                new BigDecimal("5000000"), "RETAIL", false);

        DecisionResult result = engine.evaluate(
                DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 12, 1)),
                Facts.of("applicant", applicant, "amount", new BigDecimal("300000000")));

        System.out.println(result.output());       // {decision=APPROVE, limit=309000000}
        System.out.println(result.reasonCodes());  // []
        System.out.println(result.matchedRules()); // [R4]
        System.out.println(result.ruleVersion());  // 3@sha256:…
    }
}
```

Step by step:

1. **`RuleStone.builder()…build()`** loads the table and **compiles and type-checks every rule**. If anything is wrong, `build()` throws and nothing is evaluated. Build the engine once, at startup, and share it: it is immutable and thread-safe.
2. **`.clock(...)`** sets the time zone used to determine "today" when a request does not give a date. Set it to your bank's time zone.
3. **`DecisionRef.of("loan_eligibility").asOf(...)`** names the decision and the business date. The engine picks the version effective on that date. Passing the date explicitly makes the decision reproducible: replaying it a year later selects the same version.
4. **`Facts.of(...)`** supplies the inputs. `applicant` is a Java record; a `Map<String, Object>` works too. Money is always `BigDecimal`. Passing a `double` is rejected.
5. **`DecisionResult`** holds the outputs, reason codes, the rules examined and the exact rule version used. Store `ruleVersion()` with the decision: it identifies the rule content byte for byte.

The applicant earns 25,750,000.50 a month. Twelve months is 309,006,006.00, which `R4` rounds down to 309,000,000. The requested 300,000,000 is within that, so `R4` matches.

Try an applicant with too much debt:

```java
Facts.of("applicant", new Applicant(30, new BigDecimal("20000000"),
                new BigDecimal("12000000"), "RETAIL", false),
         "amount", new BigDecimal("100000000"))
```

```
output       {decision=REJECT}
reasonCodes  [DTI_HIGH]
trace        R1 ✗, R2 ✗, R3 ✓
```

The debt-to-income ratio is 0.6, so `R3` rejects with reason `DTI_HIGH`, and `R4` and `R5` are never evaluated.

## 4. When a rule is wrong

Change `R1` to misspell a field and compare a decimal with a floating-point number:

```json
{ "id": "R1", "when": "applicant.agee < 18 || applicant.monthlyIncome < 1.5", ... }
```

`build()` now throws an `InvalidDecisionException` that lists every problem with its location (the list of candidate overloads is abridged here):

```
invalid decision definition (1 problem):
  - loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'
     | applicant.agee < 18 || applicant.monthlyIncome < 1.5
     | .........^
    ERROR: <input>:1:48: found no matching overload for '_<_' applied to '(decimal, double)' (candidates: …)
     | applicant.agee < 18 || applicant.monthlyIncome < 1.5
     | ...............................................^
```

`InvalidDecisionException.problems()` returns the same information as a list, one entry per rule expression or JSON location.

RuleStone checks rules when they are loaded, not when a customer is waiting. In particular it will not let a `decimal` meet a floating-point number, because floating point cannot represent most money amounts exactly.

## Next steps

- [Decision tables](decision-tables.md): every field of the file format, hit policies, versions and effective dates.
- [Expression language](expressions.md): what you can write in `when` and `then`.
- [Java API](java-api.md): embedding the engine, input types, results, errors and reloading rules.
- [Examples](examples.md): more complete decision tables.
