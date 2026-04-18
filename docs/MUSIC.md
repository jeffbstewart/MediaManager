<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Music (CDs and Digital Audio) in Media Manager — Design Proposal

*Created April 18, 2026. Status: proposal, no code written.*

---

## Overview

Media Manager currently catalogs physical video media (DVD / Blu-ray / UHD / HD DVD) plus physical and digital books. This document extends the same model to **albums** — compact discs scanned by barcode, with ripped audio files (FLAC / MP3 / AAC) picked up from the NAS and played back in-browser and on the native clients.

Albums aren't a third product. They slot into the existing `title`, `media_item`, genre, tag, wishlist, and ownership-photo infrastructure the same way books did. The new pieces are a `track` sub-entity (album contains tracks, parallel to TV-show → episodes), an `artist` first-class entity (parallel to `author`), and a persistent audio player in the web UI.

This doc follows the same shape as `docs/BOOKS.md`. If you've read that, you know the layout.

---

## Goals

1. **Insurance-grade inventory for CDs** that are still in the collection by format/title/artist — separate from the pricing angle, since the user's existing collection mostly lacks jewel cases (and therefore UPCs) and has no recorded purchase prices. Admin can hand-fill pricing later if desired.
2. **Bulk import of an existing ripped library.** The dominant ingestion path for the user's current collection isn't the scanner — it's the NAS directory walk (M4 below). M1's barcode flow mostly serves *future* CD acquisitions.
3. **Physical location tracking.** "Where is my copy of *Kind of Blue*?" Reuse the `media_item.storage_location` column books already added.
4. **Play ripped audio on screen.** FLAC / MP3 / AAC / M4A files dropped into a configured NAS directory get matched to the catalogue; the web UI plays them through a persistent bottom-bar player. Progress saved per-user.
5. **First-class compilations.** *Best of 1984*, *NOW That's What I Call Music!*, various classical-label anthologies — a meaningful fraction of the user's collection. Per-track artist credit is a primary case, not an edge case.
6. **Multi-disc sets.** *The Beatles (White Album)*, box sets, *The Complete Mozart* — day-one support.
7. **Band vs. performer distinction.** Morrison vs. The Doors. The schema can answer "who played drums on *L.A. Woman*?" even when the album-level artist is just the band name. See the schema discussion below.

---

## Non-Goals (for now)

- **No Roku / Android TV / iOS music surfaces** in the initial rounds. Books shipped web-first; music will too. The schema, file serving, and transcode contract are built so those clients can slot in later with no server rework.
- **No CD ripping from the server.** Media Manager lives in Docker on a NAS without an optical drive. Users rip on their own machine (EAC, dBpoweramp, XLD, etc.) and drop the output into a configured directory. Server-side ripping can be added later as an optional sidecar if someone attaches a drive, but is not in scope.
- **No Spotify / Tidal / streaming-service integration.** This is a collection manager, not a streaming-service aggregator.
- **No lyrics, no annotations, no Loudness / replay-gain analysis** in MVP. All possible later.
- **No parental-advisory filtering.** Content rating stays `NR` for albums; existing rating-ceiling filters don't gate music.

---

## Conceptual Model

### Album as the title; track as the sub-unit

Music has a cleaner unit of ownership than books: **you own an album**. Individual songs aren't independently owned. That maps onto the existing schema the same way TV does:

| Video analog | Music |
|--------------|-------|
| `Title` (`media_type = TV`) — the show | `Title` (`media_type = ALBUM`) — the album |
| `Episode` — one slot in the show | `Track` — one slot in the album |
| `Transcode` — the playable file for an episode | `Transcode` — the playable file for a track (new nullable `track_id` column) |
| `MediaItem` — one physical disc or digital edition | `MediaItem` — one physical CD, vinyl LP, or digital-rip directory |

Albums reuse the existing TV-shaped skeleton. The work, not the performance unit, is the title.

### Editions with and without barcodes

- **Physical editions** (CD, vinyl LP, cassette) may have an EAN-13 / UPC barcode — same scanner hardware — but a large fraction of the user's existing CDs are old enough that their jewel cases (and therefore barcodes) are gone. `MediaItem.upc` stays nullable.
- **Digital editions** (ripped FLAC directory, purchased MP3 / AAC bundle) typically have no barcode.

Entry paths mirror books, but **the primary path for the user's existing collection is file discovery, not barcode scan**:

| Path | Book version | Music version | User's bulk import |
|------|--------------|---------------|-----|
| Barcode scan | ISBN → Open Library → create Title + MediaItem | EAN-13 → MusicBrainz → create Title + Tracks + MediaItem | Rare (mostly future acquisitions) |
| File discovery | NAS scan detects .epub → match/create | NAS scan detects .flac / .mp3 / .m4a directory → match/create | **Dominant path** |
| Manual entry | OL search by title/author | MB search by artist/album | Occasional (disc with no usable tags) |

### Barcode-less old CDs and dBpoweramp

