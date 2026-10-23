# Changelog

All notable changes to RuleStone are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, minor versions may contain breaking changes; they are called out under **Changed**.

## [Unreleased]

### Added

- Decision tables with `FIRST`, `UNIQUE` and `COLLECT` hit policies.
- The `.stone.json` file format, read strictly: unknown and duplicate keys are errors.
- Rule versions identified by version and SHA-256 content hash, e.g. `3@sha256:…`.
- Effective dating with `effectiveFrom` / `effectiveTo`; overlapping versions are rejected.
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
