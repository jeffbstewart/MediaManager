<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Feature Tracker

Planned and in-progress features. See [Completed Features](#completed-features)
at the end for previously shipped work.

Status key: `[ ]` planned · `[~]` in progress · `[x]` done

---

---

## iOS App

`[ ]` **Native iOS app (iPhone + iPad) backed by this server's API**

Build a mobile client that talks to the exposed server API:

- REST or GraphQL API layer on the server (separate from Vaadin UI)
- Authentication via tokens (JWT or similar) from the login facility
- Browse library, search titles/actors, view details
- In-app video playback (AVPlayer with HLS or progressive MP4)
- **Mobile transcode profile**: optional smaller/lower-bitrate transcode
  setting (e.g., 720p, lower CRF) for bandwidth-conscious mobile streaming
- **Offline caching**: download videos to device for offline viewing
  - Track download state per-user per-title
  - Manage storage (delete cached videos, show space used)
- Push notifications (new titles added, transcode completed, etc.)
- SwiftUI preferred; support iOS 16+

### App Store Considerations

**Distribution:** TestFlight is the realistic path for personal/family
use. Avoids most App Store review friction, supports up to 10,000
testers, but builds expire after 90 days (requires periodic refresh).

**Potential barriers if submitting to the public App Store:**

- **DRM (FairPlay):** Apple requires DRM for copyrighted content
  streaming. This app streams ripped physical media as plain MP4.
  Plex/Jellyfin/Infuse set precedent that personal media servers are
  acceptable, but Apple has been inconsistent. TestFlight avoids this.
- **Guideline 4.2 (Minimum Functionality):** A WKWebView wrapper
  around the Vaadin UI would be rejected. Must build a native SwiftUI
  interface consuming a JSON API.
- **Sign in with Apple:** Apps with account creation must offer SIWA
  as an option. Can be avoided if the app only logs in (account
  creation happens via the web UI).
- **Guideline 4.3 (Copycat):** Must not look too similar to
  Plex/Jellyfin. Less of a concern for TestFlight.
- **Server unavailability:** App must handle offline/unreachable
  server gracefully (error screen, not crash).

---

## Roku Remote Control via ECP

`[ ]` **Drive the Roku UI remotely via the External Control Protocol**

The Roku External Control Protocol (ECP) is an HTTP REST API on port 8060 that
allows programmatic control of the device. This enables Claude to navigate the
channel, trigger actions, and verify behavior without a physical remote.

**Key ECP endpoints:**

| Action | Command |
|--------|---------|
| Key press | `curl -d '' http://$ROKU_IP:8060/keypress/Select` |
| Key down/up | `curl -d '' http://$ROKU_IP:8060/keydown/Right` |
| Launch channel | `curl -d '' http://$ROKU_IP:8060/launch/dev` |
| List installed apps | `curl http://$ROKU_IP:8060/query/apps` |
| Current app info | `curl http://$ROKU_IP:8060/query/active-app` |
| Device info | `curl http://$ROKU_IP:8060/query/device-info` |
| Media player state | `curl http://$ROKU_IP:8060/query/media-player` |
| Send text input | `curl -d '' 'http://$ROKU_IP:8060/keypress/Lit_H'` (one char at a time) |
| App icon | `curl http://$ROKU_IP:8060/query/icon/dev` |

**Limitations:**
- No screenshot capture via ECP
- No direct access to SceneGraph node tree via ECP — use telnet port 8087
- ECP is unauthenticated — any device on the local network can control the Roku
- Response is XML, not JSON

---

## Family Movies / Home Video Support

`[ ]` **First-class support for non-TMDB personal videos (family movies, home videos)**

The NAS scanner now auto-discovers all top-level directories, which means
personal video folders (e.g., `Family Movies/`) are picked up as unmatched
files. These need a different workflow since they have no TMDB metadata.

### Requirements

- **Manual title creation** for family videos (no UPC, no TMDB enrichment)
- **Assisted hero image selection**: let the user pick a frame from the video
  as the title's poster/hero image. Generate a grid of thumbnail candidates
  (e.g., evenly-spaced frames via FFmpeg) and let the user choose one.
  Store the selected frame as the title's poster image in the local cache.
- **Custom metadata**: free-form description, date filmed, people/tags
  (no TMDB genres — user-defined tags only)
- **Skip enrichment**: family movies should be created with `SKIPPED`
  enrichment status so the enrichment agent ignores them
- **Playback**: works the same as any other transcode — ForBrowser
  transcoding, streaming, Roku feed inclusion (if desired)
- **Optional Roku feed exclusion**: family videos may want a separate
  feed or a toggle to exclude from the main Roku channel

### Hero Image Selection UX

1. User creates a family movie title and links it to a NAS file
2. System generates N thumbnail candidates (e.g., 12-16 frames evenly
   spaced through the video) via FFmpeg
3. UI presents a grid of candidates; user clicks to select
4. Selected frame is cropped/scaled to poster dimensions and saved
   to the poster cache as the title's poster image
5. Fallback: user can upload their own image instead of picking a frame

---

## Backlog (Low Priority)

### Popularity Refresh

`[ ]` Slowly refresh all TMDB popularity scores over time (e.g., ~1% per day
so a full cycle completes every few months). Applies to both `Title.popularity`
and `CastMember.popularity`.

### Mobile Offline Playback

`[ ]` **Low-fidelity transcodes for offline storage on mobile devices**

Enable downloading smaller-format transcodes to a mobile device (likely
iPad) for offline viewing — planes, road trips, etc.

- **Mobile transcode profile**: separate FFmpeg preset targeting small
  file sizes (e.g., 720p, CRF 23-28, lower audio bitrate). Stored
  alongside existing ForBrowser transcodes in a `ForMobile/` mirror
  directory on the NAS
- **On-demand or background**: transcode to mobile format when a user
  requests a download, or batch-transcode popular titles in advance
- **Download endpoint**: serve the mobile transcode with
  `Content-Disposition: attachment` for save-to-device
- **Offline support**: may require a native iOS app with local storage management
- **Download tracking**: record which users have downloaded which
  titles, for cleanup prompts and storage accounting

---

---

## Unmatched Transcode Notification

`[x]` **Alert admins when unmatched transcode files need attention** *(done r393)*

Red pill badge on the "Unmatched" drawer item and "Transcodes" parent
group (when collapsed) showing the count of unmatched discovered files.
Only visible to admin users, only when count > 0. Ignored files
(match_status = IGNORED) do not contribute to the count.

---

## Scheduled NAS Scan

`[x]` **Run NAS scans automatically on a daily schedule** *(done r387)*

Currently the NAS scan must be triggered manually from the Transcodes >
Status page. Add a background scheduler that runs a full NAS scan once
per day (e.g., overnight) so newly added or removed files are discovered
without admin intervention.

- Configurable schedule (default: daily at 2:00 AM server time)
- Setting in Transcodes > Settings to enable/disable and set the time
- Keep the existing manual "Scan Now" button for on-demand use
- Log scan results (files discovered, matched, removed) as usual

---

## Speed Up Gradle Build

`[x]` **Reduce local `./gradlew build` time** *(done r397)*

Profiled with `--profile`. Clean build was 53s, incremental 27s (already
much faster than the original 4-6 min estimate thanks to configuration
cache). Applied three optimizations:

- **Parallel project execution** (`org.gradle.parallel=true`) — compiles
  `transcode-common` and `transcode-buddy` concurrently with main module
- **Test parallelism** (`maxParallelForks = cores/2`) — runs test classes
  concurrently (safe: each DB test uses a unique H2 in-memory URL)
- **Increased JVM heap** (`-Xmx2048m`) — reduces GC pressure during
  Kotlin compilation

Results: clean 53s → 45s (15%), incremental 27s → 21s (22%). Tests were
the biggest win (21.6s → 15.4s). Remaining time dominated by Kotlin
compilation (15s) which is inherent to the codebase size

---

## Multi-User Roku Support

`[ ]` **User picker on Roku startup when multiple users are paired**

When multiple users have paired with the same Roku device (multiple
device tokens in the registry), show a user selection screen on channel
launch:

- Display user avatars/names in a horizontal list
- Selected user's token is used for all subsequent API calls (feed
  fetch, playback progress, subtitle preferences)
