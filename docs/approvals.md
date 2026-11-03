# Approvals

An engine built with `requireApproved` loads only content that someone other than its authors approved. An approval is a signed **approval statement** that names the exact content hash of every table, flow, parameter set and reason-code catalog it covers. The engine computes every hash itself from the content it loads, verifies the signature with a key from its own configuration, and refuses to build if anything is not covered, if an approver is also a maker, or if too few approvers are named.

This is step 1 of the [governance design](governance.md#11-implementation-plan). It is available in the Java engine and in the TypeScript engine, which give the same problems in the same words. Releases, the repository interface and the governance service follow in later steps; until then, a CI job can issue the statements (see [Issuing statements from CI](#issuing-statements-from-ci)).

- [Example](#example)
- [What the engine checks](#what-the-engine-checks)
- [The approval statement](#the-approval-statement)
- [The signature](#the-signature)
- [Keys](#keys)
- [Approvals in files](#approvals-in-files)
- [Problems](#problems)
- [Issuing statements in tests](#issuing-statements-in-tests)
- [Issuing statements from CI](#issuing-statements-from-ci)
- [Limits](#limits)

## Example

The rules directory holds the content as today, and the signed statements next to it:

```
rules/
  loan_eligibility.v4.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
```

The engine reads the statements and is told whose signatures to trust:

```java
PublicKey governanceKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicKeyBase64)));

RuleStoneEngine engine = RuleStone.builder()
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v8.params.json")))
        .reasonCodeCatalog(ReasonCodeCatalogJson.read(Path.of("rules/reason-codes.json")))
        .approvals(Path.of("rules/approvals"))             // every *.approval.jws file
        .requireApproved(ApprovalVerifier.builder()
                .trustedKey("gov-2026", governanceKey)      // the signing key's id and public key
                .minApprovers(1)                            // a floor the engine checks itself
                .build())
        .build();
```

In TypeScript, `createEngine` takes the statements' text and the verifier:

```ts
import { ApprovalVerifier, createEngine } from '@dxa/rulestone-engine'

const governanceKey = await crypto.subtle.importKey('spki', publicKeyBytes, { name: 'Ed25519' }, false, ['verify'])

const engine = await createEngine({
  tables, paramSets, reasonCodeCatalog,
  approvals: [{ name: 'CHG-2026-0042.approval.jws', jws: approvalText }],
  requireApproved: new ApprovalVerifier({ trustedKeys: { 'gov-2026': governanceKey }, minApprovers: 1 }),
})
```

If a single character of `loan_eligibility.v4.stone.json` changes after approval, `build()` and `createEngine` fail:

```text
invalid decision definition (1 problem):
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
```

An engine built without `requireApproved` ignores approvals and loads any content, as before, for development and tests.

## What the engine checks

When it is built, an engine with `requireApproved`:

1. **verifies every statement given to it**: the header, the signature with a trusted key and an allowed algorithm, and the payload, which must be a valid statement in canonical form. A statement that does not verify is a problem even if other statements cover everything, because a tampered or foreign file in the approvals directory is worth knowing about;
2. **computes the exact version** `<label>@sha256:<hash>` of every registered table, flow, parameter set and reason-code catalog from its canonical form, as decision records name it;
3. **requires each one to be covered** by a verified statement with a subject of the same kind, id and exact version. A catalog has no id of its own and is named `reason-codes`;
4. **checks four-eyes and the minimum** for each covering statement: no approver is one of its `makers`, and it names at least `minApprovers` approvers. An artifact is accepted when at least one statement that covers it passes both checks.

The engine does not check roles, tiers or organisational units: they are the governance policy's business, which the issuer of the statement applies and attests with its signature. All checks happen once, when the engine is built; evaluating a decision verifies nothing and reads no file.

## The approval statement

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

| Field | Required | Content |
|---|---|---|
| `type` | yes | Always `rulestone.approval`. |
| `approvalId` | yes | The statement's unique id, such as a UUID. |
| `changeSet` | yes | The change approved. Problems name the statement by it. |
| `subjects` | yes, at least one | The exact versions approved: `kind` (`table`, `flow`, `params` or `catalog`), `id`, and `version` as `<label>@sha256:<64 hex digits>`. |
| `makers` | yes, at least one | Every principal who wrote or submitted the change. |
| `approvers` | yes, at least one | Each a distinct `principal`, with an optional `role` and the instant `at` which they approved. |
| `policy` | no | The governance policy applied. |
| `emergency` | yes | `true` for an [emergency change](governance.md#35-emergency-changes), which then has a `postApprovalDue` instant. |
| `issuer` | yes | Who signed: the governance service, or a CI job. |
| `issuedAt` | yes | When it was signed. |
| `logEntry` | no | `sha256:<hex>` of the governance log entry that issued it, once a governance service exists. |

Instants are UTC, written as `2026-11-20T07:41:02Z`, optionally with a fraction of a second. Reading is strict: unknown and duplicate keys are errors. The statement holds principal ids, versions and hashes, never rule content or customer data. Both engines read and write statements: `ApprovalStatementJson` in Java, `parseApprovalStatement` and `writeApprovalStatement` in TypeScript.

## The signature

A signed statement is a JWS in compact serialization ([RFC 7515](https://www.rfc-editor.org/rfc/rfc7515)): three base64url parts separated by dots, `header.payload.signature`.

- The **header** is exactly `{"alg":"EdDSA","kid":"gov-2026","typ":"rulestone-approval+jws"}`: an algorithm, the id of the signing key, and the type. Any other header parameter is refused, so keys can never come from the statement (`jwk`, `jku`, `x5u`, `x5c`), and `crit` cannot ask for extensions the engine does not know.
- The **payload** is the statement in RuleStone's canonical JSON: compact, keys sorted, absent optional fields omitted, the same form that content hashes are computed from. A payload that parses but is not in exactly this form is refused, so that one approval has one text.
- The **signature** is computed over `header.payload` with one of two algorithms:

| `alg` | Key | Signature |
|---|---|---|
| `EdDSA` | Ed25519 | 64 bytes |
| `ES256` | ECDSA on P-256 with SHA-256 | 64 bytes, R and S of 32 bytes each, as JWS writes them |

`alg: none`, HMAC, RSA and every other algorithm are refused. A verifier can allow fewer: `allowedAlgorithms("EdDSA")` in Java, `allowedAlgorithms: ['EdDSA']` in TypeScript. Both engines use only their platform's cryptography: the JDK in Java, WebCrypto in TypeScript, with no JOSE library.

## Keys

The engine trusts the public keys it is configured with, each under the `kid` that statements name:

| Engine | Key |
|---|---|
| Java | `ApprovalVerifier.builder().trustedKey(kid, publicKey)`, with an Ed25519 `PublicKey` or an EC `PublicKey` on P-256. Any other key is refused when the verifier is built. |
| TypeScript | `new ApprovalVerifier({ trustedKeys: { [kid]: key } })`, with a `CryptoKey` imported for `Ed25519`, or for `ECDSA` with `namedCurve: 'P-256'`, with the `verify` usage. |

A key pair can be made with OpenSSL. The private key belongs in the bank's HSM or KMS, or in the CI system's secret store; only the public key goes into the engine's configuration:

```sh
openssl genpkey -algorithm ed25519 -out approval-signing.pem
openssl pkey -in approval-signing.pem -pubout -out approval-verify.pem
```

`approval-verify.pem` holds the public key in X.509 SubjectPublicKeyInfo form, which Java reads with `X509EncodedKeySpec` and WebCrypto with `importKey('spki', …)` after removing the PEM header and footer and decoding the base64. For `ES256`, use `-algorithm EC -pkeyopt ec_paramgen_curve:P-256`.

**Rotation.** Add the new key under a new `kid` before the old one is retired, and keep the old key configured: a statement signed with it stays valid for the content it covers, because that content never changes.

## Approvals in files

| Engine | Statements |
|---|---|
| Java | `.approvals(Path dir)` reads every `*.approval.jws` file of a directory, in file name order, without subdirectories, and names each by its file name. `.approval(name, jws)` adds one statement with a name; `.approval(jws)` adds one named `approvals[i]` by its position. |
| TypeScript | `approvals` takes the statements' text, as `{ name, jws }` or as a bare string named `approvals[i]`. The engine reads no files, so that it also runs in browsers. |

Leading and trailing spaces and line breaks, such as the final newline of a file, are ignored. Statements for content the engine does not load are allowed, so an approvals directory can keep the statements of older versions.

## Problems

Problems are reported with every other problem of the definitions, in one `InvalidDecisionException` in Java and one `InvalidDecisionError` in TypeScript. First come the statements that do not verify, in the order given, then the artifacts that are not acceptably covered, in the order parameter sets, tables, flows, catalog. Problems name files, keys, versions, change sets and principals, never rule content or input values.

```text
invalid decision definition (4 problems):
  - approval CHG-2026-0041.approval.jws: signature does not verify with key 'gov-2026'
  - retail_credit@8 (parameter set): approval CHG-2026-0042 names approver u:tran.thi.b, who is also a maker
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
  - reason-codes@5 (reason-code catalog): approval CHG-2026-0043 names 1 approver; at least 2 are required
```

| Problem | Meaning |
|---|---|
| `not a JWS compact serialization (three base64url parts separated by '.')` | The text is not a signed statement. |
| `header is not a JSON object` | The first part does not decode to a JSON object. |
| `header parameter 'jwk' is not allowed` | The header has a parameter other than `alg`, `kid` and `typ`. |
| `header typ must be 'rulestone-approval+jws'` | The type is missing or different. |
| `header alg is required`, `header kid is required` | A required header parameter is missing. |
| `algorithm 'none' is not allowed (allowed: EdDSA, ES256)` | The algorithm is not in the verifier's allowlist. |
| `unknown key 'gov-2025'` | No trusted key has this `kid`. |
| `key 'gov-2026' is an EdDSA key, not ES256` | The header names an algorithm that the key is not for. |
| `signature does not verify with key 'gov-2026'` | The statement was altered, or signed with another key. |
| `payload is not a JSON object`, `payload is not in canonical form` | The payload is not a statement in canonical form. |
| `$.subjects[0].version: …` | A field of the statement is invalid, with its location. |
| `<artifact>: no approval covers <exact version>` | No verified statement names this content; if one names another content under the same label, it is shown. |
| `<artifact>: approval <change set> names approver <principal>, who is also a maker` | Four-eyes is not met. |
| `<artifact>: approval <change set> names 1 approver; at least 2 are required` | Fewer approvers than `minApprovers`. |

An artifact is named `id@label` for a table, followed by `(flow)`, `(parameter set)` or `(reason-code catalog)` for the other kinds.

## Issuing statements in tests

`rulestone-test` has an `ApprovalIssuer` that signs statements, so that tests can build engines with `requireApproved`:

```java
ApprovalIssuer issuer = ApprovalIssuer.generate("test-key", ApprovalVerifier.EDDSA);
String jws = issuer.issue(ApprovalIssuer.statement("CHG-1")
        .covering(table)
        .covering(parameterSet)
        .maker("u:nguyen.van.a")
        .approver("u:tran.thi.b", "RULE_CHECKER")
        .build());

RuleStoneEngine engine = RuleStone.builder()
        .decision(table)
        .paramSet(parameterSet)
        .approval(jws)
        .requireApproved(issuer.verifier())
        .build();
```

`issuer.sign(header, payload)` signs any text as it is, to test how an engine refuses a malformed statement. The TypeScript engine has the same helper in `@dxa/rulestone-engine/testing`:

```ts
import { ApprovalIssuer, approvalSubjects } from '@dxa/rulestone-engine/testing'

const issuer = await ApprovalIssuer.generate('test-key', 'EdDSA')
const jws = await issuer.issue({
  approvalId: 'a-1', changeSet: 'CHG-1', subjects: await approvalSubjects({ tables, paramSets }),
  makers: ['u:nguyen.van.a'], approvers: [{ principal: 'u:tran.thi.b', role: 'RULE_CHECKER', at: '2026-11-20T03:12:00Z' }],
  emergency: false, issuer: 'test', issuedAt: '2026-11-20T03:12:01Z',
})
const engine = await createEngine({ tables, paramSets, approvals: [jws], requireApproved: issuer.verifier() })
```

## Issuing statements from CI

Before a governance service exists, a CI job can issue the statements for a pull request: it checks that the pull request's approving reviewers are not among its commit authors and hold the right roles, then signs a statement with a key from the CI system's secret store and commits it to `rules/approvals/`. Because the engine verifies the signature, content pushed past review is refused even if branch protection fails.

```java
PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
        new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(System.getenv("APPROVAL_SIGNING_KEY"))));

ApprovalStatement statement = ApprovalIssuer.statement("PR-1234")
        .covering(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .maker("u:nguyen.van.a")                       // the pull request's commit authors
        .approver("u:tran.thi.b", "RULE_CHECKER")      // its approving reviewers
        .issuer("ci@bank.example")
        .build();

Files.writeString(Path.of("rules/approvals/PR-1234.approval.jws"), ApprovalIssuer.of("ci-2026", key).issue(statement));
```

`APPROVAL_SIGNING_KEY` holds the base64 body of the PKCS#8 private key, such as `approval-signing.pem` without its header and footer. Git mode proves four-eyes less strongly than a governance service: the job can only compare commit authors with reviewers (see [Open questions](governance.md#10-open-questions)).

## Limits

- **Completeness and rollback need releases.** `requireApproved` proves that every loaded version was approved, not that the engine loads every version it should, nor that a cancelled version is not loaded again. Signed releases with a growing sequence number close both gaps; they are step 2 of the [governance design](governance.md#38-releases).
- **One signature per statement.** The issuer, a governance service or a CI job, signs for the approvers. Signatures by each approver (JWS JSON serialization) are an option for later.
- **Canary and shadow versions** are registered versions, so they need approval like any other.
- **Emergency approvals** are checked like any other: they need an approver who is not a maker and at least `minApprovers` approvers. Their post-approval is the governance service's business.
- **The engine's configuration is the bank's control.** An application built without `requireApproved` loads unapproved content; production deployment control must require it.
