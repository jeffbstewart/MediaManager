# Offline Playback Architecture

How downloading and offline playback works on iOS, across all four media kinds: **movies**, **TV episodes**, **ebooks** (EPUB / PDF), and **audio CDs / albums**.

The same gRPC server-streaming download primitive is reused for every kind; the differences are in storage layout, the catalog cache, and the player.

---

## Storage Layout

Everything lives under `Library/Application Support/Downloads/` with `isExcludedFromBackup = true` — persists across launches (unlike `Caches/` which iOS purges), doesn't surface in Files, and stays out of iCloud backup so multi-GB media never re-uploads.

```
Library/Application Support/Downloads/
├── downloads.meta.db              — video downloads metadata (protobuf, primary)
├── downloads.meta.db.backup       — previous version (crash recovery)
├── 0000001.mp4                    — completed video download (ForMobile transcode)
├── 0000001.subs.vtt               — WebVTT subtitles
├── 0000001.chapters.json          — chapters + skip segments
├── 0000001.detail.pb              — cached TitleDetail proto for offline video browse
├── 0000002.mp4.downloading        — in-progress (atomic rename on completion)
├── posters/
│   └── 0000001.jpg                — cached poster (offline display)
│
├── Books/                         — owned by BookCacheManager
│   ├── index.json                 — [DownloadedBook]
│   ├── 972.epub                   — ebook, named by mediaItemId
│   ├── 456.pdf
│   ├── 12345.detail.pb            — TitleDetail proto, named by titleId
│   └── …
│
└── Audio/                         — owned by AudioCacheManager
    ├── index.json                 — [DownloadedAlbum]
    ├── playlists.json             — [DownloadedPlaylist]
    ├── 67890.detail.pb            — album TitleDetail proto, named by titleId
    ├── 67890/                     — per-album track directory
    │   ├── detail.pb              — same proto (legacy mirror, kept for backward compatibility)
    │   ├── 1001                   — raw audio bytes for trackId 1001
    │   ├── 1002
    │   └── …
    └── …
```

### Video filename convention

Sequential 7-digit names (e.g. `0000001.mp4`). The sequence comes from a monotonic counter in `downloads.meta.db`. Reasons:

- No path-encoding issues from title names with special characters
- Simple collision-free allocation
- `.downloading` suffix is Chrome-style staging — atomic rename on completion
- `.mp4` extension keeps AVPlayer and OS thumbnail generation happy

### Books filename convention

`<mediaItemId>.epub` or `<mediaItemId>.pdf` (i.e., not sequence-numbered). The mediaItem ID is the catalog primary key, so naming is collision-free and the reader can look up the file directly from a route value.

### Audio filename convention

`<titleId>/<trackId>` (no extension). The track files are referenced indirectly via the album's index entry plus the `AVPlayer`-friendly content type sniff at open time — extensions aren't needed.

---

## Catalog Metadata Cache

Offline browsing is driven by **cached `MMTitleDetail` protos** written alongside the media files. Without these, the offline catalog has no titles / authors / albums to show even if the media bytes are on disk.

| Kind | File | Owner |
|------|------|-------|
| Video | `<sequence>.detail.pb` next to the .mp4 | `DownloadManager` writes during `performDownload` |
| Books | `Books/<titleId>.detail.pb` | `BookCacheManager.cacheTitleDetail(_:)`, called from the **Download** button in `BookDetailView` (also opportunistically on subsequent online visits — see `BookDetailView.load`) |
| Audio | `Audio/<titleId>.detail.pb` | `AudioCacheManager.cacheTitleDetail(_:)`, called from `AlbumDetailView` at download start |

`OfflineDataModel` walks each manager's downloads list, reads the cached detail protos, and assembles `homeFeed`, `titles(type:)`, `authors`, `artists`, `actorDetail`, etc. from the union.

---

## Download Engine

Video and books share a **gRPC server-streaming** primitive; audio uses **HTTP** through `APIClient`. The split is historical — video and books needed range-resume + auth-aware streaming over the same channel as their manifest RPC, audio downloads are smaller per-track and per-byte progress isn't worth the complexity (track-level granularity is fine).

| Kind | Manifest RPC | File transfer |
|------|--------------|---------------|
| Video | `DownloadService.GetManifest` | `DownloadService.DownloadFile` (server-streaming gRPC) |
| Book | `DownloadService.GetBookManifest` | `DownloadService.DownloadBookFile` (server-streaming gRPC) |
| Audio | (catalog gRPC for tracklist) | Per-track HTTP GET via `APIClient` |

For the gRPC streams: server sends ~1MB chunks; HTTP/2 flow control (4MB window) handles backpressure.

### Flow (video example — books / audio follow the same shape)

1. User taps **Download** → `DownloadManager.startDownload()` → entry created as `QUEUED`
2. `startNextQueued()` picks up to 3 entries (concurrency limit)
3. `performDownload()`:
   - Fetches manifest (file size, aux file flags)
   - Checks disk space (requires 500MB buffer for video)
   - Downloads supporting files (video: subtitles, chapters, poster, title detail)
   - Opens the stream RPC with `offset = bytesDownloaded`
   - Writes each chunk to `{seq}.mp4.downloading` via `FileHandle`
   - Persists progress every 10 chunks (first chunk always persisted)
4. On completion: rename `.downloading` → `.mp4`, pin images in cache
5. On error: auto-retry with 2-second backoff (up to 50 retries)

### Resume

Each entry tracks `bytesDownloaded`. On resume (manual or auto-retry), the client opens a new stream RPC with `offset = bytesDownloaded`. The server seeks to that position and streams from there.

### HAProxy connection drops

