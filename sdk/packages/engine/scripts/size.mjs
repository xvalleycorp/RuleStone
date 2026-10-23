// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Prints the size of the engine bundled for browsers: minified, and minified + gzipped.

import { build } from 'esbuild'
import { gzipSync } from 'node:zlib'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const result = await build({
  entryPoints: [join(root, 'src', 'index.ts')],
  bundle: true,
  minify: true,
  format: 'esm',
  target: 'es2022',
  platform: 'browser',
  write: false,
})
const code = result.outputFiles[0].contents
const kib = (n) => `${(n / 1024).toFixed(1)} KiB`
console.log(`@rulestone/engine: ${kib(code.length)} minified, ${kib(gzipSync(code, { level: 9 }).length)} minified + gzipped`)
