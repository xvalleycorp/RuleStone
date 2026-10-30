# RuleStone architecture

> **Every version set in stone. Every change in seconds.**
>
> Status: draft · Last updated: 2026-10-25

This document describes where RuleStone is going, not only what exists today. Each module and capability is marked **available** or **planned**; the [roadmap](#9-roadmap) shows the order of work. For how to use what is available now, see the [guides](README.md).

**About the name.** The name comes from the stone stele on which the Code of Hammurabi (c. 1750 BC) was carved, one of the first written legal codes and one that regulated loans, interest and deposits. Every rule version in RuleStone is likewise "set in stone": immutable, identified and auditable. Publishing a new version, however, takes seconds and needs no application redeploy.

## 1. Goals and scope

RuleStone is a **business rules management** library for banking and finance, written in Java. It aims to be:

- **Deterministic and auditable.** The same inputs and the same rule version always give the same output, and every decision leaves a complete trace.
- **Easy to integrate.** It can be embedded as a plain library, run as a service, or plugged into Spring, Kafka or AI agents, without forcing a change of stack.
- **Ready for hybrid AI.** AI can help write rules, explain decisions and act as a guarded node in a decision, but the final decision always goes through the deterministic engine.

Out of scope for now: stateful inference (Rete, forward chaining), complex event processing, and general BPM or workflow. Case management, labelling of reviewed cases and model training also belong to applications built on RuleStone, such as [OpenFRAML](https://github.com/xvalleycorp/OpenFRAML); RuleStone gives them what they build on: decision records to attach labels to, shadow and canary versions, model nodes, and the planned replay and re-scoring.

## 2. Design principles

1. **A framework-free core.** `rulestone-core` uses only the JDK and a small number of libraries (CEL, Jackson). Spring, Kafka, AI and the like live in optional modules.
2. **Extension through SPIs, not inheritance.** Every integration point is an interface, loaded through `ServiceLoader` or a builder, so each part can be replaced on its own.
3. **No arbitrary code.** There is no JavaScript or Groovy scripting in rules. Expressions are CEL: not Turing-complete, and bounded. Extension functions must be registered explicitly and be pure.
4. **JSON is the canonical format, with an equivalent text syntax.** Rules are stored and exchanged as JSON (`.stone.json`). A text syntax (`.stone`) is planned for human review and for generation by LLMs, convertible both ways without loss.
5. **Money is `BigDecimal`.** There is no `double` anywhere money is involved.
6. **Specification first.** Behaviour is specified with language-independent conformance tests, so other implementations can follow.

## 3. Modules

| Module | Responsibility | Main dependencies | Status |
|---|---|---|---|
| `rulestone-bom` | Version alignment for users | — | available |
| `rulestone-api` | Public API: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult`; later the SPIs | JDK | available |
| `rulestone-core` | Decision model, evaluator, trace. Decision tables, decision records, timeouts, and canary and shadow versions are available; decision flows, switches and sub-decisions are planned | `rulestone-api`, `rulestone-cel`, Jackson | available (partial) |
| `rulestone-cel` | cel-java integration and extensions. `decimal`, `money`, `date` and fuzzy name matching are available; business calendars are planned | cel-java | available (partial) |
| `rulestone-dsl` | `.stone` text syntax ⇄ JSON, formatter | ANTLR runtime | planned |
| `rulestone-analysis` | Static analysis: overlapping rows, gaps and unreachable rows, using an SMT solver where available | Z3 (optional) | planned |
| `rulestone-governance` | Maker-checker workflow, hash-chained audit log | `rulestone-api` | planned |
| `rulestone-simulation` | Backtesting and champion/challenger on historical data. Champion/challenger on live traffic (shadow and canary versions) is in `rulestone-core` | `rulestone-core` | planned |
| `rulestone-repository-jdbc` / `-git` / `-s3` | Rule repositories | respective clients | planned |
| `rulestone-audit-kafka` | Audit sink that publishes decision records to Kafka, fast enough for inline paths | `kafka-clients` (provided) | available |
| `rulestone-audit-jdbc` | Audit sink | JDBC | planned |
| `rulestone-observability` | Metrics (Micrometer) and tracing (OpenTelemetry) | Micrometer, OTel API | planned |
| `rulestone-ai` | SPI for AI nodes, authoring assistant, decision explanation | `rulestone-api` only (no LLM vendor dependency) | planned |
| `rulestone-spring-boot-starter` | Auto-configuration, properties, actuator endpoint | Spring Boot | planned |
| `rulestone-server` | REST and gRPC service with OpenAPI, standalone or as a sidecar | — | planned |
| `rulestone-mcp` | MCP server so that AI agents can use the engine as a guardrail | MCP Java SDK | planned |
| `rulestone-test` | JSON test cases for rules (`.stone-test.json`), JUnit 5 dynamic tests, rule coverage, fluent assertions | JUnit | available |
| `rulestone-temporal` | Decisions as Temporal activities, or in workflow code with rule versions pinned across replays | Temporal SDK (provided) | available |
| `rulestone-camunda8` / `-camunda7` | A Camunda 8 job worker with an element template; a Camunda 7 delegate and expression bean | Camunda clients (provided) | available |
| `rulestone-editor` | Local web editor for decision tables, with try-it on sample facts | JDK HTTP server | available |
| Browser SDK (`sdk/`) | A TypeScript engine with the same results as the Java engine, checked by a conformance suite, and Web Components for editing tables and designing flows | none at run time | available |
| `rulestone-benchmark` | JMH benchmarks of evaluation: latency and allocation. Not published | JMH | available |

Dependency rule: `core` **never** depends on `ai`, `spring` or `server`. This will be enforced with ArchUnit in CI.

## 4. Integration

### 4.1 Deployment modes

| Mode | Use when | Latency | Status |
|---|---|---|---|
| **Embedded** (plain library) | An existing Java application needs low latency (payments, limits) | µs–ms | available |
| **Spring Boot starter** | Spring microservices | µs–ms | planned |
| **REST/gRPC service** | Non-Java stacks (.NET, Node, legacy core banking), or one shared decision service | ms | planned |
| **Kafka processor** | Event-stream scoring and screening (AML, fraud) | ms | planned |
| **MCP server** | AI agents that must check policy before calling a tool | ms | planned |
| **Workflow engines** | Temporal workflows and Camunda 7 or 8 processes | µs–ms | available |
| **Browser or Node.js** | Editors, simulators and checks before a request is sent, with the TypeScript engine | µs–ms | available |

### 4.2 Service provider interfaces (planned)

| SPI | Responsibility | Bundled implementations |
|---|---|---|
| `RuleRepository` | Load rules by name, version and effective date | File, JDBC, Git, S3 |
| `FactResolver` | Fetch extra data lazily (e.g. outstanding balance from core banking), with caching and timeouts | — (user-provided) |
| `TypeAdapter` | Convert inputs to CEL values: `Map`, POJO/record, Jackson `JsonNode`, Protobuf | Map, record and JSON (`FactsJson`) are available; Protobuf planned |
| `FunctionLibrary` | Custom business functions, pure and typed | Decimal, Money, dates and fuzzy matching (available), BusinessCalendar |
| `AuditSink` | Write decision records | Kafka (available), JDBC; the interface itself is available |
| `DataMasker` | Mask sensitive data (card numbers, national ID numbers, account numbers) in traces and logs | By annotation and by path |
| `ModelProvider` | Provide an ML model or LLM for an AI node | — (separate adapters) |
| `ApprovalWorkflow` | Maker-checker, connectable to an existing approval system | In-memory, JDBC |

### 4.3 Dynamic loading

- Rules are compiled (parsed, type-checked, turned into CEL programs) **once** at load time. *Available.*
- Hot reload swaps the whole compiled rule set atomically. A half-loaded state never exists. *Available* through building a new engine and swapping it; see [Reloading rules](java-api.md#reloading-rules).
- Canary releases: route a percentage of traffic to a new version, chosen by correlation id, or run it in shadow mode for comparison without affecting real results. *Available*; see [Canary and shadow versions](java-api.md#canary-and-shadow-versions).

## 5. API sketch

```java
// Target API. Available today: clock(), decision(), decisions(), maxIterations(), auditSink(),
// KafkaAuditSink, and the timeout as RuleStone.builder().timeout(...) or DecisionRef.timeout(...).
RuleStoneEngine engine = RuleStone.builder()
    .repository(JdbcRuleRepository.of(dataSource))
    .auditSink(KafkaAuditSink.of(producer, "rulestone.audit"))
    .functions(BusinessCalendarFunctions.vietnam())
    .limits(Limits.builder()
        .maxIterations(10_000)
        .timeout(Duration.ofMillis(50))
        .build())
    .masker(DataMasker.byPaths("applicant.idNumber", "card.pan"))
    .build();

DecisionResult result = engine.evaluate(
    DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 11, 5)),
    Facts.of("applicant", applicant,
             "amount", new BigDecimal("300000000")));

