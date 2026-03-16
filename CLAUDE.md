# CLAUDE.md

## Project Overview

mediaManager is a Kotlin web application for managing physical media collections (DVD, Blu-ray, UHD, HD DVD). It catalogs titles via UPC barcode scanning, enriches them with TMDB metadata, discovers transcoded media files on a NAS, links them to catalog titles, and provides in-browser video playback via pre-transcoded ForBrowser cache. Built with Vaadin-on-Kotlin for server-side rendered UI (no JavaScript/npm toolchain) with an H2 embedded database.

The history of the app's evolution is recorded in `claude.log` in the project root.

## Working Directory

At the start of every session, `cd` to the project root (`/c/Programming/github/MediaManager`) as your **first Bash command** so all subsequent commands can use relative paths without repeating the `cd` prefix. Do this before any other shell operations on the mediaManager project.

## Build and Run Commands

```bash
./gradlew build          # Build the project (production mode)
./gradlew --no-daemon run            # Run the server (localhost:8080, production mode)
./gradlew test           # Run tests
./gradlew clean build    # Clean and rebuild
./gradlew --no-daemon run -Pvaadin.devMode   # Run with Vaadin dev mode (Vite dev server)
```

**Important:** Always use `--no-daemon` when running the server to avoid Gradle daemon locking problems. Kill existing Java processes first (`taskkill //F //IM java.exe`) if needed.

## Docker Deployment

```bash
./lifecycle/deploy-all.sh         # Stop buddy, build Docker + push + Watchtower, restart buddy
./lifecycle/docker-build.sh       # Build image, tag, push to registry, trigger Watchtower redeploy
```

Use `deploy-all.sh` for changes that affect both the server and the transcode buddy (shared code in transcode-common, buddy code, or server code). Use `docker-build.sh` alone for server-only changes.

**Buddy commands:**
```bash
./lifecycle/run-buddy.sh             # Start buddy (output to data/buddy.log)
./lifecycle/stop-buddy.sh            # Stop buddy
./lifecycle/buddy-log.sh             # Last 50 lines of buddy log
./lifecycle/buddy-log.sh -f          # Live follow
```

For Watchtower config, health/metrics server, and Prometheus setup, see `docs/ADMIN_GUIDE.md`. For transcode buddy architecture, see `docs/TRANSCODE_BUDDY.md`.

## Architecture

- **UI Framework:** Vaadin 25 via Vaadin-on-Kotlin (VoK) — server-side rendered, no JavaScript
- **UI DSL:** Karibu-DSL for type-safe Kotlin Vaadin component building
- **Server:** Embedded Jetty via vaadin-boot (started from `main()`, no app server deployment)
- **Database:** H2 in file mode (`./data/mediamanager.mv.db`)
- **Connection Pool:** HikariCP
- **Migrations:** Flyway — SQL files in `src/main/resources/db/migration/`, naming convention `V{NNN}__{description}.sql`
- **ORM:** vok-framework-vokdb (jdbi-orm under the hood)
- **Logging:** SLF4J with custom BufferingServiceProvider (stderr + in-memory ring buffers at `/admin/logs`)
- **Build:** Gradle 9.3.1 with Kotlin DSL, version catalog in `gradle/libs.versions.toml`
- **JDK:** Corretto 25 (Java 21+ required by Vaadin 25.x)
- **Package:** net.stewart.mediamanager

For the full file listing (views, entities, services, servlets), see `docs/index.md`.

### Database

H2 stores its file at `./data/mediamanager.mv.db` (in the `data/` directory, which is gitignored). Flyway manages schema migrations via SQL files in `src/main/resources/db/migration/`, applied in lexicographic order on startup.

**Encryption at rest:** When `H2_FILE_PASSWORD` is set, the database uses AES encryption (`CIPHER=AES`). On first startup with the env var, Bootstrap automatically exports, backs up, encrypts, and reimports. Once encrypted, `H2_FILE_PASSWORD` is required on every startup.

### External APIs

