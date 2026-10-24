// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

// The demo runs against the sources of both packages, and reads the example tables from the
// repository's examples/ directory.
export default defineConfig({
  root: here('.'),
  base: './',
  resolve: {
    alias: { '@rulestone/engine': here('../../engine/src/index.ts') },
  },
  server: {
    fs: { allow: [here('../../../../')] },
  },
  build: {
    outDir: here('./dist'),
    emptyOutDir: true,
    target: 'es2022',
  },
})