result.output();       // {decision=APPROVE, limit=309000000}
result.reasonCodes();  // e.g. [DTI_HIGH], for adverse-action notices
result.ruleVersion();  // 3@sha256:… (version + content hash)
result.trace();        // which rules were evaluated and which matched; no input values
```

## 6. Banking requirements

### 6.1 Requirement → mechanism

| Requirement | Mechanism | Status |
|---|---|---|
| Deterministic, reproducible | Immutable versions with a content hash. No I/O in expressions. `asOf` fixes the business date; rules never read the system clock. | available |
| Exact money | `BigDecimal` throughout; floating point rejected in rules and inputs. | available |
| Effective dating | Each version has `effectiveFrom` and an optional `effectiveTo`; a new version supersedes the previous one from its `effectiveFrom`, so published versions are never edited; contradictory dates are rejected; a decision for day X can be re-run with the rules in force on day X. | available |
| Segregation of duties (maker-checker) | Rule lifecycle `DRAFT → SUBMITTED → APPROVED → ACTIVE → RETIRED`; author and approver must differ, enforced in the governance layer. | planned |
| Tamper-evident audit | Hash-chained, optionally signed audit records holding masked inputs, rule version, trace and result. | planned |
| Explainability | Explicit reason codes plus a structured trace; AI only rephrases the trace. A decision record for every decision, including model versions, outputs and attributions, is specified in [Hybrid AI decisioning](hybrid-ai.md#4-the-decision-record). | available (codes, trace, decision record for tables) |
| Personal data protection | No input values in traces or exception messages (available). `DataMasker` on by default for audit records (planned). | partial |
| Execution safety | CEL without reflection or eval; bounded list operations; a timeout per evaluation, per engine or per request. | available |
| High availability | Embedded mode has no network dependency on the hot path; if the rule repository fails, the loaded version keeps running. | available (embedded) |
| Change testing | Rule unit tests with rule coverage (`rulestone-test`, available); shadow and canary versions on live traffic (available); static analysis and mandatory backtesting before a version becomes `ACTIVE` (planned). | partial |

### 6.2 Software supply chain

| Practice | Status |
|---|---|
| Apache-2.0 license; `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` in every jar; license header checked on every source file | available |
| Third-party license allowlist (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause) enforced by the build | available |
| CycloneDX SBOM per module | available |
| Reproducible builds (fixed archive timestamps) | available |
| Sources and Javadoc jars | available |
| Contributions under the Developer Certificate of Origin | available |
| Vulnerability scanning (OWASP Dependency-Check) and SAST in CI | planned |
| Signed artifacts published to Maven Central | planned |
| Support policy: LTS versions, security fixes with a response target, SemVer | planned |

Dependencies are kept to a minimum: every library is code a bank must audit.

### 6.3 Regulatory frameworks to check against

This is a checklist to review while the specification matures. Current texts must be verified before making any commitment to a customer.

- **Vietnam:** State Bank of Vietnam regulations on information system security in banking and on the security of online banking services; personal data protection law.
- **International:** PCI DSS v4 (if card data is processed); BCBS 239 (risk data lineage); SR 11-7 and model risk management (for AI nodes); the EU AI Act (credit scoring is high-risk AI, requiring explainability, human oversight and record-keeping).

## 7. AI integration (planned)

The mechanisms for combining rules with machine-learning models are specified in [Hybrid AI decisioning](hybrid-ai.md). That page covers model scores as rule inputs, models as nodes in decision flows, and the decision record.

| Pattern | Description | Guardrail |
|---|---|---|
| **Authoring assistant** | An LLM drafts `.stone` rules from policy documents. | Drafts must pass the parser, type checker, static analysis, unit tests and maker-checker. AI only ever creates `DRAFT`s. |
| **Decision explanation** | Turns a trace into plain language for customers or auditors. | Input is the masked trace only; reason codes remain the source of truth. |
| **AI node** | An ML model or LLM as one node in a decision graph. | Declared output type, confidence threshold, timeout and fallback branch. The model is versioned and recorded in the audit trail. Surrounding rules bound its influence. |
| **Agent guardrail** | An agent calls `rulestone-mcp` before taking an action. | The engine's answer is deterministic; the agent cannot override it. |
| **Test generation and improvement suggestions** | An LLM generates edge cases or analyses decision data to propose changes. | Proposals only become `DRAFT`s. |

`rulestone-ai` will define SPIs only. Adapters for each provider (on-premise or cloud LLMs) will be separate modules, so a bank can run entirely on-premise.

All problems in a rule definition are already reported together, each with its location (*available*). This is the feedback an authoring assistant needs to correct a draft in one pass.

## 8. Decisions

### 8.1 Made

| Question | Decision |
|---|---|
| Product name | RuleStone; rule files `.stone.json` (and `.stone` for the planned text syntax) |
| License | Apache-2.0 for the open-source project |
| Copyright holder | "The RuleStone Authors", as listed in `AUTHORS` |
| Contributions | Developer Certificate of Origin (sign-off), no CLA |
| Documentation language | English |
| Java baseline | Java 17 (compatible with Spring Boot 3) |
| Build tool | Maven |
| groupId / package | `vn.io.dxa.rulestone`, from the domain `dxa.io.vn` |
| Money arithmetic | `decimal` backed by `BigDecimal`; division at 34 significant digits, HALF_EVEN; rounding is explicit; equality between `decimal` and `int` is a type error |
| `money` type | Minor units from a fixed ISO 4217 table shared by both engines, overridable per currency (`currencyScale`); `HALF_EVEN` rounding by default |

### 8.2 Open

| Question | Current proposal |
|---|---|
| Publishing to Maven Central | Register the `vn.io.dxa` namespace on the Central Portal and verify it with a DNS TXT record on `dxa.io.vn`. Requires project URL, SCM and developer entries in the POM. |
| Commercial offering | Open core: the engine and basic adapters stay Apache-2.0; advanced governance, a rule editor and enterprise support could be offered commercially as separate products. |
| GoRules JDM compatibility | An importer from JDM to RuleStone to ease migration. |

## 9. Roadmap

1. **M0 — Specification** *(done for decision tables)*: decision model, CEL extensions (`decimal`, `money`, `date`), decision record format, and a conformance suite that checks the TypeScript engine against the Java engine. Flows follow with M1.
2. **M1 — Core** *(in progress)*
   - Done: decision tables with `FIRST`, `UNIQUE` and `COLLECT`; strict `.stone.json` format; content hashes; effective dating in which a new version supersedes the previous one; `decimal`, `money` and `date` types; fuzzy name matching; typed struct and list inputs; traces; decision records with `AuditSink`; timeouts; canary and shadow versions; `rulestone-test`; all definition problems reported together; licensing, SBOM and documentation; decision flows with `table`, `switch`, `derive`, `split` and `end` nodes (step 2 of the [hybrid AI plan](hybrid-ai.md#9-implementation-plan)).
   - Remaining: business calendars.
3. **M2 — Governance:** maker-checker, hash-chained audit, JDBC repository and JDBC audit sink.
4. **M3 — Integration** *(started)*: Temporal, Camunda 7 and 8, and the Kafka audit sink are done. Remaining: Spring Boot starter, REST/gRPC server, observability (Micrometer, OpenTelemetry), Kafka processor.
5. **M4 — Analysis and AI** *(started)*: model nodes (step 3 of the [hybrid AI plan](hybrid-ai.md#9-implementation-plan)) are done. Remaining: static analysis, `.stone` text syntax, explanations, replay and re-scoring, and model adapters (steps 4 to 7), authoring assistant, MCP server.
6. **M5 — Simulation** *(started)*: shadow and canary versions on live traffic, and the web editor for tables, are done. Remaining: backtesting and champion/challenger on historical data, flow editing beyond design.
