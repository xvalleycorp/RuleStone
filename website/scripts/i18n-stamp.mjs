// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Records that a translation matches the current English source, by writing the source's
// SHA-256 into the translation's `sourceHash` front matter. Run it only after re-reading the
// English changes and updating the translation.
//
//   npm run i18n:stamp -- getting-started.md      # docs/vi/getting-started.md
//   npm run i18n:stamp -- index.md                # docs/vi/index.md (source: docs/README.md)
//   npm run i18n:stamp -- home                    # website/src/vi/index.md (the home page)
//   npm run i18n:stamp -- ../docs/vi/architecture.md
//   npm run i18n:stamp -- --locale vi getting-started.md
//
// A translation whose code blocks differ from the English source is not stamped.

import { writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { LOCALES, ROOT, setFrontMatterValue, translations } from './i18n-lib.mjs';

const args = process.argv.slice(2);
let code = 'vi';
const names = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--locale') code = args[++i];
  else names.push(args[i]);
}
if (!LOCALES[code]) {
  console.error(`Unknown locale "${code}". Known: ${Object.keys(LOCALES).join(', ')}`);
  process.exit(2);
}
if (!names.length) {
  console.error('Usage: npm run i18n:stamp -- [--locale vi] <file>...   (e.g. getting-started.md, index.md, home)');
  process.exit(2);
}

const all = translations(code);
// npm runs scripts from website/; INIT_CWD is where `npm run` was typed.
const cwd = process.env.INIT_CWD ?? process.cwd();
let failed = false;
for (const name of names) {
  const asPath = relative(ROOT, resolve(cwd, name)).split('\\').join('/');
  const t =
    all.find((x) => x.path === asPath || x.path === name) ??
    (name === 'home' ? all.find((x) => x.kind === 'site' && x.file === 'index.md') : undefined) ??
    all.find((x) => x.kind === 'docs' && x.file === name) ??
    all.find((x) => x.kind === 'docs' && x.source === name);
  if (!t) {
    console.error(`${name}: no ${code} translation found. Translations: ${all.map((x) => x.path).join(', ')}`);
    failed = true;
    continue;
  }
  if (!t.currentHash || t.problems.some((p) => p.startsWith('code block') || p.startsWith('its source') || p.startsWith('must be named'))) {
    console.error(`${t.path}: not stamped, fix first:\n  ${t.problems.join('\n  ')}`);
    failed = true;
    continue;
  }
  if (t.sourceHash === t.currentHash) {
    console.log(`${t.path}: already up to date with ${t.englishPath}`);
    continue;
  }
  writeFileSync(join(ROOT, t.path), setFrontMatterValue(t.markdown, 'sourceHash', t.currentHash));
  console.log(`${t.path}: stamped sourceHash ${t.currentHash} (${t.englishPath})`);
}
process.exit(failed ? 1 : 0);