- **UPCitemdb** — Free trial tier, no API key required. Per-IP throttling: 6 requests/minute, 100/day. Used for barcode-to-product lookup.
- **TMDB (The Movie Database)** — Requires API key via `secrets/.env` file (`TMDB_API_KEY`). Used for canonical title names, poster images, release years, and descriptions. A TMDB API key is required for a functional catalog — without it, there are no poster images, cast data, descriptions, or popularity sorting.
  - **IMPORTANT: TMDB ID namespaces are separate for movies and TV shows.** A movie and a TV show can share the same integer ID (e.g., movie 253 = "Live and Let Die", TV 253 = "Star Trek"). Any lookup, dedup, or comparison involving `tmdb_id` **must also consider `media_type`**. Failing to do so causes cross-type collisions — titles silently reused, wishes fulfilled for the wrong type, etc. Always pair `tmdb_id` with `media_type` in all queries and set operations.
- **Keepa** — Amazon price tracking API. Requires paid subscription. API key stored in `app_config` (`keepa_api_key`). **Amazon.com (US, domain=1) only.** Token-based rate limiting configured via `keepa_tokens_per_minute` in Settings.

### Video Playback

Two modes: **In-Browser** (HTML5 `<video>` via `VideoStreamServlet`) and **Roku** (custom sideloaded BrightScript channel consuming `/roku/feed.json`). MP4/M4V stream directly; MKV/AVI served from pre-transcoded ForBrowser mirror. Background `TranscoderAgent` batch-transcodes to browser/Roku-compatible MP4, prioritized by TMDB popularity.

For FFmpeg command details, codec requirements, and Roku-specific output flags, see `docs/TRANSCODE_BUDDY.md`.

### Live Camera Streaming

Architecture: `Camera (RTSP) → go2rtc → HLS/MJPEG → MediaManager proxy → clients`. Cameras configured in DB, administered via `/cameras/settings`. `UriCredentialRedactor` redacts RTSP credentials everywhere. go2rtc binary path via `app_config` key `go2rtc_path`. Never port-map go2rtc's API port (1984) in docker-compose.

### Live TV Streaming

Architecture: `HDHomeRun (MPEG-TS) → FFmpeg (HLS) → LiveTvStreamManager → LiveTvStreamServlet → Browser/Roku`. Admin enters HDHomeRun IP (IPv4-only, no SSRF). Concurrency controls: global max streams, per-tuner limits, per-user stream replacement, idle timeout. Content rating gate and per-user quality filter on channels.

### Schema Updater Framework

Programmatic data updates that need Kotlin code (API calls, computation). Flyway handles DDL; SchemaUpdaters handle data population. Tracked in `schema_updater` table (name PK, version, applied_at). Bumping an updater's version re-triggers it on next startup. Runner called from Bootstrap.init() after Flyway migrations.

### CLI Flags

See `docs/ADMIN_GUIDE.md` for the full CLI flags table. Key ones: `--developer_mode`, `--port N`, `--max_transcode_deletes N`, `--disable_local_transcoding`, `--internal_port N`.

### Decisions Made

- **Vaadin-on-Kotlin over Ktor+Angular/JS** — eliminates npm/node/webpack entirely. UI is rendered server-side; browser receives DOM diffs via WebSocket.
- **H2 over SQLite** — native Java, no JNI/native wrappers, fully supported by VoK/Flyway/HikariCP ecosystem. File-mode gives same single-file-DB experience.
- **Flyway for migrations** — standard Java migration tool, auto-creates tracking table, checksums applied migrations.
- **Gradle version catalog** (`libs.versions.toml`) — centralizes dependency versions.
- **Gradle 9.3.1 over 8.12** — Java 25 support requires Gradle 9.1+; 8.x cannot parse version "25.0.2".
- **discovered_file staging table** — `transcode.title_id` is NOT NULL, so unmatched NAS files park in `discovered_file` until linked (auto or manual), then a `Transcode` record is created.
- **Stateless parser/matcher** — `TranscodeFileParser` and `TranscodeMatcherService` are pure-logic objects with no DB or I/O dependencies, fully unit-testable.
- **In-browser playback** — HTML5 `<video>` allows any network client to watch. MKV files are pre-transcoded to ForBrowser mirror; MP4 files stream directly.
- **Background transcoder over on-the-fly remux** — Pre-transcoding in the background avoids interactive wait times. Files are transcoded in TMDB popularity order (most popular first). Play button only appears when the ForBrowser MP4 exists.
- **Path-mirrored ForBrowser cache** — `{nas_root}/ForBrowser/` mirrors the source directory structure.
- **Schema updater framework** — Flyway handles DDL only; SchemaUpdater interface + runner handles programmatic data updates (API calls, computation) with version tracking for re-runnability.
- **Roku JSON feed over Direct Publisher** — Direct Publisher was sunset January 2024. Custom sideloaded BrightScript channel consumes `/roku/feed.json`. API key auth (UUID in `app_config`) for stateless device authentication.
- **Sideloaded Roku channel over beta channel** — Beta channels expire after 120 days. Sideloading via Developer Mode has no expiration and requires no certification review.

