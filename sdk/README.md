# RuleStone browser SDK

JavaScript packages for running and editing RuleStone decision tables in the browser, with no Java server. They are an npm workspace:

| Package | Content |
|---|---|
| [`packages/engine`](packages/engine) (`@rulestone/engine`, provisional name) | The rules engine: `.stone.json` reader, type-checked CEL with exact decimal, money and date types, versioned evaluation and decision records. It gives the same results as the Java engine on everything the conformance suite covers. |
| [`packages/editor`](packages/editor) (`@rulestone/editor`, provisional name) | Embeddable visual editors as Web Components: the decision table editor, rules grid, condition builder, schema designer, decision-flow canvas (design only) and a Try it panel that runs rules with the engine. |

Planned: the website sandbox that runs and edits tables in the browser.

The Java engine remains the engine of record. The browser engine is for instant feedback. Its conformance suite runs every example table, every example test case and the documented expressions against fixtures written by the Java engine. See the [engine README](packages/engine/README.md#conformance).

## Working on the SDK

Node.js 22 or later (CI uses Node 24):

```sh
cd sdk
npm ci
npm run licenses     # every npm package must be Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause or ISC
npm run typecheck
npm run build
npm test
```

The packages are licensed under Apache-2.0, like the rest of RuleStone. Their source files carry the same SPDX header as the Java sources. Dependencies are pinned to exact versions in `package-lock.json`. The `vite` override keeps the test tooling on a version whose dependencies all meet the license allowlist.
