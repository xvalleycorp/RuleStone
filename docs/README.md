# RuleStone documentation

## Guides

| Page | Read it to |
|---|---|
| [Getting started](getting-started.md) | Write and evaluate your first decision table in ten minutes. |
| [Decision tables](decision-tables.md) | Learn the `.stone.json` format, hit policies, versions, effective dates and validation. |
| [Expression language](expressions.md) | Write conditions and outputs: operators, decimals, rounding, lists, missing fields, errors. |
| [Java API](java-api.md) | Embed the engine: inputs, results, errors, reloading rules, threading. |
| [Examples](examples.md) | Study complete tables for loans, deposit rates and transfer fees. |

## Design

| Page | Content |
|---|---|
| [Architecture](architecture.md) | Goals, design principles, modules, integration modes, banking requirements, AI integration and roadmap. |

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
