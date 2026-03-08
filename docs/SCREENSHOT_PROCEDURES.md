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

### 3. Admin Screenshots (log out, log in as admin)

| File | Route | Account | Notes |
|------|-------|---------|-------|
| `docs/images/screenshots/transcode-status.png` | `/transcodes/status` | Admin | Transcoder status panel |
| `docs/images/screenshots/users.png` | `/users` | Admin | User management grid |

## Referenced In

- `docs/USER_GUIDE.md` — home, catalog, title-detail, player
- `docs/ADMIN_GUIDE.md` — transcode-status, users

## Notes

- All screenshots saved as PNG
- The `data/screenshots/` directory is for throwaway/debug screenshots (gitignored)
- The `docs/images/screenshots/` directory is for committed doc screenshots
