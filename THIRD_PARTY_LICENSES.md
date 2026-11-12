# Third-Party Licenses

This file lists third-party libraries **vendored into this repository**
(i.e., their source is checked in) so that redistributions of the repo
and the built binaries include the required notices. Libraries pulled
in only via Gradle / npm dependency managers are not listed here — their
licenses travel with their published artifacts.

---

## Web-app vendored JavaScript

Located under `web-app/public/vendor/`. Each entry has a sibling
`.LICENSE` file carrying the full license text.

### epub.js

- **File:** `web-app/public/vendor/epub.min.js`
- **Version:** 0.3.93
- **Upstream:** https://github.com/futurepress/epub.js
- **License:** BSD-2-Clause
- **License text:** `web-app/public/vendor/epub.LICENSE`
- **Used by:** `web-app/src/app/features/reader/reader.ts` — EPUB
  rendering on the web reader (M5).

### JSZip

- **File:** `web-app/public/vendor/jszip.min.js`
- **Version:** 3.10.1
- **Upstream:** https://github.com/Stuk/jszip
- **License:** MIT (the project is dual-licensed MIT OR GPL-3.0; we use
  the MIT option)
- **License text:** `web-app/public/vendor/jszip.LICENSE`
- **Used by:** epub.js expects a global `window.JSZip`, so we load it
  before epub.js from the reader component.

---

## Web-app vendored fonts

Located under `web-app/public/vendor/fonts/`. Loaded via
`vendor/fonts/fonts.css` from `index.html`.

The font files below are licensed under the SIL Open Font License 1.1
(OFL). The OFL permits bundling the fonts with software under any
license, so this repository's MIT license covers the application code
only; the font files remain under the OFL, and their license text ships
beside them. They are redistributed exactly as published by Fontsource,
not further subset, re-hinted, or renamed. Neither family declares a
Reserved Font Name.

### Inter

- **Files:** `web-app/public/vendor/fonts/inter-latin-400-normal.woff2`,
  `inter-latin-600-normal.woff2`, `inter-latin-700-normal.woff2`
- **Source:** Fontsource `@fontsource/inter` 5.3.0
  ([fontsource.org/fonts/inter](https://fontsource.org/fonts/inter)),
  upstream [rsms/inter](https://github.com/rsms/inter)
- **Copyright:** 2016 The Inter Project Authors
- **License:** SIL Open Font License 1.1
- **License text:** `web-app/public/vendor/fonts/inter.LICENSE`
- **Scope:** Latin subset only; static faces for weights 400, 600 and
  700, the three the UI uses. Body text in every theme.

### Figtree

- **File:** `web-app/public/vendor/fonts/figtree-latin-800-normal.woff2`
- **Source:** Fontsource `@fontsource/figtree` 5.3.0
  ([fontsource.org/fonts/figtree](https://fontsource.org/fonts/figtree)),
  upstream [erikdkennedy/figtree](https://github.com/erikdkennedy/figtree)
- **Copyright:** 2022 The Figtree Project Authors
- **License:** SIL Open Font License 1.1
- **License text:** `web-app/public/vendor/fonts/figtree.LICENSE`
- **Scope:** Latin subset only; the single weight-800 face. Display
  headings in the Marquee theme. The `@font-face` declares
  `font-weight: 700 800` so heading rules written as 700 resolve to it
  (see `docs/plans/marquee-reference/font-bakeoff-report.md`).

### Material Icons

- **File:** `web-app/public/vendor/fonts/material-icons.woff2`
- **Source:** Google Fonts (`fonts.gstatic.com/.../materialicons/v145/...`),
  upstream [google/material-design-icons](https://github.com/google/material-design-icons)
- **License:** Apache License 2.0
- **License text:** `web-app/public/vendor/fonts/material-icons.LICENSE`
- **Notes:** Classic static icon font — matches the `@font-face` block
  Google Fonts serves for the same family. `font-display: block` so
  icon glyphs don't flash as letter placeholders during load.

---

## Data source attribution

### TMDB

- **Upstream:** https://www.themoviedb.org/
- **Used for:** movie and TV metadata, posters, backdrops, and cast
  headshots.
- **Required notice:** This product uses the TMDB API but is not
  endorsed or certified by TMDB.
- **Shown in:** `README.md` (Credits), the web app's Help page
  (Credits section), the iOS app's About screen, and the Android TV
  app's Profile screen.

---

## How to add an entry

When vendoring a new library:

1. Save the minified / distribution file under `web-app/public/vendor/`
   (or an equivalent folder for server-side deps).
2. Save the upstream LICENSE file next to it as `<name>.LICENSE`.
3. Add a section here listing version, upstream, license, license-file
   path, and where in the code it's used.
4. Mention the library in the commit message so the attribution is
   also in the git history.
