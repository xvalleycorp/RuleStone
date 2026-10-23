// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Checks the built site (src/.vitepress/dist), after `vitepress build`:
// - every internal link resolves to a page, and every #anchor to an element id on it
//   (VitePress itself only checks that linked pages exist);
// - <html lang> matches the locale of each page;
// - the language switcher of each translated or placeholder page leads to its counterpart.
// Exits with status 1 and lists the problems otherwise.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, posix, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LOCALES } from './i18n-lib.mjs';

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

/** Site URL of a built HTML file: docs/index.html -> /docs/, docs/x.html -> /docs/x. */
const urlOf = (file) => '/' + relative(dist, file).split('\\').join('/').replace(/(^|\/)index\.html$/, '$1').replace(/\.html$/, '');

/** Built file for a site URL, or null. */
function fileFor(url) {
  const path = decodeURIComponent(url).replace(/^\//, '');
  const candidates = path === '' || path.endsWith('/') ? [`${path}index.html`] : [`${path}.html`, path, `${path}/index.html`];
  return candidates.map((c) => join(dist, c)).find((c) => existsSync(c) && statSync(c).isFile()) ?? null;
}

const pages = walk(dist).filter((f) => f.endsWith('.html'));
const html = new Map(pages.map((f) => [f, readFileSync(f, 'utf8')]));
const idsCache = new Map();
const idsOf = (file) => {
  if (!idsCache.has(file)) {
    const text = html.get(file) ?? readFileSync(file, 'utf8');
    idsCache.set(file, new Set([...text.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  return idsCache.get(file);
};
const decodeEntities = (s) => s.replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"');

const problems = [];
let links = 0;
for (const file of pages) {
  const url = urlOf(file);
  const text = html.get(file);

  // <html lang> per locale.
  const lang = /<html[^>]*\slang="([^"]+)"/.exec(text)?.[1];
  const code = Object.keys(LOCALES).find((c) => url === `/${c}/` || url.startsWith(`/${c}/`));
  const expected = code ? LOCALES[code].lang : 'en-US';
  if (url !== '/404' && lang !== expected) problems.push(`${url}: <html lang="${lang}">, expected "${expected}"`);

  // Links and anchors.
  for (const [, raw] of text.matchAll(/<a\s[^>]*?href="([^"]*)"/g)) {
    const href = decodeEntities(raw);
    if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('//')) continue;
    links++;
    const [path, anchor] = href.split('#');
    let targetFile = file;
    if (path !== '') {
      const base = url.endsWith('/') ? url : posix.dirname(url) + '/';
      const resolved = path.startsWith('/') ? path : posix.normalize(base + path);
      if (/\.(css|js|svg|png|jpe?g|webp|ico|xml|txt|json)$/.test(resolved)) {
        if (!existsSync(join(dist, decodeURIComponent(resolved)))) problems.push(`${url}: broken link ${href}`);
        continue;
      }
      targetFile = fileFor(resolved);
      if (!targetFile) {
        problems.push(`${url}: broken link ${href}`);
        continue;
      }
    }
    if (anchor && !idsOf(targetFile).has(decodeURIComponent(anchor))) {
      problems.push(`${url}: broken anchor ${href} (no id "${decodeURIComponent(anchor)}" on ${urlOf(targetFile)})`);
    }
  }
}

// Language switcher: every page of a locale links to its counterpart, and back.
let switches = 0;
for (const code of Object.keys(LOCALES)) {
  for (const file of pages) {
    const url = urlOf(file);
    if (!url.startsWith(`/${code}/`)) continue;
    const english = url.slice(code.length + 1);
    const englishFile = fileFor(english);
    if (!englishFile) {
      problems.push(`${url}: no English counterpart ${english}`);
      continue;
    }
    const hrefs = (f) => [...html.get(f).matchAll(/<a\s[^>]*?href="([^"#]*)/g)].map((m) => m[1]);
    if (!hrefs(englishFile).includes(url)) problems.push(`${english}: language switcher does not link to ${url}`);
    if (!hrefs(file).includes(english)) problems.push(`${url}: language switcher does not link to ${english}`);
    switches++;
  }
}

if (problems.length) {
  console.error(`Site check failed (${problems.length} problem(s)):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`Site check passed: ${pages.length} pages, ${links} internal links and anchors, ${switches} language pairs.`);
