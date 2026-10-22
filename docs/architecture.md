# RuleStone architecture

> **Every version set in stone. Every change in seconds.**
>
> Status: draft · Last updated: 2026-10-22

This document describes where RuleStone is going, not only what exists today. Each module and capability is marked **available** or **planned**; the [roadmap](#9-roadmap) shows the order of work. For how to use what is available now, see the [guides](README.md).

**About the name.** The name comes from the stone stele on which the Code of Hammurabi (c. 1750 BC) was carved, one of the first written legal codes and one that regulated loans, interest and deposits. Every rule version in RuleStone is likewise "set in stone": immutable, identified and auditable. Publishing a new version, however, takes seconds and needs no application redeploy.

## 1. Goals and scope

RuleStone is a **business rules management** library for banking and finance, written in Java. It aims to be:

- **Deterministic and auditable.** The same inputs and the same rule version always give the same output, and every decision leaves a complete trace.
- **Easy to integrate.** It can be embedded as a plain library, run as a service, or plugged into Spring, Kafka or AI agents, without forcing a change of stack.
- **Ready for hybrid AI.** AI can help write rules, explain decisions and act as a guarded node in a decision, but the final decision always goes through the deterministic engine.

Out of scope for now: stateful inference (Rete, forward chaining), complex event processing, and general BPM or workflow.

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
| `rulestone-core` | Decision model, evaluator, trace. Decision tables are available; decision graphs, switches and sub-decisions are planned | `rulestone-api`, `rulestone-cel`, Jackson | available (partial) |
| `rulestone-cel` | cel-java integration and extensions. `decimal` is available; `money`, dates and business calendars are planned | cel-java | available (partial) |
| `rulestone-dsl` | `.stone` text syntax ⇄ JSON, formatter | ANTLR runtime | planned |
| `rulestone-analysis` | Static analysis: overlapping rows, gaps and unreachable rows, using an SMT solver where available | Z3 (optional) | planned |
| `rulestone-governance` | Maker-checker workflow, hash-chained audit log | `rulestone-api` | planned |
| `rulestone-simulation` | Backtesting and champion/challenger on historical data | `rulestone-core` | planned |
| `rulestone-repository-jdbc` / `-git` / `-s3` | Rule repositories | respective clients | planned |
| `rulestone-audit-jdbc` / `-kafka` | Audit sinks | respective clients | planned |
| `rulestone-observability` | Metrics (Micrometer) and tracing (OpenTelemetry) | Micrometer, OTel API | planned |
| `rulestone-ai` | SPI for AI nodes, authoring assistant, decision explanation | `rulestone-api` only (no LLM vendor dependency) | planned |
| `rulestone-spring-boot-starter` | Auto-configuration, properties, actuator endpoint | Spring Boot | planned |
| `rulestone-server` | REST and gRPC service with OpenAPI, standalone or as a sidecar | — | planned |
| `rulestone-mcp` | MCP server so that AI agents can use the engine as a guardrail | MCP Java SDK | planned |
| `rulestone-test` | JUnit 5 helpers for unit-testing rules | JUnit | planned |

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

### 4.2 Service provider interfaces (planned)

| SPI | Responsibility | Bundled implementations |
|---|---|---|
| `RuleRepository` | Load rules by name, version and effective date | File, JDBC, Git, S3 |
| `FactResolver` | Fetch extra data lazily (e.g. outstanding balance from core banking), with caching and timeouts | — (user-provided) |
| `TypeAdapter` | Convert inputs to CEL values: `Map`, POJO/record, Jackson `JsonNode`, Protobuf | Map and record are available; Jackson and Protobuf planned |
| `FunctionLibrary` | Custom business functions, pure and typed | Decimal (available), Money, BusinessCalendar |
| `AuditSink` | Write decision records | Log, JDBC, Kafka |
| `DataMasker` | Mask sensitive data (card numbers, national ID numbers, account numbers) in traces and logs | By annotation and by path |
| `ModelProvider` | Provide an ML model or LLM for an AI node | — (separate adapters) |
| `ApprovalWorkflow` | Maker-checker, connectable to an existing approval system | In-memory, JDBC |

### 4.3 Dynamic loading

- Rules are compiled (parsed, type-checked, turned into CEL programs) **once** at load time. *Available.*
- Hot reload swaps the whole compiled rule set atomically. A half-loaded state never exists. *Available* through building a new engine and swapping it; see [Reloading rules](java-api.md#reloading-rules).
- Canary releases: route a percentage of traffic to a new version, or run it in shadow mode for comparison without affecting real results. *Planned.*

## 5. API sketch

```java
// Target API. Available today: clock(), decision(), decisions(), maxIterations().
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
| Effective dating | Each version has `effectiveFrom`/`effectiveTo`; overlaps are rejected; a decision for day X can be re-run with the rules in force on day X. | available |
| Segregation of duties (maker-checker) | Rule lifecycle `DRAFT → SUBMITTED → APPROVED → ACTIVE → RETIRED`; author and approver must differ, enforced in the governance layer. | planned |
| Tamper-evident audit | Hash-chained, optionally signed audit records holding masked inputs, rule version, trace and result. | planned |
| Explainability | Explicit reason codes plus a structured trace; AI only rephrases the trace. | available (codes, trace) |
| Personal data protection | No input values in traces or exception messages (available). `DataMasker` on by default for audit records (planned). | partial |
| Execution safety | CEL without reflection or eval; bounded list operations (available). Per-decision timeout (planned). | partial |
| High availability | Embedded mode has no network dependency on the hot path; if the rule repository fails, the loaded version keeps running. | available (embedded) |
| Change testing | Rule unit tests (`rulestone-test`), static analysis, and mandatory backtesting before a version becomes `ACTIVE`. | planned |

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

### 8.2 Open

| Question | Current proposal |
|---|---|
| Publishing to Maven Central | Register the `vn.io.dxa` namespace on the Central Portal and verify it with a DNS TXT record on `dxa.io.vn`. Requires project URL, SCM and developer entries in the POM. |
| Commercial offering | Open core: the engine and basic adapters stay Apache-2.0; advanced governance, a rule editor and enterprise support could be offered commercially as separate products. |
| `money` type | Requires choosing the per-currency scale table (ISO 4217 or bank-specific) and default rounding policy. |
| GoRules JDM compatibility | An importer from JDM to RuleStone to ease migration. |

## 9. Roadmap

1. **M0 — Specification:** decision model, CEL extensions (`decimal`, `money`, `date`), trace format, conformance tests.
2. **M1 — Core** *(in progress)*
   - Done: decision tables with `FIRST`, `UNIQUE` and `COLLECT`; strict `.stone.json` format; content hashes; effective dating with overlap detection; `decimal` in CEL; typed struct and list inputs; traces; all definition problems reported together; licensing, SBOM and documentation.
   - Remaining: `money` type, `date` type, `rulestone-test` module.
3. **M2 — Governance:** maker-checker, hash-chained audit, JDBC repository.
4. **M3 — Integration:** Spring Boot starter, REST/gRPC server, observability, Kafka.
5. **M4 — Analysis and AI:** static analysis, `.stone` text syntax, authoring assistant, MCP server.
6. **M5 — Simulation:** backtesting, champion/challenger, web editor.
