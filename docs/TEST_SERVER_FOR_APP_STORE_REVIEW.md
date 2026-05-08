# Test Server for App Store Review

A plan — not yet implemented — for standing up a deterministic test
instance of MediaManager pre-loaded with public-domain content. Two
use cases drive the design:

1. **Periodic refresh.** Apple's App Review wants demo credentials
   and a working server to exercise. Spinning that up by hand each
   time the App Store listing changes is mechanical-but-tedious; the
   recipe should be checked in and re-runnable.
2. **Automated screenshot extraction.** Fastlane on iOS and
   Playwright on web both want a target server with stable, known
   content so the same screenshot run on Tuesday and Friday produces
   the same images. Without deterministic fixtures, drift in the
   underlying data corrupts the App Store listing.

## Repo shape

A new repo, `jeffbstewart/MediaManager-test-fixtures`, MIT-licensed
for the scripts. Pure text — **no media, no H2 database, no image
cache.** Cold-start time goes up (~30 min to seed instead of restoring
a snapshot in seconds), but every other tradeoff favors text-only:

- No Git LFS / no binary-diff weirdness
- No "what schema was this seeded with?" — the docker image's Flyway
  migrations always produce the right schema, fixtures land into
  whatever is current
- No takedown risk — see "GitHub considerations" below

```
MediaManager-test-fixtures/
├── docker-compose.yml          # references prod image from the registry
├── .githooks/
│   └── pre-commit              # block media file commits
├── .github/workflows/
│   └── no-media.yml            # server-side guard for --no-verify bypass
├── scripts/
│   ├── setup-hooks.sh          # one-time `git config core.hooksPath`
│   ├── fetch-movies.sh         # archive.org → ffmpeg → out dir
│   ├── fetch-albums.sh         # Musopen / archive.org 78s → out dir
│   ├── fetch-books.sh          # Standard Ebooks OPDS → out dir
│   ├── seed-users.sh           # admin API → create test accounts
│   ├── link-fixtures.sh        # admin API → attach TMDB / MB / OL ids
│   └── reset.sh                # nuke H2 + media + cache, start over
├── fixtures/
│   ├── movies.tsv              # archive_id, tmdb_id, target_filename
│   ├── albums.tsv              # mb_release_mbid, target_dir
│   ├── books.tsv               # standardebooks_id
│   └── users.tsv               # username, role, display_name, content_ceiling
├── secrets/
│   └── example.env             # template; real .env is gitignored
└── README.md
```

## Public-domain content sources

### Movies — archive.org Feature Films

Items pinned by `archive.org` identifier in `fixtures/movies.tsv`.
Curated starter set, all PD in the US, all on TMDB so the enrichment
pipeline runs end-to-end:

| Title | Year | TMDB ID | PD basis |
|---|---|---|---|
| Night of the Living Dead | 1968 | 10331 | Missing copyright notice |
| The General | 1926 | 974 | Pre-1929 silent |
| Nosferatu | 1922 | 653 | Pre-1929 silent |
| Metropolis | 1927 | 19 | Pre-1929 silent |
| His Girl Friday | 1940 | 970 | Copyright lapsed 1968 |
| Charade | 1963 | 4808 | Invalid copyright notice |
| Plan 9 from Outer Space | 1957 | 33170 | Notable bad film |
| Detour | 1945 | 4513 | Noir |
| The Phantom of the Opera | 1925 | 39106 | Lon Chaney silent |
| A Trip to the Moon | 1902 | 775 | Méliès |

Each archive.org item ships an MP4 download. Some items use weird
codecs / containers; the fetch script normalizes to H.264 + AAC +
faststart via `ffmpeg -c:v libx264 -c:a aac -movflags +faststart` so
the transcode buddy doesn't have to.

### Books — Standard Ebooks first, Project Gutenberg as fallback

`standardebooks.org` ships ~700 hand-typeset PD EPUBs with cover art
baked in and consistent metadata. OPDS feed at
`standardebooks.org/feeds/opds` gives us a machine-readable index.

Starter mix targets author / series variety so the catalog browse
surfaces aren't degenerate:

- **Austen** (Pride and Prejudice, Sense and Sensibility, Emma) —
  multi-book single-author shelf
- **Conan Doyle** (Sherlock Holmes canon, ~9 volumes) — multi-volume
  series detection
- **H. G. Wells** (Time Machine, War of the Worlds, Invisible Man) —
  same-author no-series