- "Add User" button initiates the QR code pairing flow for a new user
- "Remove User" option to unpair a user from this device
- Last-used user is pre-selected on next launch

---

## Session Revocation Notification Timing

`[ ]` **Bug: UI notifications from session revocation appear on next navigation, not immediately**

When revoking a session (single or "Revoke All Other Sessions"), any
success/error notifications don't appear at the time of the action —
they show up later when the user navigates to a different page. The
notification should display immediately after the revocation completes.

- Likely cause: `Notification.show()` is being called but the UI push
  isn't flushing immediately (missing `ui.access {}` wrapper or
  `ui.push()` call)

---

## Transcode Buddy Authentication Improvement

`[ ]` **Replace shared API key auth with device-token pairing for buddy workers**

The transcode buddy currently authenticates via bcrypt-hashed API keys
stored in the `buddy_api_key` table (multiple named keys, shown once at
creation). This is a static secret that must be manually copied to the
buddy's `buddy.properties` config file.

Replace with a pairing flow similar to the Roku QR code pairing,
adapted for headless CLI workers:

- **CLI pairing command**: `transcode-buddy --pair` enters pairing mode,
  calls `POST /api/pair/start` with `device_name` set to hostname,
  displays the pair code on stdout, polls for confirmation
- **Web UI confirmation**: Admin navigates to Settings or a new "Devices"
  page, enters the pair code to authorize the buddy
