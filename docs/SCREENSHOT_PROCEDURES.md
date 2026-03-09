# Screenshot Update Procedures

This file documents how to regenerate the documentation screenshots.

## Prerequisites

- **Server URL:** Prompt the user for the production server URL, or retrieve it from
  auto-memory (NOT from any source-controlled file). The URL must never appear in
  committed files.
- **Credentials:** Read from `secrets/test-credentials.agent_visible_env`
  - Viewer screenshots: use `VIEWER_USERNAME` / `VIEWER_PASSWORD`
  - Admin screenshots: use `ADMIN_USERNAME` / `ADMIN_PASSWORD`

## Browser Setup

Resize Playwright viewport to **1024 x 768** before taking any screenshots.

**Exception:** For the title detail page, temporarily resize to **1024 x 2000** and use
`fullPage: true` to capture the entire page (hero, watch locations, cast, similar titles)
in one screenshot. Resize back to 1024 x 768 afterward.

## Screenshot Sequence

Start from a logged-out state. Navigate to the login page first.

### 1. Login Page

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `docs/images/screenshots/login.png` | `/login` | (none) | Empty login form |

### 2. Viewer Screenshots (log in as viewer)

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `docs/images/screenshots/home.png` | `/` | Viewer | Home screen with carousels |
| `docs/images/screenshots/catalog.png` | `/catalog` | Viewer | Catalog grid with posters |
| `docs/images/screenshots/title-detail.png` | `/title/{id}` | Viewer | Pick a well-known enriched movie title with poster/cast/description. Use tall viewport (see Browser Setup). |
| `docs/images/screenshots/player.png` | (video dialog) | Viewer | Find a playable title via catalog search (e.g., House S01E01). Click "Watch in Browser", let video start playing, then wait for user prompt before capturing. |
| `docs/images/screenshots/wishlist.png` | `/wishlist` | Viewer | Wish list with a mix of active and status-updated wishes. Populate wishes first, then have admin set various statuses before capturing. |

### 3. Admin Screenshots (log out, log in as admin)

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `docs/images/screenshots/purchase-wishes.png` | `/purchase-wishes` | Admin | User wishes grid with mixed statuses (Ordered, Rejected, Not Available, Unreviewed). Populate viewer wishes first. |
| `docs/images/screenshots/transcode-status.png` | `/transcodes/status` | Admin | Transcoder status panel |
| `docs/images/screenshots/users.png` | `/users` | Admin | User management grid |

### 4. Roku Screenshots (via HDMI capture)

Roku screenshots use an HDMI capture card + OBS Virtual Camera + FFmpeg, not Playwright.

**Setup:**
1. Connect HDMI capture card to the Roku
2. Open OBS, add the capture card as a Video Capture source
3. Start Virtual Camera in OBS (Output Type: Program)
4. Use `./lifecycle/roku-screenshot.sh <path>` to capture frames

**Reproducing:** Deploy the dev channel to the Roku first (`./lifecycle/roku-deploy.sh`).
The channel must have data to show — the server should be running with enriched titles
and playable transcodes.

| File | Roku Screen | Notes |
|------|-------------|-------|
| `docs/images/screenshots/roku-home.png` | Roku Home | Navigate to the Roku home screen. Select the "Media Manager (dev)" channel tile so it has the white selection outline. The channel name and item count appear above the tile row. |

## Referenced In

- `docs/USER_GUIDE.md` — home, catalog, title-detail, player, wishlist
- `docs/ADMIN_GUIDE.md` — purchase-wishes, transcode-status, users
- `docs/ROKU_GUIDE.md` — roku-home

## Notes

- All screenshots saved as PNG
- The `data/screenshots/` directory is for throwaway/debug screenshots (gitignored)
- The `docs/images/screenshots/` directory is for committed doc screenshots
- Roku screenshots are captured via `lifecycle/roku-screenshot.sh` (FFmpeg from OBS Virtual Camera)
