# Bản dịch tiếng Việt · Vietnamese translation

**Bạn muốn đọc tài liệu?** Bắt đầu từ [mục lục tài liệu tiếng Việt](index.md), hoặc đọc trên trang web: <https://rulestone.dxa.io.vn/vi/docs/>.

This folder holds the Vietnamese translation of the guides in [`docs/`](../). The English pages are the source of truth; this page explains how to translate and keep translations up to date. The terminology to use is in the [glossary](GLOSSARY.md).

## How it is published

| File | Published at | Content |
|---|---|---|
| `index.md` | `/vi/docs/` | Reader-facing index; translation of [`docs/README.md`](../README.md). |
| `<page>.md` | `/vi/docs/<page>` | Translation of `docs/<page>.md`, with the **same file name**. |
| `README.md` (this page), `GLOSSARY.md` | not published | For translators; read on GitHub. |

The Vietnamese home page is [`website/src/vi/index.md`](../../website/src/vi/index.md) (translation of `website/src/index.md`) and follows the same rules.

English pages without a translation are listed in the Vietnamese sidebar with the mark "(English)" and link to the English page. Their address under `/vi/docs/` shows a short notice pointing to the English page, so the language switcher never leads to a missing page.

## Rules for a translation

1. **Front matter.** Every translation starts with:

   ```yaml
   ---
   source: getting-started.md
   sourceHash: <SHA-256 of docs/getting-started.md, written by npm run i18n:stamp>
   ---
   ```

   `source` is the English file name in `docs/` (`README.md` for `index.md`; `index.md` in `website/src/` for the home page). Do not compute `sourceHash` by hand: run the stamp command below.

2. **Code is not translated.** Fenced code blocks (code, JSON, commands, program output) must be **byte-identical** to the English page's, in the same order and the same number, including comments inside the code. Explain a code comment in the text after the block if needed. The website build fails otherwise and names the page and the block number.

3. **Keep in English:** identifiers, API names, file names, JSON keys and values, reason codes (`DTI_HIGH`), hit policy names (`FIRST`), CLI commands, and product names. Use the [glossary](GLOSSARY.md) for everything else; add new terms there in the same pull request.

4. **Links** are written so they work on GitHub, relative to this folder:
   - a translated page: `architecture.md`, `architecture.md#9-lộ-trình`;
   - an English page that is not translated yet: `../decision-tables.md`, and add "(English)" after the link. When that page is translated, the website automatically links to the Vietnamese page instead;
   - anything else in the repository: `../../examples/`, `../../CONTRIBUTING.md`.

   Anchors are written the way GitHub generates them (Vietnamese letters keep their diacritics: `#9-lộ-trình`). The website build converts them to the anchors of the site (`#_9-lo-trinh`) and warns about anchors that match no heading.

5. **Numbers in prose** follow Vietnamese usage (`25.750.000,50 đồng`); numbers in code stay as they are.

6. **Style:** address the reader as "bạn"; write for bank IT and engineering readers; prefer short, plain sentences; follow the English structure (same headings, same order) so pages can be compared side by side.

## Workflow

All commands run in `website/` (Node.js 20 or newer, `npm ci` once).

```sh
npm run i18n:status                       # up-to-date / outdated / invalid / missing, per page
npm run i18n:stamp -- getting-started.md  # record that a translation matches its English source
npm run build                             # runs every check below, then builds the site
```

**Add a translation.** Copy the English page to `docs/vi/<page>.md`, add the front matter with `source: <page>.md` and `sourceHash: pending`, translate the prose, then run `npm run i18n:stamp -- <page>.md` and `npm run build`. Link it from [`index.md`](index.md) (replace the `../<page>.md` (English) link). The sidebar, the navigation and links from other pages switch to the translation automatically.

**Update a translation after the English page changed.** `npm run build` and `npm run i18n:status` list it as *outdated*, and the published page shows a notice ("Bản dịch này có thể đã cũ so với bản tiếng Anh…") with a link to the English page. Compare the English changes (`git log -p docs/<page>.md`), update the translation, copy any changed code blocks verbatim, then run `npm run i18n:stamp -- <page>.md`. Stamping only after re-checking is what makes the notice trustworthy.

The build:

- **fails** if a translation has no `source` or `sourceHash`, is not named after its source, or its code blocks differ from the English page;
- **warns** (and shows the notice on the page) if the English page changed since the translation was stamped, or if a link anchor matches no heading.

See also [`website/README.md`](../../website/README.md#translations).
