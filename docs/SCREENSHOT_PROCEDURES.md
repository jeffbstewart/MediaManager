# Screenshot Update Procedures

This file documents how to regenerate the documentation screenshots.

## Web app (canonical flow)

```bash
./lifecycle/capture-web-screenshots.sh
```

That wrapper runs `web-app/tests/ci-run.sh docs`, which starts the dev
server and drives Playwright through `web-app/tests/visual/docs.spec.ts`
(via `tests/visual/docs.mjs`). PNGs are written into
`docs/images/screenshots/`, overwriting whatever was there. It needs
nothing but the web-app dependencies (`npm ci` under `web-app/`): no
server, no accounts, no secrets.

### Why the pictures come from the mocked app

The pages are rendered against the same request mocks the Playwright
suites use, with two substitutions so the result can be published:

- **Artwork.** Every poster, backdrop, cover, and headshot is generated
  abstract artwork (`tests/helpers/image-stub.ts`, `artFor`): a
  gradient, a few translucent shapes, and a grain, all derived from the
  image URL, so the same title always draws the same picture. Nothing in
  it is a real poster. TMDB images are licensed for display inside the
  app, not for redistribution, and the film artwork itself belongs to
  the studios.
- **Names.** The fixtures name real films, shows, albums, books, and
  people so the suites read naturally. For screenshots those are swapped
  for invented names at the mock boundary
  (`tests/helpers/fictional-names.ts`, enabled by `MM_FICTIONAL_NAMES=1`,
  which only `docs.mjs` sets). When a fixture gains a real-world name,
  add a row to that table.

The viewport is 1280 × 800 in the Marquee theme. Pages whose content
runs below the fold (home, the title pages, the wish list, settings,
purchase wishes) are captured full page.

### Manifest

Routes are relative to the SPA mount (`/app/`). "Mock" is the
`mockBackend` option set; `viewer` and `admin` are the two feature-flag
fixtures, both with the populated home feed.

| File | Route | Mock | Notes |
|------|-------|------|-------|
| `login.png` | `/login` | none | Empty sign-in form |
| `setup-wizard.png` | `/setup` | none, `discover: setup-required` | First-run form |
| `home.png` | `/` | viewer | Full page: welcome, heroes, chips, rows, FAQ |
| `home-top.png` | `/` | viewer | Above the fold, for the README |
| `home-empty.png` | `/` | viewer, `homeFeed: empty` | New-account home |
| `catalog.png` | `/content/movies` | viewer | Movies grid with filter pills |
| `catalog-tv.png` | `/content/tv` | viewer | TV grid |
| `search.png` | `/search?q=harbor` | viewer | Mixed-type results |
| `title-detail-movie.png` | `/title/100` | viewer | Full page, through Similar Titles |
| `title-detail-tv.png` | `/title/200` | viewer | Full page, seasons and episodes |
| `player.png` | `/play/42` | viewer + player stubs | Synthetic 20 s clip (`tests/fixtures/player`); mouse moved so the controls show |
| `wishlist.png` | `/wishlist` | viewer | Full page: media, transcode, and book wishes |
| `profile.png` | `/profile` | viewer | Account, quality filter, passkeys, sessions |
| `settings.png` | `/admin/settings` | admin | Full page |
| `users.png` | `/admin/users` | admin | User table |
| `transcode-status.png` | `/admin/transcodes/status` | admin | Progress and recent activity |
| `purchase-wishes.png` | `/admin/purchase-wishes` | admin | Full page, aggregate wishes |

To add a page, add a row to `SHOTS` in `tests/visual/docs.spec.ts` with
the route, a selector that proves the page rendered, and the mock
options; reference the file from the guide that needs it.

## Embedding screenshots in markdown

Use the link-wrapped image pattern so docs render a thumbnail and the
user can click to open the full-resolution PNG in a new tab:

```markdown
<a href="images/screenshots/home.png" target="_blank">
  <img src="images/screenshots/home.png" alt="Home screen with carousels" width="640">
</a>
```

GitHub honors the `width` attribute, so the same PNG serves as both
the inline thumbnail and the full-res target &mdash; no separate
thumb file needed. Use `width="640"`; the full-page captures render
fine at that width even though their intrinsic height is much taller.

## iOS App Store screenshots (separate flow)

> These shots are taken from the App Store demo server with its real
> TMDB artwork, not from the mocked app. They are not committed here,
> but the same redistribution question applies to what is uploaded to
> App Store Connect.

App Store Connect requires functional iOS screenshots at one of
its accepted pixel sizes per category:

- iPhone — `1242x2688` (6.5", iPhone 11 Pro Max) or `1284x2778`
  (6.7", iPhone 12 / 13 Pro Max). Newer Pro Max sims (14 / 15 / 16
  Pro Max) capture at 1290x2796 / 1320x2868 which ASC rejects;
  this pipeline uses **iPhone 13 Pro Max** to land on 1284x2778
  natively, and lets ASC auto-derive larger 6.9" sizes.
- iPad — **iPad Pro 13"** latest M-series (2064x2752 native).

**Driver:** `lifecycle/capture-ios-screenshots.sh`. Sources
`app_store_demo_setup/secrets/.env`, then for each device class:

1. Builds the XCUITest target once.
2. Creates a fresh disposable simulator with a `MM-Snap-*` prefix —
   never reuses an existing simulator (those may be paired with the
   production NAS server).
3. Runs `MediaManagerUITests/SnapshotTests/testViewerShots`, with
   demo credentials propagated through `TEST_RUNNER_MM_SNAPSHOT_*`
   env vars to the test runner, then through
   `app.launchEnvironment` to the app.
4. Extracts the captured `XCTAttachment` PNGs from the
   `.xcresult` bundle via `xcrun xcresulttool export attachments`.
5. Tears down the simulator on success; leaves it (and prints its
   UDID) on failure for forensics.

**Server-URL safety:** the demo URL must not appear in any
submitted screenshot. The `-MMSnapshotMode` launch arg (see
`MediaManager/Services/SnapshotMode.swift`) gates two view sites:
`ServerSetupView` skips SSDP discovery (no "Server found" banner),
and `LoginView` suppresses the `serverURL.host()` text. The
`server-setup` shot is captured before any URL is typed.

**Manifest** (`MediaManagerUITests/SnapshotTests.swift`):

| File | Account | Screen |
|------|---------|--------|
| `01-server-setup.png`   | (none) | `ServerSetupView` empty state |
| `02-app-policy.png`     | (none) | `AppPolicyAgreementView` |
| `03-login.png`          | (none) | `LoginView` with `viewer` typed |
| `04-home.png`           | viewer | Home with carousels |
| `05-catalog-movies.png` | viewer | Movies catalog grid |
| `06-title-detail-tv.png`| viewer | `TitleDetailView` for title 33 (Sherlock Holmes) |

**Output:** `ios/MediaManager/fastlane/screenshots/en-US/<device>/`
— `.gitignore`'d. Regenerate; do not commit.

**Prereqs:** populate `app_store_demo_setup/secrets/.env` (see
`example.env`), and ensure the four screenshot accounts
(`viewer` / `kid` / `empty` / `admin`) exist on the demo server.
The `reviewer-*` accounts are reserved for Apple App Review and
must never be used by this pipeline.

## Roku screenshots (separate flow)

Roku screenshots use an HDMI capture card + OBS Virtual Camera +
FFmpeg, not Playwright.

**Setup:**

1. Connect HDMI capture card to the Roku.
2. Open OBS, add the capture card as a Video Capture source.
3. Start Virtual Camera in OBS (Output Type: Program).
4. Use `./lifecycle/roku-screenshot.sh <path>` to capture frames.

**Reproducing:** Deploy the dev channel to the Roku first
(`./lifecycle/roku-deploy-dev.sh`). The channel must have data to show
&mdash; the server should be running with enriched titles and
playable transcodes.

| File | Roku Screen | Notes |
|------|-------------|-------|
| `roku-home.png` | Roku Home | Navigate to the Roku home screen. Select the "Media Manager (dev)" channel tile so it has the white selection outline. The channel name and item count appear above the tile row. Shows the real artwork of whatever is in the library; a mocked equivalent does not exist for Roku. |

## Referenced In

- `README.md` &mdash; home-top
- `docs/USER_GUIDE.md` &mdash; home, home-empty, catalog, catalog-tv, search, title-detail-movie, title-detail-tv, player, wishlist, profile
- `docs/ADMIN_GUIDE.md` &mdash; settings, transcode-status, users, purchase-wishes
- `docs/ROKU_GUIDE.md` &mdash; roku-home

## Notes

- All screenshots saved as PNG.
- `data/screenshots/` is for throwaway/debug screenshots (gitignored).
- `docs/images/screenshots/` is for committed doc screenshots.
- Roku screenshots are captured via `lifecycle/roku-screenshot.sh`
  (FFmpeg pulling from OBS Virtual Camera).
