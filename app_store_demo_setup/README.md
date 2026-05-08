# App Store Demo Setup

Scripts and fixture lists for standing up a deterministic
MediaManager instance pre-loaded with public-domain content. Used
for two things:

1. **App Review demo server** — Apple's reviewers need credentials
   and a working server to exercise. The recipe here is committed
   so refreshing it for each App Store listing change is a single
   re-run.
2. **Screenshot capture** — Fastlane (iOS) and Playwright (web)
   both want a deterministic backend so a Tuesday run and a Friday
   run produce byte-identical screenshots.

## Two directories, separately mounted

The demo server uses **two** host directories. Keep them separate so
re-seeding doesn't force re-downloading and so the storage volume
can be wiped clean independent of the media library.

| Directory | Holds | Wiped by |
|---|---|---|
| `demo_media/` | The fetched MP4s / EPUBs / FLACs that the scanners walk. Slow to populate (cold start ≈ 30 minutes). | Nothing automated — manual `rm -rf`, or re-run a fetch script over it. |
| `demo_storage/` | Server runtime state: H2 database, image cache, transcode cache, log buffer. Built up by the running container. | `scripts/reset.sh demo_storage` (with the server stopped). |

The setup scripts in this directory only need to know where
`demo_media` lives — they populate it and exit. The server itself is
launched separately (see "Server runtime" below) with both
directories mounted.

### Layout the fetch scripts produce inside `$DEMO_MEDIA`

```
$DEMO_MEDIA/
├── movies/                                # → nas_root_path
│   ├── Night of the Living Dead (1968)/
│   │   └── night-of-the-living-dead.mp4
│   ├── The General (1926)/
│   │   └── the-general.mp4
│   └── ...
├── books/                                 # → books_root_path
│   ├── Pride and Prejudice - Jane Austen.epub
│   ├── The Time Machine - H. G. Wells.epub
│   └── ...
└── music/                                 # → music_root_path
    ├── Johann Sebastian Bach/
    │   └── Brandenburg Concertos/
    │       ├── 01 - Concerto No. 1 in F.flac
    │       └── ...
    └── ...
```

Whatever the demo-server runtime uses for `app_config` (REST `/api/
v2/admin/settings` or the gRPC `AdminService.UpdateSettings` RPC),
the three keys must point at those three sibling subdirs:
`nas_root_path` → `$DEMO_MEDIA/movies`, `books_root_path` → `$DEMO_
MEDIA/books`, `music_root_path` → `$DEMO_MEDIA/music`.

## End-to-end walkthrough

1. **Pick directories.** Outside the source tree:
   ```sh
   export DEMO_MEDIA=$HOME/mm-demo/media
   export DEMO_STORAGE=$HOME/mm-demo/storage
   mkdir -p "$DEMO_MEDIA" "$DEMO_STORAGE"
   ```

2. **Fetch fixtures into `$DEMO_MEDIA`.** Each fetcher is
   independent and idempotent — re-running skips anything already
   present:
   ```sh
   ./gradlew :app_store_demo_setup:run --args="fetch-movies $DEMO_MEDIA"
   ./gradlew :app_store_demo_setup:run --args="fetch-books  $DEMO_MEDIA"
   ./gradlew :app_store_demo_setup:run --args="fetch-albums $DEMO_MEDIA"
   ```
   …or run all three in sequence:
   ```sh
   ./gradlew :app_store_demo_setup:run --args="seed-all $DEMO_MEDIA"
   ```
   **Prerequisite:** ffmpeg on PATH. The fetchers normalize fetched
   videos to H.264 + AAC + faststart so the transcode buddy doesn't
   have to re-encode them on first play.

3. **Launch the demo server.** (See "Server runtime" below.) Both
   `$DEMO_MEDIA` and `$DEMO_STORAGE` are mounted; the three scan-root
   `app_config` keys are set to the three media subdirs.

4. **First-time wizard.** Open the running server's URL and walk the
   `/setup` wizard to create the first admin (the wizard insists on
   it — admin-create requires an existing admin to authenticate, so
   the very first one bootstraps from the wizard).

5. **Seed extra users.** Once the first admin exists:
   ```sh
   ./gradlew :app_store_demo_setup:run --args="seed-users $DEMO_MEDIA"
   ```
   Reads `fixtures/users.tsv`, logs in as the wizard-created admin
   (password from `secrets/.env`), creates the rest.

6. **Trigger the catalog scan** through the admin UI. The three
   scanner agents (NAS, Books, Music) will walk the media subdirs
   and ingest. Movies hit TMDB, books hit OpenLibrary, albums hit
   MusicBrainz — give it a few minutes.

7. **Pin TMDB / MusicBrainz / OpenLibrary identifiers.** The admin
   linking flows handle this manually in production; for demo
   determinism we want the same TMDB ID every run, no human in the
   loop:
   ```sh
   ./gradlew :app_store_demo_setup:run --args="link-fixtures $DEMO_MEDIA"
   ```
   Uses the identifiers in `fixtures/movies.tsv` etc. and the admin
   API to pin each scanned title to the right external record.

## Resetting

Plan A: nuke server-runtime state, leave media in place. Re-running
the seed flow against a fresh `$DEMO_STORAGE` re-ingests the still-
on-disk fetches without re-downloading.

