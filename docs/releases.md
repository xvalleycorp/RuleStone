# Releases

An [approval](approvals.md) says that content may run. A **release** says which approved versions the engines of one application load, as one signed set. An engine built with `requireRelease` loads exactly the versions its release lists, takes its canary and shadow versions from it, and refuses a release older than the one it accepted before. Together with `requireApproved`, this closes the two gaps that approvals alone leave: a missing file can no longer keep an older version in force, and a cancelled version that still has a valid approval cannot be loaded again.

This is step 2 of the [governance design](governance.md#11-implementation-plan). It is available in the Java engine and in the TypeScript engine, which give the same problems in the same words. The same step adds the read-only `RuleRepository` interface, a repository over a directory or Git checkout, and a helper that reloads an engine when a newer release appears.

- [Example](#example)
- [The release](#the-release)
- [What the engine checks](#what-the-engine-checks)
- [Problems](#problems)
- [Repositories](#repositories)
- [Reloading](#reloading)
- [Issuing releases](#issuing-releases)
- [Limits](#limits)

## Example

The rules directory holds the content and the approvals as for [approvals](approvals.md#approvals-in-files), and one signed release per target:

```
rules/
  loan_eligibility.v3.stone.json
  loan_eligibility.v4.stone.json
  loan_eligibility.v5.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
  releases/
    retail-credit-prod.release.jws
```

A **target** is one deployed application or decision service; each has its own releases, so that different applications can run different sets of approved versions. The engine reads the target's release from a repository and checks it:

```java
RuleRepository repository = FileRuleRepository.of(Path.of("rules"));

RuleStoneEngine engine = RuleStone.builder()
        .snapshot(repository.load("retail-credit-prod"))   // the release, the versions it lists, the approvals
        .requireApproved(verifier)                         // every version approved; the release signed
        .requireRelease("retail-credit-prod")              // exactly the listed versions
        .build();
```

In TypeScript:

```ts
import { createEngine } from '@dxa/rulestone-engine'

const engine = await createEngine({
  snapshot: await repository.load('retail-credit-prod'),
  requireApproved: verifier,
  requireRelease: 'retail-credit-prod',
})
```

Content can also be registered one by one, as before, with the release given as text: `.release(name, jws)` in Java, `release: { name, jws }` in TypeScript. An engine built without `requireRelease` ignores a release, as one without `requireApproved` ignores approvals.

## The release

```json
{
  "type": "rulestone.release",
  "target": "retail-credit-prod",
  "sequence": 118,
  "previous": "sha256:c41d…",
  "artifacts": [
    { "kind": "table", "id": "loan_eligibility", "version": "3@sha256:0a7e…", "state": "RETIRED" },
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…", "state": "ACTIVE" },
    { "kind": "table", "id": "loan_eligibility", "version": "5@sha256:2b9e…", "state": "ACTIVE" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…", "state": "ACTIVE" },
    { "kind": "catalog", "id": "reason-codes", "version": "5@sha256:fccd…", "state": "ACTIVE" }
  ],
  "routing": [
    { "decision": "loan_eligibility", "kind": "SHADOW", "version": "5@sha256:2b9e…" }
  ],
  "issuedAt": "2026-11-20T07:45:00Z"
}
```

| Field | Required | Content |
|---|---|---|
| `type` | yes | Always `rulestone.release`. |
| `target` | yes | The application whose engines load the release. |
| `sequence` | yes | The release's number for its target: a whole number from 1 to 2<sup>53</sup> − 1, growing with every release. |
| `previous` | after the first | `sha256:<hex>` of the canonical form of release `sequence − 1`, so that releases form a chain. Absent exactly in release 1. |
| `artifacts` | yes, at least one | The exact versions the engines load: `kind`, `id`, `version` as `<label>@sha256:<64 hex digits>`, and `state`. Each label of an artifact is listed once, and at most one reason-code catalog, because an engine has one. |
| `routing` | no | Canary and shadow versions: `decision` (a table or flow id), `kind` (`CANARY` or `SHADOW`), the exact `version`, and for a canary its `share` of the traffic, above 0 and at most 1 with at most 6 decimals, as [`canary`](java-api.md#canary-and-shadow-versions) takes it. At most one canary and one shadow per decision. Absent when there is none. |
| `issuedAt` | yes | When it was signed, a UTC instant. |

**States.** Both states are loaded. `ACTIVE` versions are the current ones: a version can be active weeks before its `effectiveFrom`, and effective dates still decide, per request, which loaded version applies. `RETIRED` versions can no longer be in force from today on, but stay loaded, so that back-dated requests and replays still select them and so that removing one does not extend its predecessor (see [versions and effective dates](decision-tables.md#versions-and-effective-dates)). Only an `ACTIVE` version can be routed to.

**Canary and shadow versions are governed.** A routed version is listed and loaded like any other, so it must be approved; routing traffic to a version is part of the signed release, not of the application's code.

**Signature.** A release is signed exactly like an approval statement (see [The signature](approvals.md#the-signature)): JWS compact serialization whose header is exactly `alg`, `kid` and `typ`, with `typ` `rulestone-release+jws`, signed with `EdDSA` or `ES256` by a key the verifier trusts, and whose payload is the release in canonical JSON: compact, keys sorted, `previous` and `routing` omitted when absent, a share written as a plain decimal such as `0.05` or `1`. The typ keeps a statement from being taken for a release and the other way round. The same `ApprovalVerifier` verifies both: `verifyRelease(jws)` in both engines. `ReleaseJson` in Java, and `parseRelease`, `writeRelease` and `releaseHash` in TypeScript, read and write releases; the hash is the `previous` of the next release.

## What the engine checks

When it is built, an engine with `requireApproved` and `requireRelease(target)`:

1. checks the approvals of every registered version as [before](approvals.md#what-the-engine-checks);
2. **verifies the release**: header, signature and canonical payload, with the verifier's keys. If it does not verify, nothing else of it is checked;
3. checks that it is a release **of its target**;
4. if it was given the **last accepted release**, the one its predecessor ran or the one a cache kept (`lastAcceptedRelease` in both engines), refuses a release with a **lower sequence** (a rollback), a release with the **same sequence but other content**, and, when the release directly follows the accepted one, a `previous` that is not the accepted release's hash. A release several numbers ahead is accepted: an engine may miss releases;
5. checks that the release lists **exactly the registered versions**: every registered table, flow, parameter set and catalog at the exact version the engine computed, and every listed version registered. A file that is missing, added or changed is a problem;
6. checks the **routing**: each canary or shadow version is a listed `ACTIVE` table or flow version. The engine then routes to them as with `canary` and `shadow` in Java, which cannot be called on the builder together with `requireRelease`.

`requireRelease` needs `requireApproved`, whose keys verify the release. As for approvals, every check happens once, when the engine is built.

## Problems

Release problems come after every other problem of the definitions, in one `InvalidDecisionException` in Java and one `InvalidDecisionError` in TypeScript. They name the release by its file name when it has one, versions and hashes, never rule content or input values:

```text
invalid decision definition (3 problems):
  - loan_eligibility@5: no approval covers 5@sha256:4884…
  - retail_credit@2 (parameter set): release 117 lists 2@sha256:65c6…, which is not loaded
  - release retail-credit-prod.release.jws: shadow loan_eligibility@3 is RETIRED; only an ACTIVE version can be routed to
```

| Problem | Meaning |
|---|---|
| `release: required for target 't', but none is registered` | No release was given. |
| `release <name>: <signature problem>` | The release does not verify; the problems are those of [approvals](approvals.md#problems), with `typ` `rulestone-release+jws`, and those of its fields, such as `$.previous: required after the first release: the hash of release 117`. |
| `release <name>: target is 'a', but this engine loads 'b'` | A release of another application. |
| `release <name>: sequence 117 is older than release 118, which this engine already accepted` | A rollback. |
| `release <name>: sequence 118 was already accepted with other content` | Two different releases with one number. |
| `release <name>: previous is sha256:…, but release 117 accepted before is sha256:…` | The chain is broken. |
| `<artifact>: <exact version> is not in release 118` | A loaded version the release does not list; if it lists the label with other content, that content is shown. |
| `<artifact>: release 118 lists <exact version>, which is not loaded` | A listed version is missing, such as a retired version whose file was deleted. |
| `release <name>: canary <decision>@<label> is not listed: …` | Routing to a version the release does not list. |
| `release <name>: shadow <decision>@<label> is RETIRED; only an ACTIVE version can be routed to` | Routing to a retired version. |

## Repositories

`RuleRepository`, in `rulestone-api`, is the read-only interface an engine loads from. It returns raw content and signed text, never parsed objects or trusted hashes, so that the engine computes every hash and verifies every signature itself; a repository is not trusted.

| Method | Returns |
|---|---|
| `load(target)` | A `Snapshot`: the target's signed release, the content of every version it lists, and the approval statements. |
| `find(kind, id, exactVersion)` | One exact version, for replay, also a retired one or one not in the current release. |
| `latestSequence(target)` | The sequence of the current release as the repository claims it, to decide cheaply whether to load; by default it loads the snapshot. |
| `watch(target, listener)` | Notifies newer releases, if the repository can; by default it cannot, and the caller polls. |

**Files and Git.** `FileRuleRepository.of(dir)` in `rulestone-core` reads the layout of the [example](#example): tables, flows and parameter sets anywhere under the directory, named as you like; the catalog `reason-codes.json`, named `reason-codes`, or `<id>.reason-codes.json`, named `<id>`, so that several catalogs can be governed side by side; every `approvals/*.approval.jws`; and `releases/<target>.release.jws`. It reads the release's list without trusting it to select the files whose `id` and `version` it names, and leaves out files that are not listed or not valid JSON, and directories whose name starts with a dot, such as `.git`. For Git, point it at a checkout that a deployment job or the application keeps up to date; it uses no Git library. A pull request then carries the new content, the approval and the new release, and the engine refuses whatever the signatures do not cover, even if branch protection fails.

**TypeScript.** `RuleRepository` is the same interface with promises, so that a repository can be read over HTTP from a browser, an edge worker or Node.js. `InMemoryRuleRepository` holds snapshots, for tests and for content that arrives some other way. A repository over HTTP takes a few lines:

```ts
const repository: RuleRepository = {
  async load(target) {
    const response = await fetch(`https://rules.bank.example/targets/${target}/snapshot`)
    return (await response.json()) as RepositorySnapshot      // verified by the engine, not trusted
  },
  async find() {
    return undefined
  },
}
```

## Reloading

`RepositoryReloader` builds an engine from the current release of a target, and builds a new one whenever a newer release appears, swapping it in only if it builds. A release that fails verification or validation is never swapped in; the previous engine keeps running and the failure is reported.

```java
AtomicReference<RuleStoneEngine> engine = new AtomicReference<>();
RepositoryReloader reloader = RepositoryReloader.builder(repository, "retail-credit-prod", verifier)
        .engine(builder -> builder.auditSink(auditSink).clock(clock))       // everything but content
        .cache(Path.of("/var/lib/loans/retail-credit-prod.release.jws"))   // the last release accepted
        .onReload(engine::set)
        .onFailure((sequence, e) -> log.error("release {} not loaded", sequence, e))
        .build();
reloader.reloadIfNewer();                                     // at start-up
reloader.pollEvery(Duration.ofSeconds(30), scheduler);        // or call reloadIfNewer() yourself
```

```ts
const reloader = new RepositoryReloader({
  repository, target: 'retail-credit-prod', verifier,
  engine: { auditSinks: [sink], timeZone: 'Asia/Ho_Chi_Minh' },
  store,                                                      // a ReleaseStore: the last release accepted
  onReload: (engine) => { current = engine },
  onFailure: (sequence, error) => console.error(`release ${sequence} not loaded`, error),
})
await reloader.reloadIfNewer()
reloader.pollEvery(30_000)
```

- `reloadIfNewer()` asks the repository for the latest sequence and, if it is newer than the running release or nothing runs yet, loads the snapshot and builds an engine with `requireApproved`, `requireRelease` and, as the last accepted release, the running one. It returns whether a new engine was swapped in. Reloads never run concurrently.
- The reloader starts no thread or timer of its own. `pollEvery` schedules `reloadIfNewer` on a `ScheduledExecutorService` you give it in Java, or with `setInterval` in TypeScript; `watch()` reloads on the repository's notifications; `close()` and `stop()` end both.
- **The cache refuses rollbacks after a restart.** The last release accepted is kept, signed: in a file in Java, written atomically, or in a `ReleaseStore` in TypeScript (`MemoryReleaseStore`, or your own over `localStorage`, IndexedDB or a key-value store). On start-up it is verified and used as the last accepted release, so a repository rolled back to an older release is refused even by a fresh process. A cache that does not verify is an error, not a reason to accept any release.

## Issuing releases

`ApprovalIssuer` in `rulestone-test` issues releases for tests and CI jobs, with the keys of approval statements:

```java
Release release = ApprovalIssuer.release("retail-credit-prod", 118)
        .previous(release117)                    // or .previous("sha256:…")
        .retired(loanEligibilityV3)
        .active(loanEligibilityV4)
        .active(loanEligibilityV5)
        .active(retailCreditV8)
        .active(catalog)
        .shadow(loanEligibilityV5)               // or .canary(loanEligibilityV5, 0.05)
        .build();
String jws = issuer.issue(release);
```

In TypeScript, `issuer.issueRelease(release)` signs a `Release` object, and `releaseEntries({ tables, flows, paramSets, reasonCodeCatalog }, state)` from `@dxa/rulestone-engine/testing` lists exact versions for it.

Before a governance service exists, the CI job that [issues approval statements](approvals.md#issuing-statements-from-ci) can also issue the release: after a pull request is approved, it lists the versions of the target with their states, numbers the release one above the current one, names its hash as `previous`, signs it and commits `releases/<target>.release.jws`.

## Limits

- **No content cache.** The reloader caches the release, not the content, so an engine still needs the repository at start-up. A Git checkout or a directory is local; a repository over the network that must survive outages at start-up is for the JDBC and HTTP repositories of later steps.
- **The TypeScript engine does not route.** It verifies a release's routing exactly as the Java engine does, but it offers no canary and shadow versions, so it answers every request with the version in force.
- **The engine checks the chain only when it knows the predecessor.** A release several numbers ahead of the last accepted one is accepted; checking the whole chain is the governance log's `verify` tool, in a later step.
- **One release per engine.** A target is one deployed application; an engine that needs two sets of rules is two targets.
