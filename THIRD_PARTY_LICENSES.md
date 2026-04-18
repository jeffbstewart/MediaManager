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

## How to add an entry

When vendoring a new library:

1. Save the minified / distribution file under `web-app/public/vendor/`
   (or an equivalent folder for server-side deps).
2. Save the upstream LICENSE file next to it as `<name>.LICENSE`.
3. Add a section here listing version, upstream, license, license-file
   path, and where in the code it's used.
4. Mention the library in the commit message so the attribution is
   also in the git history.