The gRPC stream is interrupted by HAProxy every ~30–45 s. Auto-retry handles this transparently — downloads progress in bursts, resuming from the last saved offset each time.

### Albums: per-track sequencing

`AudioCacheManager` downloads an album's tracks one at a time using the same primitive. Cancel tears down the in-flight track only; previously-completed tracks stay on disk. Re-download is idempotent.

---

## Players

### Video — `CustomPlayerView`

Same player for streaming and local playback. `OnlineDataModel.streamAsset()` returns a local file URL when a download exists, or a remote HTTP URL otherwise. AVPlayer treats them identically.

- **Subtitles**: `SubtitleController` checks `{seq}.subs.vtt` first (via `localSubtitleFile`), falls back to the server endpoint.
- **Chapters**: `SkipSegmentController` loads from `{seq}.chapters.json`, falls back to gRPC `GetChapters`. Chapter list is reachable from the player controls.
- **Skip segments**: auto-detected INTRO skips (Chapter 1 < 5 min) are suppressed when a chapter list is available — the user navigates via chapters instead.

### Books — `BookReaderView`

Routes by `media_format`:

- `EBOOK_EPUB` — epub.js inside a `WKWebView`, themed via the in-app reader theme broadcaster (used by the mini-player to tint when reading).
- `EBOOK_PDF` — `PDFKit`.

Resume locator (EPUB CFI or `/page/N` for PDFs) is read from the freshest of the in-flight queue (`ReadingProgressQueue.entry`) or the data model's `readingProgress(mediaItemId:)`; offline the latter is sourced from `LocalProgressStore`.

### Audio — `AudioPlayerManager`

Single source of truth for the music queue + system Now Playing. Track playback URL resolution is local-first:

```
AudioPlayerManager → AudioCacheManager.localTrackURL(trackId:) → file:// (if downloaded)
                                                              → falls back to streaming URL
```

The mini-player + lock-screen / CarPlay / AirPods transport drives the same `AudioPlayerManager` instance whether the track came from disk or the server.

---

## Playback Position Tracking

Two layers since the M9 work: a **server-bound queue** (the path that ships positions to the cross-device source of truth) and a **local progress shadow** (`LocalProgressStore`, the path that survives an online→offline transition on a single device).

| Layer | Lifetime | Who reads it | Who writes it |
|-------|----------|--------------|---------------|
| Server | Until next overwrite | Cross-device resume (other phone, other browser) | `PlaybackService.ReportProgress` / `ReportReadingProgress` / `ReportListeningProgress` |
| Local queue | Until flushed to server | Reader-side merge in `resolveResumePosition` | `ReadingProgressQueue.record` (also queues for the server flush) |
| Local shadow (`LocalProgressStore`) | Until overwritten or the user signs out | `OfflineDataModel` getters + `OnlineDataModel` server-miss fallback | Every `reportProgress` write-through, *plus* `ReadingProgressQueue.record` mirrors here so the position survives the flush-and-delete |

`OnlineDataModel.playbackProgress` reads server-first, falls back to `LocalProgressStore`. `OfflineDataModel` reads only from `LocalProgressStore`. Same shape for reading + listening progress.

### Auto-clear

The server auto-clears progress at ≥95% watched or ≤120 seconds remaining, and sets the `VIEWED` flag at >25% watched. The local shadow follows: once the server returns "no row," `OnlineDataModel` falls back to the local shadow, which may still have a stale entry — that's fine because the user just finished the video and resume is moot.

---

## Offline Browse

Sidebar surfaces that work offline:

- **Search** — hidden offline (server search has no offline counterpart)
- **Home** — `OfflineDataModel.homeFeed` builds Recently Downloaded carousels per kind (video, books, audio) plus the Resume Playing / Reading / Listening lists from cached state
- **Movies / TV Shows / Books / Music** — all four tab destinations work offline. Movies / TV / Family route through `cachedVideoTitles`; Books goes via `cachedBookTitles` + `BookCacheManager`; Music goes via `cachedAudioAlbums` + `AudioCacheManager`.
- **Collections / Tags / Family / Cameras / Live TV** — hidden offline. Personal videos rarely cached; cameras + Live TV are streaming-only; collections / tags use server-side list endpoints.
- **Wish List / Admin tabs** — hidden offline.
- **About / Downloads** — work offline. About reads the bundle's build info + cached legal docs; Downloads is local state by definition. **Profile** technically renders but its body depends on a `GetProfile` RPC that fails offline — the view's `try?` swallows the error and the user sees a blank surface. Improving this is a follow-up.

The **Offline Mode** toggle is pinned at the top of the sidebar (rather than buried in Profile) so a user who's actually offline can reach it without firing an RPC.

### Image cache pinning

Images (poster, backdrop, cast headshots, author headshots) for downloaded titles are pinned in `ImageDiskCache` — never evicted by LRU. Pins are removed when all downloads for a title are deleted.

---

## Continue Watching / Reading

The Home feed surfaces two resume carousels today:

- **Resume Playing** (video) — progress bar overlay on the poster (blue, proportional to watch position); for TV, an episode caption below the title
- **Continue Reading** — book cover with a progress bar; tap routes straight to `BookReaderView` at the saved locator

The protocol also defines `resume_listening` on `MMHomeFeedResponse`, but HomeView doesn't currently render an audio carousel — the audio queue's resume point lives in the always-on mini-player + `AudioPlayerManager` instead.

Online, the server populates these. Offline, the resume positions still resolve through `LocalProgressStore`, though the Home feed's reconstructed offline carousels are scoped to **Recently Downloaded** per kind rather than rebuilding the resume lists.
