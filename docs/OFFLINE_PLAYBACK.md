# Offline Playback Architecture

How downloading and offline playback works on iOS.

## Storage Layout

All downloads live in `Library/Application Support/Downloads/` with `isExcludedFromBackup = true`. Files use sequential 7-digit names, not title-based paths.

```
Library/Application Support/Downloads/
├── downloads.meta.db              — protobuf metadata (primary)
├── downloads.meta.db.backup       — previous version (crash recovery)
├── 0000001.mp4                    — completed download (ForMobile transcode)
├── 0000001.subs.vtt               — WebVTT subtitles
├── 0000001.chapters.json          — chapters + skip segments
├── 0000001.detail.pb              — cached TitleDetail protobuf (offline browse)
├── 0000002.mp4.downloading        — in-progress download (renamed on completion)
├── 0000003.mp4                    — another completed download
├── posters/
│   ├── 0000001.jpg                — cached poster for offline display
│   └── 0000003.jpg
```

### Why sequential filenames?

- No path encoding issues from title names with special characters
- Simple collision-free allocation via `next_sequence` counter
- `.downloading` suffix for Chrome-style staging (atomic rename on completion)
- `.mp4` extension for AVPlayer compatibility and OS thumbnail generation

### Why Application Support?

Persists across app launches (unlike `Caches/` which iOS purges under storage pressure), doesn't show in Files app, and `isExcludedFromBackup` prevents iCloud backup of multi-GB video files.

## Metadata: `downloads.meta.db`

Protobuf-encoded `DownloadDatabase` containing all download state (defined in `proto/download_meta.proto`). Single file, not per-download.

### Atomic Write Protocol

1. If both `.db` and `.backup` exist, delete `.backup`
2. If `.db` exists, rename `.db` → `.backup`
3. Write new `.db`

On startup: try `.db` → `.backup` → wipe directory and start fresh.

### Orphan Cleanup

On startup, `DownloadStore.cleanOrphans()`:
- Deletes files not referenced by any metadata entry
- Reconciles `bytesDownloaded` against actual `.downloading` file size on disk
- Marks completed entries as failed if their `.mp4` file is missing

## Download Engine

Downloads use **gRPC server-streaming** via `DownloadService.DownloadFile`. The server streams ~1MB chunks from the ForMobile transcode file. HTTP/2 flow control (4MB window) handles backpressure.

### Flow

1. User taps Download → `DownloadManager.startDownload()` → entry created as `QUEUED`
2. `startNextQueued()` picks up to 3 entries (concurrency limit)
3. `performDownload()`:
   - Fetches manifest via `GetManifest` gRPC (file size, aux file flags)
   - Checks disk space (requires 500MB buffer)
   - Downloads supporting files (subtitles, chapters, poster, title detail)
   - Opens `DownloadFile` gRPC stream with `offset = bytesDownloaded`
   - Writes each chunk to `{seq}.mp4.downloading` via `FileHandle`
   - Persists progress every 10 chunks (first chunk always persisted)
   - Flushes `FileHandle` before persisting metadata
4. On completion: rename `.downloading` → `.mp4`, pin images in cache
5. On error: auto-retry with 2-second backoff (up to 50 retries)

### Resume

The client tracks `bytesDownloaded` in the protobuf entry. On resume (manual or auto-retry), it opens a new `DownloadFile` RPC with `offset = bytesDownloaded`. The server seeks to that position and streams from there. The client appends to the existing `.downloading` file.

### HAProxy Connection Drops

The gRPC download stream is interrupted by HAProxy every ~30-45 seconds. The auto-retry mechanism handles this transparently — the download progresses in bursts, resuming from the last saved offset each time.

## Video Player

**AVPlayer via CustomPlayerView** — same player for streaming and local playback. The `OnlineDataModel.streamAsset()` returns a local file URL when a download exists, or a remote HTTP URL for streaming.

### Subtitles

Downloaded as `{seq}.subs.vtt`. The `SubtitleController` checks the local file first (via `localSubtitleFile` parameter), then falls back to the server endpoint.

### Chapters

Downloaded as `{seq}.chapters.json` (JSON-encoded `ChaptersResponse`). The `SkipSegmentController` loads from the local file (via `localChaptersFile` parameter), then falls back to gRPC `GetChapters`. Chapter navigation available via list button in player controls.

### Skip Segments

Auto-detected INTRO skip segments (created from Chapter 1 when < 5 minutes) are suppressed when chapter navigation is available — the user navigates via the chapter list instead.

## Playback Position Tracking

### Online

Player reports position every 10 seconds via `PlaybackService.ReportProgress` gRPC. On re-open, player seeks to saved position from `GetProgress`.

### Offline

Position stored in `DownloadEntry.playback_position` with `positionSynced = false`. When connectivity returns, `DownloadManager.flushPendingProgress()` batch-syncs all unsynced positions to the server.

### Auto-Clear

Server auto-clears progress at ≥95% watched or ≤120 seconds remaining. Sets VIEWED flag at >25% watched.

## Offline Browse

When offline, the sidebar shows:
- **Library** — lists downloaded titles with poster, episode count, quality
- **Downloads** — download management (active, queued, completed)

Tapping a title in Library navigates to `TitleDetailView` which loads from cached `{seq}.detail.pb` (protobuf `TitleDetail`). For TV shows, seasons and episodes are derived from `DownloadEntry` metadata.

### Image Cache Pinning

Images (poster, backdrop, cast headshots) for downloaded titles are pinned in `ImageDiskCache` — they are never evicted by LRU, ensuring offline display works. Pins are removed when all downloads for a title are deleted.

## Continue Watching

The Home feed "Resume Playing" carousel shows:
- Progress bar overlay on poster (blue, proportional to watch position)
- Episode context for TV: "S1E5 · Episode Name" below title
- Dismiss button (X) to clear progress

Server populates `resume_position`, `resume_duration`, and `resume_season_number`/`resume_episode_number`/`resume_episode_name` fields on `Title` proto for carousel items.
