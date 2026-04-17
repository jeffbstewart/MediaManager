<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Administrator Guide

This guide covers the management tools available to admin users (access level 2). All of these are found in the **Manage** section of the sidebar.

---

## Environment Variables

Set these in `secrets/.env` (local) or as Docker environment variables.

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `H2_PASSWORD` | **Yes** | &mdash; | H2 database password |
| `H2_PRIOR_PASSWORD` | No | *(empty)* | Previous password, for migrating to a new `H2_PASSWORD` |
| `H2_FILE_PASSWORD` | No | *(empty)* | Enables AES encryption at rest; auto-migrates on first set |
| `TMDB_API_KEY` | Recommended | &mdash; | TMDB API key for title enrichment, posters, cast data |
| `TMDB_API_READ_ACCESS_TOKEN` | No | &mdash; | TMDB read access token (alternative auth, not currently used) |
| `MM_NAS_ROOT` | For media | &mdash; | Path to NAS media root (must match Docker volume mount) |
| `MM_BEHIND_PROXY` | If proxied | `false` | Enables `X-Forwarded-*` header trust for reverse proxies |
| `MM_FFMPEG_PATH` | No | `/usr/bin/ffmpeg` | Override FFmpeg binary location |

---

## Command-Line Flags

Pass via `./gradlew run --args="--flag"` or as Docker CMD arguments.

| Flag | Default | Purpose |
|------|---------|---------|
| `--developer_mode` | off | Enables H2 web console at the configured console port |
| `--port N` | 8080 | HTTP listen port |
| `--h2_console_port N` | 8082 | H2 web console port (developer mode only) |
| `--listen_on_all_interfaces` | off | Bind to 0.0.0.0 instead of 127.0.0.1 |
| `--max_transcode_deletes N` | 25 | Mass-deletion guard threshold for NAS cleanup |

---

## Docker Deployment Notes

### Host Networking

The example `docker-compose.yml` uses `network_mode: host` instead of port mappings. This is required for **SSDP** (Roku device discovery), which uses UDP multicast on `239.255.255.250:1900`. Docker's default bridge networking blocks multicast traffic, so Roku devices on the LAN cannot discover the server.

With host networking the container shares the host's network stack directly. The `ports:` clause in docker-compose is commented out as documentation of which ports are in use &mdash; port mapping is not needed (or allowed) in host mode.

### Non-Root Container User

The `user: "1046:100"` directive runs the container process as a specific UID:GID instead of root. This is a security best practice &mdash; if the application is compromised, the attacker has limited filesystem access.

The UID and GID must have **read/write access** to both the cache volume (database, poster cache, backups) and the media volume (NAS files, ForBrowser transcodes). On **Synology DSM**, find the correct UID by SSH-ing into the NAS and running:

```bash
id -u <your-username>    # e.g., id -u admin → 1046
id -g <your-username>    # e.g., id -g admin → 100
```

Use these values in the `user:` directive. If the container can't write to the volumes (permission errors in the logs), the UID:GID doesn't match the volume ownership.

### Synology NAS: ACL Permissions

Synology DSM uses an **overlay ACL layer** on top of standard POSIX permissions. This means that even if a file or directory shows `chmod 777`, access can still be denied if the ACL doesn't grant the requesting UID access. Standard `ls -la` output can be misleading.

When running the container as a non-root user, the UID must:

1. **Correspond to a real Synology user** &mdash; creating a Linux-only user inside the container (e.g., UID 1000) won't work because Synology's ACL layer doesn't recognize it
2. **Be a member of the `users` group** (GID 100) &mdash; Synology's default shared folder permissions grant access to the `users` group
3. **Have explicit access to the shared folder** containing your media &mdash; in DSM, go to **Control Panel &rarr; Shared Folder &rarr; (your share) &rarr; Permissions** and ensure the user has Read/Write access

**Symptom:** The container starts successfully and the database works (cache volume is fine), but streaming fails with permission errors when accessing files on the media volume. The logs may show `java.io.FileNotFoundException` or `AccessDeniedException` for paths under `/media`.

**Fix:** Use the UID of an existing Synology user who has access to the media shared folder:

```bash
# SSH into the NAS
ssh admin@your-nas-ip

# Find your user's UID and GID
id -u your-username    # → e.g., 1046
id -g your-username    # → e.g., 100

# Verify the user can access the media share
ls -la /volume1/your-media-share/
```