The user rips existing old CDs with [dBpoweramp](https://www.dbpoweramp.com/), whose CD ripper integrates with **AccurateRip** (a crowd-sourced database of per-track checksums at specific CD-drive read offsets) and **MusicBrainz** (and/or the proprietary PerfectMeta combining multiple metadata sources). Even when the physical jewel case is long gone and there's no UPC to scan, dBpoweramp identifies the disc from the audio itself — the TOC offsets form a pseudo-fingerprint that matches MB / AccurateRip records — and writes Vorbis comments / ID3 tags that include `MUSICBRAINZ_ALBUMID`, `MUSICBRAINZ_RELEASETRACKID`, `MUSICBRAINZ_ARTISTID`, and an `ACCURATERIPCRC` / `ACOUSTID_FINGERPRINT`.

That means M4's file scanner can authoritatively match a directory of old CD rips without needing the UPC at all — it reads the MBIDs out of the tags and resolves them through the same MusicBrainz endpoints M1's scanner uses. This is load-bearing for the user's migration story: the M1 barcode scan works for any future CD acquisition but does **not** need to cover the existing collection.

### Various-artist albums

Compilation albums ("Various Artists") need per-track artist credit. Schema supports this via a `track_artist` join. Title-level credit is "Various Artists" (a canonical MBID exists for this); each track carries its actual performer(s). Most singles-artist albums never touch `track_artist` — they just inherit from the title-level `title_artist`.

### Multi-disc sets

Tracks carry `disc_number` and `track_number`. *The White Album* has tracks 1–17 on disc 1 and 1–13 on disc 2. Track list rendering groups by disc when `max(disc_number) > 1`.

---

## Data Sources

### Primary: MusicBrainz

[MusicBrainz](https://musicbrainz.org/) is the metadata spine for music — free, open, no API key required, well-documented API at `musicbrainz.org/ws/2/`. It's the Open Library of music.

| Need | Endpoint | Notes |
|------|----------|-------|
| Barcode → release | `GET /ws/2/release/?query=barcode:{ean}&fmt=json` | Returns one or more `Release` objects (different pressings can share a barcode). |
| Release details | `GET /ws/2/release/{mbid}?inc=recordings+artists+labels+release-groups&fmt=json` | Track list with durations + recording MBIDs, artist credits, label, year, release-group link. |
| Release group | `GET /ws/2/release-group/{mbid}?fmt=json` | The "album across pressings" — MB's work-like concept. Dedup key, same role as OL's work ID. |
| Artist details | `GET /ws/2/artist/{mbid}?inc=url-rels&fmt=json` | Biography fields, Wikidata/Wikipedia URLs, begin/end dates. |
| Artist discography | `GET /ws/2/release-group?artist={mbid}&type=album&limit=100&fmt=json` | For "Other Works" on ArtistScreen. |
| Free-text search | `GET /ws/2/release/?query=artist:{a}+AND+release:{r}&fmt=json` | Manual-add flow. |

**Dedup key:** `musicbrainz_release_group_id + media_type=ALBUM`. Scanning a US pressing and then a UK pressing of the same album resolves both to the same `title`; two `media_item` rows hang off it via `media_item_title` (just like paperback + hardback of the same book).

**Rate limit:** MB asks for 1 req/sec with a descriptive `User-Agent`. Scan volume is human-paced; background enrichment paces itself at 1 req/sec.

### Cover art: Cover Art Archive

`https://coverartarchive.org/release/{mbid}/front-500.jpg` and `/front-1200.jpg`. Part of the MB ecosystem, no API key. Same "route through the image proxy" pattern the book covers use — add a new `COVER_ART_ARCHIVE` provider to `ImageProxyService` with SSRF screening and on-disk caching. Append `?default=false` to 404-instead-of-placeholder the same way the OL work did.

### Artist enrichment: Wikipedia (via Wikidata)

MB's artist bios range from nonexistent to a single sentence. The `url-rels` include on the artist endpoint returns a Wikidata URL for most well-known artists. Resolve `wikidata_id` → Wikipedia page (same machinery the book `AuthorEnrichmentAgent` already uses) and pull a richer extract + band photo. Reuses `AuthorHeadshotCacheService`'s pattern: proxied download, SSRF-screened, `/artist-headshots/{id}` endpoint.

### Pricing: Keepa — deferred

CDs have ASINs on Amazon and Keepa would work via `lookupByAsin()` the same way books could. Same deferral applies: user-entered `replacement_value` at scan time for M1; automated Keepa backfill later.

---

## Schema

### Extensions to existing tables

**`title`** — new nullable columns used only when `media_type = ALBUM`:

| Column | Type | Purpose |
|--------|------|---------|
| `musicbrainz_release_group_id` | string | MBID (UUID). Dedup key across pressings. |
| `musicbrainz_release_id` | string | MBID for the specific scanned pressing. Nullable — a manual-add title may have no specific pressing yet. |
| `track_count` | int | Denormalized; convenience for list rendering. |
| `total_duration_seconds` | int | Denormalized; sum of `track.duration_seconds`. |
| `label` | string | "Columbia", "Blue Note", etc. |

`media_type` enum gains `ALBUM`. `tmdb_id` stays null, `content_rating` defaults to `NR`.

**`media_item`** — broadened semantics, no new columns beyond what books already added:

- `upc` holds the EAN-13 barcode for physical editions.
- `file_path` (nullable — books added this) holds the directory containing the ripped audio files. Per-track file paths live on `track.file_path`; `media_item.file_path` is the parent dir for admin clarity.
- `storage_location` (books added this) — "Living room CD tower, row 2" or similar.
- `media_format` enum gains: `CD`, `VINYL_LP`, `CASSETTE` (deferred), `AUDIO_FLAC`, `AUDIO_MP3`, `AUDIO_AAC`, `AUDIO_OGG`, `AUDIO_WAV`.

**`transcode`** — one new nullable column:

| Column | Type | Purpose |
|--------|------|---------|
| `track_id` | FK → `track` | Null for movies; set for audio tracks the same way `episode_id` is set for TV episodes. |

The existing `transcode.file_path` + streaming logic continues to work. A track's source file (e.g. FLAC) and its streaming transcode (e.g. AAC m4a) live in two `transcode` rows the same way a video's source MKV and ForBrowser MP4 do.

### New tables

**`track`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `title_id` | FK → `title` | The album. |
| `track_number` | int | 1-indexed within the disc. |
| `disc_number` | int | 1-indexed; default 1. |
| `name` | string | |
| `duration_seconds` | int | |
| `musicbrainz_recording_id` | string | MBID. Dedup/re-enrichment key. |
| `file_path` | string | Nullable. Absolute path to the ripped audio file. |
| `created_at`, `updated_at` | timestamps | |
| Unique (`title_id`, `disc_number`, `track_number`) | | |

**`artist`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `name` | string | Display form, e.g. "The Doors" or "Jim Morrison" |
| `sort_name` | string | "Doors, The" / "Morrison, Jim" for alphabetical browse |
| `artist_type` | enum `GROUP` / `PERSON` / `ORCHESTRA` / `CHOIR` / `OTHER` | From MB's `type` field; drives UI (a band has members, a person has a birthdate) |
| `biography` | text | MB bio + Wikipedia fallback |
| `headshot_path` | string | Cached locally via existing image pipeline. A band photo for `GROUP`, a portrait for `PERSON`. |
| `musicbrainz_artist_id` | string | MBID |
| `wikidata_id` | string | For Wikipedia enrichment |
| `begin_date`, `end_date` | date (nullable) | Band formation / breakup, or artist birth / death |
| `created_at`, `updated_at` | timestamps | |

**`artist_membership`** (band-lineup relationships over time — "Jim Morrison was a member of The Doors, 1965–1971")

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `group_artist_id` | FK → `artist` | The band; must have `artist_type = GROUP` (or ORCHESTRA / CHOIR) |
| `member_artist_id` | FK → `artist` | The individual; `artist_type = PERSON` |
| `begin_date`, `end_date` | date (nullable) | Tenure in the group |
| `primary_instruments` | string | Freeform list pulled from MB (`vocals, guitar`) |
| `notes` | string | Nullable (MB sometimes carries "as a touring member", etc.) |
| Unique (`group_artist_id`, `member_artist_id`, `begin_date`) | | |

**`recording_credit`** (per-track performer credit — "John Densmore played drums on *L.A. Woman*")

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `track_id` | FK → `track` | |
| `artist_id` | FK → `artist` | |
| `role` | enum `PERFORMER` / `COMPOSER` / `PRODUCER` / `ENGINEER` / `MIXER` / `OTHER` | |
| `instrument` | string | "drums", "lead vocals", "Fender Rhodes" — freeform, from MB's `instrument-rels` |
| `credit_order` | int | Ordering within a track |
| Unique (`track_id`, `artist_id`, `role`, `instrument`) | | |

These last two tables are **populated lazily**. M1 doesn't require them to ship — the album-level `title_artist` credit ("The Doors") is enough to make the feature usable. When the user drills into an album and asks "who played on this?", a background enrichment pass pulls `artist-rels` and `recording-rels` from MusicBrainz (which carries this data richly for well-documented bands, patchily for obscure ones) and populates `artist_membership` / `recording_credit`. Empty is an acceptable state — the UI just hides the "Personnel" section for albums MB hasn't documented.

**`title_artist`** (album-level credit)

| Column | Type | Notes |
|--------|------|-------|
| `title_id` | FK → `title` | |
| `artist_id` | FK → `artist` | |
| `artist_order` | int | Primary is 0 |
| PK (`title_id`, `artist_id`) | | |

**`track_artist`** (per-track credit, only populated when different from title-level — keeps write volume low for single-artist albums)

| Column | Type | Notes |
|--------|------|-------|
| `track_id` | FK → `track` | |
| `artist_id` | FK → `artist` | |
| `artist_order` | int | |
| PK (`track_id`, `artist_id`) | | |

### Reading progress? Reuse `playback_progress`

`playback_progress` today keys on `(user_id, transcode_id)` and stores `position_seconds + duration_seconds`. Audio playback slots in with zero schema work — the transcode row behind a track is the same shape. "Continue listening" home-feed carousel comes out of the existing query with a different `media_type` filter.

### Why a new `artist` table instead of reusing `cast_member` or `author`?

- Different primary data source (MusicBrainz vs. TMDB / Open Library).
- Different relationships: actors have characters + cast order per title; authors are ordered per-work; artists have an album-level credit AND an optional per-track credit. None of these map onto each other cleanly.
- The UX asks "all albums by this artist" — a different query than "all movies with this actor" or "all books by this author."

Three parallel entities (`cast_member`, `author`, `artist`), each clean in its own lane, beats one overloaded "person" entity.

---

## Feature Surface

### Reused, unchanged

- **Genre** — MB tags (`jazz`, `rock`, `electronic`, etc.) map into existing `genre` records. Genre browse now mixes movies, books, and albums, filterable by media type.
- **Tag** — user-authored tags (favorites, "road-trip mixes") apply to albums the same way.
- **Wishlist** — wishing for an album works identically to wishing for a movie or book. Key is `musicbrainz_release_group_id`; new `wish_type = ALBUM`.
- **Ownership photos** — attach to physical CD `MediaItem`s the same way disc photos attach to Blu-rays today.
- **Physical location** — `media_item.storage_location` holds the shelf/row info.
- **Inventory report** — CDs + vinyl appear in the CSV alongside discs and books.

### New for albums

- **ArtistScreen** — parallel to AuthorScreen. Hero with band photo + bio, owned-albums grid, "Other Works" grid of discography you don't own (wishlist-able).
- **AlbumScreen** — title detail page with album-shaped affordances: hero with cover art, artist link, release year, label, track count + total duration, genres, tags, **track list grouped by disc**, "Play album" button at the top, per-track play buttons in the list.
- **Persistent bottom-bar player** — the big new UI primitive. Visible on every page once audio is loaded. Shows cover thumbnail, track name, artist, elapsed / duration scrubber, play/pause, previous/next. Expand to a full-screen "now playing" view. Queue management: "play album," "play next," "add to queue," shuffle, repeat (track / album / off).

### Web audio player (MVP)

- Server endpoint `/audio/{trackId}` — authenticated, streams the track file with HTTP range requests (same shape as `/stream/` for video). Access check against rating ceiling where relevant; for MVP albums default to `NR` so the gate is moot.
- Browser plays via HTML5 `<audio>` element. Codec choices below.
- Angular service (`PlaybackQueueService`) owns current-track, queue, position, shuffle / repeat state. Persists position to `playback_progress` every 10 s (same cadence as the video player).
- Keyboard shortcuts: space = play/pause, ←/→ = skip, ↑/↓ = volume. Standard.

### Codec policy and transcoding — on-the-fly, not pre-transcoded

| Client | Best audio codec |
|--------|------------------|
| Chrome / Firefox / Edge | AAC (M4A), FLAC |
| Safari (desktop + iOS) | AAC, ALAC, MP3, FLAC (macOS 11+ / iOS 14+) |
| Roku | MP3, AAC, FLAC |
| Android TV (ExoPlayer) | All of the above |

**Key difference vs. video:** audio transcoding is fast enough to do on-demand. FFmpeg encodes FLAC → AAC m4a at **20–40× realtime** on a single CPU core, and piped output can be streamed to the browser while encoding. A 4-minute song encodes in about 8 seconds of CPU wall time, well under the buffer the browser builds up before it needs more data. No pre-transcoded AAC mirror on disk, no background transcoder queue, no "wait for the queue to reach this album" delay.

**Serving decision tree:**

1. If the client's `Accept` header covers the source format (FLAC source + browser accepts `audio/flac`), stream the source file directly via HTTP range requests. Zero CPU, zero latency.
2. Otherwise, pipe through FFmpeg: `ffmpeg -i source.flac -c:a aac -b:a 256k -f adts -` and stream the output. Wrap in a small server-side ephemeral cache keyed by `track_id + target_codec` — the first request for a given track pays the encode, subsequent requests (and `Range` re-fetches as the user scrubs) serve from the cache. LRU-evict the ephemeral cache when it grows past a cap (a few GB is plenty).
3. Range scrubbing into an encoded stream is tricky because AAC frames don't line up with arbitrary byte offsets. Two mitigations: (a) encoded output goes to the cache file first, then gets range-served (preferred when the user is likely to replay the track); (b) for long-form rarely-replayed tracks, an in-flight seek triggers an FFmpeg `-ss` restart at the requested offset.

**No `MusicTranscoderAgent`, no pre-transcoded `transcode` rows for audio.** The `track_id` column on `transcode` stays part of the schema (harmless if empty), and the `/audio/{trackId}` endpoint either streams the source or transcodes on-the-fly. This simplifies M6 dramatically — see the revised milestone below.

### Download support

`DownloadService.DownloadFile` works unchanged for audio files — same chunk protocol the video and ebook paths already use. Offline audio on iOS slots in with no new server work when that client arrives.

---

## Ingestion Paths

### Scan a CD barcode

Extends the barcode-scan branch of `UpcLookupAgent`:

1. User scans EAN-13. Not a 978/979 ISBN, so fall through to the non-book branch.
2. **New:** query MusicBrainz with `barcode:{ean}`. If MB returns a match, use it.
3. If MB misses, fall back to UPCitemdb (the existing DVD / Blu-ray path). Plenty of CDs are in UPCitemdb too, but the metadata is sparser — MB wins when both return something.
4. For an MB hit: fetch the release (with recordings + artists + labels + release-group), upsert artist(s), create / reuse Title keyed by release-group MBID, create Track rows, create MediaItem with `media_format = CD`.
5. Pull cover art from Cover Art Archive via the image proxy.
6. Prompt for storage location + ownership photos using existing flows.

Barcode priority table:

| Prefix | Route |
|--------|-------|
| 978-, 979- | Open Library (books, already shipped) |
| Anything else | MusicBrainz first, UPCitemdb second |

### Discover audio files on the NAS

Extend the NAS scanner similarly to the books pass:

1. Configurable `music_root_path` in `app_config`, parallel to `books_root_path`. Empty = disabled.
2. NAS scanner classifies that directory as MUSIC and hands it off to the new `MusicScannerAgent`, the same pattern the books scanner uses.
3. `MusicScannerAgent` walks for `.flac`, `.mp3`, `.m4a`, `.ogg`, `.wav`, `.opus`. Directory-per-album convention: `<music_root>/<Artist>/<Album>/<NN>-<Track>.flac`.
4. Read embedded tags (JVM libraries: `jaudiotagger` for broad format support, or `mp3agic` + native FLAC parsing for a thinner dep). Prefer MBIDs when present (`MUSICBRAINZ_RELEASE_ID`, `MUSICBRAINZ_ALBUMID`, `MUSICBRAINZ_RELEASETRACKID`).
5. If MBIDs match an existing Title + Tracks → link files by updating `track.file_path`.
6. If tags name an artist + album but no MBIDs → fuzzy search MB, present top hit for admin confirmation via a new **Unmatched Audio** queue (same UI pattern as Unmatched Books).
7. If no usable tags → unmatched; admin picks manually.

Track-file / Track-row matching within a confirmed album: by track title (fuzzy) and duration (tight tolerance, ±2 s).

### Manual add

An "Add album" flow runs an MB search (artist + album) in the same Add Item page shell books already use. Admin picks a release-group; a Title is created with no MediaItem (the album is wishlisted or catalogued-without-owning, parallel to the books case).

---

## Implementation Plan

Phases ordered so each ships a usable increment. Milestones prefixed **M**.

### **M1 — Schema + CD barcode scanning** *(smallest shippable inventory feature)*

- Flyway migrations:
  - Add `ALBUM` to `media_type` enum.
  - Add album-specific nullable columns to `title` (`musicbrainz_release_group_id`, `musicbrainz_release_id`, `track_count`, `total_duration_seconds`, `label`).
  - Add new audio formats to `media_format` enum (`CD`, `VINYL_LP`, `AUDIO_FLAC`, `AUDIO_MP3`, `AUDIO_AAC`, `AUDIO_OGG`, `AUDIO_WAV`).
  - Add nullable `track_id` to `transcode`.
  - Create `track`, `artist`, `title_artist`, `track_artist` tables.
- `MusicBrainzService` — release/release-group/artist fetch, barcode search, fuzzy search. Follows `OpenLibraryService` shape. 1 req/sec throttle. Descriptive `User-Agent`.
- `ImageProxyService` — new `COVER_ART_ARCHIVE` provider; `/proxy/caa/release/{mbid}/{size}` endpoint with path validation (UUID shape, `front-500` / `front-1200`).
- Scan flow: EAN-13 not-ISBN branch tries MB first, falls back to UPCitemdb.
- Dedup at ingest: look up by `musicbrainz_release_group_id + media_type=ALBUM` before creating a new title.
- Web UI: the existing Scan page handles albums with a conditional "review" panel surfacing artist / track count / label for confirmation before save.
- Inventory report includes CDs.

**Deliverable:** Scan any CD; it lands in the catalogue with artist + tracks + cover + storage location + ownership photos. Inventory CSV covers CDs. No browse screens or playback yet.

### **M2 — Artist + album as browse destinations**

- `ArtistScreen` web view at `/artist/:artistId`. New `ArtistHttpService`. Surfaces: hero, bio, owned-albums grid.
- `AlbumScreen` — repurposes `TitleDetailComponent` with album-shaped rendering: cover art hero, artist byline, release year + label, genres + tags, **track list grouped by disc**, per-track duration, play-button placeholders (greyed out until M5).
- `ArtistEnrichmentAgent` — background daemon, two passes: MusicBrainz (bio, begin/end dates, Wikidata URL) then Wikipedia (richer extract + band photo). Pattern copied from `AuthorEnrichmentAgent`.
- Home-feed carousel for albums: "Recently added albums." Gated on `features.has_music`.
- Catalog browse gets a Music landing page (`/content/music`) via the shared `TitleGridComponent` with `mediaType="ALBUM"`. Shell nav surfaces Music when `has_music`.

**Deliverable:** Albums are browseable by artist and directly. Track list is visible. No playback yet.

### **M3 — Album wishlists, "other works," and "complete the discography"**

Parallels M3 for books — extends `wish_list_item` with `musicbrainz_release_group_id` and `wish_type = ALBUM`, plus admin-fulfillment for acquired albums. Artist bibliography loading (`MusicBrainzService.listArtistReleaseGroups(mbid)`) drives the **Other Works** grid on ArtistScreen. No per-series equivalent — music doesn't have a universal series concept, though a "box set" analog is possible later.

**Deliverable:** From an ArtistScreen you can wishlist any of that artist's albums you don't own. Wishlist page shows album wishes alongside media and book wishes.

### **M4 — Audio file ingestion**

- New `MusicScannerAgent` watches `music_root_path`. Walks for audio extensions; reads tags; matches tracks-to-track-rows by MBID or by title/duration fuzzy match.
- New `unmatched_audio` staging table + admin triage queue mirroring Unmatched Books.
- `media_item.file_path` populated for digital rips; `track.file_path` populated per track.
- NAS scanner classifies `music_root_path` as MUSIC and skips video classification, same guard the books directory got.
- Post-NAS-scan trigger kicks `MusicScannerAgent.scanNow()`, same pattern `BookScannerAgent.scanNow()` already uses.

**Deliverable:** Drop a ripped album's directory into the music folder; tracks appear under the album's track list. Still no playback.

### **M5 — Web audio player with on-the-fly transcode**

- `/audio/{trackId}` authenticated range-serving endpoint. Decision tree: (a) if the source format is in the browser's `Accept`, stream the source file with Range support; (b) otherwise, pipe through FFmpeg to AAC m4a @ 256 kbps with a small on-disk LRU ephemeral cache (keyed by `track_id + target_codec`). No pre-transcode queue, no background agent.
- New bottom-bar `AudioPlayerComponent` in the shell. Hidden until the queue has something. Cross-route playback persistence (keep playing through a navigation without rebuilding the `<audio>` element) is **explicitly deferred** — a route change can stop-and-restart playback on M5; the "Continue listening" carousel means a resume is one click away. Picking this up later is a clean refactor that doesn't invalidate M5's UX.
- `PlaybackQueueService` — current track, queue, shuffle / repeat state, position.
- Play button wired up on AlbumScreen (play-album) and per-track rows (play-track-and-queue-rest-of-album).
- `playback_progress.report` extended to include audio; "Continue listening" home-feed carousel.

**Deliverable:** Click Play on an album. Player starts at the bottom of the screen. Nav around the app — player keeps playing. Close the tab, come back tomorrow — a "Continue listening" row resumes at the right track and position. Every browser MM supports can play every track, regardless of source codec.

### **M6 — Personnel enrichment and AccurateRip validation**

- `ArtistEnrichmentAgent` extended to pull MusicBrainz `artist-rels` (band membership with dates) into `artist_membership`, and `recording-rels` (per-track performer credits) into `recording_credit`, for albums the user owns. Rate-limited at 1 req/sec, background.
- AlbumScreen gains a **Personnel** section below the track list when `recording_credit` has rows: groups by role (Performers / Composers / Producers / Engineers), linked artist names, instrument list per performer. Collapses to "Personnel documented by MusicBrainz (N credits)" when not expanded.
- ArtistScreen **Band Members** / **Member Of** section for groups / persons respectively, using `artist_membership` with tenure date ranges.

**Deliverable:** Albums show who played what. Artist pages show band lineups over time.

### **Later**

- **Audiobook playback** (already schema-accommodated via the books milestones) — HTML5 `<audio>` with chapter marker support + per-chapter resume.
- **Persistent playback across route changes** — keep the `<audio>` element alive through Angular navigations. Moderate shell refactor; no rush.
- **"Start Radio" from a seed album or track** — see the design sketch below.
- **iOS player** — AVPlayer-backed player with background playback, offline downloads via the existing DownloadService contract.
- **Android TV / Roku music surfaces** — native UI with DPAD navigation; most work is UI, the data model and servers are ready.
- **Lyrics** — synced or unsynced, from MusicBrainz release-rels or LyricFind (paid) or a local `.lrc` sidecar file next to each track.
- **Loudness normalization** — EBU R128 scan at transcode time, store per-track gain on `track`; apply in the browser via WebAudio.
- **Server-side CD ripping** — optional sidecar (cdparanoia + flac) for users who attach an optical drive to the NAS container.
- **Playlists** — user-authored cross-album track lists. New `playlist` + `playlist_track` tables. Not needed for collection-management but obvious UX next step.

### Design sketch: "Start Radio from this Album" *(post-M6)*

Spotify-style "play music that's like what I'm playing now," bounded to the user's owned library. Three moving parts: **pick seeds**, **discover similar tracks**, **build a queue with variety**.

#### 1. Similar-artist data source — Last.fm, optional API key, graceful degradation

[Last.fm's Artist.getSimilar](https://www.last.fm/api/show/artist.getSimilar) wins on signal-to-noise among the free options. Scrobble-based co-occurrence ranking, huge coverage for mainstream and a useful amount for deep cuts.

**Key handling.** The API key is **optional**, configured on the admin Settings page alongside TMDB / Keepa / etc. No key → radio starts disabled. Once a key is present, the enrichment agent hydrates `artist.lastfm_mbid_similar_json` on demand (per seed, lazily) so we don't pre-walk the whole catalogue.

**Graceful degradation if the key is later removed.** The cached similar-artist JSON on every hydrated `artist` row stays usable for seeds we've already radio'd from; it only expires when the server decides it's too stale. So the feature doesn't hard-disable when the key goes missing — it degrades to "works for the artists we've already cached." The `has_music_radio` feature flag surfaces as:

- `true` when either the API key is set **or** at least one `artist` row has a non-null, non-empty `lastfm_mbid_similar_json` (meaning we have cached similarity data we can serve even without an active key).
- `false` only when the key is absent **and** we've never fetched any similarity data. In that state the "Start Radio" button disappears from the album detail page.

New nullable columns on `artist`:

| Column | Purpose |
|--------|---------|
| `lastfm_mbid_similar_json` | Cached similar-artist list from Last.fm (MBID + match score, serialized) |
| `similar_fetched_at` | Timestamp used for opportunistic refresh; absence of a newer fetch does not invalidate the row |

No new table needed — the data attaches to the artist.

#### 2. Seed → candidate tracks

Given a seed album `A`:

1. Album-level artist(s) are the primary seeds. Fetch Last.fm similar-artists for each.
2. Intersect with **artists the user owns** (queries `title_artist` for `media_type = ALBUM` with `track` rows having `file_path`, or whatever sync/tag ingestion produced). Unowned similar-artists are ignored — we can only play what's ripped.
3. For each owned similar-artist, pick N candidate tracks, biased toward:
   - The artist's most popular tracks (Last.fm has per-track popularity; if missing, use MB's listener-count proxy, or just pick tracks from their earliest-released albums in the collection which tend to be the canonical ones).
   - Avoid back-to-back same-artist (shuffle across artists before within an artist).
4. Append a tail of "deeper cuts from the seed album's artist" for continuity.

#### 3. Queue shape

Radio is a continuously-generated queue, not a finite list:

- `PlaybackQueueService` gets a `QueueMode` flag: `EXPLICIT` (an album play), `RADIO` (generated).
- In `RADIO` mode, when fewer than ~5 tracks remain, the service generates the next ~15 from the seed+history and appends. Keeps indefinite radio cheap.
- Skip-tracking: a skip within 30 seconds of start down-weights that artist for the remainder of the session. Personalization without a permanent feedback table.
- UI: an album's detail page gains a **Start Radio** button next to **Play Album**. The bottom-bar player shows a small "Radio: seeded from *Kind of Blue*" chip when in RADIO mode, and a "Turn off radio" action.

#### 4. Degenerate cases

- **Library too small for overlap.** If fewer than 3 owned similar-artists exist, fall back to same-genre owned albums (existing `genre` join), then to random owned tracks from the era (`release_year ± 5`). The radio always produces something, even if the user only owns six albums.
- **Seed is a compilation.** The album-level credit is "Various Artists," so seed artists are instead drawn from each track's `recording_credit` / `track_artist` data (populated at M6). If personnel enrichment hasn't run yet, seed from the genres on the compilation.
- **Seed is a single track.** Same algorithm with the track's artist(s) as the only seed. Shorter candidate pool but otherwise identical.

#### Milestone slot

This is a **M8 or later** piece — behind audiobook playback in priority. Prerequisites from the current plan: M5 (queue infrastructure) and M6 (recording-credit data for compilation seeding). Self-contained after that.

---

## Resolved Design Decisions

### Q1. MusicBrainz vs. Discogs as primary metadata — **MusicBrainz**

Discogs has a richer physical-release catalogue, but it requires an API key, has stricter rate limits, and its terms of service are more restrictive. MusicBrainz is API-key-free, fully open, has better structured work/release-group/release/recording layering, and its cover-art coverage (via Cover Art Archive) is strong for mainstream catalogue. Discogs stays a possible enrichment source later for rarities and pricing.

### Q2. Album as the title; track as the sub-unit — **yes, not the reverse**

Tempting alternative: every track is its own `title`. Rejected. Music is culturally album-keyed (people say "I bought *Dark Side of the Moon*", not "I bought ten song files"). Genre / tag / ownership / wishlist all make sense at the album level, not per-track. Track-level plays land in `playback_progress` via the existing transcode schema; that's the only per-track persistence needed for MVP.

### Q3. One `artist` table vs. merging with `author` / `cast_member` — **three parallel entities**

See the schema discussion above. Different data sources, different relationship shapes, different queries. Tempting to unify under a `person` table; rejected for the same reasons `author` didn't reuse `cast_member`.

### Q4. File format in NAS layout — **artist / album / NN-track convention, but read tags authoritatively**

Expected directory shape: `<music_root>/<Artist>/<Album>/<NN>-<Track Title>.<ext>`. Tag metadata is authoritative; the directory structure is advisory for human browsing. The scanner doesn't require this layout — it'll match by tag — but defaults its created output to this shape.

### Q5. Transcoding strategy — **on the fly, with an LRU ephemeral cache**

Unlike video (hours per file), audio transcoding runs at 20–40× realtime, so there's no bulk-transcode phase at all. `/audio/{trackId}` serves the source directly when the client accepts the source format; otherwise pipes through FFmpeg to AAC and streams the encoded bytes, cached on disk keyed by `(track_id, target_codec)` so replays and range-scrubs reuse the encoded output. LRU-evict when the cache exceeds a configurable cap. No background transcoder agent, no `transcode` rows for audio.

### Q6. Band vs. individual performer — **modelled as first-class, populated lazily**

`artist.artist_type` enum (`PERSON` / `GROUP` / `ORCHESTRA` / `CHOIR` / `OTHER`). `artist_membership` table captures band-lineup-over-time (*Jim Morrison, The Doors, 1965–1971, vocals*). `recording_credit` table captures per-track performer credits with instrument and role (performer / composer / producer / engineer / mixer).

These are schema-day-one so we don't have to migrate later, but **M1 doesn't populate `recording_credit` or `artist_membership`** — the album-level `title_artist` credit is sufficient to make a catalogue usable. M6 is the milestone that actually fills these in via a background enrichment pass against MusicBrainz's `artist-rels` and `recording-rels`, and the UI gracefully hides the "Personnel" and "Members" sections for artists MB hasn't documented.

MusicBrainz does carry this data for well-documented acts. Coverage is patchy for obscure bands and classical ensembles, but that's an acceptable degradation.

### Q7. User's existing collection — **file-based ingest (M4) is the load-bearing path, not barcode scan (M1)**

The user's current CD collection predates widespread jewel-case retention. Barcodes and purchase prices are mostly unrecoverable. dBpoweramp rips the audio and embeds MusicBrainz IDs plus AccurateRip CRCs in the tags, so M4's file scanner can authoritatively identify tracks from the ripped audio alone.

Implications for milestone ordering:

- M1's barcode-scan path stays the entrance tutorial and covers *future* CD acquisitions, but won't bear the weight of the initial import.
- M4 is where the user does their actual bulk import. Every subsequent milestone assumes M4 has populated the catalogue.
- `MediaItem.upc` and `MediaItem.purchase_price` are nullable and expected to stay null for the bulk of the existing collection. Inventory reporting must render "—" gracefully when pricing is absent rather than zeroing out the replacement total.
- Cover art for tag-ingested tracks comes from MusicBrainz Cover Art Archive keyed by the MB release MBID, not by UPC.
- Proof-of-ownership photos apply just as they do for movies and books. Admin captures photos of the disc / spine / liner notes against the `MediaItem` through the existing flow. With jewel cases gone, the photo is usually the raw disc, which is still legitimate ownership evidence.

### Q8. Replacement pricing — **opportunistic via MB-provided UPC, then Keepa. No manual entry.**

For the fraction of albums where MusicBrainz *does* carry a `barcode` on the release (increasingly common for releases from the last ~20 years, spottier further back), M4's ingest copies the barcode into `media_item.upc`. A `replacement_value`-backfill job then runs the existing Keepa `lookupByAsin` path against the UPC — the same plumbing the DVD flow uses today. Keepa's music root category is `5174`; parameterizing the category on the search endpoints was already flagged as the blocker for book pricing, so fixing it once pays off for both.

Albums where MB has no barcode leave `replacement_value` null. No manual-entry UI — realistically the admin won't use it, and building an unused workflow isn't worth the surface area. Inventory reports render "—" for the null case and carry an annotation that music pricing is incomplete. If automated pricing coverage later turns out to matter more, Discogs scraping or AcoustID-based ASIN lookup can be revisited then.

### Q9. Wishlist entries — **per specific album, never per artist, and compilations render title-first**

The wishlist key is always `musicbrainz_release_group_id + wish_type=ALBUM` — a specific purchasable album, the same way a book wish keys on OL work ID. No artist-level wishes, no genre-level wishes, no "whatever this artist puts out next."

**Display rule for compilation-shaped entries:** when the album's `title_artist` is "Various Artists" (MB's canonical MBID `89ad4ac3-39f7-470e-963a-56509c546377`), OR the album credits more than two artists at the title level, the wishlist row renders:

- **Line 1:** album name in bold
- **Line 2:** a "Compilation" badge plus release year (e.g. "Compilation · 1984")
- **Thumbnail:** cover art

Never a raw "Various Artists" string and never an unwieldy list of 7 artist names. The same rule applies to recently-added carousels and any place the album-artist pair renders.

Single-artist and duet-shaped albums render the familiar "Artist — Album" shape.

---

## Pre-M1 Verifications

All checks complete as of 2026-04-18. M1 can execute against this audit.

- **`media_format` enum extension — safe.** ✅ All five non-exhaustive `when (media_format)` expressions (`CatalogGrpcService.formatPriority` line 1011, `ProtoMappers.toProtoQuality` + `toProtoMediaFormat`, `RokuHomeService` carousel-item quality, `RokuSearchService.qualityLabel`, `RokuFeedService` streaming quality, `RokuTitleService.formatPriority` + `qualityLabel`) have `else` fallbacks that yield `0` / `"FHD"` / `Quality.QUALITY_UNKNOWN` / `MediaFormat.MEDIA_FORMAT_UNKNOWN` for unrecognized values. Music formats that somehow leak into a video-shaped query resolve cleanly. `InventoryReportGenerator.DIGITAL_FORMATS` is the only semantic site — M1 adds `AUDIO_FLAC`, `AUDIO_MP3`, `AUDIO_AAC`, `AUDIO_OGG`, `AUDIO_WAV` to that set (physical `CD` / `VINYL_LP` stay in the report). No pre-M1 refactor.

- **MusicBrainz rate limit — comfortable with an explicit gap.** ✅ MB asks for ≤1 req/sec with a descriptive `User-Agent`. `UpcLookupAgent` already uses the same pattern for UPCitemdb (`MIN_LOOKUP_GAP_MS = 11_000L` enforced via `Clock.sleep`). `MusicBrainzService` copies that: 1100ms minimum gap, `User-Agent = "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"` (already used by `OpenLibraryHttpService`). Scan volume is human-paced so bursts aren't a concern; background enrichment paces itself at 1 req/sec.

- **Cover Art Archive image proxy — one-line Provider extension.** ✅ `ImageProxyService.Provider` currently holds `TMDB` and `OPEN_LIBRARY`; adding `COVER_ART_ARCHIVE("coverartarchive.org", "caa")` is a one-line change. The existing 4-hop redirect follower with per-hop SSRF screening already handles CAA → archive.org → iaN-mirror chains (identical to the OL cover flow we debugged earlier this month). `ImageProxyHttpService` adds a `/proxy/caa/release/{mbid}/{size}` route with an MBID UUID-v4 validator and a size regex matching `front-250` / `front-500` / `front-1200` / `front`. Existing SSRF test suite covers the new provider with no new cases needed.

- **Audio tag reading — FFprobe, no new Java dep.** ✅ We already invoke FFmpeg for video transcoding; the Alpine `ffmpeg` package includes `ffprobe` in the same binary. `ffprobe -print_format json -show_format -show_streams file.flac` returns `format.tags` as a JSON object carrying every MB tag we need (`MUSICBRAINZ_ALBUMID`, `MUSICBRAINZ_RELEASETRACKID`, `MUSICBRAINZ_ARTISTID`, `TITLE`, `ALBUM`, `ARTIST`, `ALBUMARTIST`, `TRACKNUMBER`, `DISCNUMBER`, `DATE`, `GENRE`, `ACCURATERIPCRC`). No `jaudiotagger` dependency, no LGPL attribution, no new license footprint. Slightly slower per file than an in-process reader but fine for a background scanner walking a library overnight.

- **Browser FLAC support — universal on target browsers.** ✅ Chrome 56+ (Jan 2017), Firefox 51+ (Jan 2017), Safari 11+ / iOS 11+ (2017), Edge 16+ (2017). All years past the threshold. AAC on-demand stays in the design for robustness (ancient browsers, odd FLAC encodes), not as a required crutch.

- **Docker FFmpeg includes AAC.** ✅ Dockerfile line 28 — `apk add --no-cache ffmpeg curl`. Alpine's `ffmpeg` ships with libavcodec's built-in `aac` encoder. No `libfdk_aac` (GPL-incompatible with Alpine's licensing) but built-in AAC at 256 kbps is transparent for near-all listeners. `ffmpeg -i in.flac -c:a aac -b:a 256k -f adts -` works out-of-box.

- **Multi-disc tag conventions — split-on-slash handles every case.** ✅ dBpoweramp writes disc/track numbers in each format's canonical form: Vorbis/FLAC `DISCNUMBER=1/2` (fractional) or `DISCNUMBER=1` + `TOTALDISCS=2`; ID3v2.4 `TPOS=1/2`; M4A `disk` atom as two uint16s. FFprobe exposes each as a string under `format.tags`. A single rule — split on `/`, take the first token, parse as int — handles every combination. Same rule applies to `TRACKNUMBER` / `TRKN`. No extra cases.

All pre-M1 items resolved. Schema and ingestion plan is ready to execute when the milestone gets picked up.

---

## Open Questions (for the user)

All previously-outstanding questions have resolved:

- Pricing — Keepa via MB-provided UPC where available; null otherwise. No manual entry.
- Wishlist identity — per specific album, compilation-aware rendering.
- Cross-route playback persistence — deferred to Later.
- Radio — Last.fm (optional key) with graceful degradation on cached data; design sketched.
- AccurateRip verification — dropped.

None currently outstanding. Next open question raises itself when an M1 prototype hits something unexpected.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="BOOKS.md">Books Design Doc</a> &bull;
  <a href="FEATURES.md">Feature Tracker</a>
</p>
