# Screenshot Update Procedures

This file documents how to regenerate the documentation screenshots.

## Canonical capture flow

```bash
./lifecycle/capture-web-screenshots.sh
```

That wrapper sources `app_store_demo_setup/secrets/.env` and runs
`web-app/tests/scripts/capture-docs-screenshots.mjs` against the App
Store demo server (`DEMO_BASE_URL`). PNGs are written directly into
`docs/images/screenshots/`, overwriting whatever was there.

The script captures the **deterministic** shots — pages that show a
predictable view once the right account is logged in and the URL is
navigated to. Stateful shots that require pre-populated user state
(see the **Tier 2** section below) are skipped and must be captured
interactively via the Playwright MCP after fixturing the state.

### Prerequisites

- `app_store_demo_setup/secrets/.env` populated with the seven demo
  passwords (copy from `secrets/example.env`).
- The four screenshot-pipeline accounts seeded on the demo server.
  Run `./gradlew :app_store_demo_setup:run --args="seed-users <demo_media>"`
  once after `/setup`. The accounts are:
  - `viewer` &mdash; most user-facing shots
  - `kid` &mdash; PG-13 rating-gate shots (tier 2)
  - `empty` &mdash; new-user empty-state shots
  - `<DEMO_ADMIN_USER>` &mdash; admin shots
- The viewer's wish list pre-populated with a mixed-status fixture
  for `wishlist.png` and `purchase-wishes.png`. Run
  `./gradlew :app_store_demo_setup:run --args="seed-wishes <demo_media>"`
  once after `seed-users`. The fixture data is in
  `app_store_demo_setup/fixtures/wishes.tsv` &mdash; edit that to
  change the titles or status mix.
- The demo server reachable at `DEMO_BASE_URL`.
- `web-app/` dependencies installed (`npm ci` if not already).

### Browser setup baked into the script

- Viewport defaults to **1024 &times; 768**.
- Title-detail pages temporarily switch to **1024 &times; 2000** with
  `fullPage: true` to capture hero + cast + episode list in one PNG.
- One browser context is reused per account to stay under the per-IP
  login rate limit (10/min).

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
thumb file needed. Use `width="640"` for landscape shots; the title-
detail full-pages render fine at the same width even though their
intrinsic height is much taller.

## Tier 1 manifest (deterministic)

These are what `capture-docs-screenshots.mjs` produces today. Routes
are relative to the SPA mount (`/app/`).

### 0. Setup wizard (one-time only)

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `setup-wizard.png` | `/app/setup` | (none) | Empty Configure New Server form. **Special-cased** &mdash; the wizard route is only reachable when zero users exist, so this can only be captured before the first admin is bootstrapped. To re-capture, point a Playwright session at a fresh demo server (empty `demo_storage/`) before walking through `/setup`. Do not re-shoot from a populated server. |

### 1. Pre-login

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `login.png` | `/app/login` | (none) | Empty username/password form |

### 2. Viewer

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `home.png` | `/app/` | viewer | Home with Recently Added carousels |
| `home-empty.png` | `/app/` | empty | Same layout, fresh account &mdash; no Continue Watching row |
| `catalog.png` | `/app/content/movies` | viewer | Movies catalog grid + filter chips |
| `catalog-tv.png` | `/app/content/tv` | viewer | TV Shows catalog (Sherlock tile) |
| `search.png` | `/app/search?q=sherlock` | viewer | Mixed media-type results |
| `title-detail-movie.png` | `/app/title/30` | viewer | The General. Tall viewport, fullPage |
| `title-detail-tv.png` | `/app/title/33` | viewer | Sherlock Holmes + 39-episode list. Tall viewport, fullPage |
| `player.png` | `/app/title/27` &rarr; click Watch | viewer | In-browser player on Night of the Living Dead, mouse moved to summon the native controls bar |
| `wishlist.png` | `/app/wishlist` | viewer | Mixed-status fixture (Ordered + Won't order + Not feasible + Wished for). Requires `seed-wishes` |
| `profile.png` | `/app/profile` | viewer | Username, passkey list, active sessions |

### 3. Admin

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `settings.png` | `/app/admin/settings` | admin | Basic Configuration through Legal Documents. Tall viewport, fullPage |
| `users.png` | `/app/admin/users` | admin | User grid with all 7 demo accounts |
| `transcode-status.png` | `/app/admin/transcodes/status` | admin | Overall progress + Recent Activity |
| `purchase-wishes.png` | `/app/admin/purchase-wishes` | admin | Aggregate view of viewer wishes with status badges. Requires `seed-wishes` |

### How stateful shots are made deterministic

`player.png` uses a Playwright `customAction` to click the play link
on a known title, wait for the `<video>` element, then mouse-move so
the native controls bar appears before the screenshot.

`wishlist.png` and `purchase-wishes.png` rely on the `seed-wishes`
fixture (see Prerequisites). That subcommand seeds five wishes for
the `viewer` account and assigns admin statuses (ORDERED, REJECTED,
NOT_AVAILABLE) to three of them so the screenshots render a visually
rich mix of badges.

## iOS App Store screenshots (separate flow)

App Store Connect requires functional iOS screenshots on the
currently-required device classes:

- iPhone 6.9" (iPhone 16 Pro Max)
- iPad 13" (iPad Pro 13", latest M-series)

Older 6.5" / 6.7" / 12.9" classes auto-derive in App Store Connect.

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
(`./lifecycle/roku-deploy.sh`). The channel must have data to show
&mdash; the server should be running with enriched titles and
playable transcodes.

| File | Roku Screen | Notes |
|------|-------------|-------|
| `roku-home.png` | Roku Home | Navigate to the Roku home screen. Select the "Media Manager (dev)" channel tile so it has the white selection outline. The channel name and item count appear above the tile row. |

## Referenced In

- `docs/USER_GUIDE.md` &mdash; home, home-empty, catalog, catalog-tv, search, title-detail-movie, title-detail-tv, player, wishlist, profile
- `docs/ADMIN_GUIDE.md` &mdash; settings, transcode-status, users, purchase-wishes
- `docs/ROKU_GUIDE.md` &mdash; roku-home

## Notes

- All screenshots saved as PNG.
- `data/screenshots/` is for throwaway/debug screenshots (gitignored).
- `docs/images/screenshots/` is for committed doc screenshots.
- Roku screenshots are captured via `lifecycle/roku-screenshot.sh`
  (FFmpeg pulling from OBS Virtual Camera).
