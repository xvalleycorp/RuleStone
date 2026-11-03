# Governance

> Status: **design proposal**, partly implemented · Last updated: 2026-11-03
>
> Steps 1 and 2 of the [implementation plan](#11-implementation-plan) are **available** in both engines: approval statements and `requireApproved()` (see [Approvals](approvals.md)), and signed releases with `requireRelease()`, the `RuleRepository` interface with its files and Git implementation, and the reload helper (see [Releases](releases.md)). Everything else on this page is the design for the rest of milestone M2 of the [roadmap](architecture.md#9-roadmap): the governance service with its hash-chained audit log, the JDBC repository and the JDBC audit sink. It is for review and will change before it ships.

A bank may change a credit limit in seconds only if it can show, years later, who wrote the change, who approved it, that the approved text is exactly the text that ran, and that nobody altered the history since. RuleStone already makes every version immutable and identified by a content hash, and records which version made each decision. This page adds the missing half: an approval that binds to the content hash, an engine that refuses unapproved content, and a tamper-evident log of every governance event.

## Contents

1. [Principles](#1-principles)
2. [What gets governed](#2-what-gets-governed)
3. [Maker-checker](#3-maker-checker)
4. [The governance audit log](#4-the-governance-audit-log)
5. [Storage](#5-storage)
6. [Integration with a bank's systems](#6-integration-with-a-banks-systems)
7. [TypeScript engine parity](#7-typescript-engine-parity)
8. [Regulatory mapping](#8-regulatory-mapping)
9. [Out of scope](#9-out-of-scope)
10. [Open questions](#10-open-questions)
11. [Implementation plan](#11-implementation-plan)

## 1. Principles

1. **Approval binds to content, not to a name.** An approval names the exact content hash of every artifact it covers. Content that differs by one character is not approved, whatever its version label.
2. **The engine checks, it does not trust.** An engine built with `requireApproved()` recomputes every hash from the content it loads and verifies a signature over it. A row changed in a database or a file pushed past review is refused at build time, not discovered in an audit.
3. **Governance state lives beside the content, never inside it.** Approving, activating or retiring a version changes no byte of its file, so its content hash, and every decision record that names it, stays valid.
4. **No user management.** Identity, sign-in and roles come from the bank's systems. RuleStone stores principal ids and checks the rules of maker-checker; it never stores a password.
5. **Approvals can come from anywhere, the invariants cannot be skipped.** An approval may be given in RuleStone's own screens, in Camunda or in the bank's change-management system. Four-eyes and hash binding are checked by RuleStone in every case.
6. **Governance is not on the decision path.** Evaluating a decision reads no governance store and verifies no signature. All checks happen when an engine is built, so latency and availability of decisions are unchanged.
7. **Both engines agree.** The Java engine and the TypeScript engine verify approvals and releases identically, checked by the conformance suite.

## 2. What gets governed

### 2.1 Artifacts

Every versioned artifact that can change a decision is governed:

| Kind | File | Identified by | Content hash |
|---|---|---|---|
| `table` | `.stone.json` | `id`, `version` | [available](decision-tables.md#content-hash) |
| `flow` | `.flow.json` | `id`, `version` | [available](flows.md#versions-and-content-hashes) |
| `params` | `.params.json` | `id`, `version` | [available](decision-tables.md#parameter-set-files) |
| `catalog` | `reason-codes.json` | a name given by the repository, `version` | [available](explanations.md#the-reason-code-catalog) |

A catalog has no `id` field, because an engine has one catalog. The repository names it, by default after its file (`reason-codes`), so that several catalogs for several engines can be governed side by side.

Hashes do not reach across artifacts, and neither do approvals:

- A table's hash covers its `params` declaration, not the values, so **a parameter set version needs its own approval**. Changing a limit is a change, even though no rule changed.
- A flow's hash covers the flow file, not the tables its nodes use unless a node pins a version. **Each table version needs its own approval.** Because a new table version changes the behaviour of every flow that uses it without a new flow version, the review of a table version shows the flows that use it.
- A model node pins an exact model version inside the flow's hash, so changing a model is a new flow version and is governed here. Validating the model itself belongs to the bank's model risk process (see [Out of scope](#9-out-of-scope)).

### 2.2 Lifecycle

```
            submit            approve (policy met)          activate
  DRAFT ─────────────▶ SUBMITTED ─────────────▶ APPROVED ─────────────▶ ACTIVE ─────▶ RETIRED
    ▲                     │                                               │
    │       withdraw      │  reject (with a reason)                       │ effectiveFrom
    └─────────────────────┤                                               ▼
                          ▼                                     in force on business dates
                       REJECTED
```

| State | Meaning |
|---|---|
| `DRAFT` | A working copy. It can be edited freely and is not a version yet: no engine with `requireApproved()` loads it. |
| `SUBMITTED` | Frozen for review at one content hash. An edit returns it to `DRAFT` and voids every approval given so far. |
| `REJECTED` | A checker refused it, with a reason. Final for that submission; the author revises it as a new draft. |
| `APPROVED` | The approval policy is met and a signed approval statement exists for its content hash. Not yet loaded by production engines. |
| `ACTIVE` | Part of the current release: production engines load it. It is *in force* on the business dates its `effectiveFrom` and `effectiveTo` give, exactly as today. |
| `RETIRED` | No longer in force on any date from today on. Still kept, still loadable, so that back-dated decisions and replays work. |

**ACTIVE is not "in force".** Activation decides which versions engines load; effective dates still decide, per request, which loaded version applies. A version can be activated weeks before its `effectiveFrom` and take effect at midnight without a reload, as today.

**Retirement is mostly bookkeeping.** A version becomes `RETIRED` automatically when it can no longer be in force: an active successor has taken effect, or its `effectiveTo` has passed. It stays registered, because a request with a past `asOf` date must still select it, and because removing a version from the selection would extend its predecessor (see [Deploy every version together](decision-tables.md#versions-and-effective-dates)). To end a policy that is in force, publish a successor. The one explicit retirement is the **cancellation** of an active version that has never been in force (its `effectiveFrom` is still to come): it changes what will apply, so it needs approval like any other change.

### 2.3 Versions are superseded, approvals are bound

The existing rule stays: a published version is never edited, only superseded by a new version from its `effectiveFrom`. Governance makes it enforceable:

- A **draft is not a version.** Its label is a proposal and its content may change.
- **On approval, `id@version` is bound to one content hash forever.** The repository refuses any other content under that label, also after retirement. This is the check that [replay](replay.md#versions-that-are-not-registered) already makes when a label is registered with other content.
- Before approval, a rejected or withdrawn label may be submitted again with different content; each submission is a separate event with its own hash, and approvals of the earlier content do not carry over.
- **Anything edited after approval is unapproved.** The engine compares the hash it computes with the hash in the approval, so there is no way to edit an approved version in place.

### 2.4 Change sets

A real change often spans several artifacts: a new table version that reads a new parameter, and the catalog message for its new reason code. Artifacts are submitted, approved and activated together as a **change set**:

- one submission and one review of all items, shown together;
- one approval statement covering every item's hash;
- one activation, so engines never load half of a change.

A change set of one artifact is the common case.

## 3. Maker-checker

### 3.1 Four-eyes

**No approver may be a maker.** The makers of a change set are every principal who saved a revision of any of its items since it was last approved, and whoever submitted it, not only the submitter: otherwise A could write, B submit, and A approve. The governance service records every draft save with its principal and content hash, which makes the set of makers a fact of the log rather than a claim.

Content drafted by an AI authoring assistant has two makers: the assistant (`ai:<assistant id>`) and the person who asked for it. An AI principal can be a maker, never a checker (see [AI integration](architecture.md#7-ai-integration-planned)).

### 3.2 Approval policies

How many approvals a change needs, and from whom, is set per artifact kind, per artifact id pattern and per **risk tier**:

```yaml
# Governance policy, held by the governance service
tiers:
  standard:
    approvals: 1
    roles: [RULE_CHECKER]
  credit-policy:
    approvals: 2
    roles: [CREDIT_RISK, COMPLIANCE]   # one approver from each role
    distinctUnits: true                # approvers from different organisational units
  aml-thresholds:
    approvals: 2
    roles: [AML_OFFICER, COMPLIANCE]
assign:
  - { kind: params, id: "retail_credit", tier: credit-policy }
  - { kind: table,  id: "aml_*",         tier: aml-thresholds }
  - { kind: "*",    id: "*",             tier: standard }
```

A change set takes the strictest tier of its items. Every approver is a distinct principal. Roles come from the bank's identity provider (see [Identity](#62-identity)).

### 3.3 Separation of duties

| Duty | Who | Rule |
|---|---|---|
| Make | Analysts with a maker role for the artifact | Cannot approve their own change set. |
| Check | Holders of the roles the tier requires | Cannot be a maker of the change set. |
| Release | The governance service on approval, or a release role | Optional separate step: a release manager schedules activation but cannot alter content. |
| Emergency | A small group with an emergency role | See [Emergency changes](#35-emergency-changes). |
| Audit | Internal audit, read-only | Reads the log and runs verification; holds no other duty. |
| Administer policy | A governance administrator | Policy changes are themselves logged, and the administrator cannot approve rule changes. |

### 3.4 Review, comments and rejection

- A checker sees the change set as a **diff against the version in force**, rule by rule, with the content hash of each item, the flows affected by a table change, and the results of the tests attached to the submission (`.stone-test.json` coverage, and later backtesting).
- **Comments** are attached to the change set, optionally at a location in an item written as the JSON path the validator already uses, such as `loan_eligibility@4 $.rules[2].when`. They are governance events and live in the log.
- **Rejection requires a reason**, which is logged. A rejection ends the submission; the author revises the draft and submits again.
- **Withdrawal**: the author can take a submission back to `DRAFT` before a decision.

Comments and reasons are free text that the log keeps for good. The editors warn against pasting customer data into them; RuleStone cannot detect it.

### 3.5 Emergency changes

When a rule must change at once, for example a fraud pattern under way at night, the change set can be marked **emergency**:

- It still needs **one approver who is not a maker**, a holder of the emergency role. Four-eyes is reduced, never removed: self-approval is not offered.
- Waiting steps that the tier requires, such as a second approver or mandatory backtesting, are deferred.
- The approval statement carries `"emergency": true` and a `postApprovalDue` instant, by default the end of the next business day.
- The full policy must then **post-approve** the same content hashes. The log shows `EMERGENCY_APPROVED`, `ACTIVATED` and later `POST_APPROVED`, or `POST_APPROVAL_REJECTED`, after which the version must be superseded or cancelled, and the decisions made under it can be found by their records' `version` and [re-scored](replay.md#re-scoring-a-decision).

An overdue post-approval is raised by the governance service as an alert and shown in the log. The engine does not refuse to build because of it: a running engine would keep the version anyway, and a refused build would only block unrelated reloads.

### 3.6 What the engine enforces

`requireApproved()` makes approval a build-time requirement:

```java
RuleStoneEngine engine = RuleStone.builder()
        .snapshot(repository.load("retail-credit-prod")) // tables, flows, parameter sets, catalog, approvals, release
        .requireApproved(ApprovalVerifier.builder()
                .trustedKey("gov-2026", publicKey)       // the governance service's signing key
                .minApprovers(1)                         // a floor the engine checks itself
                .build())
        .requireRelease("retail-credit-prod")
        .build();
```

`build()` fails, listing every problem as it does for invalid rules, unless every registered table, flow, parameter set and catalog:

1. is covered by an approval statement whose subject names its kind, id, version label and **the content hash the engine computed itself**;
2. has a statement whose signature verifies with a trusted key, with an allowed algorithm;
3. has a statement in which no approver is a maker, and at least `minApprovers` approvers are named;
4. is listed in the release the engine loads (see [Releases](#38-releases)), when a release is required.

```text
invalid decision definition (2 problems):
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
  - retail_credit@8 (parameter set): approval CHG-2026-0042 names approver u:tran.thi.b, who is also a maker
```

The engine enforces the invariants that do not depend on the bank's organisation: hash binding, signatures, four-eyes and a minimum number of approvers. Roles, tiers and units are the governance service's policy, attested by its signature. Messages name versions and principals, never rule content or input values.

Engines built without `requireApproved()` work as today, for development and tests. The governance service can refuse to issue releases for engines that do not declare it, but an embedded engine is the bank's code: production configuration is the bank's control, which the [regulatory mapping](#8-regulatory-mapping) notes.

### 3.7 Approval statements

An approval is a statement signed over content hashes, stored **detached** from the artifacts: next to them as a file, or as a row. The artifact files stay exactly as they are.

```json
{
  "type": "rulestone.approval",
  "approvalId": "4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c",
  "changeSet": "CHG-2026-0042",
  "subjects": [
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…" }
  ],
  "makers": ["u:nguyen.van.a"],
  "approvers": [
    { "principal": "u:tran.thi.b", "role": "CREDIT_RISK", "at": "2026-11-20T03:12:00Z" },
    { "principal": "u:le.van.c", "role": "COMPLIANCE", "at": "2026-11-20T07:41:00Z" }
  ],
  "policy": "credit-policy@3",
  "emergency": false,
  "issuer": "rulestone-governance@bank.example",
  "issuedAt": "2026-11-20T07:41:02Z",
  "logEntry": "sha256:5e0b…"
}
```

`logEntry` is the hash of the audit log entry that issued the statement, which ties the statement to the [chain](#4-the-governance-audit-log).

**Signature format.** The proposal is **JWS compact serialization** (RFC 7515) whose payload is the statement in RuleStone's canonical JSON form, stored as `<change set>.approval.jws`:

| Option | For | Against |
|---|---|---|
| JWS, `EdDSA` (Ed25519) or `ES256` | A standard that banks' security teams and HSMs know; tooling to inspect it; both algorithms are in the JDK (Java 17) and in WebCrypto, so neither engine needs a dependency | More rules to get right: the engines accept only `typ: "rulestone-approval+jws"`, an allowlist of algorithms, keys by `kid` from configuration only, and refuse `alg: none`, embedded keys, `jku` and `x5u` |
| A bare Ed25519 signature over the canonical statement | Smallest possible format and code | Non-standard; no algorithm agility, which matters when a bank's HSM supports only ECDSA or RSA |
| Trusting the repository, no signatures | Nothing to build or manage | Anyone with write access to the store, such as a DBA or a Git administrator, can activate unapproved content |

Who signs is a separate choice:

- **The governance service signs** with a key in the bank's HSM or KMS, after it has checked the policy. This is the default: one key to manage, and approvers authenticate through the bank's SSO as they do for everything else.
- **Each approver also signs**, with a personal key from the bank's PKI or a smart card. The statement then carries several signatures (JWS JSON serialization) and the verifier can require them. Stronger non-repudiation, at the cost of key management for every checker.

Keys are referenced by `kid` and configured in the engine. Rotation adds a key before the old one is retired; statements signed with a retired key stay valid for the content they cover, because the content never changes. Signing goes through a `Signer` SPI so that the key never leaves the HSM.

### 3.8 Releases

An approval says that content may run. A **release** says which approved versions an engine loads, as one set:

```json
{
  "type": "rulestone.release",
  "target": "retail-credit-prod",
  "sequence": 118,
  "previous": "sha256:c41d…",
  "artifacts": [
    { "kind": "table", "id": "loan_eligibility", "version": "3@sha256:0a7e…", "state": "RETIRED" },
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…", "state": "ACTIVE" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…", "state": "ACTIVE" },
    { "kind": "catalog", "id": "reason-codes", "version": "5@sha256:fccd…", "state": "ACTIVE" }
  ],
  "routing": [
    { "decision": "loan_eligibility", "kind": "SHADOW", "version": "5@sha256:2b9e…" }
  ],
  "issuedAt": "2026-11-20T07:45:00Z"
}
```

It is signed like an approval. It solves three problems at once:

- **Completeness.** The engine loads exactly the listed versions, every version of a decision together, so a missing file cannot silently keep an older version in force.
- **Rollback attacks.** A cancelled version still has a valid approval. The release's `sequence` only grows and `previous` chains releases; an engine refuses a release older than the one it runs.
- **Routing is governed.** Canary and shadow versions are part of the release, so routing traffic to a version is approved like the version.

Activation is therefore "issue a new release". A change set's items enter the same release, atomically.

## 4. The governance audit log

### 4.1 Events

Every governance event is one entry in an append-only log: `DRAFT_CREATED`, `DRAFT_SAVED`, `SUBMITTED`, `WITHDRAWN`, `COMMENTED`, `APPROVED` (one per approver), `REJECTED`, `APPROVAL_ISSUED` (the policy is met and the statement signed), `EMERGENCY_APPROVED`, `POST_APPROVED`, `POST_APPROVAL_REJECTED`, `RELEASED`, `ACTIVATED`, `RETIRED`, `CANCELLED`, `POLICY_CHANGED`, `KEY_ADDED`, `KEY_RETIRED`, `ANCHORED`.

```json
{
  "seq": 1042,
  "eventId": "9d2b7a1e-3c4f-4a5b-8e6d-7f0a1b2c3d4e",
  "at": "2026-11-20T03:12:00.481Z",
  "type": "APPROVED",
  "actor": { "principal": "u:tran.thi.b", "via": "camunda8:rule-change/2251799813685321" },
  "changeSet": "CHG-2026-0042",
  "subjects": [ { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" } ],
  "details": { "role": "CREDIT_RISK", "comment": "DTI limit matches the Credit Committee minutes of 2026-11-18" },
  "prev": "sha256:8c1e…",
  "hash": "sha256:5e0b…"
}
```

- `hash` is the SHA-256 of the entry's canonical form without `hash`, the same canonical JSON as tables. `prev` is the `hash` of entry `seq - 1`; the first entry's `prev` is 64 zeros.
- `seq` has no gaps. One log per governance store gives one total order; governance events are rare, so one writer at a time is enough.
- `via` names the system the action came from, such as the editor, a Camunda process instance or the bank's change-management ticket.
- The log holds hashes, principal ids, versions and free text. It holds no rule content, which the repository keeps under the same hashes, and no customer data.

### 4.2 Verification

`rulestone-governance verify` (a Java command-line tool, also a library call) checks, and reports the first entry where a check fails:

- every `hash` recomputes and every `prev` matches, with no gap in `seq`;
- the head matches the latest anchor (see below);
- every approval statement and release verifies with the keys valid when it was issued, and names a `logEntry` that exists;
- every active or retired version in the repository has its content hash in an `APPROVAL_ISSUED` and a `RELEASED` event, and the content stored under it still hashes to it;
- every approval satisfies four-eyes against the `DRAFT_SAVED` and `SUBMITTED` events of its change set.

The result is a report an auditor can keep, itself without rule content.

### 4.3 Anchoring

A chain proves that entries were not altered only to someone who holds a later hash. An insider who can rewrite the whole table can rebuild the whole chain. Anchors put the head hash out of that insider's reach:

| Anchor | How | Fits |
|---|---|---|
| **Signed checkpoint export** | Daily, the service signs `{seq, hash, at}` of the head and writes it to storage the governance service cannot rewrite: object storage with a retention lock, or a WORM archive | Every bank; the default |
| **Trusted timestamp** | An RFC 3161 timestamp token over the head hash, from the bank's own or a licensed timestamp authority | Proving *when*, not only *what*; evidence for disputes |
| **Another team's system** | The checkpoint is sent to internal audit's or the security team's log platform (SIEM) | Separation from the platform team |
| **Database-native ledgers** | Oracle immutable or blockchain tables, SQL Server ledger tables, as extra protection of the log table | Where the bank already licenses them; not required |

Each anchor is recorded back as an `ANCHORED` event, so the next verification knows which head to compare.

### 4.4 Decision records are a separate stream

The governance log and the [decision records](java-api.md#decision-records-and-auditing) written through `AuditSink` answer different questions and stay separate:

| | Governance log | Decision records |
|---|---|---|
| Question | Who changed which rules, and who approved them | What was decided, with which version, and why |
| Volume | Tens of entries a day | Up to thousands a second |
| Order | One total order | Per correlation id at best, across many engine instances |
| Written by | The governance service | Every engine, through its sinks |

They join on the exact version: a record's `version`, the versions in its `path` and its `params` name content hashes, and the log shows who approved and released each. An auditor goes from a decision to its approvers in two lookups.

**Should decision records be chained too?** The architecture's banking requirements list tamper-evident decision records.

- *For:* deleting or altering one record becomes detectable, and regulators value it.
- *Against:* one chain serialises every decision across all engine instances, which an inline path cannot afford; per-instance chains break whenever an instance restarts or scales; records with personal data in their outputs or correlation ids must eventually be deleted when their retention period ends, which breaks a chain unless pruning is designed in; and the Kafka sink is asynchronous by design.

The proposal is to **seal records in batches, not chain them one by one**. The engine stays as it is. `rulestone-audit-jdbc` writes records append-only and, optionally, a sealing job computes per time window and partition a hash over the records in `recordId` order, chained from window to window and recorded in the governance log as a `RECORDS_SEALED` event, anchored with the log. Deleting, adding or changing a record in a sealed window is then detectable, and a window that reaches the end of its retention period is removed with its seal, leaving the seal's hash in the log. For Kafka, the same job can run on the consumer side.

## 5. Storage

### 5.1 The `RuleRepository` SPI

Engines only read. The repository interface, in `rulestone-api`, is read-only and returns raw content, never parsed objects or trusted hashes, so the engine computes every hash itself:

```java
public interface RuleRepository {

    /** The current release of a target, with its content and approvals, verified by the caller. */
    Snapshot load(String target) throws RepositoryException;

    /** One exact version, for replay, also when it is retired or not in the current release. */
    Optional<Artifact> find(ArtifactKind kind, String id, String exactVersion) throws RepositoryException;

    /** Notifies the listener when a newer release of the target is issued. Optional. */
    default Subscription watch(String target, Consumer<Long> newSequence) {
        return Subscription.NONE;
    }
}

record Artifact(ArtifactKind kind, String id, String version, byte[] content) {}
record Snapshot(long sequence, byte[] release, List<Artifact> artifacts, List<byte[]> approvals) {}
```

Writing drafts, submissions and events is the governance service's business, through a separate `GovernanceStore` SPI in `rulestone-governance`. A bank that keeps rules in its own system implements `RuleRepository` only.

| Implementation | Module | Status |
|---|---|---|
| Files and Git | `rulestone-core` | Available: `FileRuleRepository`, with approvals and releases as files (see [Releases](releases.md#repositories)) |
| JDBC | `rulestone-repository-jdbc` | M2 target: PostgreSQL, Oracle, SQL Server |
| S3-compatible object storage | `rulestone-repository-s3` | Later |

### 5.2 Files and Git

The directory layout of today keeps working, with two kinds of files added:

```
rules/
  loan_eligibility.v3.stone.json
  loan_eligibility.v4.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
  releases/
    retail-credit-prod.release.jws
```

Drafts and submissions live in branches and pull requests, as the [web editor's workflow](editor.md#how-files-are-saved) describes today. Approval is the signed statement, issued by the governance service or by a CI job that checks the pull request's reviews against the policy and holds the signing key. Because the engine verifies the signatures, a commit pushed past review is refused even if branch protection fails.

In Git mode, the Git host's review history is the detailed record of who commented and approved; the governance log of [section 4](#4-the-governance-audit-log), with drafts, comments and four-eyes over every save, needs the governance service and its store.

### 5.3 JDBC

`rulestone-repository-jdbc` implements both `RuleRepository` and `GovernanceStore`. **Versions are immutable rows**: once a label is approved, its row is never updated or deleted. Drafts are the only mutable data. A PostgreSQL sketch:

```sql
-- Approved content: insert-only. id@version is bound to one hash forever.
CREATE TABLE rs_artifact_version (
  kind            VARCHAR(16)  NOT NULL,          -- table, flow, params, catalog
  artifact_id     VARCHAR(200) NOT NULL,
  version_label   VARCHAR(100) NOT NULL,
  content_hash    CHAR(71)     NOT NULL,          -- sha256:<64 hex>
  content         TEXT         NOT NULL,          -- canonical JSON, exact bytes as UTF-8
  effective_from  DATE,
  effective_to    DATE,
  created_at      TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (kind, artifact_id, version_label)
);

-- Working copies: the only table that is updated.
CREATE TABLE rs_draft (
  draft_id        UUID         PRIMARY KEY,
  change_set_id   VARCHAR(64)  NOT NULL,
  kind            VARCHAR(16)  NOT NULL,
  artifact_id     VARCHAR(200) NOT NULL,
  version_label   VARCHAR(100) NOT NULL,
  content         TEXT         NOT NULL,
  revision        INTEGER      NOT NULL,          -- optimistic locking, like the editor's ETag
  updated_by      VARCHAR(200) NOT NULL,
  updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE rs_change_set (
  change_set_id   VARCHAR(64)  PRIMARY KEY,
  title           VARCHAR(500) NOT NULL,
  tier            VARCHAR(64)  NOT NULL,
  emergency       BOOLEAN      NOT NULL,
  state           VARCHAR(16)  NOT NULL,          -- a cache of the log, checked by verification
  external_ref    VARCHAR(200)                    -- the ApprovalWorkflow's process or ticket
);

CREATE TABLE rs_approval (
  approval_id     UUID         PRIMARY KEY,
  change_set_id   VARCHAR(64)  NOT NULL REFERENCES rs_change_set,
  statement       TEXT         NOT NULL,          -- JWS compact serialization
  issued_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE rs_release (
  target          VARCHAR(100) NOT NULL,
  sequence        BIGINT       NOT NULL,
  manifest        TEXT         NOT NULL,          -- signed release, JWS
  issued_at       TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (target, sequence)
);

-- The governance log: insert-only, one writer at a time.
CREATE TABLE rs_governance_log (
  seq             BIGINT       PRIMARY KEY,       -- no gaps: assigned as head + 1 under a lock
  entry           TEXT         NOT NULL,          -- canonical JSON of the entry
  entry_hash      CHAR(71)     NOT NULL UNIQUE,
  prev_hash       CHAR(71)     NOT NULL,
  at              TIMESTAMPTZ  NOT NULL
);

CREATE FUNCTION rs_refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END $$;

CREATE TRIGGER rs_artifact_version_immutable BEFORE UPDATE OR DELETE ON rs_artifact_version
  FOR EACH ROW EXECUTE FUNCTION rs_refuse_change();
CREATE TRIGGER rs_governance_log_immutable BEFORE UPDATE OR DELETE ON rs_governance_log
  FOR EACH ROW EXECUTE FUNCTION rs_refuse_change();
```

The same triggers protect `rs_approval` and `rs_release`. The application's database user is granted `INSERT` and `SELECT` only on the insert-only tables; triggers are a second line, and the hashes and signatures are the third, because a DBA can disable triggers.

| Concern | PostgreSQL | Oracle | SQL Server |
|---|---|---|---|
| Content | `TEXT` (not `JSONB`, which reorders keys) | `CLOB` | `NVARCHAR(MAX)` |
| Instants | `TIMESTAMPTZ` | `TIMESTAMP WITH TIME ZONE` | `DATETIMEOFFSET` |
| Booleans | `BOOLEAN` | `NUMBER(1)` | `BIT` |
| Refusing updates and deletes | `BEFORE UPDATE OR DELETE` trigger | Trigger with `RAISE_APPLICATION_ERROR`; optionally an immutable table | `INSTEAD OF UPDATE, DELETE` trigger, or `DENY`; optionally an append-only ledger table |
| Serialising log writes | `SELECT … FOR UPDATE` on a one-row head table | Same | `UPDLOCK, HOLDLOCK` on the head row |
| Change notification | `LISTEN`/`NOTIFY` | Polling | Polling |

Hashes are always computed by RuleStone from the content, never by the database, so the three databases give identical hashes. The module ships the DDL for each database as plain scripts, without a migration tool dependency.

### 5.4 Loading and hot reload

An engine loads one release of its target:

1. `repository.load(target)` returns the signed release, the content of every listed version and the approvals.
2. `build()` verifies the release (signature, `sequence` not older than the running one), recomputes every content hash, matches each against the release and the approvals, then validates the rules as today.
3. The application swaps the new engine in, as in [Reloading rules](java-api.md#reloading-rules).

A small helper does this on notification or on a timer:

```java
RepositoryReloader reloader = RepositoryReloader.builder(repository, "retail-credit-prod")
        .engine(builder -> builder.requireApproved(verifier).auditSink(auditSink))
        .onReload(engine::set)                                  // AtomicReference<RuleStoneEngine>
        .onFailure((sequence, e) -> log.error("release {} not loaded", sequence, e))
        .pollEvery(Duration.ofSeconds(30))
        .build();
```

A release that fails verification or validation is never swapped in; the previous engine keeps running. Because releases are signed, the last one loaded can also be cached on local disk and used at start-up when the repository is unreachable, which keeps the existing guarantee that decisions do not depend on the repository being up.

Step 2 implements the helper as `RepositoryReloader` in both engines, with a `reloadIfNewer()` call and optional polling (see [Reloading](releases.md#reloading)). Its cache keeps the last accepted release, signed, so that a restarted engine refuses an older release; caching the content as well, for start-up while the repository is unreachable, is left to the JDBC step.

### 5.5 A JDBC audit sink for decision records

`rulestone-audit-jdbc` writes each `DecisionRecord` as one insert-only row:

| Column | From the record |
|---|---|
| `record_id` (primary key) | `recordId` |
| `correlation_id` (indexed) | `correlationId` |
| `decision`, `version` (indexed with `business_date`) | `decision`, `version` |
| `business_date`, `evaluated_at` | `businessDate`, `evaluatedAt` |
| `status` | `status` |
| `record` | the record as `DecisionRecordJson.write` writes it, exact bytes |
| `seal_window` | the sealing window, when sealing is on |

It writes synchronously in the evaluating thread by default, so that a decision is never returned without its record, as for any sink. A batching mode hands records to a bounded queue and fails the evaluation when the queue is full, as the Kafka sink does with its buffer. Retention and partitioning by `business_date` are left to the bank's database administration.

## 6. Integration with a bank's systems

### 6.1 The `ApprovalWorkflow` SPI

Many banks already approve changes in a BPM or change-management system. The governance service hands the decision to it and keeps the invariants:

```java
public interface ApprovalWorkflow {

    /** Called once when a change set is submitted. Returns the external process or ticket id. */
    String start(ApprovalRequest request);

    /** Called when the change set is withdrawn, or edited and so voided, before a decision. */
    void cancel(String externalRef, String reason);
}

// The external system reports each decision back:
governance.decide(changeSetId, Decision.approve(principal, "CREDIT_RISK", comment));
governance.decide(changeSetId, Decision.reject(principal, reason));
```

`ApprovalRequest` carries the change set id, the items with their hashes, the tier, the makers and a link to the review screen. Whatever the external system says, the governance service checks that each approver is authenticated, holds a required role and is not a maker, before it counts the approval, and it logs the external reference in `via`.

| Implementation | Use |
|---|---|
| `InMemoryApprovalWorkflow` | Tests |
| Built-in | Approvals given in RuleStone's own review screens; stored with the JDBC store |
| Camunda 8 and Camunda 7 | Starts a BPMN process with the change set as variables; a user task per approval; a job worker or delegate reports decisions back. Builds on the [Camunda integration](camunda.md) |
| Bank-specific | A few dozen lines against the bank's ticketing or BPM API |

### 6.2 Identity

RuleStone has no users. The host provides the current principal:

```java
public interface PrincipalResolver {
    /** The authenticated caller of the current request: a stable id, a display name and roles. */
    Principal current();
}
```

Principals come from the bank's SSO (OIDC or SAML claims, Active Directory or LDAP groups), mapped to governance roles in configuration. Principal ids are stored as given (`u:tran.thi.b`), so the bank's identity system remains the source of who a person is. Leavers keep their id in the log; their approvals stay valid because they were valid when given.

### 6.3 The editor SDK

The [editor SDK](editor-sdk.md) stays persistence-free: the host application signs users in and saves. To take part in governance, the host:

- saves drafts from `rulestone-change` (`detail.table` and `detail.ruleVersion`) to the governance service's REST API, or to its own store;
- shows checkers the exact `ruleVersion` they approve, so the hash in the approval is the hash on the screen;
- if it runs its own approval workflow, either reports decisions to the governance service, or signs approval statements itself with its own key, which the engines then trust as an issuer.

The SDK will add review elements: a rule-level diff of two versions of a table or flow, and a change set summary with hashes. They make no network calls either.

### 6.4 The standalone editor

The [web editor](editor.md) has no authentication today and is safe only on a user's own machine or behind an SSO proxy. To be used by a team it needs:

1. **Authentication.** First a trusted-proxy mode: the SSO reverse proxy passes the user and groups in headers, accepted only from the proxy's address and with a shared secret or mutual TLS between proxy and editor. Then built-in OIDC sign-in.
2. **A governance store.** With `--governance jdbc:…`, saving writes a draft instead of a file, and the editor gains submit, review with diff and comments, approve and reject screens, and the log of a change set. Without it, the editor writes files as today, for one person working in a Git clone.
3. **Per-decision permissions**, from the roles the identity provider gives.

## 7. TypeScript engine parity

The TypeScript engine (`@dxa/rulestone-engine`) is a first-class engine for Node.js services and front ends, so it must refuse unapproved content exactly as the Java engine does.

| Capability | Java | TypeScript |
|---|---|---|
| Canonical form and content hashes of all four kinds | yes (available) | yes (available) |
| Reading and verifying approval statements: JWS, `EdDSA` and `ES256`, trusted keys by `kid`, hash binding, four-eyes, `minApprovers` | yes | yes, with WebCrypto; `createEngine` is already asynchronous |
| `requireApproved` with identical problems and messages | yes | yes |
| Reading and verifying releases, `sequence` monotonicity | yes | yes |
| `RuleRepository` read interface | yes | yes, asynchronous, for example over HTTP |
| Hot reload helper | yes | yes |
| Governance service: lifecycle, policies, change sets, emergency changes | yes | no |
| Governance log writer, verification tool, anchoring | yes | no; verifying a log export in the browser can come later |
| JDBC repository and JDBC audit sink | yes | no |
| `ApprovalWorkflow`, `PrincipalResolver`, Camunda workflow | yes | no |
| Editor authentication and review screens | standalone editor | review elements in the SDK; sign-in stays with the host |

Two conformance fixtures, `java-approvals.json` and `java-releases.json`, written by the Java engine, list statements and releases with the expected result: valid, a tampered hash, a wrong or unknown key, `alg: none`, an embedded key, a maker who approves, too few approvers, an old release sequence, a broken chain, unlisted and missing versions, and routing to unlisted or retired versions. The TypeScript engine must give the same verdict and the same problem text.

An engine in a browser verifies approvals so that a preview or a pre-check uses approved rules, but a browser is not a trust boundary: decisions that matter are made, or made again, by a server-side engine.

## 8. Regulatory mapping

This table guides the design; it is not legal advice. The current texts must be checked before any commitment to a customer.

| Expectation | Where it is met |
|---|---|
| Change management for banking information systems: changes are requested, assessed, approved and tested before production, with emergency changes documented and approved afterwards (State Bank of Vietnam regulations on information system security in banking; ISO/IEC 27001 controls; PCI DSS where card data is involved) | Lifecycle, change sets, tests attached to submissions, emergency path with post-approval due date |
| Segregation of duties and four-eyes control, as part of a credit institution's internal control system (State Bank of Vietnam regulations on internal control; Basel Committee principles) | Makers derived from every save, approvers distinct from makers, roles from the bank's identity provider, policy administration separated |
| Retaining logs and evidence of changes, protected against alteration | Hash-chained governance log, anchoring outside the platform team's reach, verification tool |
| Model risk management (SR 11-7 style): inventory, change control, independent validation and effective challenge, documentation | Every model pin is in a governed flow version; checkers from a validation role; comments and rejection reasons; the log as the change history of each model's use |
| Risk data lineage (BCBS 239) | Decision record → exact versions → approval statement → log entries |
| Record-keeping and human oversight for high-risk AI, such as credit scoring (EU AI Act, where relevant) | Decision records always produced; AI may only draft; approvals by people |
| Reproducing a past decision for a customer complaint or an inspection | Retired versions stay loadable; replay by exact version; approvals prove which text was in force |
| Personal data protection (Vietnam's personal data protection law and decrees) | No customer data in the governance log; sealed decision-record windows can be deleted at the end of their retention period |

Two controls remain the bank's: configuring production engines with `requireApproved()`, which belongs in the bank's own deployment change control, and protecting the signing keys in its HSM or KMS.

## 9. Out of scope

- **User and role management**, sign-in pages and password storage. They belong to the bank's identity provider.
- **A general BPM or ticketing system.** RuleStone has a built-in approval flow for its own changes and connects to the bank's.
- **Model validation and a model registry.** RuleStone governs which model version a flow pins, not the model's development or validation.
- **Approving facts or input data.** Inputs are the calling application's responsibility.
- **Undoing decisions.** A rejected emergency change is superseded; affected decisions are found by their records and re-scored by the bank's processes.
- **Key management.** Keys stay in the bank's HSM or KMS, behind the `Signer` SPI.
- **Public blockchains** as anchors. Private anchors meet the need without publishing anything.

## 10. Open questions

| Question | Current proposal |
|---|---|
| JWS or a bare signature? | JWS compact serialization with `EdDSA` and `ES256` only, parsed by a small strict reader in each engine, without a JOSE library. Add `PS256` if a bank's HSM requires RSA. |
| Service signature or per-approver signatures? | Service signature by default; per-approver signatures as an option that the verifier can require. |
| Must shadow versions be approved? | Yes: a shadow runs on production data, and its records sit with real decisions. A canary answers customers, so it obviously must. Both are listed in the release. |
| When is a label bound to a hash? | At approval. Before that, a label can be resubmitted with other content as a new submission. |
| What happens to an overdue emergency post-approval? | An alert and a log entry, not a build failure. Banks may want a stricter mode that refuses new releases until it is resolved. |
| Is the governance policy itself a governed artifact? | Policy changes are logged as `POLICY_CHANGED` and need a governance administrator. Treating the policy as a change set with its own approvers may follow. |
| One log per store or per target? | One per store: simpler verification, and the volume is low. |
| Chained decision records? | Batch sealing in the JDBC sink, opt-in; no chaining in the engine. |
| How does Git mode prove four-eyes over every save? | It cannot as strongly as the governance store: the CI job checks the pull request's commit authors against its approving reviewers. Banks that need the full log use the JDBC store. |
| Migration tooling for the DDL | Plain scripts per database, which banks' DBAs usually require; Flyway and Liquibase can include them. |
| Which release does an engine load? | One `target` per deployed application or decision service, so that different applications can run different sets of approved versions. |

## 11. Implementation plan

Each step is a separate, reviewable change with tests and documentation in English and Vietnamese. Steps 1 and 2 are in both engines, with conformance fixtures.

1. **Approval statements and `requireApproved()`.** *Done.* See [Approvals](approvals.md). The statement format, JWS verification with `EdDSA` and `ES256`, trusted keys, hash binding, four-eyes and `minApprovers` checks, approvals read from files. `java-approvals.json` for the TypeScript engine. This delivers enforcement first: a bank can issue statements from a CI job and refuse unapproved content before any governance service exists.
2. **Releases and the `RuleRepository` SPI.** *Done.* See [Releases](releases.md). The release format with `sequence` and `previous`, signed like approvals with `typ` `rulestone-release+jws`; `requireRelease()`, which loads exactly the listed versions, refuses older releases and broken chains, and takes canary and shadow versions from the release; the read-only `RuleRepository` interface with `FileRuleRepository` for files and Git checkouts; `RepositoryReloader` and the local cache of the last accepted release. `java-releases.json` for the TypeScript engine, which verifies routing but does not route.
3. **The governance service core** (`rulestone-governance`): lifecycle, change sets, policies and tiers, separation of duties, emergency changes, the `PrincipalResolver` and `Signer` SPIs, an in-memory store, the hash-chained log and the `verify` tool.
4. **JDBC.** `rulestone-repository-jdbc` with the DDL for PostgreSQL, Oracle and SQL Server, tested against each database, and `rulestone-audit-jdbc` with optional batch sealing.
5. **Anchoring.** Signed checkpoint export, an RFC 3161 timestamp client behind an SPI, and `ANCHORED` events.
6. **`ApprovalWorkflow`.** The SPI, the built-in workflow, and Camunda 8 and Camunda 7 workflows.
7. **Editors.** Authentication and governance screens in the standalone editor; diff and change set elements in the SDK, with a guide for hosts.
8. **Architecture and roadmap.** Mark M2 done in [Architecture](architecture.md), and move the banking requirements for segregation of duties and tamper-evident audit to available.
