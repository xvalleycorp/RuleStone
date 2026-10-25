# RuleStone

[![Build](https://github.com/xvalleycorp/RuleStone/actions/workflows/build.yml/badge.svg)](https://github.com/xvalleycorp/RuleStone/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Java 17+](https://img.shields.io/badge/java-17%2B-orange.svg)

**Every version set in stone. Every change in seconds.**

Website: **[rulestone.dxa.io.vn](https://rulestone.dxa.io.vn)**

RuleStone is an open-source business rules engine for banking and finance, written in Java. Business rules such as loan eligibility, pricing, fees and limits live in versioned decision tables instead of application code. They can change without a redeploy, and every decision can be explained and reproduced.

> **Status: pre-release (0.x).** The core engine works and is tested, but the API may still change and nothing is published to Maven Central yet. See the [roadmap](docs/architecture.md#9-roadmap).

## Why RuleStone

- **Exact money.** A `decimal` type backed by `BigDecimal`. Floating-point numbers are rejected, both in rules and in inputs.
- **Mistakes caught at load time.** Every rule is type-checked when it is loaded. A misspelt field, a type error or a misspelt key in the file stops the load with a precise message, not a customer transaction.
- **Deterministic and reproducible.** The same rule version and inputs always give the same result. Rule versions carry a SHA-256 content hash, and effective dates select the version in force on the business date.
- **Explainable.** Each result carries reason codes, the rules examined and the exact rule version, without any input values, so it is safe to store in audit logs.
- **Safe expressions.** Rules are written in [CEL](https://cel.dev): no scripting, no I/O, guaranteed termination.
- **Embeddable.** A plain Java library with an immutable, thread-safe engine. No framework or server required.

## Quick start

Requires Java 17 or newer. Until the first release, build from source with `mvn install`, then add:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A decision table (`examples/loan_eligibility.v3.stone.json`, abridged):

```json
{
  "id": "loan_eligibility",
  "version": "3",
  "effectiveFrom": "2026-11-01",
  "hitPolicy": "FIRST",
  "inputs": {
    "applicant": { "age": "int", "monthlyIncome": "decimal", "monthlyDebt": "decimal", "blacklisted": "bool" },
    "amount": "decimal"
  },
  "outputs": { "decision": "string", "limit": "decimal" },
  "rules": [
    { "id": "R1", "when": "applicant.age < 18",
      "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" },
    { "id": "R3", "when": "applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')",
      "then": { "decision": "'REJECT'" }, "reasonCode": "DTI_HIGH" },
    { "id": "R4", "when": "amount <= applicant.monthlyIncome * 12",
      "then": { "decision": "'APPROVE'",
                "limit": "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000" } },
    { "id": "R5", "when": "true",
      "then": { "decision": "'REVIEW'" }, "reasonCode": "AMOUNT_ABOVE_LIMIT" }
  ]
}
```

Evaluating it:

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decision(DecisionTableJson.read(Path.of("examples/loan_eligibility.v3.stone.json")))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 12, 1)),
        Facts.of("applicant", applicant, "amount", new BigDecimal("300000000")));

result.output();        // {decision=APPROVE, limit=309000000}
result.reasonCodes();   // []
result.matchedRules();  // [R4]
result.ruleVersion();   // 3@sha256:…
```

Continue with **[Getting started](docs/getting-started.md)**.

## Documentation

- [Getting started](docs/getting-started.md)
- [Decision tables](docs/decision-tables.md): file format, hit policies, versions and effective dates
- [Expression language](docs/expressions.md): operators, decimals, rounding, lists
- [Java API](docs/java-api.md): embedding, inputs, results, errors, reloading
- [Examples](docs/examples.md)
- [Architecture and roadmap](docs/architecture.md)

## Modules

| Module | Purpose |
|---|---|
| `rulestone-api` | Public API: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult`, exceptions. JDK only. |
| `rulestone-core` | Decision tables, the `.stone.json` format, effective dating, the embedded engine. |
| `rulestone-cel` | The expression language: CEL with typed inputs and `decimal`. |
| `rulestone-audit-kafka` | Audit sink that publishes decision records to Kafka. |
| `rulestone-bom` | Bill of materials for aligning module versions. |

## Building

```sh
mvn verify
```

This compiles all modules, runs the tests, and checks license headers, third-party licenses and Javadoc. Each module's jar contains `META-INF/LICENSE`, `META-INF/NOTICE` and `META-INF/THIRD-PARTY.txt`. A CycloneDX software bill of materials is written to `target/bom.json`.

## License

RuleStone is licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and distribute it, including in commercial and closed-source products. When you redistribute it, include a copy of the license, keep the copyright notices and the [NOTICE](NOTICE) file, and mark any files you have modified. Each contributor also grants a patent license covering their contributions. This summary is for convenience only; the [LICENSE](LICENSE) text governs.

All runtime dependencies are under Apache-2.0 or BSD-3-Clause licenses. The build fails if a dependency with any other license is introduced. The full list is in each jar's `META-INF/THIRD-PARTY.txt`.

Copyright 2026 The RuleStone Authors. See [AUTHORS](AUTHORS).

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md); commits must be signed off under the [Developer Certificate of Origin](https://developercertificate.org/). Report security issues privately as described in [SECURITY.md](SECURITY.md). Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Disclaimer

RuleStone is a software library. It does not provide legal, regulatory or credit advice. The organization that deploys it remains responsible for the content of its rules and for compliance with the regulations that apply to it. As stated in the license, the software is provided "as is", without warranty of any kind.
