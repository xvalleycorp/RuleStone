# Governance service

`rulestone-governance` is the governance service of RuleStone as a Java library: it runs the **lifecycle** of rule artifacts in **change sets**, enforces **maker-checker** with approval policies and emergency changes, **signs the approval statements and releases** that engines accept, and writes every event to a **hash-chained audit log** that a `verify` tool checks. An engine built with `requireApproved()` and `requireRelease()` from what the service issued loads exactly what was approved and released, and nothing else.

This is step 3 of the [governance design](governance.md#11-implementation-plan). The service is Java only, as the design says ([TypeScript engine parity](governance.md#7-typescript-engine-parity)); the TypeScript engine verifies what the service issues, as the Java engine does. This step has an in-memory store; the JDBC store, anchoring, the `ApprovalWorkflow` SPI with Camunda, and the editor screens follow in the next steps.

- [Example](#example)
- [Setting up](#setting-up)
- [Lifecycle and operations](#lifecycle-and-operations)
- [Change sets, makers and labels](#change-sets-makers-and-labels)
- [Policies and tiers](#policies-and-tiers)
- [Who may do what](#who-may-do-what)
- [Emergency changes](#emergency-changes)
- [Releases](#releases)
- [What engines load](#what-engines-load)
- [The audit log](#the-audit-log)
- [Verifying](#verifying)
- [The SPIs](#the-spis)
- [Errors](#errors)
- [Differences from the design](#differences-from-the-design)
- [Limits](#limits)

## Example

A maker saves and submits a table, a checker approves it, a release manager activates it, and an engine is built from what the service issued:

```java
GovernancePolicy policy = GovernancePolicy.builder()
        .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
        .assign("*", "*", "standard")
        .makerRoles("RULE_MAKER")
        .releaseRoles("RELEASE_MANAGER")
        .build();

InMemoryPrincipalResolver who = new InMemoryPrincipalResolver();   // the bank's SSO, in a real service
GovernanceService service = GovernanceService.builder()
        .store(new InMemoryGovernanceStore())
        .principals(who)
        .signer(PrivateKeySigner.of("gov-2026", privateKey))        // an HSM or KMS in production
        .policy(policy)
        .build();

who.actAs(Principal.of("u:nguyen.van.a", "RULE_MAKER"));
service.createChangeSet("CHG-2026-0042", "Raise the score gate");
service.saveDraft("CHG-2026-0042", ArtifactKind.TABLE, tableJson);
service.submit("CHG-2026-0042");

who.actAs(Principal.of("u:tran.thi.b", "RULE_CHECKER"));
service.approve("CHG-2026-0042", "RULE_CHECKER", "Matches the committee minutes");

who.actAs(Principal.of("u:rm.one", "RELEASE_MANAGER"));
service.activate("CHG-2026-0042", "retail-credit-prod");

RuleStoneEngine engine = RuleStone.builder()
        .snapshot(service.repository().load("retail-credit-prod"))
        .requireApproved(ApprovalVerifier.builder().trustedKey("gov-2026", publicKey).build())
        .requireRelease("retail-credit-prod")
        .build();
```

`u:nguyen.van.a` could not have approved his own change set: he saved and submitted it, so he is one of its makers, and `approve` would fail with `FOUR_EYES`. The service wrote these entries to its log, each chained to the one before:

```text
1  POLICY_CHANGED   svc:rulestone-governance
2  DRAFT_CREATED    u:nguyen.van.a   CHG-2026-0042
3  DRAFT_SAVED      u:nguyen.van.a   CHG-2026-0042  table gate_decision@1
4  SUBMITTED        u:nguyen.van.a   CHG-2026-0042
5  APPROVED         u:tran.thi.b     CHG-2026-0042
6  APPROVAL_ISSUED  svc:rulestone-governance   (the statement's logEntry is this entry's hash)
7  RELEASED         u:rm.one         release 1 of retail-credit-prod
8  ACTIVATED        u:rm.one         CHG-2026-0042
```

## Setting up

Add the module; it depends on `rulestone-core` and the JDK only:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-governance</artifactId>
</dependency>
```

`GovernanceService.builder()` takes:

| Setting | Meaning |
|---|---|
| `store` | A [`GovernanceStore`](#the-spis). Required. `InMemoryGovernanceStore` in this step. |
| `principals` | A [`PrincipalResolver`](#the-spis): who is calling. Required. |
| `signer` | A [`Signer`](#the-spis) that signs statements and releases. Required. |
| `policy` | The [policy](#policies-and-tiers). Required. A store without a policy gets it, with a `POLICY_CHANGED` entry. A store that has one must hold exactly this policy, whatever its version: a policy changes only through `changePolicy`, which needs an administrator and is logged. |
| `clock` | For entries, statements and releases, and for the business date of [retirement](#releases). Default UTC system time; a fixed clock makes tests repeatable. |
| `idGenerator` | The source of event ids and statement ids. Default random UUIDs; a counter makes them repeatable. |
| `issuer` | Written to statements as `issuer`, and as `svc:<issuer>` for the service's own entries. Default `rulestone-governance`. |
| `listener` | Receives every log entry after it is committed, such as the alert for an [overdue post-approval](#emergency-changes). A listener that throws is ignored: the log is the record. |

The service serialises its operations, one writer at a time, as the log needs. Reading goes through `service.changeSet(id)`, `changeSets()`, `stateOf(kind, id, label)`, `policy()` and the store.

## Lifecycle and operations

A version moves through the states of the [design](governance.md#22-lifecycle); `LifecycleState` lists the moves the service allows, and every other move is refused with `ILLEGAL_STATE`:

```text
            submit            approve (policy met)          activate
  DRAFT ─────────────▶ SUBMITTED ─────────────▶ APPROVED ─────────────▶ ACTIVE ─────▶ RETIRED
    ▲                     │
    │  withdraw, edit     │  reject (with a reason)
    ├─────────────────────┤
    │  revise             ▼
    └──────────────── REJECTED
```

| Operation | Who | State needed | Log entries |
|---|---|---|---|
| `createChangeSet(id, title)` | maker role | none | `DRAFT_CREATED` |
| `saveDraft(changeSet, kind, content)` | maker role | `DRAFT`, `SUBMITTED` or `REJECTED` | `DRAFT_SAVED` |
| `removeDraft(changeSet, kind, id, label)` | maker role | as `saveDraft` | `DRAFT_SAVED` with `removed` |
| `submit(changeSet)` | maker role | `DRAFT` | `SUBMITTED` |
| `withdraw(changeSet, reason)` | a maker of the change set | `SUBMITTED` | `WITHDRAWN` |
| `comment(changeSet, location, text)` | any authenticated principal | any | `COMMENTED` |
| `approve(changeSet, role, comment)` | a checker | `SUBMITTED` | `APPROVED` or `EMERGENCY_APPROVED`, then `APPROVAL_ISSUED` when the policy is met |
| `reject(changeSet, reason)` | a checker | `SUBMITTED` | `REJECTED` |
| `declareEmergency(changeSet, reason)` | maker role | `DRAFT` or `SUBMITTED` | `EMERGENCY_DECLARED` |
| `postApprove(changeSet, role, comment)` | a checker | emergency awaiting post-approval | `APPROVED`, then `POST_APPROVED` when the full policy is met |
| `rejectPostApproval(changeSet, reason)` | a checker | emergency awaiting post-approval | `POST_APPROVAL_REJECTED` |
| `checkOverdue()` | the host, on a timer | none | `POST_APPROVAL_OVERDUE` |
| `activate(changeSet, target[, routing])` | release role | `APPROVED`, or `ACTIVE` in another target | `RELEASED`, `ACTIVATED`, `RETIRED` |
| `retireSuperseded(target)` | release role | none | `RELEASED`, `RETIRED` |
| `changePolicy(policy)` | administrator | none | `POLICY_CHANGED` |

An operation that breaks a rule throws a `GovernanceException` and changes nothing: the store applies the change set, the statement, the release and the log entries of one operation in a single atomic update, or none of them.

## Change sets, makers and labels

A **change set** groups artifact versions that are submitted, reviewed, approved and activated together: one review of all items, one approval statement naming the hash of every item, one activation, so that engines never load half of a change. `saveDraft` takes the content as JSON text and reads the artifact's kind-specific identity from it: the `id` and `version` label of a table, flow or parameter set, and the version of a catalog, which has no id and is named `reason-codes` unless `saveDraft(changeSet, ArtifactKind.CATALOG, name, content)` names it. The service parses the content with the same readers as the engine, so invalid content is refused when it is saved (`INVALID_CONTENT`), and computes the **exact version** `<label>@sha256:<hash>` itself.

**Makers are a fact of the log.** Every principal who saved a revision of any item, who removed an item, or who submitted the change set is a maker of it, with whoever an AI principal acted for (`Principal.actingFor`). A save is a `DRAFT_SAVED` entry with the saver as its actor, so the set of makers can be derived from the log by anyone, and `verify` does so.

**Versions are superseded, approvals are bound.** Before approval, a label is a proposal: a rejected or withdrawn label may be submitted again with other content, each submission with its own hash, and the approvals of earlier content do not carry over. An edit of a submitted change set returns it to `DRAFT` and voids its approvals. **On approval, `id@label` is bound to one content hash forever**: the store refuses any other content under that label, also after retirement, and the service refuses to save, submit or approve it (`LABEL_BOUND`). Two change sets may draft the same label; the first one approved wins and the other must use a new label, for which `removeDraft` takes the old one out. The content of an approved version is kept in the store, never edited, and found again by `repository().find(kind, id, exactVersion)`.

## Policies and tiers

The policy says how many approvals a change needs, and from whom. It is a Java object or a JSON file:

```json
{
  "version": 1,
  "tiers": {
    "standard":      { "approvals": 1, "roles": ["RULE_CHECKER"] },
    "credit-policy": { "approvals": 2, "roles": ["CREDIT_RISK", "COMPLIANCE"], "distinctUnits": true }
  },
  "assign": [
    { "kind": "params", "id": "retail_credit", "tier": "credit-policy" },
    { "kind": "*", "id": "*", "tier": "standard" }
  ],
  "roles": {
    "maker": ["RULE_MAKER"],
    "emergency": ["EMERGENCY_APPROVER"],
    "release": ["RELEASE_MANAGER"],
    "administrator": ["GOVERNANCE_ADMIN"]
  },
  "emergency": { "postApprovalBusinessDays": 1, "zone": "Asia/Ho_Chi_Minh" }
}
```

`GovernancePolicyJson.read` and `write` convert it; reading is strict, so a misspelt key is an error. In Java:

```java
GovernancePolicy policy = GovernancePolicy.builder()
        .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
        .tier(GovernancePolicy.Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits())
        .assign(ArtifactKind.PARAMS, "retail_credit", "credit-policy")
        .assign("*", "*", "standard")
        .makerRoles("RULE_MAKER")
        .emergencyRoles("EMERGENCY_APPROVER")
        .releaseRoles("RELEASE_MANAGER")
        .administratorRoles("GOVERNANCE_ADMIN")
        .postApprovalDue(1, ZoneId.of("Asia/Ho_Chi_Minh"))
        .build();
```

- A **tier** needs `approvals` distinct approvers, at least as many as it has roles, and **an approver in every role it lists**: an approver says the role they approve in, which must be one they hold. Approvals in other roles do not count towards the tier.
- `distinctUnits` needs the approvers from different organisational units (`Principal.unit`). An approver without a unit, or from a unit that has approved already, is refused with `UNIT_NOT_DISTINCT`.
- **Assignments** are tried in order and the first that matches the kind and the id pattern (`*` matches any characters) gives the tier of an artifact. An artifact that no assignment matches is a policy error, so end the list with `{ "kind": "*", "id": "*" }`.
- A change set takes the **strictest tier** of its items: the one that needs the most approvals. If several tiers need as many, the requirement is their union, named `a+b`, with the roles of all of them. A catalog message changed together with a credit parameter is therefore approved by the credit policy's approvers alone, and the statement's `policy` field says which tier applied, as `credit-policy@3`: the tier and the policy version.
- `roles` lists who may make (`maker`; empty means every authenticated principal), approve an emergency (`emergency`; empty means emergency changes are off), activate (`release`; empty means every principal except an AI) and change the policy (`administrator`; empty means nobody). Roles come from the bank's identity provider.

**Changing the policy** is an event: `changePolicy(policy)` needs an administrator, stores the policy with the next version number, and logs `POLICY_CHANGED` with the old and new version, the policy's hash and the tier names. Change sets that are already submitted are judged by the policy in force when they are decided.

## Who may do what

The rules of [separation of duties](governance.md#33-separation-of-duties), as the service enforces them:

| Rule | Refused with |
|---|---|
| No approver or rejecter is a maker of the change set. | `FOUR_EYES` |
| An AI principal (`ai:` prefix) can make changes and never check them or release. | `FORBIDDEN` |
| A governance administrator cannot approve or reject rule changes. | `FORBIDDEN` |
| An approver holds the role they approve in. | `FORBIDDEN` |
| That role is one the change set's tier lists (or, for an emergency, an emergency role). | `ROLE_NOT_REQUIRED` |
| One approval per principal. | `DUPLICATE_APPROVAL` |
| A rejection needs a reason, and a checker with a role of the tier. | `INVALID_REQUEST`, `FORBIDDEN` |
| Withdrawing is for a maker of the change set. | `FORBIDDEN` |
| Making needs a maker role, activating a release role, changing the policy an administrator role, when the policy names them. | `FORBIDDEN` |

When the tier is met, the service in one atomic update binds each label to its hash, signs the approval statement, and logs `APPROVAL_ISSUED`. The statement is the format of [Approvals](approvals.md#the-approval-statement), with `makers` from the log, one approver per approval with the role and the instant they approved in, `policy` as `tier@version`, and `logEntry`: the hash of the `APPROVAL_ISSUED` entry, which ties the statement to the [chain](#the-audit-log). The statement is stored as `<change set>.approval.jws`.

## Emergency changes

When a rule must change at once, a maker declares the change set an emergency (`declareEmergency`), if the policy names emergency roles. The [design](governance.md#35-emergency-changes) reduces four-eyes and never removes it:

- **One approver** who holds an emergency role and is not a maker is enough, in place of the tier. Approvals in the tier's roles still count towards the tier: if the full policy approves first, the statement is an ordinary one.
- The statement says `"emergency": true`, names `postApprovalDue` and has `policy` `emergency@<version>`. By default the due instant is the end (23:59:59) of the next business day, Monday to Friday, in the policy's time zone; `postApprovalDue(days, zone)` sets both.
- The change set can be activated at once. The log shows `EMERGENCY_DECLARED`, `EMERGENCY_APPROVED`, `APPROVAL_ISSUED` and `ACTIVATED`.
- The **full policy then post-approves** the same hashes with `postApprove` (a checker, not a maker, in a role of the tier, not an emergency role). When it is met, a second statement without the emergency flag is issued as `<change set>.post.approval.jws`, and the log shows `POST_APPROVED`. An engine that asks for more approvers than the emergency statement has is satisfied by this one.
- `rejectPostApproval(changeSet, reason)` logs `POST_APPROVAL_REJECTED`. The version must then be superseded or cancelled, and the decisions made under it found by their records' `version` and [re-scored](replay.md#re-scoring-a-decision). The service refuses to activate it in further targets.
- **An overdue post-approval is an alert and a log entry, not a build failure.** `checkOverdue()`, called on a timer, logs `POST_APPROVAL_OVERDUE` once for each emergency change whose due instant has passed, and hands the entry to the `listener`. Engines keep building, because a running engine would keep the version anyway and a refused build would only block unrelated reloads. A late post-approval is still accepted.

## Releases

`activate(changeSet, target)` issues the next signed release of the target: the [release format](releases.md#the-release) with `sequence` one above the target's latest (1 for the first), `previous` the hash of that release, the versions of the previous release and the items of the change set as `ACTIVE`, and `issuedAt`. The change set becomes `ACTIVE`. It can enter the releases of other targets too, one release each. Activating a change set that is not approved, or one that is retired, or twice in one target, is refused (`ILLEGAL_STATE`).

- **Retirement is bookkeeping.** A version becomes `RETIRED` when it can no longer be in force on today's date: its `effectiveTo` has passed (`"reason": "expired"`), or an active successor has taken effect (`"reason": "superseded"`). It stays listed, so that back-dated requests and replays select it and removing it does not extend its predecessor. Every `activate` applies this, and `retireSuperseded(target)` issues a release for it alone, if anything changed; call it on a timer or when the date passes. "Today" is the date of the service's clock in its zone. Retired versions appear as `RETIRED` events, and the item and change set states follow (`RETIRED` when all items are).
- **A catalog is replaced, not retired.** An engine has one reason-code catalog, so a release lists at most one: activating a new catalog version drops the old one from the release (`RETIRED` with `"reason": "replaced"`), and it stays in the store.
- **Routing.** `activate(changeSet, target, routing)` sets the canary and shadow versions of the new release, each to a table or flow version the release lists as active. Routing is part of the signed release, so engines route only as the release says; setting it needs one of the policy's release roles. Without it, the previous release's routing carries over while its versions stay active.
- **Rollbacks.** The sequence only grows and the store refuses a release that does not directly follow the latest one. An engine given an older release than the one it already accepted refuses it (`lastAcceptedRelease`, or the cache of `RepositoryReloader`).

## What engines load

`service.repository()` is a `StoreRuleRepository`, a read-only [`RuleRepository`](releases.md#repositories) over the store: `load(target)` returns the latest signed release, the content of every version it lists and all approval statements. It trusts nothing and the engine verifies everything, so you can also pass it to a `RepositoryReloader`:

```java
RepositoryReloader reloader = RepositoryReloader.builder(service.repository(), "retail-credit-prod", verifier)
        .onReload(engine::set)
        .build();
```

`GovernanceExport.write(store, directory)` writes the same state as files in the layout of `FileRuleRepository`, for engines that read files and for audit:

```text
export/
  gate_decision.v1.stone.json                         the versions the latest release of each target lists
  reason-codes.json
  approvals/CHG-2026-0042.approval.jws                every statement
  releases/retail-credit-prod.release.jws             the latest release of each target
  releases/history/retail-credit-prod.1.release.jws   every release, for the chain
  governance-log.jsonl                                the log, one entry per line
  governance-policy.json                              the policy in force
```

## The audit log

Every event is one entry of an append-only log, in canonical JSON (compact, keys sorted at every level):

```json
{
  "seq": 5,
  "eventId": "00000000-0000-4000-8000-000000000005",
  "at": "2026-11-20T03:12:00.481Z",
  "type": "APPROVED",
  "actor": { "principal": "u:tran.thi.b", "via": "camunda8:rule-change/2251799813685321" },
  "changeSet": "CHG-2026-0042",
  "subjects": [ { "kind": "table", "id": "gate_decision", "version": "1@sha256:9f2c…" } ],
  "details": { "role": "RULE_CHECKER", "comment": "Matches the committee minutes" },
  "prev": "sha256:8c1e…",
  "hash": "sha256:5e0b…"
}
```

- `hash` is the SHA-256 of the entry's canonical form without `hash`; `prev` is the `hash` of entry `seq - 1`; the first entry's `prev` is `sha256:` followed by 64 zeros (`LogEntry.GENESIS`). `seq` has no gaps. `LogEntryJson` writes and reads entries.
- `via` is what the `PrincipalResolver` reports as the system the action came from. The service's own entries have the actor `svc:<issuer>`.
- The log holds ids, versions, hashes, principals, and the free text of comments, reasons and titles. It holds **no rule content and no customer data**. Comments are free text that the log keeps for good, and RuleStone cannot detect customer data in them: tell the people who write them.
- `details` holds strings, whole numbers, booleans, lists and maps, nothing else, so that one entry has one text.

| Event | Written when | Details |
|---|---|---|
| `POLICY_CHANGED` | The service starts on an empty store, or `changePolicy` | `fromVersion`, `toVersion`, `policyHash`, `tiers` |
| `DRAFT_CREATED` | `createChangeSet` | `title` |
| `DRAFT_SAVED` | `saveDraft`, `removeDraft` | `submission`, `voidedApprovals`, `onBehalfOf`, `removed` |
| `SUBMITTED` | `submit` | `submission`, `tier`, `tiers`, `requiredApprovals`, `emergency` |
| `WITHDRAWN`, `REJECTED` | `withdraw`, `reject` | `reason`, `voidedApprovals` |
| `COMMENTED` | `comment` | `text`, `location` |
| `APPROVED` | One approval, or one post-approval | `role`, `comment`, `post` |
| `EMERGENCY_DECLARED` | `declareEmergency` | `reason` |
| `EMERGENCY_APPROVED` | An approval in an emergency role | `role`, `comment` |
| `APPROVAL_ISSUED` | The policy or the emergency approval is met | `approvalId`, `policy`, `emergency`, `approvers`, `postApprovalDue` |
| `POST_APPROVED` | The full policy post-approved | as `APPROVAL_ISSUED` |
| `POST_APPROVAL_REJECTED` | `rejectPostApproval` | `reason` |
| `POST_APPROVAL_OVERDUE` | `checkOverdue` | `due`, `overdueSeconds` |
| `RELEASED` | A release was signed | `target`, `sequence`, `releaseHash`, `previous`, `changeSet` |
| `ACTIVATED` | A change set entered a release | `target`, `sequence` |
| `RETIRED` | A version left the active set of a release | `target`, `reason` |

`CANCELLED`, `KEY_ADDED`, `KEY_RETIRED` and `ANCHORED` are defined and not written yet.

## Verifying

`GovernanceVerifier` checks a log with the statements and releases it issued, and reports every problem; `VerifyTool` is its command line.

```java
GovernanceVerifier verifier = GovernanceVerifier.builder()
        .verifier(ApprovalVerifier.builder().trustedKey("gov-2026", publicKey).build())
        .build();
VerificationReport report = verifier.verify(store);          // or verify(directory), or verify(log, approvals, releases)
System.out.print(report.format());
```

```sh
java -cp "$CLASSPATH" vn.io.dxa.rulestone.governance.VerifyTool verify ./export \
    --key gov-2026=gov-2026-public.pem --min-approvers 1
```

The key files hold public keys, Ed25519 or P-256, in X.509 form as PEM or plain base64. Keep retired keys configured: a statement stays valid for the content it covers. The exit status is 0 when everything verifies, 1 when a problem was found and 2 for unusable arguments or files. It checks:

- **The chain**: `seq` without gaps, every `prev` the hash of the previous entry, every `hash` recomputed. The first entry where a check fails is reported, with its `seq`, and the checks that rely on the log are skipped. An edited, removed, added or reordered entry is found here.
- **The anchors**, through an `AnchorCheck` (see below).
- **Every approval statement**: the signature and canonical form (as engines check them), a `logEntry` that exists, is an `APPROVAL_ISSUED` or `POST_APPROVED` entry of the same change set and names the same statement id and versions; `makers` that include everyone the log shows saving or submitting the change set; approvers that are no maker and that the log shows approving before; and a statement for every issuing entry of the log.
- **Every release**: signature, sequence from 1 without gaps, `previous` the hash of the release before, a `RELEASED` entry for it, and every listed version named in an earlier `APPROVAL_ISSUED` entry and in a `RELEASED` entry of the target.
- **The content**: with the store or the exported files, the content of every version that the latest release of each target lists still hashes to it.

```text
log entries: 8
approval statements: 1
releases: 1
FAILED: 2 problems
  - entry 5: hash does not match the entry's content: the entry was edited
  - the checks of statements and releases need an intact log, and were skipped
```

The report holds positions, change sets, statements, releases, versions and principals, never rule content, so an auditor can keep it.

**Anchoring is the next step.** A chain proves that entries were not altered only to someone who holds a later hash: an insider who can rewrite the whole log can rebuild the whole chain, and a log that lost its last entries is still a valid chain. `AnchorCheck` is the extension point: `GovernanceVerifier.builder().anchors(check)` calls it with the log after the chain verified, and it compares the head with the latest anchor. The signed checkpoints, the RFC 3161 timestamps and the `ANCHORED` events that implement it are step 5; until then `AnchorCheck.NONE` checks nothing.

## The SPIs

| Interface | Implemented by this step | For |
|---|---|---|
| `PrincipalResolver` | `InMemoryPrincipalResolver` | `current()` returns the `Principal` of the caller: a stable id such as `u:tran.thi.b`, roles, an optional unit, and for an AI principal whom it acted for. `via()` names the system the call came from. The host maps its SSO claims or groups to governance roles; RuleStone stores ids and checks rules, never passwords. |
| `Signer` | `PrivateKeySigner` | `keyId()`, `algorithm()` (`EdDSA` or `ES256`) and `sign(signingInput)`: the service builds the JWS and asks for the signature of its signing input, so that the key can stay in an HSM or KMS. For `ES256` the signature is R and S of 32 bytes each. `PrivateKeySigner` signs with a JDK `PrivateKey`, for tests and CI jobs. |
| `GovernanceStore` | `InMemoryGovernanceStore` | Change sets and drafts, the content of approved versions, statements, releases, the policy and the log, changed only through `commit(Update)`, which is atomic, chains the log, binds labels once and refuses a release that does not follow the latest. The JDBC store follows. |
| `AnchorCheck` | `AnchorCheck.NONE` | Compares the log's head with an anchor. |
| `RuleRepository` | `StoreRuleRepository` | What engines read. |

`JwsWriter` in `rulestone-core` writes the JWS compact serialization that both `PrivateKeySigner` and `ApprovalIssuer` in `rulestone-test` use, so statements and releases are the same whoever issues them.

## Errors

`GovernanceException.code()` says which rule refused, for callers that react to it:

| Code | Meaning |
|---|---|
| `UNAUTHENTICATED` | The `PrincipalResolver` has no caller. |
| `NOT_FOUND`, `ALREADY_EXISTS` | The change set, target or release does not exist, or exists already. |
| `ILLEGAL_STATE` | The lifecycle does not allow the operation now. |
| `FORBIDDEN` | The caller lacks a role, or is an AI principal or an administrator where that is not allowed. |
| `FOUR_EYES`, `DUPLICATE_APPROVAL` | The caller is a maker, or has approved already. |
| `ROLE_NOT_REQUIRED`, `UNIT_NOT_DISTINCT` | The role is not one the tier lists, or the units are not distinct. |
| `LABEL_BOUND` | The version label is bound to a hash by an approval. |
| `INVALID_CONTENT`, `INVALID_POLICY`, `INVALID_REQUEST` | The content, the policy or the request is wrong. |
| `CONFLICT` | The store changed meanwhile, or refused an update that would break its invariants. |
| `SIGNING_FAILED` | The `Signer` could not sign, or returned a signature that is not 64 bytes. Nothing was changed. |

Messages name change sets, versions, principals and roles. They never contain rule content or input values.

## Differences from the design

- **Strictest tier.** The design says a change set takes the strictest tier of its items. The service takes the one that needs the most approvals, and combines tiers that tie, so that no tier's roles are skipped silently.
- **Policy at decision time.** Change sets are judged by the policy in force when they are decided, not by the one in force when they were submitted.
- **The post-approval statement.** The design says the full policy post-approves the same hashes. The service issues a second statement for it, so that the post-approval is in the repository and verifiable like any other.
- **Retirement and cancellation.** Versions are retired automatically as the design describes. The explicit cancellation of an active version that was never in force, which needs an approval, and the `CANCELLED` event, are not in this step.
- **Reviews.** Diffs, the flows affected by a table change, and tests attached to a submission, which the design shows to checkers, belong to the editor screens. Comments are events here; a mandatory backtest is not modelled, so an emergency has nothing to defer but the second approver.
- **`GENESIS`.** The first entry's `prev` is `sha256:` and 64 zeros, in the form of every other hash.

## Limits

- **In-memory only.** `InMemoryGovernanceStore` is lost with the process; `GovernanceExport` writes the signed files and the log. Durable storage is the JDBC step.
- **No anchoring** yet: a truncated or fully rebuilt log is found only with an `AnchorCheck`.
- **One signature per statement.** The service signs for the approvers, with one key; signatures by each approver are an option for later.
- **Approvals from other systems** (`ApprovalWorkflow`, Camunda, the bank's ticketing) and the editor screens are later steps. Today the caller of `approve` is the authenticated principal.
- **Routing is set by the releaser**, with a release role, and is not approved as a change of its own.
- **The decision path is untouched.** Evaluating a decision reads no governance store and verifies no signature; the checks happen when an engine is built.