## Coding Rules

- **Always use `Clock.sleep()` instead of `Thread.sleep` directly.** The `Clock` interface (in `service/Clock.kt`) abstracts time operations for testability. All production code should inject `clock: Clock = SystemClock` and call `clock.sleep(duration)`. Direct `Thread.sleep` calls make code untestable.

## Philosophy

**"Debugging sucks, Testing Rocks."** — Write integration and unit tests before unleashing code on real data. If something can be tested with mocked dependencies and an in-memory database, it should be.

## Security

- Claude must **never read files with the `.env` extension** in `secrets/` (contains real API keys and passwords)
- Claude **may read `.agent_visible_env` files** in `secrets/` (test credentials, non-secret config)
- Values from `secrets/.env` must **never be committed to source control or logged**
- Use `secrets/example.env` as the template for documenting required environment variables

### User Authentication

Two access levels enforce role-based access:

| Level | Role | Can Access |
|-------|------|------------|
| 1 | Viewer | Home, Catalog, Title Detail, Actor, Search, Playback |
| 2 | Admin | Everything Viewer can + Scan, Purchases, Expand, Transcodes, Users |

**Routes:** `SecurityServiceInitListener` enforces Vaadin route access. Public routes (`login`, `setup`) are always accessible. Admin routes (`scan`, `purchases`, `expand`, `transcodes`, `users`) require Level 2. All other routes require any authenticated user.

**Servlets:** `AuthFilter` protects `/posters/*`, `/headshots/*`, `/stream/*` via cookie token validation against `session_token` table. Falls back to API key auth (`?key=` parameter validated against `roku_api_key` in `app_config`) for Roku device access. Returns 401 for unauthenticated requests. Allows all through when no users exist (pre-setup).

**First-user setup:** When no users exist in `app_user`, all navigations redirect to `/setup` where the first account (always admin) is created.

**Sessions:** 30-day persistent login via `mm_session` cookie mapped to `session_token` DB rows. Cookie set via client-side JS. Expired tokens cleaned on startup.

**Testing:** `secrets/test-credentials.agent_visible_env` (gitignored) contains test account credentials for automated UI and API testing. Claude may read this file. Copy from `secrets/example.test-credentials.env` and create the test accounts manually after first setup.

## Version Control

This repository uses **git** hosted on GitHub. See `.gitignore` for excluded files and directories.

### Commit Message Style

Commit messages follow this format:

```
Short summary line (imperative mood, ~50 chars)

Body paragraph(s) describing what changed and why. Wrap at ~72 chars.
Use bullet points with `-` for lists of changes. Include context like
"replaces X with Y" or "adds X for Y purpose".

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
```

- **First line**: imperative verb ("Add", "Fix", "Replace", "Redesign"), concise summary
- **Body**: explain the what/why, list notable changes with `-` bullets
- **Co-author trailer**: always include when Claude authored or co-authored the change
- No need to check `git log` for style — just follow this format

### Creating Releases

Releases are created by pushing a version tag. **Never create releases manually via `gh release create`** — the CI workflow handles release creation and attaches the Roku channel zip as an asset. Manually-created releases are immutable by the time CI runs, causing asset upload failures.

