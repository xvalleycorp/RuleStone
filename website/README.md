# RuleStone website

The source of [rulestone.dxa.io.vn](https://rulestone.dxa.io.vn): a [VitePress](https://vitepress.dev) site served by a Cloudflare Worker with static assets.

## How it is put together

| Path | Content |
|---|---|
| `src/index.md` | Home page. |
| `src/docs/` | **Generated.** Copied from the repository's [`docs/`](../docs/) by `scripts/sync-docs.mjs` on every build. Edit the files in `docs/`, not here. |
| `src/.vitepress/config.mts` | Navigation, sidebar, search, footer. |
| `src/.vitepress/theme/` | Colours and home page styles. |
| `src/public/` | Logo, favicon, `robots.txt` and `_headers` (security and cache headers). |
| `wrangler.jsonc` | Cloudflare Worker configuration, including the custom domain. |

The sync script rewrites links that leave `docs/` (to `examples/`, `CONTRIBUTING.md`, `LICENSE`, ...) into GitHub URLs, so the guides read correctly both on GitHub and on the site.

## Develop

Requires Node.js 20 or newer.

```sh
npm ci
npm run dev       # http://localhost:5173, reloads on change
npm run build     # output in src/.vitepress/dist
npm run preview   # serve the build locally
```

The build fails on links to pages that do not exist.

The VitePress development server has known advisories that only affect the local dev server (`npm audit`). It listens on `localhost` only; do not expose it to a network. The published site is static files and is not affected.

## Deploy

Pushes to `main` that change `docs/` or `website/` are deployed by the [Website workflow](../.github/workflows/website.yml). It needs the repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.

To deploy by hand from a machine logged in with `npx wrangler login`:

```sh
npm run deploy
```

The Worker serves only `rulestone.dxa.io.vn` (the `dxa.io.vn` zone must be in the same Cloudflare account); `workers.dev` and preview URLs are disabled.
