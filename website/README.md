# RuleStone website

The source of [rulestone.dxa.io.vn](https://rulestone.dxa.io.vn): a [VitePress](https://vitepress.dev) site served by a Cloudflare Worker with static assets.

## How it is put together

| Path | Content |
|---|---|
| `src/index.md` | Home page. |
| `src/playground.md` | The playground: the browser SDK's editor components on the examples (`src/.vitepress/playground/`). |
| `src/docs/` | **Generated.** Copied from the repository's [`docs/`](../docs/) by `scripts/sync-docs.mjs` on every build. Edit the files in `docs/`, not here. |
| `src/vi/index.md`, `src/vi/playground.md` | Vietnamese home page and playground (translations of `src/index.md` and `src/playground.md`). |
| `src/vi/docs/` | **Generated.** Vietnamese guides copied from [`docs/vi/`](../docs/vi/), plus placeholder pages for guides not translated yet. |
| `src/.vitepress/config.mts` | Locales, navigation, sidebar, search, footer. |
| `src/.vitepress/theme/` | Colours, home page styles, and the notice shown on outdated translations. |
| `scripts/` | `sync-docs.mjs` (copy and check docs), `i18n-*.mjs` (translation status and stamping), `check-site.mjs` (links, anchors and languages of the built site), `csp.mjs` (adds the Content-Security-Policy to the built `_headers`). |
| `src/public/` | Logo, favicon, `robots.txt` and `_headers` (security and cache headers). |
| `wrangler.jsonc` | Cloudflare Worker configuration, including the custom domain. |

The sync script rewrites links that leave `docs/` (to `examples/`, `CONTRIBUTING.md`, `LICENSE`, ...) into GitHub URLs, so the guides read correctly both on GitHub and on the site.

## Develop

Requires Node.js 22 or newer. The playground builds the browser SDK (`../sdk`) from its sources, so install the SDK's dependencies first.

```sh
(cd ../sdk && npm ci)
npm ci
npm run dev       # http://localhost:5173, reloads on change
npm run build     # output in src/.vitepress/dist
npm run preview   # serve the build locally
```

The build fails on links to pages or anchors that do not exist (`npm run check:links` runs the anchor check on an existing build).

The build also adds a Content-Security-Policy to `_headers`. It allows the few scripts VitePress inlines in every page by their SHA-256 hashes, which change between builds, and no `eval`. So config values must not be functions: VitePress would evaluate them in the browser. `npm run preview` does not send the headers; to check the policy, serve `src/.vitepress/dist` with them, or deploy.

The VitePress development server has known advisories that only affect the local dev server (`npm audit`). It listens on `localhost` only; do not expose it to a network. The published site is static files and is not affected.

## Playground

`/playground` (and `/vi/playground`) embeds the components of [`@rulestone/editor`](../sdk/packages/editor/) on the tables of [`examples/`](../examples/) and the example flow of [`docs/hybrid-ai.md`](../docs/hybrid-ai.md):

- `src/.vitepress/playground/examples.data.ts` reads those files when the site is built (a VitePress data loader), with one case of each `.stone-test.json` as sample facts. The examples are never copied by hand; the build fails if a featured example or its test case is missing.
- `Playground.vue` loads the SDK only on that page, after mounting (pages are also rendered at build time, where custom elements cannot run). `config.mts` resolves `@rulestone/engine` and `@rulestone/editor` to the SDK's sources and tells Vue that `rulestone-*` and `rs-*` tags are custom elements.
- Everything runs in the visitor's browser. Share links carry the table compressed (`CompressionStream`, deflate) and base64url-encoded after `#table=`; a link is size-limited, and its content is read with the engine's strict `.stone.json` reader, never executed. The last edit of each example is kept in `localStorage`, for that browser only.

## Translations

The site is in English at `/` and in Vietnamese at `/vi/`. English is the source of truth; English URLs never change.

| English | Vietnamese | Source of the Vietnamese page |
|---|---|---|
| `/` | `/vi/` | `website/src/vi/index.md` |
| `/docs/` | `/vi/docs/` | `docs/vi/index.md` (translation of `docs/README.md`) |
| `/docs/<page>` | `/vi/docs/<page>` | `docs/vi/<page>.md` |
| `/playground` | `/vi/playground` | `website/src/vi/playground.md` |

The language switcher in the navigation bar maps each page to the same path in the other language. A guide that is not translated yet appears in the Vietnamese sidebar and navigation with the mark "(English)" and links to the English page; its `/vi/docs/<page>` address serves a short placeholder (not indexed by search engines or the site search, not in the sitemap) that points to the English page, so the switcher never leads to a 404. Translated pages declare `<link rel="alternate" hreflang>` to each other.

Translator guidelines, the link conventions and the terminology are in [`docs/vi/README.md`](../docs/vi/README.md) and [`docs/vi/GLOSSARY.md`](../docs/vi/GLOSSARY.md).

### Drift protection

Every translation starts with front matter naming its English source and the SHA-256 of that source when the translation was last checked:

```yaml
---
source: getting-started.md
sourceHash: e736cfc9…
---
```

`npm run build` (in `scripts/sync-docs.mjs`):

- **fails** if a translation has no `source` or `sourceHash`, is not named like its source, or if its fenced code blocks are not byte-identical to the English source's, in the same order. The error names the page, the block number and the first differing line;
- **warns** with the list of outdated translations when an English source no longer matches its `sourceHash`. The page is still published, with a notice at the top in Vietnamese ("Bản dịch này có thể đã cũ so với bản tiếng Anh…") linking to the English page;
- converts GitHub-style anchors in translations (`#9-lộ-trình`) to VitePress anchors (`#_9-lo-trinh`) and warns about anchors that match no heading; `check-site.mjs` then fails the build on any broken link or anchor.

```sh
npm run i18n:status                        # up-to-date / outdated / invalid / missing, per English page
npm run i18n:status -- --strict            # same, exit status 1 unless all translations are up to date
npm run i18n:stamp -- getting-started.md   # after re-checking a translation: record the current source hash
npm run i18n:stamp -- index.md home        # the docs index and the home page
npm run i18n:stamp -- src/vi/playground.md # the playground
```

### Add or update a translation

1. **New page:** create `docs/vi/<page>.md` with `source: <page>.md` and `sourceHash: pending`, translate the prose, copy every code block unchanged, and replace the `../<page>.md` (English) link in `docs/vi/index.md`. Add the Vietnamese title to `LOCALES.vi.titles` in `scripts/i18n-lib.mjs` if it is not there. The sidebar, the navigation and links from other translations switch to the new page automatically.
2. **English page changed:** `npm run i18n:status` lists the translation as outdated. Review the English change (`git log -p docs/<page>.md`), update the translation.
3. Run `npm run i18n:stamp -- <page>.md`, then `npm run build`.

A new English guide needs one line in `SIDEBAR` in `src/.vitepress/config.mts` (English and Vietnamese titles come from the same entry); until it is translated, the Vietnamese site links to the English page.

To add another language, add it to `LOCALES` in `scripts/i18n-lib.mjs` and to `locales` in `src/.vitepress/config.mts`.

## Deploy

Pushes to `main` that change `docs/` or `website/` are deployed by the [Website workflow](../.github/workflows/website.yml). It needs the repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.

To deploy by hand from a machine logged in with `npx wrangler login`:

```sh
npm run deploy
```

The Worker serves only `rulestone.dxa.io.vn` (the `dxa.io.vn` zone must be in the same Cloudflare account); `workers.dev` and preview URLs are disabled.
