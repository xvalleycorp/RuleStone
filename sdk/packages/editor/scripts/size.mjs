// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Prints the size of each entry point bundled for browsers, minified and minified + gzipped:
// with its dependencies (Lit and @rulestone/engine), and the editor's own code alone.

import { build } from 'esbuild'
import { gzipSync } from 'node:zlib'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const engine = join(root, '..', 'engine', 'src', 'index.ts')
const entries = {
  '@rulestone/editor (all elements)': 'src/index.ts',
  './table-editor': 'src/elements/table-editor.ts',
  './rules-grid': 'src/elements/rules-grid.ts',
  './condition-builder': 'src/elements/condition-builder.ts',
  './schema-designer': 'src/elements/schema-designer.ts',
  './flow-canvas': 'src/elements/flow-canvas.ts',
  './try-it': 'src/elements/try-it.ts',
  './cel-input': 'src/elements/cel-input.ts',
}

async function size(entry, external) {
  const result = await build({
    entryPoints: [join(root, entry)],
    bundle: true,
    minify: true,
    format: 'esm',
    target: 'es2022',
    platform: 'browser',
    write: false,
    ...(external.length === 0 ? { alias: { '@rulestone/engine': engine } } : {}),
    external,
    // Lit's production build, which bundlers select by default.
    conditions: ['production'],
    logLevel: 'error',
  })
  const code = result.outputFiles[0].contents
  return { min: code.length, gz: gzipSync(code, { level: 9 }).length }
}

const kib = (n) => `${(n / 1024).toFixed(1)} KiB`
console.log('| Entry point | With Lit and the engine | Editor code only |')
console.log('|---|---|---|')
for (const [name, entry] of Object.entries(entries)) {
  const full = await size(entry, [])
  const own = await size(entry, ['lit', 'lit/*', '@rulestone/engine'])
  console.log(`| \`${name}\` | ${kib(full.min)} min, ${kib(full.gz)} gzip | ${kib(own.min)} min, ${kib(own.gz)} gzip |`)
}
