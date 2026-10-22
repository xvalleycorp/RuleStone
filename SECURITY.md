# Security Policy

RuleStone evaluates business rules inside banking and financial systems. We take security reports seriously and appreciate responsible disclosure.

## Supported versions

RuleStone has not had a stable release yet. Until 1.0, security fixes are made only in the latest release.

| Version | Supported |
|---|---|
| Latest 0.x release | Yes |
| Older 0.x releases | No |

## Reporting a vulnerability

**Do not report security vulnerabilities through public issues, discussions or pull requests.**

Email **opensource@dxa.io.vn** with the subject line `[SECURITY] RuleStone: <short summary>`. Include:

- the affected version or commit;
- a description of the issue and its impact;
- steps to reproduce, ideally a minimal decision definition and input;
- any suggested fix.

Do not include real customer data in a report.

We aim to:

- acknowledge your report within **3 business days**;
- give an initial assessment within **10 business days**;
- keep you informed while we work on a fix;
- credit you in the release notes, unless you prefer to stay anonymous.

Please give us reasonable time to release a fix before disclosing the issue publicly. We will agree a disclosure date with you.

## Scope

Examples of issues we consider security vulnerabilities:

- a rule expression that can execute arbitrary code, read files, open network connections or otherwise escape the expression sandbox;
- a rule or input that can exhaust CPU or memory despite the engine's limits;
- input values leaking into exception messages, traces or logs;
- a way to make the engine select a rule version other than the one that is effective, or to alter a rule version without changing its content hash;
- a dependency with a known vulnerability that is reachable through RuleStone.

Rule *content* is not in scope: a correctly evaluated rule that encodes the wrong business policy is a bug in that rule, not in RuleStone.

## Security design

The [architecture document](docs/architecture.md) describes the security properties RuleStone is designed to provide, including the absence of scripting in rules, bounded evaluation, data masking and the third-party license and dependency policy.