Use those values in `docker-compose.yml`:
```yaml
user: "1046:100"
```

---

## Adding Titles to the Catalog

**Sidebar &rarr; Add Item**

The unified Add Item page is the single entry point for adding new media to the catalog. It has three tabs and a persistent items grid:

### Scan Barcode Tab

Two input methods are available:

- **Text field** &mdash; Type or scan a UPC barcode using a USB barcode scanner. Press Enter to submit.
- **Scan with Camera** &mdash; Tap the button to open your phone's camera as a barcode scanner. Point the camera at a UPC barcode and it is detected automatically. Supports continuous scanning &mdash; after each scan, the camera stays active so you can scan the next disc immediately. Audio and visual feedback confirm each scan (green flash for new, blue for duplicate, red for error).

The camera scanner works on **iOS Safari**, **iOS Chrome**, and other mobile browsers with camera access. It supports UPC-A, UPC-E, EAN-8, and EAN-13 barcode formats. On desktop browsers without a camera, the dialog shows an appropriate error message.

> **Bluetooth barcode scanner tip (iOS):** When a Bluetooth barcode scanner is paired, iOS treats it as an external keyboard and hides the on-screen keyboard. This prevents typing in the search field. To keep the software keyboard available alongside the scanner, go to **Settings &rarr; Accessibility &rarr; Keyboards &rarr; Full Keyboard Access** and turn it off, or enable **Settings &rarr; Accessibility &rarr; Touch &rarr; AssistiveTouch** which forces the on-screen keyboard to remain available. The most reliable method is **Settings &rarr; General &rarr; Keyboard &rarr; Show Keyboard** (iPadOS) or tapping the small keyboard icon in the bottom-right corner of the screen when a text field is focused.

If you scan a barcode that's already in the database, a notification tells you so (with the title name if known) instead of silently ignoring it.

After scanning, the system:

1. Looks up the barcode via UPCitemdb (free, no API key)
2. Creates a catalog entry with the product name
3. Cleans the title (strips marketing text like "Blu-ray + Digital")
4. Searches TMDB for the canonical title, poster, cast, genres, and description
5. Detects multi-packs ("Double Feature", "Trilogy") for manual expansion

The UPC API has a daily limit of 100 lookups. A quota tracker in the UI shows remaining capacity.

### Search TMDB Tab

Search TMDB directly and add a title without a barcode. Useful for discs where you've thrown away the case. Select Movie or TV, pick a result, choose a format (and seasons for TV), and add to the collection. The title is immediately enriched with full TMDB data.

### From NAS Tab

Shows unmatched files discovered on the NAS. You can accept a suggested title match, open a link dialog to search for the right title, or ignore files that don't belong. Linking creates the Title and Transcode records. This is the same data shown on the Transcodes &rarr; Unmatched page, but accessible inline for convenience.

### Items Needing Attention

Below the tabs, a persistent grid shows recently added items (last 30 days) that still need work. Items appear here regardless of how they were added. Completion indicators show what's missing: enrichment status, purchase info, and ownership photos. Click any row to open the detail panel.

The grid is database-backed, so it works across sessions. Scan 150 barcodes today, hit the UPC quota limit, come back tomorrow &mdash; the items that enriched overnight appear ready for details.

A filter toggle switches between "Needs Attention Only" (default) and "Show All Recent".

### Item Detail Panel

Clicking a row opens an inline detail panel with:

- **Title &amp; Enrichment** &mdash; poster, title, format, enrichment status. "Fix" button for failed enrichment.
- **Purchase Info** &mdash; place, date, and price fields that auto-save on change.
- **Matching NAS Files** &mdash; if the title has unmatched NAS files with similar names, they appear here with "Link" buttons. This bridges the physical-disc and digital-file worlds.
- **Ownership Photos** &mdash; camera capture button and photo strip for proof-of-ownership documentation.
- **Actions** &mdash; "Done" collapses the panel; "View in Catalog" navigates to the title detail page.

### Amazon Import

**Sidebar &rarr; Amazon Order Import**

Upload an Amazon order history CSV to bulk-fill purchase dates and prices for titles already in your catalog. The system fuzzy-matches Amazon product names to catalog titles, then presents matches for your review before committing.

---

## Expanding Multi-Packs

**Sidebar &rarr; Expand**

