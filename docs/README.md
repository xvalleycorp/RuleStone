# RuleStone documentation

## Guides

| Page | Read it to |
|---|---|
| [Getting started](getting-started.md) | Write and evaluate your first decision table in ten minutes. |
| [Decision tables](decision-tables.md) | Learn the `.stone.json` format, hit policies, versions, effective dates and validation. |
| [Expression language](expressions.md) | Write conditions and outputs: operators, decimals, rounding, lists, missing fields, errors. |
| [Fuzzy matching](fuzzy-matching.md) | Compare names for AML/KYC screening: normalization, similarity scores, thresholds and limitations. |
| [Java API](java-api.md) | Embed the engine: inputs, results, errors, reloading rules, threading. |
| [Testing](testing.md) | Test decision tables: JSON test cases, JUnit 5 integration, rule coverage and fluent assertions. |
| [Web editor](editor.md) | Create and edit decision tables in the browser, try them on sample facts and save `.stone.json` files. |
| [Editor SDK](editor-sdk.md) | Embed the visual editors in your own web application, with React, Angular, Vue or plain HTML, and run rules in the browser. |
| [Temporal integration](temporal.md) | Evaluate decisions from Temporal workflows, as activities or inside workflow code with pinned rule versions. |
| [Camunda integration](camunda.md) | Call decisions from BPMN processes with a Camunda 8 job worker or a Camunda 7 delegate. |
| [Examples](examples.md) | Study complete tables for loans, deposit rates, transfer fees, card fraud, underwriting and AML. |

## Design

| Page | Content |
|---|---|
| [Architecture](architecture.md) | Goals, design principles, modules, integration modes, banking requirements, AI integration and roadmap. |
| [Hybrid AI decisioning](hybrid-ai.md) | Design proposal: model scores as rule inputs, models as nodes in decision flows, and the decision record that explains every decision. |

## Project

| Page | Content |
|---|---|
| [Contributing](../CONTRIBUTING.md) | Development setup, checks, the DCO sign-off and the pull request checklist. |
| [Security policy](../SECURITY.md) | How to report a vulnerability. |
| [Changelog](../CHANGELOG.md) | Changes per release. |
| [License](../LICENSE) | Apache License 2.0. |

## Conventions in these pages

- `monospace` marks code, file names and values exactly as written.
- Amounts in examples are in Vietnamese đồng and use no decimal places unless the example is about rounding.
- Every example in [`examples/`](../examples/) and every Java snippet in *Getting started* and *Java API* is executed by the test suite. If you find a difference between these pages and the behaviour of RuleStone, please [report it](../CONTRIBUTING.md#ways-to-contribute).
