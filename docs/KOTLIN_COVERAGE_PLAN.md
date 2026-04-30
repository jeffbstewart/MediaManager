# Kotlin Test Coverage Plan

## Tooling

- **JaCoCo 0.8.13** wired into `build.gradle.kts` (Apr 2026).
  - `./gradlew jacocoTestReport` builds HTML + XML at
    `build/reports/jacoco/test/`.
  - `./gradlew coverageSummary` prints a low-token plain-text summary
    (overall %, per-package, top-N least-covered classes) read off the
    XML — designed for terminal use without opening the HTML.
  - The summary task accepts `-Plimit=N` (default 25) and
    `-Ppackage=net/stewart/mediamanager/service` for drill-down.
- **Authored-source filter.** JaCoCo's class-include list is computed
  from `src/main/kotlin/**/*.kt` at task-config time so protoc-generated
  Java messages and Kotlin DSL builders never enter the denominator.
  Without this filter the headline number reads ~6% because 163K lines
  of generated grpc code dominate the count.

## Baseline (2026-04-28, master at f6b4b68)

```
Overall:
  L  17.1% (  5082/ 29644)   B  10.4% ( 2137/20524)   I  15.6% (33570/215774)
  M  24.2% (  1184/  4895)   C  49.4% (  257/  520)

Per package (line %, worst-first):
  net.stewart.mediamanager            0.0% (   0/  414)   <- Main.kt + Bootstrap.kt
  net.stewart.mediamanager.util       0.0% (   0/    4)
  net.stewart.mediamanager.armeria    4.0% ( 284/ 7122)
  net.stewart.mediamanager.grpc      12.0% ( 779/ 6476)
  net.stewart.mediamanager.service   23.6% (3484/14759)
  net.stewart.mediamanager.entity    61.6% ( 535/  869)
```

## Where the missing lines live

The ten worst classes by absolute missed lines account for ~6,200 of
the 24,500 uncovered lines (25%):

| Class | Pkg | Line% | Missed |
|---|---|---:|---:|
| AdminGrpcService | grpc | 4.9% | 1,808 |
| CatalogGrpcService | grpc | 0.6% | 1,577 |
| TranscodeLeaseService | service | 5.5% | 460 |
| NasScannerService | service | 2.4% | 456 |
| MusicScannerAgent | service | 0.0% | 449 |
| ProtoMappersKt | grpc | 27.2% | 401 |
| UnmatchedAudioHttpService | armeria | 1.8% | 376 |
| TitleDetailHttpService | armeria | 0.6% | 349 |
| TranscoderAgent | service | 1.2% | 338 |
| VideoStreamHttpService | armeria | 2.2% | 314 |

The gap to 90% is ~21,600 lines. Realistic scope: 700–3,000 new test
methods at 15–30 LoC each. Achievable over multiple commits, not in
one sitting.

## Strategy

The plan is tiered: do the easy-yield work first (entities, pure-logic
service classes) to lift the floor, then tackle gRPC + service
integration in larger commits, and exclude code that isn't worth
testing from the target denominator.

### What to exclude from the 90% target

These are either too expensive to test in unit form, are being
deleted, or aren't authored business logic:

- `Main.kt` — entry point; covered by smoke startup tests, not unit
  tests.
- `Bootstrap.kt` — Flyway / DB / agent wiring; exercised by
  `ArmeriaServerStartupTest` already. Leave at low coverage.
