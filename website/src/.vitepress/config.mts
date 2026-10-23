// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { defineConfig } from 'vitepress';
// Translations: which pages exist in Vietnamese, and whether they are up to date.
import { LOCALES, englishDocs, englishUrl, parseFrontMatter, readText, sha256, translatedUrl, translations } from '../../scripts/i18n-lib.mjs';

const SITE = 'https://rulestone.dxa.io.vn';
const REPO = 'https://github.com/xvalleycorp/RuleStone';
const DESCRIPTION =
  'Open-source business rules engine for banking and finance. Versioned, effective-dated decision tables ' +
  'with exact decimal arithmetic and type-checked expressions.';
const DESCRIPTION_VI =
  'Bộ máy luật nghiệp vụ mã nguồn mở cho ngân hàng và tài chính. Bảng quyết định có phiên bản, có ngày hiệu lực, ' +
  'tính toán thập phân chính xác và biểu thức được kiểm tra kiểu.';

// Vietnamese pages that exist (keyed by English source), and English guides without one.
const vi = translations('vi').filter((t) => t.status !== 'invalid');
const viDocs = new Map(vi.filter((t) => t.kind === 'docs').map((t) => [t.source, t]));
const viTitles: Record<string, string> = LOCALES.vi.titles;
const viPlaceholders = new Set(
  englishDocs()
    .filter((f) => !viDocs.has(f))
    .map((f) => (f === 'README.md' ? 'vi/docs/' : `vi/docs/${f.replace(/\.md$/, '')}`)),
);

// The guides in the sidebar, by group: [file in docs/, English title]. Both languages are
// built from this list, so they always mirror each other. Vietnamese titles are in
// LOCALES.vi.titles (scripts/i18n-lib.mjs).
const SIDEBAR: { text: string; vi: string; items: [string, string][] }[] = [
  {
    text: 'Guide',
    vi: 'Hướng dẫn',
    items: [
      ['README.md', 'Overview'],
      ['getting-started.md', 'Getting started'],
    ],
  },
  {
    text: 'Reference',
    vi: 'Tham khảo',
    items: [
      ['decision-tables.md', 'Decision tables'],
      ['expressions.md', 'Expression language'],
      ['fuzzy-matching.md', 'Fuzzy matching'],
      ['java-api.md', 'Java API'],
      ['testing.md', 'Testing'],
      ['temporal.md', 'Temporal integration'],
    ],
  },
  {
    text: 'More',
    vi: 'Thêm',
    items: [
      ['examples.md', 'Examples'],
      ['architecture.md', 'Architecture'],
      ['hybrid-ai.md', 'Hybrid AI decisioning'],
    ],
  },
];
const REFERENCE = SIDEBAR[1].items;

type Item = { text: string; link: string };
const enItem = ([file, title]: [string, string]): Item => ({ text: title, link: englishUrl(`docs/${file}`) });

/** A Vietnamese entry: the translation if there is one, else the English page marked "(English)". */
function viItem(file: string): Item {
  const title = viTitles[file] ?? SIDEBAR.flatMap((g) => g.items).find(([f]) => f === file)?.[1] ?? file;
  return viDocs.has(file)
    ? { text: title, link: translatedUrl('vi', viDocs.get(file)) }
    : { text: `${title} (English)`, link: englishUrl(`docs/${file}`) };
}

// English and Vietnamese URLs of each translated page, for <link rel="alternate" hreflang>.
const pageUrl = (relativePath: string) => '/' + relativePath.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '');
const alternates = new Map<string, { en: string; vi: string }>();
for (const t of vi) {
  const pair = { en: englishUrl(t.englishPath), vi: translatedUrl('vi', t) };
  alternates.set(pair.en, pair);
  alternates.set(pair.vi, pair);
}

const editLinkPattern = ({ filePath }: { filePath: string }) => {
  // Pages under docs/ and vi/docs/ are copied from the repository's docs/ folder; edit the originals.
  // This function is serialized to the browser, so it cannot use constants from this file.
  const edit = 'https://github.com/xvalleycorp/RuleStone/edit/main/';
  if (filePath === 'docs/index.md') return edit + 'docs/README.md';
  if (filePath.startsWith('docs/')) return edit + filePath;
  if (filePath.startsWith('vi/docs/')) return edit + 'docs/vi/' + filePath.slice('vi/docs/'.length);
  return edit + 'website/src/' + filePath;
};