- **Device token**: Buddy receives a permanent device token, stores it
  in `buddy.properties` (or a separate `buddy-token` file)
- **Per-buddy identity**: Each buddy authenticates with its own token,
  enabling per-buddy audit trails and selective revocation
- **Migration path**: Keep `buddy_api_key` support temporarily for
  backward compatibility, log deprecation warnings when used

---

## Roku Token Revocation Flow

`[ ]` **Bug: Revoking the Roku auth token does not restart the QR-code pairing flow**

When a Roku device token is revoked (from Active Sessions), the Roku should
detect the 401 on its next feed fetch and re-enter the QR code pairing flow.
Instead, it currently appears to trigger a now-nonexistent configuration flow
(the old manual server URL + API key settings screen).

- On 401 response, the Roku channel should clear its stored device token
  and re-enter the SSDP discovery + QR code pairing flow
- Should NOT fall back to the legacy SettingsScreen (manual IP/key entry)
- Verify the channel handles the transition cleanly (no leftover state)

---

## Roku Navigation Freeze After Playback

`[ ]` **Bug: After completing a video on Roku, navigation freezes**

After a video finishes playing on the Roku channel, the user cannot start
another video or navigate. The channel appears to be stuck.

- Likely cause: the Video node is not being properly closed/destroyed after
  playback completes, blocking the scene graph's focus and key handling
- Investigate `onVideoStateChange` for the "finished" state — ensure the
  video node is removed from the scene and focus returns to the previous
  screen (DetailScreen or EpisodePicker)
- Test with both movies (return to DetailScreen) and TV episodes (return
  to EpisodePicker, possibly auto-advance to next episode)

---

## Roku Trick Play with Thumbnail Sprites

`[ ]` **Leverage thumbnail sprites for visual seek preview on Roku**

When the user presses fast-forward or rewind on the Roku remote during
playback, show thumbnail sprite previews so they can visually navigate
to the desired point in the video.

- Roku's Video node supports trick play via `trickPlayBar` fields and
  BIF (Base Index Frames) format for thumbnail previews
- Option A: Generate BIF files from existing sprite sheets (convert
  JPG sprite grids to individual frames, package as BIF)
- Option B: Generate BIF files directly during the ForBrowser
  transcoding pass (FFmpeg frame extraction → BIF packaging)
- BIF spec: simple binary format — 8-byte header, frame index table,
  concatenated JPEG frames. Roku SDK documents the format
- Serve BIF files via a new endpoint or extend VideoStreamServlet
- Wire into the Roku feed as a `trickPlayUrl` field on each content node

---

## Security Audit Findings (2026-03-07)

Findings from the March 2026 security audit, organized by implementation priority.

### Immediate Priority

#### Hash Buddy API Key

