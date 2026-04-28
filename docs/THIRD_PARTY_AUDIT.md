# Third-party request audit (Phase 1)

## Goal

Inventory every place a browser hitting the SPA could open a connection
to a host other than our origin. The remediation plan is in
`docs/NO_THIRD_PARTY_PLAN.md` (Phases 2–7).

## Method

- Grep `web-app/src/` for full URLs (`https?://...`) and known third-
  party hostnames (TMDB, OpenLibrary, Cover Art Archive, Wikimedia,
  Google Fonts, common CDNs, analytics, video hosts).
- Grep the production `dist/` bundle for the same patterns to catch
  anything pulled in by transitive dependencies.
- Grep templates for `<img src>`, `<iframe src>`, `<script src>`,
  `<link href>`, `<a target=_blank>`, `<video src>`, `<source>`,
  `<track>`.
- Grep TypeScript for `fetch(`, `XMLHttpRequest`, `new URL(`,
  `window.open(`.
- Grep CSS/SCSS for `@import` and `url(...)`.
- Confirm the existing `Content-Security-Policy` posture.

Audit run: 2026-04-28.

## Findings

### Confirmed leak (one)

**`web-app/src/app/features/admin/purchase-wishes.ts:130`** —
`albumCoverUrl()` constructs `https://coverartarchive.org/release/${releaseId}/front-250`
and the template at `purchase-wishes.html:32` binds it to
`<img [src]>`. Same-origin replacement: `/proxy/caa/release/${releaseId}/large`
(servlet exists at `ImageProxyHttpService.kt:120`). CSP is currently
blocking these images on this page; admins are seeing broken cover
art. Fix in **Phase 2** (image-layer refactor), or land a tactical
patch sooner.

### Allowed exception (one)

**`web-app/src/app/features/auth/terms.html`** — the privacy-policy
and terms-of-use anchors render `<a [href]="s.privacy_policy_url"
target="_blank">` and `<a [href]="s.terms_of_use_url" target="_blank">`.
URLs come from admin-configured `app_config` rows. Per the project
rule, third-party links are permitted as long as they're user-
initiated `<a target="_blank">` clicks; the page never fetches them
on load.

### Subresources audited and clean

- **`index.html`**: fonts and icons load from `/app/vendor/fonts/...`
  same-origin. No `<link rel="preconnect|dns-prefetch">` to anywhere.
  No external `<script src>`.
- **CSS**: no `@import` from third-party hosts; no external `url(...)`
  references. Material's icon font self-hosted.
- **Reader scripts**: epub.js + JSZip vendored locally
  (`vendor/epub.min.js`, `vendor/jszip.min.js`) and loaded with SRI
  via `loadScriptOnce()` (`reader.ts:140-141`).
- **Iframes**: only the PDF reader (`reader.html:29`) and it sources
  `/ebook/{id}` same-origin.
- **Audio / video / track**: every player URL is constructed from a
  same-origin servlet — `/stream/{transcode_id}/...`,
  `/live-tv/stream/...`, `/audio/{trackId}`, `/cam/{id}/snapshot.jpg`,
  `/cam/{id}/mjpeg`. (Stream URLs are an explicit project-rule
  exception — too large to ship through gRPC framing.)
- **`<a target="_blank">`**: aside from the privacy/terms links above,
  the only one is `document-ownership.html:101` whose `[href]` is
  `photo.url`, populated by the server as `/ownership-photos/{id}`
  same-origin (`DocumentOwnershipHttpService.kt:130`).
- **`window.open` / external navigation**: one call site
  (`inventory-report.ts:155`) opens `/api/v2/admin/report/download`
  same-origin.
- **`fetch` / XHR**: every direct call resolves to a same-origin
  path (`/audio/...`, `/playback-progress/...`, `/ebook/...`).
  Connect-Web's gRPC fetch is wired to `credentials: 'include'`
  and uses relative paths.
- **Build output**: only `https://coverartarchive.org/` appears as
  an actually-fetched URL inside built JS. The other host strings
  (`https://github.com/...`, `https://example.com/...`,
  `https://aomedia.org`, `https://stuk.github.io`, etc.) are doc /
  error-message strings inside library code — they never hit the
  network.

