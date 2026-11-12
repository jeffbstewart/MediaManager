# Marquee reference material

Inputs for the theming work described in `../web-theming.md`. These files
are a reference listing, not part of the build.

| File | What it is |
|---|---|
| `STYLE-SPEC.md` | The measured style and DOM specification: tokens, type, shape, spacing per breakpoint, page shell, building blocks, home composition, API mapping. The contract for the shared layout and the Marquee skin. |
| `styles.css` | The prototype's working stylesheet. Every token and block in the spec, as CSS. |
| `main.ts` | The prototype's page structure and behaviours (rows, cards, hero, sheets, FAQ, tab bar). It imports `library.json` and `home-feed.json`, snapshots of a private library that are deliberately not included; read it for DOM shape and behaviour only. |
| `font-bakeoff-report.md` | Why the display font is Figtree at weight 800. |

Values in the spec were measured from screenshots of a public website at
three viewport widths. No code or assets from that site were used. The
product name of that site is a third-party trademark and does not appear
in this repository's code, theme ids, class names, or user-facing copy.

Fonts referenced here are vendored under `web-app/public/vendor/fonts/`
with their licenses; see `THIRD_PARTY_LICENSES.md` at the repo root.