`[x]` **Store buddy API key as a hash instead of plaintext** *(done r375)*

Replaced plaintext `buddy_api_key` in `app_config` with a dedicated
`buddy_api_key` table supporting multiple named keys. Keys are
bcrypt-hashed (cost 12) and shown only once at creation time.
UI supports create, revoke, and delete. Migration V042 drops the
old plaintext key.

#### Atomic Account Lockout

`[x]` **Fix TOCTOU race condition in account lockout** *(done r383)*

`AuthService.kt:136-156` reads the user, checks `locked`, sets
`locked = true`, then saves. A concurrent request could slip between
the check and the save. Use an atomic SQL update instead:
`UPDATE app_user SET locked = TRUE WHERE id = :id AND locked = FALSE`.

#### Protect Metrics and Health Endpoints

`[x]` **Move `/metrics` and `/health` to internal-only port** *(done r381)*

Moved both endpoints off the main app port (8080) to a separate
internal Jetty server on port 8081 (container-internal). Docker
maps this to LAN port 16002, which is not internet-routable.
Prometheus scrapes via `172.16.4.12:16002/metrics`.

### Short-Term Priority

#### H2 Database Encryption at Rest

`[x]` **Enable H2 file encryption via `CIPHER=AES`** *(done r382)*

The database file at `./data/mediamanager.mv.db` is stored unencrypted.
If the host filesystem is compromised, all data (password hashes,
session token hashes, API keys) is readable. H2 supports transparent
AES encryption via the JDBC URL: `jdbc:h2:file:./data/mediamanager;CIPHER=AES`.

- Requires a file password (separate from DB user password)
- Migration: export data, recreate DB with cipher, re-import
- Document the file password management in deployment guide

#### Docker Secrets for Environment Variables

`[ ]` **Replace plaintext env vars in docker-compose with Docker secrets**

Environment variables in `docker-compose.yml` are visible via
`docker inspect`. Use Docker secrets (`docker secret create`) or an
`env_file:` directive pointing to a file excluded from version control.

#### Daily Rate Limit Cap

`[x]` **Add a daily failure limit in addition to the 15-minute window** *(done r385)*

The current rate limiter uses a 15-minute sliding window with a
5-failure threshold. An attacker spacing attempts can make ~96
guesses/day. Add a longer-term cap (e.g., 100 failures per day per
IP or username) to limit slow-rate brute force attacks.

#### NAS File Discovery Symlink Check

`[x]` **Skip symbolic links during NAS file discovery** *(done r384)*

`NasScannerService.kt` uses `Files.walk()` which follows symlinks by
default. An attacker with NAS write access could plant symlinks
pointing outside the media directory. Use `LinkOption.NOFOLLOW_LINKS`
or check `Files.isSymbolicLink()` and skip.

### Longer-Term Priority

#### Admin Re-Authentication for Password Resets

`[x]` **Require admin to enter their own password before resetting another user's** *(done r380)*

`UserManagementView.kt:247-309` allows admins to reset any user's
password without re-authenticating. Add an admin password confirmation
field to the reset dialog to guard against compromised admin sessions.

#### Hash Usernames in Login Attempt Table

`[~]` **Store hashed usernames in `login_attempt` instead of plaintext** *(accepted risk)*

Attempted usernames are stored unhashed, but this is low-value to fix:
the `app_user` table already stores usernames in plaintext (the real
enumeration target), and the database is encrypted at rest via H2
`CIPHER=AES`. Hashing would also lose useful audit information about
what usernames attackers are guessing. Keeping plaintext usernames in
`login_attempt` as an accepted risk.

#### Buddy API Hardening

`[ ]` **Add per-buddy lease limits, probe validation, and audit logging**

Three related improvements to the buddy API:

- **Lease limits**: Cap simultaneous lease claims per buddy (e.g., max 3)
  to prevent a compromised key from starving other workers
- **Probe validation**: Validate JSON probe data schema and size before
  parsing (limit stream array to <100 items, validate field types)
- **Audit logging**: Log all lease operations (claim, complete, fail,
  heartbeat) with buddy name and timestamp for traceability

#### OWASP Dependency Check

`[x]` **Add OWASP dependency-check Gradle plugin for automated CVE scanning** *(done r391)*

