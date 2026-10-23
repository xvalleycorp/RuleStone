// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Copies the guides from ../docs into the site, so the repository's docs/ folder stays
// the single source of truth. Links that point outside docs/ (examples, CONTRIBUTING.md,
// LICENSE, ...) are rewritten to their GitHub URLs; docs/README.md becomes the docs index.
//
// Translations (docs/<locale>/*.md, e.g. docs/vi/) are copied to src/<locale>/docs/ after
// checks (see i18n-lib.mjs and website/README.md):
// - the build FAILS if a translation has no `source`/`sourceHash` front matter, or if its
//   fenced code blocks are not byte-identical to its English source's, in the same order;
// - it WARNS when the English source changed since the translation was stamped (the page
//   then shows a notice, see theme/TranslationNotice.vue).
// English guides without a translation get a short placeholder page in the locale, so that
// the language switcher and shared links never land on a 404.

import { existsSync, statSync } from 'node:fs';
import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  LOCALES,
  REPO_URL as REPO,
  anchorMap,
  englishDocs,
  englishUrl,
  readText,
  splitFences,
  translatedUrl,
  translations,
} from './i18n-lib.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, '..', '..', 'docs');
const siteSource = join(here, '..', 'src');
const target = join(siteSource, 'docs');

/** Applies `replace` to the targets of Markdown links outside fenced code blocks. */
function rewriteLinks(markdown, replace) {
  return splitFences(markdown)
    .map((part) => (part.code ? part.text : part.text.replace(/\]\(([^)\s]+)\)/g, (_, link) => `](${replace(link)})`)))
    .join('');
}

// ---------------------------------------------------------------------------------------
// English

// GitHub slugs "### 4.4 Reason codes" as "44-reason-codes", VitePress as "_4-4-reason-codes".
// Anchors into guides are converted with the target page's headings (anchorMap); anything
// unmatched keeps the old "_<digit>" fallback and is reported by check-site.mjs.
const englishMarkdown = new Map();

function siteAnchor(file, anchor) {
  const map = englishMarkdown.has(file) ? anchorMap(englishMarkdown.get(file)) : new Map();
  if (map.has(anchor)) {
    return map.get(anchor);
  }
  return /^\d/.test(anchor) ? `_${anchor}` : anchor;
}

function rewriteLink(link, currentFile) {
  if (/^[a-z]+:/i.test(link)) {
    return link;
  }
  const [path, anchor] = link.split('#');
  if (path.startsWith('../')) {
    const repoPath = path.slice(3);
    const kind = repoPath === '' || repoPath.endsWith('/') ? 'tree' : 'blob';
    return `${REPO}/${kind}/main/${repoPath}${anchor ? `#${anchor}` : ''}`;
  }
  const hash = anchor ? `#${siteAnchor(path === '' ? currentFile : path, anchor)}` : '';
  if (path === 'README.md') {
    return `./${hash}`;
  }
  return path + hash;
}

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });

const files = (await readdir(source)).filter((f) => f.endsWith('.md'));
for (const file of files) {
  englishMarkdown.set(file, await readFile(join(source, file), 'utf8'));
}
for (const file of files) {
  const markdown = englishMarkdown.get(file);
  const name = file === 'README.md' ? 'index.md' : file;
  const banner = `<!-- Generated from docs/${file} by website/scripts/sync-docs.mjs. Edit the original. -->\n\n`;
  await writeFile(join(target, name), banner + rewriteLinks(markdown, (link) => rewriteLink(link, file)));
}
console.log(`Synced ${files.length} pages from docs/ to website/src/docs/`);

// ---------------------------------------------------------------------------------------
// Translations

const githubUrl = (repoPath, anchor) => {
  const full = join(source, '..', repoPath);
  const kind = repoPath === '' || repoPath.endsWith('/') || (existsSync(full) && statSync(full).isDirectory()) ? 'tree' : 'blob';
  return `${REPO}/${kind}/main/${repoPath}${anchor ? `#${anchor}` : ''}`;
};