- Background `*Agent` classes that depend on real ffmpeg / NAS / TMDB
  calls (`TranscoderAgent`, `MusicScannerAgent`, `BookScannerAgent`,
  `PersonnelEnrichmentAgent`, `PriceLookupAgent`, `PopularityRefreshAgent`,
  `RecommendationAgent`, `ArtistEnrichmentAgent`, `AuthorEnrichmentAgent`).
  Inner pure-function helpers ("score this candidate", "parse this
  filename") get unit tests — the agent loop itself doesn't.
- Roku-specific HTTP services (`RokuFeedHttpService`, `RokuSearchService`,
  `RokuTitleService`) — Roku is the only consumer, behavior verified
  on the device. Mark for a tighter integration pass instead of unit
  coverage.
- HTTP services that are mid-migration to gRPC and will be deleted in
  the next sprint (`TitleDetailHttpService`, `HomeFeedHttpService`,
  `TagHttpService`, etc. — anything fully superseded by a gRPC RPC
  the SPA uses today). Only test the HTTP shim if iOS still hits it.

Concretely: add a `coverageSummary` companion task `coverageGate` that
checks the LINE % across the *included* classes (rule below). Carve
the exclusions into the JaCoCo `classDirectories` filter so the gate
matches the report.

### What's in scope for 90%

After excluding the above, the in-scope surface is roughly:

- `entity/` — almost done at 61.6%; another pass takes it to 90%+.
- `service/` (pure logic) — ~10K lines: parsers, validators, scorers,
  fuzzy matchers, transcode-file logic, search index, tag rules,
  playlist logic, wish-list lifecycle, content-rating filter, JWT,
  WebAuthn, reclassification, OL/MB/TMDB clients, ImageProxyService.
- `service/` (light I/O) — ~3K lines: WishListService, PlaylistService,
  TagService, RadioService, AlbumRescanService, ReclassifyService,
  RecommendationService, TmdbPosterPathResolver. Test against the
  in-memory H2 the existing tests already use.
- `grpc/*GrpcService.kt` — every RPC. Use `GrpcTestBase` (already
  exists) + the InProcess gRPC channel + H2 fixtures. One file per
  service, parameterised over RPCs.
- `armeria/` HTTP services that are *not* being deprecated — mostly
  the Image* family, the proxy paths, ImageHttpServices, AuthRestService,
  CSP / SecurityHeaders plumbing. Use Armeria's `ServerExtension` test
  harness.
- `grpc/AuthInterceptor`, `LoggingInterceptor`, `ObservabilityGrpcService`,
  `ProtoMappers`. Pure logic — easy.

## Phased plan

Each phase is one commit unless noted. Each phase finishes with a
`coverageSummary` line in the commit message documenting the headline
number so progress is auditable in `git log --grep coverage`.

### Phase 0 — Baseline and gate (this commit)

- ✅ JaCoCo plugin, authored-source filter, `coverageSummary` task.
- Add a `coverageGate` task that fails when overall LINE coverage
  drops below the current floor. Initial floor: 17%. Each subsequent
  phase ratchets it up.
- Wire into `lifecycle/pre-submit.sh` so PRs that drop coverage fail
  presubmit.

### Phase 1 — Entity tests (target +1.5%)

Existing entity tests cover Title / Tag / TmdbId / ContentRating.
Fill in the rest:

- `Author`, `Artist`, `BookSeries`, `Track`, `Transcode`, `MediaItem`,
  `WishListItem`, `Playlist`, `PlaylistTrack`, `RecommendedArtist`,
  `LiveTvChannel`, `Camera`, `Genre`, `TitleArtist`, `TitleAuthor`,
  `TitleTag`, `AppUser`, `SessionToken`, `ScanRecord`, `LocalImage`,
  `OwnershipPhoto`, `AppConfig`, etc.
- Each test: round-trip `save()` + `findById()`, assert mappings of
  enums and nullable fields, exercise the `*Url` helpers.
- Stretch: parameterised data-class tests for `equals` / `hashCode` /
  `copy` semantics where the entity has computed properties.

Estimated effort: ~30 small test files, ~600 LoC of tests.

### Phase 2 — Pure-logic service tests (target +5–7%)

Classes whose tests need no I/O and no DB:

- `TitleCleanerService`, `FilenameSanitizer`, `SeasonDetector`,
  `MultiPackDetector`, `FuzzyMatchService`, `SearchQueryParser`,
  `TranscodeFileParser`, `ContentRatingFilter`, `UriCredentialRedactor`,
  `parseSeriesLine`, `Bm25SearchScorer` if any.
- `AlbumRescanService.scoreMatch` + `albumTagLooksRight` —
  pulled out in the rescan extraction commit, easy to drive with
  fake `AudioTagReader.AudioTags` instances.
- `ProtoMappers` — every `toProto*` mapper. Build the entity, call
  the mapper, assert proto fields. Highest density of easy wins
  in the grpc package.

A few of these already have tests but rarely with full branch
coverage. Spot-check each existing test, add the missing branches.

Estimated effort: ~25 test files, ~1,500 LoC.

### Phase 3 — Service tests with H2 (target +6–8%)

Existing `WishListServiceBookTest` is the template — `init { ... }`
sets up Flyway against an in-memory H2 and seeds entities. Apply
the pattern to:

- `PlaylistService` (has a stub test today; expand to every operation:
  add/remove/reorder/duplicate/hero/privacy/progress).
- `TagService` (already has one — extend to cover every CRUD path
  + name-collision handling).
- `WishListService` (extend Phase 2 of an existing test to cover
  album wishes, transcode wishes, vote ON/OFF, dismiss lifecycle).
- `RadioService`.
- `RecommendationService.recompute` — the math + the DB write.
- `ReclassifyService` — title media-type changes + side effects.
- `AlbumRescanService.rescan` — wire fake audio files via a
  temp directory + `AudioTagReader.read` redirect.
- `TmdbService`, `OpenLibraryService`, `MusicBrainzService` HTTP
  layer — already partially tested; extend for error paths,
  retries, redirect handling.
- `ImageProxyService` — existing test; extend for circuit-breaker
  open / 404 / 503 paths.

Estimated effort: ~20 test files, ~3,000 LoC (some are large because
each operation needs a setup arc).

### Phase 4 — gRPC handlers (target +6–8%)

`GrpcTestBase` exists. Each `*GrpcService.kt` gets a sibling
`*GrpcServiceTest.kt` covering every RPC with at least:

- Happy path (returns the expected proto).
- Authorization failure (returns PERMISSION_DENIED for non-admin
  RPCs).
- Validation failure (INVALID_ARGUMENT / NOT_FOUND).
- Idempotent re-call (where applicable — duplicate add, etc.).

Priority order, by missed lines:

1. `AdminGrpcService` — 1,808 missed. Group by surface area: tags,
   transcodes, scans, users, family members, live-tv tuners, data
   quality, etc. Likely splits into 6–8 test files.
2. `CatalogGrpcService` — 1,577 missed. Most RPCs are read paths
   that just need an entity-seeded H2 + assertion on proto
   shape. Group as: home / browse / detail / search / tags / titles.
3. `WishListGrpcService` — already 15%; cover the remaining mutations
   and the `listWishes` bundling.
4. `ArtistGrpcService`, `PlaylistGrpcService`, `PlaybackGrpcService`,
   `LiveGrpcService`, `BuddyGrpcService`, `DownloadGrpcService`,
   `InfoGrpcService`, `ProfileGrpcService`, `AuthGrpcService`,
   `ImageGrpcService`, `RadioGrpcService`, `RecommendationGrpcService`.

Estimated effort: ~15 test files, ~4,500 LoC.

### Phase 5 — Auth, interceptor, observability (target +1–2%)

- `AuthInterceptor` — cookie / JWT / origin enforcement, CSRF
  branches. Existing test covers the happy paths; extend.
- `LoggingInterceptor` — already ERROR-on-non-OK; add tests for
  every Status code path.
- `ObservabilityGrpcService` — has a test; extend for the metrics
  scrape path.
- `WebAuthnService` — registration + assertion verification with
  fixture credentials.
- `JwtService` — already has a test; cover expiry, kid rotation,
  malformed tokens.

Estimated effort: ~5 test files, ~600 LoC.

### Phase 6 — Armeria HTTP services (target +2–4%)

Use Armeria's `ServerExtension` test harness:

- `ImageHttpServices` — every servlet (poster, headshot, backdrop,
  collection-poster, local-images, ownership-photo, public-album-art,
  TmdbPosterByIdHttpService, ArtistHeadshotHttpService,
  AuthorHeadshotHttpService).
- `ImageProxyHttpService` — every kind/size combination + bad input
  rejection.
- `AuthRestService` — every endpoint (already has partial coverage);
  extend.
- `CspReportHttpService`, `CspDecorator`.
- HTTP services that aren't being migrated and aren't Roku-only.

Skip: Roku-only services, mid-migration HTTP services that the SPA
no longer hits.

Estimated effort: ~8 test files, ~2,000 LoC.

### Phase 7 — Mop-up to 90%

After phases 1–6 the report tells us where the remaining gaps are.
Run `coverageSummary -Plimit=50` after each phase; the lowest-covered
classes change as phases land.

Estimated effort: variable — the long tail.

## Coverage projection

| Phase | Approx LINE coverage |
|---|---:|
| 0 (baseline) | 17.1% |
| 1 — entities | 18.5% |
| 2 — pure logic + ProtoMappers | 24% |
| 3 — services (H2) | 31% |
| 4 — gRPC handlers | 38–40% |
| 5 — auth / interceptors / observability | 41–42% |
| 6 — Armeria HTTP | 44–46% |
| 7 — mop-up | 90%+ |

Hitting 90% requires the long tail — Phase 7 alone is the bulk of the
work because the first six phases handle the *high-value* code, but
the codebase has many small classes that each contribute 0.1–0.5% and
need their own tests.

## Cadence and gating

- Every phase commit ratchets the `coverageGate` floor up by the
  amount that phase delivered, minus 1% slack. Subsequent commits
  that drop coverage fail presubmit.
- Branch coverage is a softer target: aim for 70% by the time line
  hits 90%. Branch coverage requires testing every if/when arm —
  often more invasive.
- Run `./gradlew jacocoTestReport coverageSummary` in CI (the
  presubmit script) so every PR gets the headline number.

## Risks

- **Test execution time.** Running every JUnit class through Flyway
  on H2 adds startup cost. Use `@BeforeAll` schema setup, share H2
  instance per test class, parallelise with Gradle's `maxParallelForks`.
- **Flyway version drift.** New migrations need to apply cleanly to
  the in-memory schema. Existing tests already do this; keep the
  pattern.
- **Mocking discipline.** The codebase memory says "integration tests
  with mocked dependencies and an in-memory database" is preferred
  over `mockk`. Stick with that — fakes / fixtures over mocks.
- **gRPC test setup boilerplate.** Refactor `GrpcTestBase` to take a
  service constructor lambda so each test class is one-liner setup.
- **Test fragility on file-system code.** Use JUnit 5 `@TempDir`
  consistently (it's already used in `WishListServiceBookTest`).

## Out of scope

- The web-app SPA already has its own coverage signal via the
  Playwright harness + monocart V8 coverage; that's reported
  separately and isn't part of this Kotlin plan.
- iOS / Android TV coverage — separate test stacks.
- Performance / load tests — these run in `lifecycle/perf-*.sh` and
  measure throughput, not coverage.

## Testability blockers (interactive backlog)

When a test class would require modifying production code to be
testable (extracting a helper, taking a clock, threading a fake
through a singleton, weakening visibility, etc.), the autonomous
test-coverage loop **must not** make that change. It records the
blocker here instead, and we tackle them interactively later.

Format: one bullet per class, with `Class.kt — what would need to
change — why it blocks the test`. Add new entries to the bottom.

- `AdminGrpcService.reorderTracks` — the loop saves tracks
  sequentially, but the `track` table has a UNIQUE constraint on
  `(title_id, disc_number, track_number)`. A swap of two tracks at
  the same disc collides mid-save (e.g. swap (1,1)↔(1,2): saving
  the first to (1,2) collides with the existing (1,2) row, raising
  `JdbcSQLIntegrityConstraintViolationException` which the gRPC
  layer surfaces as UNKNOWN). Tests have to choose new (disc,
  track) coordinates that don't overlap with existing rows.
  Production fix would do the writes inside a transaction with
  deferred constraint check, or reorder via a two-pass scheme
  (offset all to a non-conflicting range first, then settle).
