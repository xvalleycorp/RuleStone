// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Prints the state of every translation: up-to-date, outdated (the English source changed
// since the translation was stamped), invalid (it would fail the build) or missing.
//
//   npm run i18n:status               # report only
//   npm run i18n:status -- --strict   # exit with status 1 unless everything is up to date

import { LOCALES, englishDocs, translations } from './i18n-lib.mjs';

const strict = process.argv.includes('--strict');
let clean = true;

for (const code of Object.keys(LOCALES)) {
  const all = translations(code);
  const bySource = new Map(all.map((t) => [t.englishPath, t]));
  const english = [...englishDocs().map((f) => `docs/${f}`), 'website/src/index.md'];
  const rows = english.map((path) => {
    const t = bySource.get(path);
    return { status: t ? t.status : 'missing', path, t };
  });
  // Translations whose source does not exist are listed too.
  for (const t of all) if (!english.includes(t.englishPath)) rows.push({ status: t.status, path: t.englishPath ?? '?', t });

  console.log(`\n${code} (${LOCALES[code].lang}) translations\n`);
  const width = Math.max(...rows.map((r) => r.path.length));
  for (const { status, path, t } of rows) {
    console.log(`  ${status.padEnd(10)}  ${path.padEnd(width)}${t ? `  ->  ${t.path}` : ''}`);
    for (const p of t?.problems ?? []) console.log(`              ${p}`);
  }
  const count = (s) => rows.filter((r) => r.status === s).length;
  console.log(
    `\n  ${count('up-to-date')} up-to-date, ${count('outdated')} outdated, ${count('invalid')} invalid, ${count('missing')} missing`,
  );
  if (count('outdated') || count('invalid')) {
    clean = false;
    console.log('  After checking a translation against its English source, run: npm run i18n:stamp -- <file>');
  }
}

if (strict && !clean) process.exit(1);