export default defineConfig({
  lang: 'en-US',
  title: 'RuleStone',
  titleTemplate: ':title · RuleStone',
  description: DESCRIPTION,
  cleanUrls: true,
  sitemap: {
    hostname: SITE,
    // Placeholder pages for untranslated guides only point to the English page.
    transformItems: (items) => items.filter((item) => !viPlaceholders.has(item.url.replace(/^\//, ''))),
  },

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#92400e' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'RuleStone' }],
    ['meta', { property: 'og:title', content: 'RuleStone — business rules, set in stone' }],
    ['meta', { property: 'og:description', content: DESCRIPTION }],
    ['meta', { property: 'og:url', content: SITE }],
  ],

  // Marks a translation whose English source changed since it was stamped (see
  // scripts/i18n-lib.mjs); the theme then shows a notice at the top of the page.
  transformPageData(pageData) {
    const path = pageData.relativePath;
    const translation = path.startsWith('vi/docs/')
      ? `docs/vi/${path.slice('vi/docs/'.length)}`
      : path === 'vi/index.md'
        ? 'website/src/vi/index.md'
        : null;
    if (!translation || pageData.frontmatter.translationPlaceholder) return;
    const { data } = parseFrontMatter(readText(translation));
    if (!data.source) return;
    const englishPath = translation.startsWith('docs/') ? `docs/${data.source}` : `website/src/${data.source}`;
    if (sha256(readText(englishPath)) !== data.sourceHash) {
      pageData.frontmatter.outdated = englishUrl(englishPath);
    }
  },

  transformHead({ pageData }) {
    const pair = alternates.get(pageUrl(pageData.relativePath));
    if (!pair) return [];
    return [
      ['link', { rel: 'alternate', hreflang: 'en', href: SITE + pair.en }],
      ['link', { rel: 'alternate', hreflang: 'vi', href: SITE + pair.vi }],
      ['link', { rel: 'alternate', hreflang: 'x-default', href: SITE + pair.en }],
    ];
  },

  themeConfig: {
    logo: { light: '/logo.svg', dark: '/logo-dark.svg', alt: 'RuleStone' },
    socialLinks: [{ icon: 'github', link: REPO }],
    outline: { level: [2, 3] },

    search: {
      provider: 'local',
      options: {
        locales: {
          vi: {
            translations: {
              button: { buttonText: 'Tìm kiếm', buttonAriaLabel: 'Tìm kiếm' },
              modal: {
                displayDetails: 'Hiện chi tiết',
                resetButtonTitle: 'Xoá từ khoá',
                backButtonTitle: 'Đóng tìm kiếm',
                noResultsText: 'Không tìm thấy kết quả cho',
                footer: {
                  selectText: 'chọn',
                  selectKeyAriaLabel: 'phím Enter',
                  navigateText: 'di chuyển',
                  navigateUpKeyAriaLabel: 'phím mũi tên lên',
                  navigateDownKeyAriaLabel: 'phím mũi tên xuống',
                  closeText: 'đóng',
                  closeKeyAriaLabel: 'phím Escape',
                },
              },
            },
          },
        },
      },
    },
  },

  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/docs/getting-started', activeMatch: '/docs/getting-started' },
          { text: 'Reference', items: REFERENCE.map(enItem) },
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
          '/docs/': SIDEBAR.map((group) => ({ text: group.text, items: group.items.map(enItem) })),
        },

        editLink: { pattern: editLinkPattern, text: 'Edit this page on GitHub' },

        footer: {
          message: `Released under the <a href="${REPO}/blob/main/LICENSE">Apache License 2.0</a>.`,
          copyright: 'Copyright © 2026 The RuleStone Authors',
        },
      },
    },

    vi: {
      label: 'Tiếng Việt',
      lang: 'vi-VN',
      link: '/vi/',
      title: 'RuleStone',
      titleTemplate: ':title · RuleStone',
      description: DESCRIPTION_VI,
      themeConfig: {
        nav: [
          { text: 'Hướng dẫn', link: viItem('getting-started.md').link, activeMatch: '/vi/docs/getting-started' },
          { text: 'Tham khảo', items: REFERENCE.map(([file]) => viItem(file)) },
          viItem('examples.md'),
          { ...viItem('architecture.md'), activeMatch: '/vi/docs/architecture' },
          {
            text: 'Bản 0.1 tiền phát hành',
            items: [
              { text: 'Nhật ký thay đổi (English)', link: `${REPO}/blob/main/CHANGELOG.md` },
              { text: 'Đóng góp (English)', link: `${REPO}/blob/main/CONTRIBUTING.md` },
              { text: 'Chính sách bảo mật (English)', link: `${REPO}/blob/main/SECURITY.md` },
              { text: 'Dịch tài liệu', link: `${REPO}/blob/main/docs/vi/README.md` },
            ],
          },
        ],

        sidebar: {
          '/vi/docs/': SIDEBAR.map((group) => ({ text: group.vi, items: group.items.map(([file]) => viItem(file)) })),
        },

        editLink: { pattern: editLinkPattern, text: 'Sửa trang này trên GitHub' },

        footer: {
          message: `Phát hành theo <a href="${REPO}/blob/main/LICENSE">Giấy phép Apache 2.0</a>.`,
          copyright: 'Bản quyền © 2026 The RuleStone Authors',
        },

        outline: { level: [2, 3], label: 'Trên trang này' },
        docFooter: { prev: 'Trang trước', next: 'Trang sau' },
        lastUpdated: { text: 'Cập nhật lần cuối' },
        langMenuLabel: 'Đổi ngôn ngữ',
        darkModeSwitchLabel: 'Giao diện',
        lightModeSwitchTitle: 'Chuyển sang giao diện sáng',
        darkModeSwitchTitle: 'Chuyển sang giao diện tối',
        sidebarMenuLabel: 'Mục lục',
        returnToTopLabel: 'Lên đầu trang',
        skipToContentLabel: 'Bỏ qua, đến nội dung chính',
        notFound: {
          title: 'KHÔNG TÌM THẤY TRANG',
          quote: 'Trang bạn tìm không tồn tại hoặc đã được chuyển sang địa chỉ khác.',
          linkLabel: 'về trang chủ',
          linkText: 'Về trang chủ',
        },
      },
    },
  },
});