OWASP dependency-check plugin with `./gradlew recordDepScan` task.
Presubmit script warns if scan is >30 days stale. Suppressions file
for false positives (kotlin-css-jvm, disputed jackson CVE). First
scan found CVE-2026-1605 (Jetty GzipHandler memory leak); fixed by
forcing Jetty 12.1.6.

### Accepted / Documented Risks

The following audit findings were reviewed and accepted as low-risk
given the deployment context (private network, single household):

- **Session token SHA-256 without salt** — Tokens are 128-bit UUIDs
  (high entropy); rainbow tables are impractical. Accepted.
- **Cookie Secure flag conditional on HTTPS** — Production runs behind
  a reverse proxy with TLS. Development on localhost is HTTP-only by
  design. Accepted with documentation.
- **Roku API key in feed URLs** — Required by Roku's stateless HTTP
  client. Mitigated by HTTPS in production. Accepted.
- **Password policy length-only** — BCrypt cost 12 mitigates brute
  force. Complexity requirements frustrate users more than they help.
  Accepted.
- **HSTS not set by application** — Delegated to reverse proxy, which
  is the standard Vaadin deployment pattern. Documented as deployment
  requirement.
- **In-memory log buffers** — Small ring buffers, admin-only access.
  Sensitive data scrubbing would add complexity for minimal benefit.
  Accepted.

---

## User Self-Service Account Management

`[x]` **User self-service password changes** *(done r389)*

Users can change their own password from the profile menu (Change
Password). Password policy enforced everywhere (min 8, max 128,
cannot match username, must differ from current). Admins can force a
password change on next login via the Users page. The
`must_change_password` flag intercepts navigation and redirects to the
change-password page until the user complies.

Remaining: self-service account deletion (deferred)

---

## Automated Database Backups

`[x]` **Keep rolling daily and weekly backups of the H2 database** *(done r386)*

The database contains expensive-to-replicate data: UPC scan history,
file-to-title mappings, manually added movies, pricing data, user
accounts, and enrichment state. Guard against corruption or mass
unwanted changes with automated backups.

- **6 daily backups**: snapshot once per day, retain the last 6
- **4 weekly backups**: snapshot once per week, retain the last 4
- Use H2's `SCRIPT TO` or file copy (with DB quiesced) for consistency
- Store backups in a configurable directory (default `./data/backups/`)
- Run as a scheduled task in the cleanup scheduler (already in Main.kt)
- Log backup success/failure; expose backup status in `/health` or Settings
- Consider: compressed SQL script (portable, smaller) vs. file copy (faster restore)

---

## Purchase Wish List: Season Display Bugs

`[ ]` **Bug: Admin wish list view doesn't display season information**

When a user wishes for a specific season of a TV show, the admin
purchase wish list page doesn't show which season was requested.

`[ ]` **Bug: Multi-season wishes appear as multiple votes instead of separate items**

When a user wishes for multiple seasons of the same show, they appear
as duplicate votes for the same title rather than separate line items.
Seasons should be displayed as individual wish list entries since
seasons may not all become available on media at the same time.

---

## Inventory Report: PDF + CSV Formats

`[x]` **Support insurance inventory report in both PDF and CSV format** *(done r395)*

Report page offers both PDF (for printing/archiving) and CSV (for
Excel users). CSV includes all items in a single flat table with
columns: Title(s), Format, UPC, Purchase Date, Purchase Place,
Order #, Purchase Price.

---

## Migrate Feature Tracker to GitHub Issues

`[ ]` **Move planned features and bugs from this file to GitHub Issues**

Once the repository is on GitHub, migrate all open items from this file
into GitHub Issues with appropriate labels:

- Create `enhancement` issues for each planned feature
- Create `bug` issues for each known bug
- Preserve the detail from this file in the issue body
- Keep the completed features table in this file as historical record
- Delete the planned/in-progress sections once migrated
- Use `gh issue create` via Claude Code to automate the migration

---

# Completed Features

Previously completed features, listed in implementation order. Details removed;
see git history for full specifications.