```bash
git tag v1.0.3
git push --tags
```

The release workflow (`.github/workflows/release.yml`) will:
1. Run tests
2. Build and push the Docker image to GHCR
3. Package `roku-channel/` as `roku-channel.zip`
4. Create a GitHub Release with auto-generated notes and the zip attached

To add custom release notes, edit the release on GitHub after CI creates it.

### Presubmit Check

A git pre-commit hook scans staged changes for sensitive data (hardcoded IPs, UUIDs, API keys, etc.). If the commit is rejected, fix the violations or add known-safe values to `lifecycle/presubmit-allowlist.txt`.

### Documentation Check Before Commit

Before each commit, consider whether the changes require documentation updates. Check:

- **CLAUDE.md** — Does a new flag, entity, service, servlet, or architectural change need to be reflected here?
- **README.md** — Does the user-facing overview need updating?
- **docs/** — Do the Admin Guide, Getting Started, Transcode Buddy, Roku Guide, User Guide, or architecture diagram (index.md) need updating?
- **docs/FEATURES.md** — Should a feature request be marked done, or a new one added?
- **docker-compose.yml** — Do port mappings, environment variables, or healthchecks need updating?
- **claude.log** — Has this session's work been logged?

If documentation updates are needed, include them in the same commit or a follow-up commit before moving on.

## Roku Debugging

Three helper scripts in `lifecycle/` support Roku channel development. All read `ROKU_IP` and `ROKU_DEV_PASSWORD` from `secrets/roku-deploy.env` (gitignored; copy from `secrets/example.roku-deploy.env`).

### Deploy Channel

```bash
./lifecycle/roku-deploy.sh          # Zip roku-channel/, sideload to Roku via HTTP digest auth
```

After deploy, the channel auto-launches on the Roku. Verify installation:
```bash
./lifecycle/roku-remote.sh apps     # List installed apps (look for "dev" entry)
```

### Debug Console

The Roku debug console (port 8085) accumulates all BrightScript `print` output since the channel launched. The `roku-debug.sh` script connects via ncat and streams output to both the terminal and a local file via `tee` (overwrites each run, no `-a`). The connection is fragile — ncat frequently drops, so don't rely on it staying connected. The local log file (`data/roku-debug.log`) is **always stale** — it only contains output captured during the last script run. Never read the local file and assume it reflects current Roku state.

```bash
./lifecycle/roku-debug.sh                                  # Stream to terminal + data/roku-debug.log
./lifecycle/roku-debug.sh prod                             # Connect to prod Roku
./lifecycle/roku-debug.sh dev data/custom-output.log       # Custom log file name
```

Always write debug logs to the `data/` directory (gitignored) to avoid cluttering the project root.

All mediaManager channel logs use the `[MM]` prefix. Filter with:
```bash
grep '\[MM\]' data/roku-debug.log
```

### Build Timestamp Verification

Each deploy stamps a `build_timestamp` into the manifest (format `YYYYMMDD-HHMMSS`, e.g., `20260313-075327`). The deploy scripts (`roku-deploy-dev.sh`, `roku-deploy-prod.sh`) inject this into the manifest before zipping and remove it after, so it only appears in the deployed package. The channel logs it at startup:
```
[MM] main: starting Media Manager v2.0.0 (build 20260313-075327)
```
**Always check this line** after fetching debug logs to confirm you are reading output from the expected build, not a stale session.

### Roku Debug Workflow

Follow this exact sequence when debugging Roku channel behavior. Do NOT skip steps or read stale logs.

1. **Delete the local debug log** — `rm -f data/roku-debug.log` — ensures no stale data contaminates results
2. **Deploy the channel** — `./lifecycle/roku-deploy-dev.sh` (or `-prod.sh`) — stamps build timestamp, zips, sideloads; channel auto-launches on the Roku
3. **Perform the user action** — navigate menus, play content, trigger the behavior under test. Use `./lifecycle/roku-remote.sh` for ECP keypresses if needed, or ask the user to perform the action manually
4. **Fetch debug logs** — `./lifecycle/roku-debug.sh` — connects to port 8085, captures the console buffer to `data/roku-debug.log`, connection will drop on its own
5. **Inspect logs** — `grep '\[MM\]' data/roku-debug.log` — verify the build timestamp matches the deploy (step 2), then analyze the relevant log lines

**Critical:** Steps must be sequential. The log file captures output printed since the channel launched. If you fetch logs before the user action, you won't see the relevant output. If you skip step 1, you may confuse old output with new.

**Important:** This Windows environment does not have `nc` (netcat). Use `ncat` (from Nmap, installed at `/c/Program Files (x86)/Nmap/ncat`) instead. The roku-debug.sh script uses `ncat` for this reason.

**Roku audio requirement:** The Roku's **Settings > Audio > Digital Output Format** must be set to **Stereo** (not "Auto"). When set to "Auto", the Roku attempts to negotiate audio formats with the TV, which causes the Video node to play video without audio while system/UI navigation sounds still work. This is a device-level setting, not a channel code issue.

**Debug ports:**
| Port | Protocol | Purpose |
|------|----------|---------|
| 8080 | HTTP | Roku installer web UI |
| 8085 | Telnet | BrightScript debug console (`print` output) |
| 8087 | Telnet | SceneGraph debug console (node inspector) |
| 8089 | Telnet | SceneGraph profiler |
| 8060 | HTTP | External Control Protocol (ECP) |

### Remote Control via ECP

**Prerequisite:** The Roku must have ECP keypress enabled: **Settings > System > Advanced system settings > Control by mobile apps > Network access** set to **"Enabled"** (default is "Limited", which returns HTTP 403 for keypress commands). Query endpoints like `active-app` and `device-info` work regardless.

```bash
./lifecycle/roku-remote.sh                # Press Select (OK)
./lifecycle/roku-remote.sh Home           # Press Home
./lifecycle/roku-remote.sh Back           # Press Back
./lifecycle/roku-remote.sh Up             # Navigation keys: Up, Down, Left, Right
./lifecycle/roku-remote.sh launch         # Launch the sideloaded dev channel
./lifecycle/roku-remote.sh apps           # List installed apps (XML)
./lifecycle/roku-remote.sh active         # Show active app
./lifecycle/roku-remote.sh info           # Device info
./lifecycle/roku-remote.sh player         # Media player state (playing/paused/stopped)
./lifecycle/roku-remote.sh type "text"    # Type text into a Roku keyboard dialog
```

**Key names:** `Home`, `Rev`, `Fwd`, `Play`, `Select`, `Left`, `Right`, `Down`, `Up`, `Back`, `InstantReplay`, `Info`, `Backspace`, `Search`, `Enter`, `VolumeDown`, `VolumeUp`, `VolumeMute`

## Screenshots

Save all Playwright screenshots to `data/screenshots/` (e.g., `data/screenshots/navbar-test.png`). The `data/` directory is gitignored so screenshots never enter version control.

## MCP Tools

The Playwright MCP server is available for browser automation and visual verification. Use it to:
- Navigate to the running app (`http://localhost:8080/`)
- Take snapshots (accessibility tree) and screenshots (visual)
- Click elements, fill forms, and verify navigation
- Run arbitrary Playwright code for complex interactions

**Login persistence:** The browser session persists across Playwright launches. The default state when opening the browser is **already logged in** as the admin test user. You do not need to navigate to `/login` or enter credentials at the start of each session — just navigate directly to the page you want to test.

Console logs are stored in `.playwright-mcp/` (gitignored).

## Conversation Transcript

All Claude Code conversations for this project must be logged to `claude.log` in the project root. Log **every substantive exchange** — not just at session end, but as the conversation progresses. Each entry must include a date and timestamp. Format:

```
=== YYYY-MM-DD HH:MM — <brief topic> ===
- What was discussed
- What was decided
- What was changed (files created/modified/deleted)
===
```

This log serves as a persistent project history across sessions. Always append; never overwrite.
