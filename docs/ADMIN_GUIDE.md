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

There are three ways to add titles:

### Barcode Scanning

**Sidebar &rarr; Scan New Purchase**

Type or scan a UPC barcode. The system:

1. Looks up the barcode via UPCitemdb (free, no API key)
2. Creates a catalog entry with the product name
3. Cleans the title (strips marketing text like "Blu-ray + Digital")
4. Searches TMDB for the canonical title, poster, cast, genres, and description
5. Detects multi-packs ("Double Feature", "Trilogy") for manual expansion

The UPC API has a daily limit of 100 lookups. A quota tracker in the UI shows remaining capacity.

### Manual TMDB Entry

**Sidebar &rarr; Add Title**

Search TMDB directly and add a title without a barcode. Useful for discs where you've thrown away the case. The title is immediately enriched with full TMDB data.

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

## Settings

**Sidebar &rarr; Settings**

| Setting | Purpose |
|---------|---------|
| **NAS Root Path** | Root directory of your media files |
| **FFmpeg Path** | Path to FFmpeg binary (auto-detected in Docker) |
| **Roku Base URL** | Base URL for Roku feed poster/stream URLs (auto-detected if blank) |
| **Buddy API Keys** | Named API keys for Transcode Buddy workers (bcrypt-hashed, shown once at creation). Supports multiple keys with per-key delete. |
| **Lease Duration** | How long a buddy lease lasts before expiring |

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

- **Reset password** &mdash; Set a new password for any user
- **Promote / Demote** &mdash; Toggle between Viewer and Admin
- **Sessions** &mdash; View and revoke active sessions for any user
- **Delete** &mdash; Remove the account and all associated sessions

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

Track what you paid for each disc &mdash; useful for insurance documentation or curiosity. Links to Amazon order data when available. The inventory report (Sidebar &rarr; Report) generates a downloadable summary.

---

## Monitoring

### Request Log

**`/admin/requests`** (internal port 8081, not on main app port) &mdash; In-memory log of the last 200 HTTP requests. Filterable by user-agent, path, and status code. Access via `http://<nas-ip>:16002/admin/requests` on your LAN. Not internet-accessible.

### Application Log

**`/admin/logs`** (internal port 8081, not on main app port) &mdash; Recent application log messages (errors, warnings, info) in a color-coded table. Stack traces are expandable. Filterable by severity and logger name. Access via `http://<nas-ip>:16002/admin/logs` on your LAN. Not internet-accessible.

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