```sh
./gradlew :app_store_demo_setup:run --args="reset $DEMO_STORAGE"
```

The reset subcommand first verifies the server isn't running
against that storage directory (refuses to nuke a live database),
then deletes the H2 file + image / transcode caches.

To go further and also clear the media library, pass `--full`:

```sh
./gradlew :app_store_demo_setup:run \
  --args="reset --full $DEMO_STORAGE $DEMO_MEDIA"
```

## Test user accounts (from `fixtures/users.tsv`)

| username | role | display_name | rating ceiling | purpose |
|---|---|---|---|---|
| `admin` | admin | Test Admin | none | catalog management screenshots |
| `viewer` | viewer | Sample Viewer | none | populated library, watch history |
| `kid` | viewer | Kid Account | PG-13 | content-rating-gate screenshots |
| `empty` | viewer | New User | none | empty-state screenshots |

Passwords live in `secrets/.env` (gitignored). The repo can
document "logged in as `viewer`" without exposing real-looking
credentials. Copy `secrets/example.env` to `secrets/.env` and fill
in values once.

## Why a Kotlin tool, not shell scripts

This started as `bash` scripts. Killed by the realization that
parsing the archive.org metadata API in shell needs a JSON tool
(`jq`), and the project's conventions ban Python and reserve Node
for SPA / Playwright. A small Gradle subproject with a Kotlin
`main` slots in cleanly:

- reuses the project's existing `gson` dependency for JSON parsing
- the JDK's `java.net.http.HttpClient` covers retries + streaming
  downloads without a third-party HTTP library
- one toolchain (Gradle) instead of two; Windows / macOS / Linux
  all work without per-OS shell quirks
- `installDist` produces a `bin/app_store_demo_setup` wrapper if
  you'd rather not type the gradle invocation every time

The TSVs in `fixtures/` stay plain text — they're the curated
source-of-truth and language-neutral.

Subcommand source layout:

```
app_store_demo_setup/
├── build.gradle.kts                    # subproject definition
└── src/main/kotlin/net/stewart/mediamanager/demosetup/
    ├── Main.kt          # subcommand dispatcher
    ├── Tsv.kt           # shared TSV reader
    ├── Http.kt          # shared retry + streaming HTTP client
    ├── FetchMovies.kt   # archive.org → ffmpeg → demo_media/movies/
    ├── FetchBooks.kt    # (TODO) Standard Ebooks → demo_media/books/
    ├── FetchAlbums.kt   # (TODO) Musopen / 78s → demo_media/music/
    ├── SeedUsers.kt     # (TODO) admin-API account creation
    ├── LinkFixtures.kt  # (TODO) pin TMDB / MB / OL ids per row
    └── Reset.kt         # (TODO) confirm-then-nuke demo_storage
```

`fetch-movies` is the only fully-implemented subcommand today;
the rest print an "not yet implemented" stub so the dispatcher
shape is fixed and incremental work can land subcommand-by-
subcommand.

## Server runtime

(TBD — covered in a follow-up. Two viable shapes:
1. `scripts/run-server.sh` that wraps `docker run` against the
   `mediamanager:latest` image with the two volumes mounted and
   the three `MM_*_ROOT` env vars set.
2. A minimal `docker-compose.yml` checked into this directory that
   does the same. The user's existing `lifecycle/` scripts target
   the *production* docker setup; the demo wants its own
   compose file so port + volume choices don't collide.

Pick one once the fetch / seed / reset path is in.)

## Public-domain content sources

See `fixtures/movies.tsv`, `fixtures/books.tsv`, `fixtures/albums.
tsv` for the curated lists. Provenance per source:

- **Movies** — archive.org Feature Films collection. Each entry
  pinned by archive.org identifier; the fetch script normalizes to
  H.264 + AAC + faststart so the transcode buddy doesn't have to.
- **Books** — Standard Ebooks (`standardebooks.org`) hand-typeset
  PD EPUBs with cover art baked in. OPDS feed at
  `standardebooks.org/feeds/opds`.
- **Music** — Musopen for explicitly-PD-released classical
  recordings; archive.org's `78rpm` collection for early jazz /
  blues. BPM mix intentionally hits a few dance-preset tempo bands
  (Strauss waltzes for Slow / Viennese Waltz, 1920s big-band for
  Foxtrot, 1920s tango for Argentine Tango).

If a fetch URL ever 404s (rare but happens — archive.org items get
moved or de-listed), swap the identifier in the relevant `.tsv` and
re-run. The scripts surface non-200s loudly rather than silently
skipping.

## Why this lives in the main repo

Earlier draft (see `docs/TEST_SERVER_FOR_APP_STORE_REVIEW.md`)
proposed a separate `MediaManager-test-fixtures` repo. We collapsed
that into this directory because:

- the data root for fetched media lives **outside** the source tree
  anyway (the path-arg scripts populate `$DEMO_MEDIA`), so the
  takedown / repo-bloat concerns from the separate-repo plan don't
  apply here;
- keeping the fixture lists alongside the code that ingests them
  means proto / API breakages can't drift between repos;
- presubmit + the existing `data/` ignore already cover the few
  binary-asset patterns this directory could accidentally produce.

Anything fetched lives in `$DEMO_MEDIA` (outside the repo) and is
never staged.
