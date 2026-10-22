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
