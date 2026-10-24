// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    // Tests run against the engine's sources, so they need no build first.
    alias: { '@rulestone/engine': fileURLToPath(new URL('../engine/src/index.ts', import.meta.url)) },
  },
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'happy-dom',
  },
})