| Feature | Summary |
|---------|---------|
| Manage Submenu | Admin nav items consolidated into "Manage" dropdown |
| Authentication | User login with bcrypt-hashed passwords, session management |
| Authorization | Route-level and servlet-level auth checks, admin gating |
| First-User Setup | `/setup` wizard for initial admin account creation |
| Docker Packaging | Multi-stage Dockerfile, docker-compose, Synology deployment |
| Wish Lists | Per-user media and transcode wish lists with priority transcoding |
| Security Hardening | TLS, rate limiting, input validation, session policies |
| Roku App | BrightScript channel with feed, playback, QR pairing |
| Request Log | In-memory HTTP request log at `/admin/requests` |
| App Log | In-memory application log at `/admin/logs` |
| Roku Debug Logging | `[MM]`-prefixed print statements throughout channel code |
| Roku Deploy | `roku-deploy.sh` sideloading automation |
| Roku Debug Tailing | `roku-debug.sh` for streaming debug console output |
| Roku Transcoding | Roku-compatible MP4 output (SAR, frame rate, SEI, deinterlace) |
| HTTP Streaming | Full-range 206 Partial Content with proper headers |
| Transcode Buddy | Distributed GPU transcoding via REST API lease system |
| Prometheus Metrics | `/metrics` endpoint with Micrometer + JVM binders |
| Expand View Enter Key | Enter key triggers search in Expand view |
| Audit Endpoints | Verified no non-admin endpoints accept client-supplied user IDs |
| Manual Title Entry | Add titles via TMDB search without UPC barcode |
| Amazon Import | Bulk import purchase dates/prices from Amazon order CSV |
| Similar Titles | Genre, cast, and TMDB recommendation-based similarity scoring |
| Content Ratings | MPAA/TV ratings with per-user ceiling filtering |
| Video Thumbnails | Sprite sheet generation + WebVTT seek preview on progress bar |
| Auto-Play Next | "Up Next" prompt with countdown near end of TV episodes |
| Filter Credits | Hide incidental TV appearances from actor "Other Works" |
| User Tags | Admin-managed colored tags, star (priority transcode), personal hide |
| Playback Progress | Per-user resume, continue watching, viewed flags, Roku sync |
| Enhanced Search | In-memory inverted index with phrases, negation, tag operators |
| Inventory Export | Downloadable CSV valuation report for insurance documentation |
| Bulk Tag Seeding | Auto-create genre tags from TMDB data via SchemaUpdater |
| TMDB Wish Search | Search TMDB directly from wish list page |
| Missing Seasons | Detect unowned TV seasons, home page notifications, season wishes |
| Typed TMDB ID | `TmdbId` data class pairing integer ID with media type namespace |
| Security Audit | Address/accept all findings from 2026-03-04 security audit |
| Continue Watching | Home screen rows, progress bars, resume indicators, Roku sync |
| Roku QR Pairing | SSDP discovery + QR code phone-mediated device pairing |
| Device Token Lifecycle | Permanent tokens with revocation on password change |
| Active Sessions | User-facing session list with revocation, admin view |
| Dual-Location Sprites | Thumbnail sprites copied to both ForBrowser and source dirs |
| Roku Subtitles | Per-user subtitle preference, SRT tracks in Roku feed |
| README & Docs | Architecture diagram, prerequisites, documentation site |
| Remove Roku API Key | Dropped `roku_api_key` from DB, UI, and auth; device tokens only |
| Scrub Sensitive Data | Removed hardcoded IPs, leaked keys, and secrets from all tracked files |
| Remove Catalog Tags | Removed franchise and decade tag auto-population; genre tags only |
| Browse View | Poster grid at `/catalog` with sort, filter chips, tag chips, playable toggle |
| Data Quality View | Admin enrichment triage at `/data-quality` with status badges and filters |
| Tag Browsing | Tag chips in browse view for OR-mode filtering by tag |
| Smart Tag Association | Auto-associate tags from TMDB genres and collections on enrichment |
| Collection Sort Names | Auto-generate sort keys from TMDB collection order (e.g., "Back to the Future Collection 001") |
| Probe-Based Format Detection | FFprobe resolution analysis replaces directory-name format inference; `.mm-ignore` marker files; auto-classify TV vs Movie by directory structure (r404) |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
