// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { defineConfig } from 'vitepress';

const SITE = 'https://rulestone.dxa.io.vn';
const REPO = 'https://github.com/xvalleycorp/RuleStone';
const DESCRIPTION =
  'Open-source business rules engine for banking and finance. Versioned, effective-dated decision tables ' +
  'with exact decimal arithmetic and type-checked expressions.';

export default defineConfig({
  lang: 'en-US',
  title: 'RuleStone',
  titleTemplate: ':title · RuleStone',
  description: DESCRIPTION,
  cleanUrls: true,
  sitemap: { hostname: SITE },

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#92400e' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'RuleStone' }],
    ['meta', { property: 'og:title', content: 'RuleStone — business rules, set in stone' }],
    ['meta', { property: 'og:description', content: DESCRIPTION }],
    ['meta', { property: 'og:url', content: SITE }],
  ],

  themeConfig: {
    logo: { light: '/logo.svg', dark: '/logo-dark.svg', alt: 'RuleStone' },

    nav: [
      { text: 'Guide', link: '/docs/getting-started', activeMatch: '/docs/getting-started' },
      {
        text: 'Reference',
        items: [
          { text: 'Decision tables', link: '/docs/decision-tables' },
          { text: 'Expression language', link: '/docs/expressions' },
          { text: 'Java API', link: '/docs/java-api' },
        ],
      },
      { text: 'Examples', link: '/docs/examples' },
      { text: 'Architecture', link: '/docs/architecture' },
      {
        text: '0.1 pre-release',
        items: [
          { text: 'Changelog', link: `${REPO}/blob/main/CHANGELOG.md` },
          { text: 'Contributing', link: `${REPO}/blob/main/CONTRIBUTING.md` },
          { text: 'Security policy', link: `${REPO}/blob/main/SECURITY.md` },
        ],
      },
    ],

    sidebar: {
      '/docs/': [
        {
          text: 'Guide',
          items: [
            { text: 'Overview', link: '/docs/' },
            { text: 'Getting started', link: '/docs/getting-started' },
          ],
        },
        {
          text: 'Reference',
          items: [
            { text: 'Decision tables', link: '/docs/decision-tables' },
            { text: 'Expression language', link: '/docs/expressions' },
            { text: 'Java API', link: '/docs/java-api' },
          ],
        },
        {
          text: 'More',
          items: [
            { text: 'Examples', link: '/docs/examples' },
            { text: 'Architecture', link: '/docs/architecture' },
          ],
        },
      ],
    },

    socialLinks: [{ icon: 'github', link: REPO }],

    editLink: {
      // Pages under docs/ are copied from the repository's docs/ folder; edit the originals.
      // This function is serialized to the browser, so it cannot use constants from this file.
      pattern: ({ filePath }) => {
        const edit = 'https://github.com/xvalleycorp/RuleStone/edit/main/';
        if (filePath === 'docs/index.md') return edit + 'docs/README.md';
        if (filePath.startsWith('docs/')) return edit + filePath;
        return edit + 'website/src/' + filePath;
      },
      text: 'Edit this page on GitHub',
    },

    search: { provider: 'local' },
    outline: { level: [2, 3] },

    footer: {
      message: `Released under the <a href="${REPO}/blob/main/LICENSE">Apache License 2.0</a>.`,
      copyright: 'Copyright © 2026 The RuleStone Authors',
    },
  },
});
