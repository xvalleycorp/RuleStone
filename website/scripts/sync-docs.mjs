// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Copies the guides from ../docs into the site, so the repository's docs/ folder stays
// the single source of truth. Links that point outside docs/ (examples, CONTRIBUTING.md,
// LICENSE, ...) are rewritten to their GitHub URLs; docs/README.md becomes the docs index.

import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = 'https://github.com/xvalleycorp/RuleStone';
const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, '..', '..', 'docs');
const target = join(here, '..', 'src', 'docs');

function rewriteLink(link) {
  if (/^[a-z]+:/i.test(link)) {
    return link;
  }
  const [path, anchor] = link.split('#');
  // GitHub slugs "## 9. Roadmap" as "9-roadmap"; VitePress as "_9-roadmap".
  const vitepressAnchor = anchor && /^\d/.test(anchor) ? `_${anchor}` : anchor;
  const hash = anchor ? `#${path.startsWith('../') ? anchor : vitepressAnchor}` : '';
  if (path === 'README.md') {
    return `./${hash}`;
  }
  if (path.startsWith('../')) {
    const repoPath = path.slice(3);
    const kind = repoPath === '' || repoPath.endsWith('/') ? 'tree' : 'blob';
    return `${REPO}/${kind}/main/${repoPath}${hash}`;
  }
  return path + hash;
}

function rewrite(markdown) {
  // Leave fenced code blocks untouched.
  return markdown
    .split(/(^```[\s\S]*?^```)/m)
    .map((part, i) => (i % 2 === 1 ? part : part.replace(/\]\(([^)\s]+)\)/g, (_, link) => `](${rewriteLink(link)})`)))
    .join('');
}

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });

const files = (await readdir(source)).filter((f) => f.endsWith('.md'));
for (const file of files) {
  const markdown = await readFile(join(source, file), 'utf8');
  const name = file === 'README.md' ? 'index.md' : file;
  const banner = `<!-- Generated from docs/${file} by website/scripts/sync-docs.mjs. Edit the original. -->\n\n`;
  await writeFile(join(target, name), banner + rewrite(markdown));
}
console.log(`Synced ${files.length} pages from docs/ to website/src/docs/`);