Some products contain multiple titles: double features, trilogies, box sets, or slash-separated packs ("Aliens / Predator"). The multi-pack detector flags these during scanning.

On the Expand page, search TMDB for each individual title in the pack, link them, and the system creates separate catalog entries. Each child title gets its own poster, metadata, and transcode links.

---

## NAS Directory Structure

Media Manager auto-discovers media files under the configured NAS root path. It classifies each top-level subdirectory as either **Movies** (flat — files directly in the folder) or **TV** (nested — files inside show/season subdirectories).

You can organize your directories however you like — names don't matter. The scanner determines TV vs Movie by structure, not by directory name. A directory with `SxxExx` patterns in filenames is always classified as TV regardless of depth.

### Excluding Directories with `.mm-ignore`

Drop an empty file named **`.mm-ignore`** in any directory under the NAS root to exclude it from scanning. The scanner skips any directory containing this marker file.

**Automatically managed:** Media Manager creates and maintains `.mm-ignore` in its own managed directories (currently `ForBrowser/`). If the `ForBrowser/` directory doesn't exist, it is created automatically on startup with a `.mm-ignore` marker.

**User-managed:** Create `.mm-ignore` in any other directory you want excluded — bonus features folders, work-in-progress directories, etc. Just create an empty file:

```bash
touch /path/to/nas/root/SomeFolder/.mm-ignore
```

### Media Format Detection

Media format (DVD, Blu-ray, UHD) is determined by **FFprobe resolution analysis**, not by directory name. After the NAS scan discovers new files, a background probe phase examines each file's video resolution:

| Resolution | Format |
|-----------|--------|
| 3840×2160 or higher | UHD Blu-ray |
| 1920×1080 or higher | Blu-ray |
| 640×480 or higher | DVD |
| Below 640×480 | Other |

Files that haven't been probed yet show as **Unknown** until the probe completes.

---

## Transcoding & NAS Management

The Transcodes section has four sub-pages:

### Status

![Transcode status](images/screenshots/transcode-status.png)

- **Scan NAS** &mdash; Discover new files on the NAS, auto-match to catalog titles, clean up deleted files
- **Transcoder progress** &mdash; Shows the current file being transcoded, queue depth, and completion stats
- **Buddy status** &mdash; If a Transcode Buddy is connected, shows its progress and encoder type

### Unmatched

Files found on the NAS that couldn't be automatically matched to a catalog title. For each file you can:

- **Link** to an existing title (search by name)
- **Create** a new title and link in one step
- **Ignore** if it's not a real title (bonus features, etc.)

The system suggests possible matches based on filename similarity.

### Linked

All files successfully matched to catalog titles. Shows the file path, format, codec, and whether a ForBrowser MP4 exists. From here you can:

- Play any linked file
- Re-trigger transcoding
- Unlink incorrect matches

### Backlog

Catalog titles that have no transcoded files at all. This is your "rip these next" list, sorted by TMDB popularity so the most-wanted titles are at the top.

---

## Intro & Credits Detection (MediaSkipDetector)

