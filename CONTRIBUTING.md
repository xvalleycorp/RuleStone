# Contributing to RuleStone

Thank you for helping. RuleStone is used to make decisions about real customers' money, so we hold changes to a high bar for correctness, determinism and clarity. This guide explains what that means in practice.

## Ways to contribute

- **Report a bug.** Open an issue with the decision definition (with personal data removed), the inputs, the result you expected and the result you got. A failing test case is even better.
- **Propose a feature.** Open an issue describing the business problem first. Rule-language and file-format changes are hard to undo, so we agree on the design before code is written.
- **Improve the documentation.** Corrections and clarifications are always welcome.
- **Report a security issue.** Do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).

## Development setup

Requirements: JDK 17 or newer, Maven 3.9 or newer.

```sh
mvn verify
```

This single command compiles every module, runs all tests and performs every check that CI performs:

| Check | What fails the build |
|---|---|
| Compiler | Any `javac` warning in new code is treated as a defect to fix (`-Xlint:all`). |
| Tests | Any failing test, including the tests that run the files in [`examples/`](examples/). |
| License headers | A `.java` file or `pom.xml` without the project license header. |
| Third-party licenses | A runtime dependency without a license, or with a license outside the allowlist (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause). |
| Javadoc | Malformed Javadoc in any public type. |
| Enforcer | Maven or Java older than required, or conflicting dependency versions. |

To add missing license headers automatically:

```sh
mvn -N com.mycila:license-maven-plugin:format
```

## Making a change

1. **Discuss first** for anything beyond a small fix (see above).
2. **Write tests.** Every behaviour change needs a test that fails without it. For rule-language changes, test both the accepted form and the rejected forms, including the error message.
3. **Keep decisions deterministic.** Evaluation must not read the clock, environment, network or any other hidden state. The same rule version and the same inputs must always produce the same result.
4. **Fail loudly.** When a decision cannot be evaluated correctly, throw. Never return a default or partial result.
5. **Never log input values.** Inputs contain personal and financial data. Messages and traces may name fields, never their values.
6. **Match the surrounding code.** Follow the style, naming and comment density of the files you change.
7. **Update the documentation** in [`docs/`](docs/) and add an entry under *Unreleased* in [CHANGELOG.md](CHANGELOG.md).

### Dependencies

Every new runtime dependency is code that banks must audit. A pull request that adds one must explain why it is needed and what alternatives were considered. Its license must be on the allowlist; the build enforces this.

## Signing your work: Developer Certificate of Origin

RuleStone uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO) instead of a contributor license agreement. By signing off a commit you certify that you wrote the change or otherwise have the right to submit it under the project's license, the Apache License 2.0.

Sign off every commit by adding a `Signed-off-by` line, which `git` does for you with `-s`:

```sh
git commit -s -m "Add HALF_DOWN rounding to decimal.round"
```

This produces:

```
Signed-off-by: Your Name <your.email@example.com>
```

Use your real name. Pull requests with unsigned commits cannot be merged. If you forgot, sign off existing commits with `git rebase --signoff main` and force-push your branch.

If your employer has rights to intellectual property you create, make sure you have permission to contribute before submitting.

## Copyright and the AUTHORS file

Source files carry the header `Copyright 2026 The RuleStone Authors`. Contributors keep the copyright in their contributions and license them under Apache-2.0. If you or your organization want to be listed as a copyright holder, add an entry to [AUTHORS](AUTHORS) in your pull request.

## Pull request checklist

- [ ] `mvn verify` passes locally.
- [ ] New behaviour is covered by tests.
- [ ] Public API changes have Javadoc.
- [ ] Documentation and `CHANGELOG.md` are updated.
- [ ] Every commit is signed off (`git commit -s`).

## Code of conduct

Everyone taking part in this project is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
