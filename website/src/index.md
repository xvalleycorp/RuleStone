---
layout: home
title: RuleStone
titleTemplate: Business rules engine for banking and finance

hero:
  name: RuleStone
  text: Business rules for banking, set in stone.
  tagline: An open-source Java engine for versioned, effective-dated decision tables with exact decimal arithmetic. Every version set in stone. Every change in seconds.
  image:
    light: /logo.svg
    dark: /logo-dark.svg
    alt: RuleStone logo, an arch of cut stones locked by a gold keystone
  actions:
    - theme: brand
      text: Get started
      link: /docs/getting-started
    - theme: alt
      text: Read the docs
      link: /docs/
    - theme: alt
      text: View on GitHub
      link: https://github.com/xvalleycorp/RuleStone

features:
  - icon: 🪙
    title: Exact money
    details: A decimal type backed by BigDecimal. Floating-point numbers are rejected in rules and in inputs, so 0.1 + 0.2 is exactly 0.3.
  - icon: 🛑
    title: Mistakes caught at load time
    details: Every rule is type-checked when it is loaded. A misspelt field or a decimal compared with 1.5 stops the load with a precise message, not a customer transaction.
  - icon: 📅
    title: Versions and effective dates
    details: Add a new version of a policy without touching the old one. Each version applies from its start date until the next one takes effect, and its hash never changes.
  - icon: 🔏
    title: Reproducible and auditable
    details: Each result carries the exact rule version with a SHA-256 content hash, reason codes and a trace of the rules examined, with no input values.
  - icon: 🧱
    title: Safe expressions
    details: Rules are written in CEL, the expression language behind Kubernetes and Envoy policies. No scripting, no I/O, guaranteed termination.
  - icon: ☕
    title: Embeddable Java library
    details: An immutable, thread-safe engine for Java 17+. No server or framework required, and only permissively licensed dependencies.
---

<div class="home-section">

## A decision table

<div class="home-columns">
<div>

### Write the policy

Rules live in versioned `.stone.json` files, outside your application code. This is an abridged version of the [loan eligibility example](https://github.com/xvalleycorp/RuleStone/blob/main/examples/loan_eligibility.v3.stone.json):

```json
{
  "id": "loan_eligibility",
  "version": "3",
  "effectiveFrom": "2026-11-01",
  "hitPolicy": "FIRST",
  "inputs": {
    "applicant": {
      "age": "int",
      "monthlyIncome": "decimal",
      "monthlyDebt": "decimal"
    },
    "amount": "decimal"
  },
  "outputs": { "decision": "string", "limit": "decimal" },
  "rules": [
    { "id": "R1", "when": "applicant.age < 18",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "UNDERAGE" },
    { "id": "R3",
      "when": "applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "DTI_HIGH" },
    { "id": "R4", "when": "amount <= applicant.monthlyIncome * 12",
      "then": { "decision": "'APPROVE'",
        "limit": "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000" } },
    { "id": "R5", "when": "true",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "AMOUNT_ABOVE_LIMIT" }
  ]
}
```

</div>
<div>

### Evaluate it from Java

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decision(DecisionTableJson.read(
                Path.of("rules/loan_eligibility.stone.json")))
        .build();   // type-checks every rule, or fails listing all problems

DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility")
                .asOf(LocalDate.of(2026, 12, 1)),
        Facts.of("applicant", applicant,
                 "amount", new BigDecimal("300000000")));

result.output();        // {decision=APPROVE, limit=309000000}
result.reasonCodes();   // []
result.matchedRules();  // [R4]
result.ruleVersion();   // 3@sha256:…
```

The engine picks the version in force on the business date, so replaying the decision a year later gives the same answer. Store `ruleVersion()` with each decision: it identifies the rule text byte for byte.

[Walk through this example →](/docs/getting-started)

</div>
</div>

## Why "RuleStone"?

The Code of Hammurabi, carved on a basalt stele around 1750 BC, is one of the first written legal codes, and among its laws are rules for loans, interest and deposits. RuleStone treats every version of your policy the same way: set in stone, identified and auditable. Publishing the next version takes seconds and needs no redeploy.

## Open source

RuleStone is licensed under the [Apache License 2.0](https://github.com/xvalleycorp/RuleStone/blob/main/LICENSE): use it freely, including in commercial and closed-source products. Every runtime dependency is under Apache-2.0 or BSD-3-Clause, and the build fails if that ever changes. Each jar ships its license, notice, third-party license list and a CycloneDX bill of materials.

Contributions are welcome under the [Developer Certificate of Origin](https://github.com/xvalleycorp/RuleStone/blob/main/CONTRIBUTING.md).

<div class="status-note">

**Pre-release.** RuleStone 0.1 is under active development: the core engine works and is tested, but the API may still change and artifacts are not yet on Maven Central. See the [roadmap](/docs/architecture#_9-roadmap).

</div>

</div>
