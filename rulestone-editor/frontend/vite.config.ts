// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// `npm run dev` serves the UI on :5173 and forwards /api to an editor started with
// `java -jar rulestone-editor-*-all.jar --rules-dir ... --port 8080 --allowed-host localhost:5173`.
export default defineConfig({
  plugins: [vue()],
  // Relative asset URLs, so the editor also works behind a reverse proxy under a sub-path.
  base: './',
  build: {
    outDir: 'dist',
    // No inline scripts or eval: the server's Content-Security-Policy is script-src 'self'.
    modulePreload: { polyfill: false },
    assetsInlineLimit: 0,
    sourcemap: false,
    chunkSizeWarningLimit: 1024,
  },
  server: {
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