[MediaSkipDetector](https://github.com/jeffbstewart/MediaSkipDetector) is a companion service that automatically finds shared intro and end-credits sequences across TV episodes using Chromaprint audio fingerprinting. It runs alongside Media Manager, pointing at the same NAS media root.

### How It Works

1. **Scans** the NAS for directories containing 2+ TV episodes (files matching `SxxExx*.mkv`)
2. **Fingerprints** the first 10 minutes of each episode (for intros) and the last 5 minutes (for credits) using fpcalc/FFmpeg
3. **Compares** episodes pairwise to find shared audio sequences (the intro music, the credits theme)
4. **Writes** a `.skip.json` file alongside each source MKV with the detected timestamps

### Setup

Deploy MediaSkipDetector as a Docker container pointing at the same media volume:

```yaml
services:
  skipdetector:
    image: ghcr.io/jeffbstewart/mediaskipdetector:latest
    container_name: skipdetector
    restart: unless-stopped
    user: "1046:100"          # Same UID:GID as mediamanager
    ports:
      - "16004:16004"        # Status page + Prometheus metrics
    volumes:
      - /volume1/your-media-share:/media          # Same media volume, read/write
      - /volume1/docker/skipdetector/data:/data    # Persistent fingerprint cache
    environment:
      - MEDIA_ROOT=/media
      - DATA_DIR=/data
```

The media volume **must be read/write** &mdash; the detector writes `.skip.json` result files alongside the source MKV files. The UID:GID should match your media share permissions (same as the mediamanager container).

fpcalc (Chromaprint) and FFmpeg are bundled in the Docker image. No additional configuration is needed for default operation. See the [MediaSkipDetector tuning docs](https://github.com/jeffbstewart/MediaSkipDetector/blob/main/docs/TUNING.md) for advanced parameter adjustment.

### Import into Media Manager

Media Manager automatically imports skip data during NAS scans (**Transcodes &rarr; Status &rarr; Scan NAS**). The scanner looks for files named `{video_basename}.{agent}.skip.json` alongside each source MKV. Each file contains a JSON array of skip segments:

```json
[
  {"start": 42.15, "end": 87.93, "region_type": "INTRO"},
  {"start": 2606.04, "end": 2658.31, "region_type": "END_CREDITS"}
]
```

On every NAS scan, existing segments from the same agent are deleted and re-imported, so updated detections are picked up automatically.

### Playback Integration

Once imported, skip segments enhance playback in both the browser and Roku players:

- **Progress bar** &mdash; Detected segments appear as **cyan regions** on the video progress bar, so you can see at a glance where the intro and credits are
- **Skip Intro button** &mdash; When playback enters an INTRO segment, a "Skip Intro" button appears in the bottom-right corner. Click it to jump past the intro.
- **Next Episode at credits** &mdash; When playback enters an END_CREDITS segment, the "Up Next" overlay appears with a 10-second countdown to auto-advance to the next episode. Without credits data, "Up Next" only appears when the video reaches the end.
- **Roku** &mdash; The Roku channel shows "Press UP to Skip Intro" during intro segments

### Monitoring

The MediaSkipDetector status page at `http://<host>:16004/status` shows scan progress, fingerprint cache stats, and a history of recent analyses with hit rates. Prometheus metrics are available at `/metrics`.

---

## Family Videos

Family videos let you add personal recordings (home movies, recitals, events) to Media Manager alongside your movie and TV collection. The feature must be enabled in Settings before use.

### Enabling the Feature

**Sidebar &rarr; Settings &rarr; Personal Video Enabled** &mdash; Toggle on. When enabled, the NAS scanner classifies files in designated directories as personal video candidates, and a "Family" link appears in the sidebar's Content section.

### Creating Family Videos

Family videos are created from unmatched NAS files:

1. **Scan your NAS** (Transcodes &rarr; Status &rarr; Scan NAS) to discover new files
2. Go to **Transcodes &rarr; Unmatched** &mdash; personal video files show a "Create" button
3. Click **Create** to open the dialog where you enter:
   - **Title** &mdash; A name for the video (e.g., "Emma's Dance Recital 2024")
   - **Event date** &mdash; When it was filmed
   - **Description** &mdash; Optional notes
   - **Family members** &mdash; Tag the people who appear in the video
   - **Tags** &mdash; Apply any existing tags for organization

The system creates a catalog entry, links it to the file, and it immediately appears in the Family Videos grid.

### Managing Family Members

**Navigate to `/family`** to manage the global family member registry. This page lets you:

- **Add members** with a name, optional birth date, and notes
- **Edit** existing member details
- **Delete** members (removes all video associations)

Family members are shared across all videos &mdash; add a person once, then tag them in as many videos as you like. Birth dates are used to calculate and display ages at the time of each video's event date.

You can also add new family members on the fly from the title detail page's "Edit Family Members" dialog.

### Setting Hero Images

Each family video can have a custom hero image extracted from the video itself, displayed in the poster-style 2:3 aspect ratio used throughout the app.

From a family video's title detail page:

1. Click **Set Hero Image** (admin only)
2. The system extracts 12 evenly-spaced frames from the video using FFmpeg
3. Each frame shows its **timestamp** (e.g., "1:23", "12:45") so you can identify the scene
4. Click a frame to set it as the hero image
5. Click **Shuffle** to get a different set of frame offsets if none of the current options work

Hero images are stored locally (not on TMDB) and served from the `/local-images/` endpoint.

### Editing Family Video Details

From the title detail page, click **Edit** to update:

- **Title** &mdash; Display name
- **Event date** &mdash; Date filmed
- **Description** &mdash; Notes about the video

Click the pencil icon next to "People in this Video" to add or remove family member tags.

### How Family Videos Differ from Catalog Titles

| Aspect | Catalog titles | Family videos |
|--------|---------------|---------------|
| **Source** | UPC scan or TMDB search | Unmatched NAS file |
| **Enrichment** | TMDB metadata, posters, cast | Manual entry only |
| **Poster** | TMDB poster image | Extracted video frame (hero image) |
| **People** | TMDB cast members | Family member registry |
| **Sorting** | Alphabetical, popularity | Event date, name, recently added |
| **Playback** | Same HTML5 player | Same HTML5 player |
| **Progress** | Same tracking &amp; resume | Same tracking &amp; resume |

---

## Live Camera Streaming

**Sidebar &rarr; Cameras (in Manage section)**

Media Manager can relay live RTSP camera feeds to authenticated browsers and Roku devices via an embedded [go2rtc](https://github.com/AlexxIT/go2rtc) process. The server proxies all streams, so cameras don't need to be directly accessible to clients &mdash; they only need to be reachable from the server.

### Architecture

```
Camera (RTSP) &rarr; go2rtc (relay, 127.0.0.1:1984) &rarr; HLS &rarr; MediaManager proxy &rarr; Roku
                                                        &rarr; MJPEG &rarr; MediaManager proxy &rarr; Browser
                                                        &rarr; JPEG snapshot &rarr; Browser thumbnails
```

### go2rtc Configuration

The camera settings page includes a **go2rtc Settings** card at the bottom:

| Setting | Default | Purpose |
|---------|---------|---------|
| **go2rtc Binary Path** | `/usr/local/bin/go2rtc` | Path to the go2rtc executable. Pre-installed in the Docker image. |
| **go2rtc API Port** | `1984` | Port go2rtc binds to on 127.0.0.1 (not externally accessible) |

go2rtc is bundled in the Docker image automatically. For non-Docker installs, download the binary from [go2rtc releases](https://github.com/AlexxIT/go2rtc/releases) and set the path.

### Adding Cameras

1. Navigate to **Cameras** in the Manage section of the sidebar
2. Click **Add Camera**
3. Fill in the fields:
   - **Name** &mdash; Display name (e.g., "Front Door", "Driveway")
   - **RTSP URL** &mdash; Full RTSP URL including credentials (e.g., `rtsp://admin:password@192.168.1.100:554/stream`)
   - **Snapshot URL** &mdash; Optional HTTP URL for JPEG snapshots (may also contain credentials)
   - **Stream Name** &mdash; Auto-generated from the camera name (lowercase, underscores). Used internally by go2rtc.
   - **Enabled** &mdash; Toggle camera on/off without deleting it
4. Click **Save**

go2rtc automatically restarts with the new camera configuration.

### Credential Security

Camera credentials (in RTSP and snapshot URLs) are handled with care:

- **On initial add:** You type/paste the full RTSP URL with credentials in a plain text field
- **On edit:** The URL is shown redacted (`rtsp://***:***@192.168.1.100:554/stream`). A "Change URL" button reveals an empty password field. Leave it empty to keep the existing URL, or enter a new URL to replace it. The raw credential never leaves the server after initial entry.
- **In the grid:** The URL column always shows the redacted form
- **In logs:** All go2rtc process output is filtered to redact credentials before logging
- **In API responses:** RTSP URLs are never included in Roku or browser API responses

### Managing Cameras

- **Reorder** &mdash; Use the up/down arrow buttons to change display order
- **Test** &mdash; Click to fetch a live snapshot and display it in a dialog (verifies connectivity)
- **Edit** &mdash; Modify name, URL (via blind update), snapshot URL, stream name, or enabled status
- **Delete** &mdash; Remove the camera. go2rtc reconfigures automatically.

### Feature Gating

- The **Cameras** viewer link (Content section) only appears when at least one enabled camera exists
- The **Cameras** admin link (Manage section) is always visible to admins
- The Roku home screen's Cameras button only appears when cameras are configured
- No camera-related UI is shown anywhere when zero cameras exist

### Docker Notes

The go2rtc binary is included in the Docker image. go2rtc binds to `127.0.0.1:1984` inside the container &mdash; **do not** map port 1984 in your docker-compose file. All client access goes through MediaManager's authenticated proxy on port 8080.

---

## Live TV (OTA Tuner)

**Sidebar &rarr; Live TV (in Manage section)**

Media Manager can stream over-the-air (OTA) television via a networked HDHomeRun tuner. The server transcodes live MPEG-2 broadcasts to H.264/AAC HLS for playback in any browser.

### Architecture

```
HDHomeRun (MPEG-TS/MPEG-2/AC-3)
  &rarr; FFmpeg (H.264/AAC HLS transcode, -preset veryfast -tune zerolatency)
    &rarr; LiveTvStreamManager (manages FFmpeg processes, temp dirs)
      &rarr; LiveTvStreamServlet (serves HLS playlist + segments)
        &rarr; Browser (auth via cookie) / Roku (auth via device token)
```

### Adding a Tuner

1. Navigate to **Live TV** in the Manage section of the sidebar
2. Click **Add Tuner**
3. Enter the tuner's IP address and click **Validate**
4. The app connects to `http://{ip}/discover.json` and auto-fills the device name, model, tuner count, and firmware
5. Click **Save**

After adding a tuner, click **Refresh Channels** to import the channel lineup from `http://{ip}/lineup.json`. Channels that have been removed from the lineup are automatically deleted on refresh.

### Channel Management

The channel grid shows all imported channels with:

- **Guide #** and **Name** &mdash; from the tuner's lineup
- **Reception Quality** &mdash; 1&ndash;5 stars (admin-rated). Default 3. Users can filter channels by quality threshold.
- **Enabled** &mdash; Toggle individual channels on/off

Quality ratings can also be set inline while watching in the browser viewer (admin only).

### Content Rating Gate

Live TV access can be restricted by content rating. Set the **Content Rating Minimum** in the Live TV settings card (default: TV-14 / PG-13). Users whose content rating ceiling is below this threshold cannot access live TV streams. Admins and unrestricted users (no rating ceiling) always have access.

### Concurrency Settings

| Setting | Default | Purpose |
|---------|---------|---------|
| **Max Concurrent Streams** | 2 | Global cap on simultaneous FFmpeg transcode processes. Prevents CPU overload. |
| **Idle Timeout (seconds)** | 60 | How long a stream runs with no client requests before FFmpeg is stopped. |

Each user holds at most one active stream. Switching channels automatically stops the previous stream. Multiple users watching the same channel share a single FFmpeg process.

### Roku Integration

Live TV channels appear on the Roku home screen as a carousel row (filtered by each user's quality threshold) and via a dedicated Live TV button. The Roku Video node plays the same HLS streams served by LiveTvStreamServlet. Channel stepping (left/right on the remote) is supported during playback, with a loading overlay shown during the 10&ndash;15 second stream startup.

### Docker Notes

FFmpeg is included in the Docker image. The FFmpeg path and NAS root path are locked in Docker mode &mdash; they cannot be changed from the settings UI.

Temporary stream files are written to `data/live-tv-streams/` inside the container. These are automatically cleaned up when streams stop and on server startup.

---

## Settings

**Sidebar &rarr; Settings**

| Setting | Purpose |
|---------|---------|
| **NAS Root Path** | Root directory of your media files |
| **FFmpeg Path** | Path to FFmpeg binary (auto-detected in Docker) |
| **Roku Base URL** | Base URL for Roku feed poster/stream URLs (auto-detected if blank) |
| **Buddy API Keys** | Named API keys for Transcode Buddy workers (bcrypt-hashed, shown once at creation). Supports multiple keys with per-key delete. |
| **Lease Duration** | How long a buddy lease lasts before expiring |
| **Keepa API Key** | API key from keepa.com for automated replacement value estimation |
| **Tokens per minute** | Keepa API rate limit (default 20, match to your subscription tier) |
| **Enable Keepa** | Activates the background price lookup agent |
| **WebAuthn RP ID** | Relying Party domain for passkey authentication. Must exactly match the domain in users' browser address bar, without protocol or port (e.g., `mm.example.com`; use `localhost` for local development) |
| **WebAuthn RP Origin** | Full origin URL including protocol and port if non-standard (e.g., `https://mm.example.com:8443`). Leave blank to default to `https://<RP ID>` on port 443. Required when using a non-standard HTTPS port. |
| **WebAuthn RP Name** | Display name shown in browser passkey prompts (defaults to "Media Manager") |

### Price Lookup

The price lookup agent uses the **Keepa API** to estimate replacement values by querying Amazon.com (US marketplace) prices. When enabled, it runs in the background pricing eligible items (DVD, Blu-ray, UHD, HD DVD) at the configured token rate.

**Setup:** Subscribe to Keepa at [keepa.com](https://keepa.com), copy your API key, paste it in Settings, enable the agent. At 20 tokens/minute, a 1,000-item collection takes about 50 minutes to price.

**ASIN resolution priority:** The agent resolves each item to an Amazon ASIN using: (1) user-set override ASIN, (2) linked Amazon order ASIN, (3) UPC-to-ASIN via Keepa, (4) title keyword search on Amazon. You can manually set or correct an ASIN from the Valuation edit dialog.

**Price selection:** Uses 30-day weighted average of new 3rd-party prices, falling back to current new, Amazon direct, or used price. Items are re-priced every 30 days.

**Deleting items:** The Valuation edit dialog includes a Delete button for removing incorrectly scanned items (e.g., non-media barcodes). This cascades to all linked data.

---

## User Management

**Sidebar &rarr; Users**

![User management](images/screenshots/users.png)

### Access Levels

| Level | Role | Access |
|-------|------|--------|
| 1 | **Viewer** | Browse, search, watch, wish lists, personalization |
| 2 | **Admin** | Everything above + all Manage section features |

### Per-User Settings

- **Display name** &mdash; Shown in the profile menu and session list
- **Content rating ceiling** &mdash; Maximum MPAA/TV rating this user can see (e.g., PG-13 hides R and NC-17 titles)
- **Subtitles** &mdash; Default subtitle preference for browser and Roku playback

### Actions

- **Reset password** &mdash; Set a new password for any user (also invalidates all passkeys)
- **Promote / Demote** &mdash; Toggle between Viewer and Admin
- **Sessions** &mdash; View and revoke active sessions for any user
- **Passkeys** &mdash; View and delete passkeys registered by any user
- **Delete** &mdash; Remove the account and all associated sessions and passkeys

### Passkey Administration

Users can register passkeys (Face ID, Touch ID, hardware security keys) for faster re-login. Passkeys supplement passwords &mdash; they do not replace them.

As an admin you can view how many passkeys each user has registered in the user list. Click into a user to see their individual passkeys and delete any that should be revoked. Resetting a user's password automatically deletes all their passkeys.

To enable passkey support, set the **WebAuthn RP ID** in Settings to match the domain your users access the site from (e.g., `mm.example.com`). Passkeys will not be offered on the login page until at least one user has registered a passkey and the RP ID is configured.

---

## User Wishes

**Sidebar &rarr; User Wishes**

![User wishes](images/screenshots/purchase-wishes.png)

This page aggregates media wish list requests from all users into a single view, sorted by vote count. When multiple users wish for the same title, their votes are combined so you can prioritize popular requests.

Each row shows the title poster, name (with season number for TV shows), release year, media type, vote count, and the list of users who requested it.

### Setting Acquisition Status

Click the status badge on any row to update its acquisition status:

| Status | Color | Meaning |
|--------|-------|---------|
| **Unknown** | Gray | Not yet reviewed |
| **Ordered** | Blue | You've ordered it &mdash; users see "Ordered" on their wish list |
| **Owned** | Green | Acquired and in the catalog &mdash; automatically fulfills matching user wishes |
| **Rejected** | Red | Won't be purchased &mdash; users see "Won't be purchased" |
| **Not Available** | Gray | Title isn't available for purchase yet |
| **Needs Assistance** | Orange | Requires special handling |

Setting a title to **Owned** triggers automatic fulfillment: all users who wished for that title (and season, for TV) see their wish updated to "Added to collection" or "Ready to watch!" depending on whether a playable transcode exists.

---

## Tags

**Sidebar &rarr; Tags**

Create colored tags to organize your collection. Each tag has a name and a background color (chosen via color picker). Tags appear as pill badges on title detail pages and as filter options in the catalog.

- Create, edit, and delete tags from the tag management page
- Apply tags to titles from the title detail page (admin edit dialog)
- Browse tagged titles at the tag detail page

---

## Valuation & Inventory

**Sidebar &rarr; Valuation**

Track what you paid for each disc &mdash; useful for insurance documentation or curiosity. Links to Amazon order data when available. The edit dialog also shows any evidence photos attached to the item, with download links.

### Document Ownership

**Sidebar &rarr; Document Ownership**

Capture proof-of-ownership photos for insurance claims. This mobile-friendly view supports two workflows:

1. **Scan &amp; Snap** &mdash; Scan a UPC barcode to identify a media item, then take photos of the disc case or shelf using the device camera.
2. **Search &amp; Snap** &mdash; Search for an item by title or UPC, then capture photos.

Photos are stored as original files (EXIF/GPS metadata preserved) in `data/ownership-photos/` on disk, with only metadata tracked in the database.

### Insurance Report

**Sidebar &rarr; Report**

Generates a downloadable PDF and CSV inventory summary. The PDF includes:

- **Executive summary** &mdash; total items, format breakdown, documented value, evidence coverage percentage
- **Gap analysis** &mdash; estimates the value of unpriced items using format-average pricing
- **Detailed item tables** &mdash; grouped by retailer, with purchase dates, prices, and replacement values
- **Ownership photos** (optional) &mdash; check the "Include ownership photos in PDF" box to embed evidence photos inline below each item, approximately 1 inch tall

---

## Monitoring

HTTP access logs and application log records are shipped to Binnacle via
SLF4J (`http.access` and per-class loggers flow through `BinnacleExporter`).
Query them there rather than on the server itself.

### Health Check

**`/health`** (internal port 8081, not on main app port) &mdash; Returns HTTP 200 when the server is running. Used by Docker health checks and monitoring tools. In Docker, mapped to LAN port 16002. Not internet-accessible.

### Prometheus Metrics

**`/metrics`** (internal port 8081, not on main app port) &mdash; Application metrics in Prometheus exposition format. JVM stats, transcode queue depth, HTTP request counts, and more. Configure Prometheus to scrape `<nas-ip>:16002/metrics`.

---

## Active Sessions

Each user can view their active sessions from the profile menu &rarr; **Active Sessions**. Admins can also view and revoke sessions for any user from the Users page.

Sessions include:
- **Browser sessions** &mdash; 30-day cookie-based login, showing browser/OS summary and last-used time
- **Device tokens** &mdash; Permanently paired Roku devices, showing device name

Admins can revoke individual sessions or use "Revoke All Other Sessions" as a security measure.

---

## Database Backups &amp; Restore

### Automated Backups

The server creates rolling database backups automatically:

- **6 daily backups** &mdash; one per day, rotating through slots 0&ndash;5
- **4 weekly backups** &mdash; one per Sunday, rotating through slots 0&ndash;3
- First backup runs 1 minute after startup, then every 24 hours
- Stored in `data/backups/` (inside the container volume)

When `H2_FILE_PASSWORD` is set, backups are AES-encrypted using H2's `CIPHER AES` (no plaintext ever touches disk). Without encryption, backups are gzip-compressed SQL scripts.

| Encryption | Daily files | Weekly files |
|------------|-------------|--------------|
| Enabled | `daily-N.sql.enc` | `weekly-N.sql.enc` |
| Disabled | `daily-N.sql.gz` | `weekly-N.sql.gz` |

### Restoring from a Backup

To restore the database from a backup:

1. **Stop the server** (or plan for a restart)
2. **Copy the desired backup** to `data/restore.sql`:
   ```bash
   cp data/backups/daily-3.sql.enc data/restore.sql
   ```
3. **Start (or restart) the server**

On startup, the server detects `data/restore.sql` and automatically:
1. Backs up the current database to `data/mediamanager.mv.db.pre-restore`
2. Creates a fresh database and imports the backup
3. Deletes the sentinel file
4. Continues normal startup (Flyway migrations, etc.)

If the import fails, the previous database is restored and the sentinel is deleted.

**Important:** The backup file must match the current encryption setting. If `H2_FILE_PASSWORD` is set, the restore file must be an encrypted backup (`.sql.enc`). If not set, it must be a plain/gzip backup (`.sql.gz`).

### Docker Restore Example

```bash
# List available backups
docker exec mediamanager ls -la /app/data/backups/

# Copy a backup to the sentinel location
docker exec mediamanager cp /app/data/backups/daily-2.sql.enc /app/data/restore.sql

# Restart the container — restore happens automatically on startup
docker restart mediamanager
```

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