### Server-side proxies (verified, not changed)

- `serveProxied()` in `ImageHttpServices.kt` and the
  `/proxy/<source>/...` family in `ImageProxyHttpService.kt` perform
  server-side fetches via `ImageProxyService` and stream the bytes
  back. **No 302 redirects**. SSRF screening + caching live there.
- `ImageProxyService` follows upstream redirects internally (up to
  `MAX_REDIRECTS = 4`), so even a third-party `Location:` header
  never reaches the browser.
- The legacy 302-redirect fallback noted in the older
  `project_tmdb_redirect_elimination` memory has been replaced by
  `serveProxied()` already; the comment at `ImageHttpServices.kt:50`
  ("Replaces the previous 302-redirect fallback") confirms.

### CSP posture (already strong)

`CspDecorator.kt` is **enforcing** today (`ENFORCING = true`) with:

```
default-src 'self';
img-src 'self' data: blob:;
script-src 'self';
style-src 'self' 'unsafe-inline' blob:;
font-src 'self';
connect-src 'self';
frame-src 'self';
object-src 'none';
base-uri 'self';
form-action 'self';
frame-ancestors 'none';
report-uri /csp-report;
report-to csp-endpoint;
```

Plus HSTS, `Cross-Origin-Opener-Policy: same-origin`,
`Permissions-Policy` blocking FLoC / Topics / payment / USB / MIDI,
`Referrer-Policy: strict-origin-when-cross-origin`. CSP violations
log to Binnacle via `CspReportHttpService`. Phase 6 of the original
plan ("CSP enforcement") is effectively done; the Cover Art Archive
leak is the one violation it's silently catching today.

## Remediation list

| # | Surface | File | Action |
|---|---|---|---|
| 1 | CAA cover URLs on `purchase-wishes` | `purchase-wishes.ts:130` | Replace with same-origin `/proxy/caa/release/{rgid}/large` (tactical) → drop entirely once `<app-image>` lands (Phase 2). |
| 2 | All `<img [src]="...poster_url">` etc. across the SPA | many | Phase 2 — `<app-image [ref]="imageRef">` directive backed by `ImageStreamService`. Drops every same-origin image URL the SPA still constructs. |
| 3 | SPA-side URL helpers (`tmdbImageUrl`, `videoPosterUrl`, `posterUrl`, the inline `/local-images/...` joins, etc.) | `catalog.service.ts`, `personal-videos.ts`, `wishlist.ts`, etc. | Delete in Phase 2; the wire format and the `<app-image>` directive carry IDs end-to-end. |
| 4 | The bridge servlet I introduced during the wishlist commit (`/tmdb-poster/{type}/{tmdb_id}/{size}`) | `ImageHttpServices.kt:264-307` + `ArmeriaServer.kt:251` | Delete in Phase 3 once `<app-image>` ships and no SPA caller hits it. |
| 5 | Camera JPEG / MJPEG fetches | `cameras.ts:51,55` | Phase 2 — switch to `<app-image>` for the snapshot mode (`IMAGE_TYPE_CAMERA_SNAPSHOT`). MJPEG keeps its `<img>` per the streaming-content exception. |
| 6 | Privacy policy / terms-of-use anchors | `terms.html:18, 29` | **Allowed exception.** Document the rule in CSP if we tighten further. |
| 7 | Phase 7 regression test | `tests/functional/no-third-party-requests.spec.ts` (new) | Capture every request a fresh navigation makes; assert each URL's host equals the page's. The CAA reference will surface here too if the tactical fix slips. |
| 8 | Presubmit lint | `lifecycle/presubmit-check.sh` | Grep for `https://image.tmdb.org`, `coverartarchive.org`, `covers.openlibrary.org`, `commons.wikimedia.org`, `fonts.googleapis.com`, etc. in SPA source. Fail on match. |

## Bottom line

Aside from the one Cover Art Archive URL on the admin
purchase-wishes page, the SPA is already same-origin-only, and CSP
is already enforcing the policy. The remaining work is the
ImageService streaming refactor (Phase 2) — that's the one that
removes URL construction from the SPA entirely so we don't depend
on CSP catching future regressions.