let failed = false;
for (const [code, locale] of Object.entries(LOCALES)) {
  const localeTarget = join(siteSource, code, 'docs');
  await rm(localeTarget, { recursive: true, force: true });
  await mkdir(localeTarget, { recursive: true });

  const all = translations(code);
  const outdated = all.filter((t) => t.currentHash && t.currentHash !== t.sourceHash);
  if (outdated.length) {
    console.warn(`\nWARNING: ${outdated.length} ${code} translation(s) may be outdated: their English source changed since they were checked.`);
    for (const t of outdated) console.warn(`  - ${t.path} (source: ${t.englishPath})`);
    console.warn(`  Update them, then run: npm run i18n:stamp -- <file>   (see website/README.md)\n`);
  }
  const invalid = all.filter((t) => t.status === 'invalid');
  for (const t of invalid) {
    console.error(`ERROR: ${t.path}: ${t.problems.join('\n  ')}`);
  }
  if (invalid.length) {
    failed = true;
    continue;
  }

  // English guide -> translation, for links that should stay in the locale.
  const docs = all.filter((t) => t.kind === 'docs');
  const bySource = new Map(docs.map((t) => [t.source, t]));
  const warnings = [];

  for (const t of docs) {
    const anchorFor = (markdown, anchor, where) => {
      if (!anchor) return '';
      const map = anchorMap(markdown);
      if (map.has(anchor)) return `#${map.get(anchor)}`;
      if (![...map.values()].includes(anchor)) warnings.push(`${t.path}: no heading for anchor #${anchor} in ${where}`);
      return `#${anchor}`;
    };
    // Links are written as on GitHub, relative to docs/<code>/: `architecture.md` for a
    // translated page, `../java-api.md` for an English one, `../../examples/` for other files.
    const rewrite = (link) => {
      if (/^[a-z]+:/i.test(link)) return link;
      const [path, anchor] = link.split('#');
      if (path === '') return anchorFor(t.markdown, anchor, 'this page');
      const repoPath = posix.normalize(posix.join(locale.docs.dir, path));
      let englishName;
      if (repoPath.startsWith(`${locale.docs.dir}/`)) {
        const name = repoPath.slice(locale.docs.dir.length + 1);
        const own = docs.find((d) => d.file === name);
        if (own) {
          return (name === 'index.md' ? './' : name) + anchorFor(own.markdown, anchor, own.path);
        }
        if (locale.docs.unpublished.includes(name) || !existsSync(join(source, name))) {
          return githubUrl(repoPath, anchor);
        }
        englishName = name; // a sibling link to a page that is not translated yet
      } else if (/^docs\/[^/]+\.md$/.test(repoPath)) {
        englishName = repoPath.slice('docs/'.length);
      } else {
        return githubUrl(repoPath, anchor);
      }
      const translated = bySource.get(englishName);
      if (translated) {
        const name = translated.file;
        return (name === 'index.md' ? './' : name) + anchorFor(translated.markdown, anchor, translated.path);
      }
      if (!existsSync(join(source, englishName))) return link; // let VitePress report the dead link
      return englishUrl(`docs/${englishName}`) + anchorFor(readText(`docs/${englishName}`), anchor, `docs/${englishName}`);
    };
    const markdown = rewriteLinks(t.markdown, rewrite);
    const banner = `\n<!-- Generated from ${t.path} by website/scripts/sync-docs.mjs. Edit the original. -->\n`;
    const frontMatter = /^---\r?\n[\s\S]*?\r?\n---\r?\n/.exec(markdown)[0];
    await writeFile(join(localeTarget, t.file), frontMatter + banner + markdown.slice(frontMatter.length));
  }

  // Placeholders for English guides that are not translated yet.
  const placeholders = englishDocs().filter((f) => !bySource.has(f));
  for (const file of placeholders) {
    const english = readText(`docs/${file}`);
    const title = locale.titles?.[file] ?? /^# (.+)$/m.exec(english)?.[1] ?? file;
    const name = file === 'README.md' ? 'index.md' : file;
    await writeFile(join(localeTarget, name), locale.placeholder({ title, englishUrl: englishUrl(`docs/${file}`), file }));
  }

  for (const w of warnings) console.warn(`WARNING: ${w}`);
  const summary = all.map((t) => `${translatedUrl(code, t)} (${t.status})`).join(', ');
  console.log(`Synced ${docs.length} ${code} translations and ${placeholders.length} placeholders to website/src/${code}/docs/; pages: ${summary}`);
}

if (failed) {
  console.error('\nTranslation checks failed (see website/README.md, "Translations").');
  process.exit(1);
}