- **Lovecraft** (Call of Cthulhu, At the Mountains of Madness) —
  short-story collections shape
- **Verne** (Around the World in 80 Days, 20,000 Leagues, Journey to
  the Centre) — translation provenance varies, good edge cases
- **Twain, Wodehouse, Christie** (verify Christie PD status — depends
  on jurisdiction)

Standard Ebooks doesn't carry ISBNs. The current scan flow is
ISBN-based, so each book goes through the admin's "search OpenLibrary"
flow at link time — same shape as the new MusicBrainz-by-URL flow we
shipped for music.

### Audio — three tiers

| Tier | Source | What's there |
|---|---|---|
| 1 | Musopen — `musopen.org` | Classical recordings explicitly released to PD. Bach Brandenburg, Beethoven symphonies, Chopin nocturnes, Goldberg Variations. Mostly FLAC + MP3. Metadata-clean. |
| 2 | archive.org `78rpm` collection | ~400k transferred 78rpm records. Caruso, early Louis Armstrong (Hot Five 1925-28, PD post-2024), 1920s jazz/blues. Variable audio quality (it's old shellac). MusicBrainz coverage usually good. |
| 3 | Free Music Archive — `freemusicarchive.org` | CC-licensed (not strict PD) modern indie / electronic / world. MB coverage spotty — would need manual catalog entries. Useful for variety but more work. |

For BPM / dance-preset testing the fixture mix should hit a few
tempo bands intentionally:

- Strauss waltzes (Musopen) → Slow Waltz / Viennese Waltz presets
- 1920s big-band jazz (archive.org 78s) → Foxtrot tempo
- 1920s Argentine tango (archive.org) → Argentine Tango preset

## Walkthrough sections (README.md outline)

1. **Quickstart** — `git clone` → `cp secrets/example.env .env` →
   `docker-compose up` → `http://localhost:8080` → setup wizard for
   first admin.
2. **Test user accounts** — credentials list (admin + viewer + family
   member), created via `scripts/seed-users.sh`. **Usernames are
   plain usernames, not email addresses** — the app's auth layer
   uses bare usernames intentionally.
3. **Seeding the catalog** —
   - 3a. `scripts/fetch-movies.sh` (archive.org → ffmpeg → NAS dir)
   - 3b. `scripts/fetch-albums.sh` (Musopen / archive.org 78s)
   - 3c. `scripts/fetch-books.sh` (Standard Ebooks OPDS)
   - 3d. `scripts/link-fixtures.sh` (uses admin API to attach TMDB /
     MB MBID / OL work to each ingested item)
4. **Resetting** — `scripts/reset.sh` nukes H2 + media + image cache,
   starts fresh. One command.
5. **Screenshot capture** — Fastlane Snapshot for iOS, Playwright
   for web. Both run against the seeded server. Both produce
   identical output across runs because the fixture data is pinned.
6. **Verifying PD status** — pointer per source to the canonical PD
   claim. Includes "if a takedown lands on a fixture, swap to a
   different identifier in the .tsv" recovery path.

## Test user accounts

`fixtures/users.tsv` columns: `username`, `role`, `display_name`,
`content_rating_ceiling`. Reasonable starter set:

| username | role | display_name | ceiling | purpose |
|---|---|---|---|---|
| `admin` | admin | Test Admin | none | catalog management screenshots |
| `viewer` | viewer | Sample Viewer | none | populated library, has watch history |
| `kid` | viewer | Kid Account | PG-13 | content-rating-gate screenshots |
| `empty` | viewer | New User | none | empty-state screenshots |

Passwords live in the gitignored `.env` so the repo can document
"logged in as `viewer`" without exposing real-looking credentials.

The first admin must still be created via the setup wizard at
`/setup` — admin-create requires an existing admin to authenticate.
`scripts/seed-users.sh` picks up from there: reads the admin
password from `.env`, logs in via the auth API, then POSTs to the
admin user-creation endpoint for each row in `users.tsv`.

## Presubmit + CI guards

Belt-and-suspenders against media accidentally landing in the repo:

### `.gitignore`

```
data/
fetched/
*.mp4 *.mkv *.m4v *.avi
*.mp3 *.flac *.m4a *.aac *.ogg *.opus *.wav
*.epub *.pdf
*.jpg *.jpeg *.png *.gif *.webp *.webm
# allow specific exceptions when needed
!fixtures/example-*
!docs/**/*.png
```

### `.githooks/pre-commit`

```bash
#!/usr/bin/env bash
set -e
forbidden=$(git diff --cached --name-only --diff-filter=ACM | \
  grep -E '\.(mp4|mkv|m4v|avi|mp3|flac|m4a|aac|ogg|opus|wav|epub|pdf)$' || true)
if [ -n "$forbidden" ]; then
  cat >&2 <<EOF
ERROR: media file in staged changes — this repo holds scripts only.
  $forbidden
Media is fetched at runtime via scripts/. Don't commit it.
EOF
  exit 1
fi
```

Activated via `scripts/setup-hooks.sh` running
`git config core.hooksPath .githooks`. README documents the one-time
setup step.

### GitHub Actions (`no-media.yml`)

Server-side guard for the `--no-verify` bypass case:

```yaml
on: [pull_request, push]
jobs:
  no-media:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: Reject media files
        run: |
          set -e
          base="${{ github.event.pull_request.base.sha || 'origin/main' }}"
          forbidden=$(git diff --diff-filter=ACM --name-only "$base"...HEAD | \
            grep -E '\.(mp4|mkv|m4v|avi|mp3|flac|m4a|aac|ogg|opus|wav|epub|pdf)$' || true)
          if [ -n "$forbidden" ]; then
            echo "::error::Media files in PR — rejecting:"
            echo "$forbidden"
            exit 1
          fi
      - name: Reject oversized files
        run: |
          set -e
          oversize=$(git diff --diff-filter=ACM --name-only origin/main...HEAD | \
            xargs -I {} find {} -size +5M 2>/dev/null || true)
          if [ -n "$oversize" ]; then
            echo "::error::Files over 5MB:"
            echo "$oversize"
            exit 1
          fi
```

The 5MB size cap catches anything that slips past the extension
matcher — a media file renamed to `.bin` would still trip this.

## Image cache caveat

Cover art / headshots / TMDB posters get cached to
`data/image-cache/` as a side effect of normal browsing. Once the
fixture data is seeded and the screenshots have run, the cache
contains thousands of JPEGs. The `data/` gitignore catches this
automatically — but worth flagging so a contributor doesn't write a
fixture-creation script that drops a `poster-seed.png` next to the
seed scripts.

## GitHub considerations

### Risk — DMCA false positive

Even genuinely-PD content occasionally draws mistaken takedowns. The
takedown is recorded as a strike on the GitHub account regardless of
whether the content was actually infringing. Three strikes triggers
suspension.

**Mitigation:** Don't host media files in the repo. Scripts reference
canonical PD sources by URL — archive.org, Standard Ebooks, Musopen.
Those sources have weathered DMCA scrutiny themselves; if a takedown
lands there, our script breaks but the account doesn't take a hit.

### Risk — repo-size limits

GitHub soft-warns above 1 GB and hard-rejects single files >100 MB.
Even an "empty" H2 with the seeded catalog would be 10–50 MB
depending on the schema. By keeping H2 out, we never approach the
limits.

### Risk — music PD edge cases

Music PD law is messier than text. Pre-1923 sound recordings entered
PD on Jan 1, 2022 under the Music Modernization Act, but specific
recordings have foreign-rights complications (an Italian recording
from 1922 might still be in copyright under EU law). For a test
fixtures repo cloned by Jeff and CI, this is theoretical. For a
shared-with-EU-users instance, it's worth flagging in the README.

**Mitigation:** Stick to recordings that are unambiguously PD in the
US — Musopen explicit-PD releases for classical, Standard Ebooks for
books. The curated movie list above covers the well-tested-PD set
for film.

## Open questions before scaffolding

1. **Repo location.** Default is `jeffbstewart/MediaManager-test-fixtures`
   alongside the main app. Could go in a personal-named org instead
   for distribution-clarity.
2. **Direct EPUB import endpoint.** Books currently lack a "drop
   EPUB and ingest" admin path; the scan flow is ISBN-based.
   `link-fixtures.sh` either fakes ISBNs (then uses the new OL search
   admin flow per book) or calls a new endpoint. The fake-ISBN path
   works against the existing app; the new endpoint is a small
   addition that significantly speeds book ingestion. Decide before
   scaffolding `fetch-books.sh`.
3. **Screenshot CI cadence.** Manual-trigger only (cold-start cost,
   ~30 min per run) or nightly schedule (faster feedback if a UI
   regression breaks layout)? Manual feels right for now —
   App Store screenshots ship on a marketing-driven schedule, not a
   code-driven one.
