// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Adds a Content-Security-Policy to the built site's _headers (src/.vitepress/dist), after
// `vitepress build`. VitePress inlines a few small scripts in every page (dark mode, the
// asset hash map), and their content changes between builds, so the policy allows each one
// by its SHA-256 hash instead of 'unsafe-inline'. Styles need 'unsafe-inline': VitePress
// renders many style attributes.
// Exits with status 1 if the build has no "/*" header block to add the policy to.

import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dist = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', '.vitepress', 'dist');
if (!existsSync(dist)) {
  console.error(`No build in ${dist}; run npm run build first.`);
  process.exit(2);
}

const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });

const hashes = new Set();
for (const page of walk(dist).filter((f) => f.endsWith('.html'))) {
  for (const [, body] of readFileSync(page, 'utf8').matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)) {
    hashes.add(`'sha256-${createHash('sha256').update(body).digest('base64')}'`);
  }
}

const policy = [
  "default-src 'self'",
  `script-src 'self' ${[...hashes].sort().join(' ')}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');

const file = join(dist, '_headers');
const headers = readFileSync(file, 'utf8');
if (!/^\/\*\s*$/m.test(headers)) {
  console.error(`${file} has no "/*" block for the Content-Security-Policy.`);
  process.exit(1);
}
writeFileSync(file, headers.replace(/^\/\*\s*$/m, `/*\n  Content-Security-Policy: ${policy}`));
console.log(`Content-Security-Policy added to _headers, with ${hashes.size} inline script hashes.`);
