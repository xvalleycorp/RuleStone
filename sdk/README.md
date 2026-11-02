# RuleStone JavaScript SDK

JavaScript and TypeScript packages for running and editing RuleStone decisions without Java: in Node.js services, edge workers and browsers. They are an npm workspace, to be published under the `@dxa` scope:

| Package | Content |
|---|---|
| [`packages/engine`](packages/engine) (`@dxa/rulestone-engine`) | The rules engine: decision tables and flows, type-checked CEL with exact decimal, money and date types, versioned evaluation, decision records, explanations and replay. It gives the same results as the Java engine on everything the conformance suite covers. |
| [`packages/editor`](packages/editor) (`@dxa/rulestone-editor`) | Embeddable visual editors as Web Components: the decision table editor, rules grid, condition builder, schema designer, decision-flow canvas and a Try it panel that runs tables and flows with the engine. |

The [playground](https://rulestone.dxa.io.vn/playground) on the website runs these components on the example tables, and the [editor SDK guide](../docs/editor-sdk.md) explains how to embed them.

The TypeScript engine and the Java engine are equivalent implementations. The conformance suite keeps them identical: it runs every example table, flow and test case, the documented expressions and the decision records against fixtures written by the Java engine. See the [engine README](packages/engine/README.md#conformance).

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
