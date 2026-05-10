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
| `profile.png` | `/app/profile` | viewer | Username, passkey list, active sessions |

### 3. Admin

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `settings.png` | `/app/admin/settings` | admin | Basic Configuration through Legal Documents. Tall viewport, fullPage |
| `users.png` | `/app/admin/users` | admin | User grid with all 7 demo accounts |
| `transcode-status.png` | `/app/admin/transcodes/status` | admin | Overall progress + Recent Activity |

## Tier 2 manifest (stateful &mdash; interactive capture)

These shots require pre-populated user state (wishes, watch progress,
mid-playback frame). The capture script flags them `interactive:
true` and skips them; capture them via Playwright MCP after running
the relevant fixture script (or by hand).

| File | Account | Pre-state | Why it's stateful |
|------|---------|-----------|-------------------|
| `player.png` | viewer | A playable transcode exists for the title you open | Open title, click "Watch in Browser", wait for first frame, screenshot the dialog |
| `wishlist.png` | viewer | A handful of wishes added with mixed statuses set by admin | Need both viewer-side ADD-WISH actions and admin-side STATUS-CHANGE actions before capturing |
| `purchase-wishes.png` | admin | Same fixture as `wishlist.png` (viewer wishes with mixed admin-set statuses) | The admin view of the same data |

Capture flow (per shot):

1. Run the fixture (or perform the prerequisite clicks).
2. Open Playwright MCP, log in as the right account at 1024 &times; 768.
3. Navigate, wait for content, screenshot to `docs/images/screenshots/<file>`.
4. Update the `<!-- TODO(tier-2 screenshots) -->` block in the
   relevant doc with the link-wrapped embed.

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

- `docs/USER_GUIDE.md` &mdash; home, home-empty, catalog, catalog-tv, search, title-detail-movie, title-detail-tv, profile (player, wishlist pending tier 2)
- `docs/ADMIN_GUIDE.md` &mdash; settings, transcode-status, users (purchase-wishes pending tier 2)
- `docs/ROKU_GUIDE.md` &mdash; roku-home

## Notes

- All screenshots saved as PNG.
- `data/screenshots/` is for throwaway/debug screenshots (gitignored).
- `docs/images/screenshots/` is for committed doc screenshots.
- Roku screenshots are captured via `lifecycle/roku-screenshot.sh`
  (FFmpeg pulling from OBS Virtual Camera).
