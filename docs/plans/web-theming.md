# Web UI theming: plan

Goal: make the Angular SPA's look and feel selectable per user, ship two
themes, and keep the app hermetic (no outside resource loads; the CSP in
`CspDecorator.kt` stays exactly as it is: `font-src 'self'`,
`style-src 'self' 'unsafe-inline' blob:`, `script-src 'self'`).

| Theme id  | User-facing label | What it is |
|-----------|-------------------|------------|
| `classic` | Classic           | Material 3 azure colours and Inter body text on the shared layout. Dark only. |
| `marquee` | Marquee           | The watch.plex.tv-derived skin from the spec: `#1e1e1e` page, amber accent, white pills, Figtree 800 display headings. Dark only. |

Both themes render the same DOM. A theme is colours, surfaces, radii,
shadows and fonts. Nothing else.

## Decisions (2026-11-07)

- **Name.** The second theme is `marquee` / "Marquee". "Plex" never appears in user-facing copy, theme ids, class names, or stored values; it is a third-party trademark. Theme ids are persisted per user and are never renamed without a migration.
- **Default theme.** A user's stored choice is nullable. `NULL` means "unset": the user gets the built-in default, a single server-side constant (`WebTheme.DEFAULT`) that may change in a later release. A user who picks a theme explicitly is pinned to it. Picking "Default" again clears the pin. There is no admin-configurable default.
- **Existing theming is not sacrosanct.** The simplifications below were approved and happen first, before any theming work.
- **Simplifications S1 to S5: approved. S6: deferred.**
  - **S1. One layout, two skins.** The shell and page structure is universal; themes differ only in tokens and fonts. No theme-conditional templates anywhere. "Classic parity" is not a goal; classic is redefined as the shared layout in Material azure colours.
  - **S2. Dark only.** `color-scheme: dark` for the whole app. Light mode is removed, including the light half of the axe matrix and the `!important` contrast overrides in `styles.scss` that existed because of `light-dark()` resolution glitches.
  - **S3. Plain CSS shell.** `mat-sidenav-container`, `mat-toolbar`, `mat-nav-list`, and the `mat-menu` profile menu are replaced by the prototype's `header` / `aside` / `main` / `dialog` structure. Material stays for forms, tables, chips, dialogs, progress indicators, and the remaining admin widgets.
  - **S4. One breakpoint set.** 1023px (nav collapses, bottom tab bar appears) and 599px (phone sizes). The 768px `isMobile` query is deleted.
  - **S5. One body font.** Inter for both themes. Roboto and its preload are removed.
  - **S6. Retire `--mat-sys-*` as the semantic layer.** Deferred. The bridge in decision 2 makes the existing tokens serve both themes; revisit only for a non-Material-shaped third theme.
- **Tint backfill** runs in the background and must tolerate interruption with ACID properties (decision 6).
- **Harness time.** Axe runs once per theme. Accepted; S2 makes the net cost zero.

## Inputs for the implementing session

Read all four before writing code. Paths are on this machine:

| File | What to take from it |
|------|----------------------|
| `C:\Users\Jeff Stewart\Documents\Craft Anything\watch-plex-tv\app\STYLE-SPEC.md` | Tokens (colour, type, shape, spacing per breakpoint), page shell, building blocks 3.1 to 3.10, home composition, API mapping. This is the contract for the shared layout and for the Marquee skin. |
| `C:\Users\Jeff Stewart\Documents\Craft Anything\watch-plex-tv\app\src\styles.css` | Working CSS for every token and block. Port it; don't reinvent it. Breakpoints are 1023px and 599px. |
| `C:\Users\Jeff Stewart\Documents\Craft Anything\watch-plex-tv\app\src\main.ts` | Working DOM structure for shell, rows, cards, hero, sheets, FAQ, tab bar, and the row-arrow / dot-sync / sheet behaviours to port into Angular. Ignore the `SAMPLE_*` data and the hash-link hrefs. |
| `C:\Users\Jeff Stewart\Documents\Craft Anything\watch-plex-tv\app\font-bakeoff\report.md` | Why the display font is Figtree at weight 800 only, and why 700 rules render as 800. Don't "fix" that. |

The prototype is a Vite page that renders static JSON. Nothing from it is
copied verbatim into the SPA except CSS values and DOM shapes.

## Architecture decisions

1. **Layout is universal; a theme is a CSS token layer selected by `data-theme` on `<html>`.**
   The spec's layout tokens (`--pad-x`, `--section-gap`, `--row-gap`, `--poster-w`, `--wide-w`, `--hero-h`, `--nav-w`, `--header-h`, `--tabbar-h`, and their two media-query overrides) live once, unscoped, in `styles/_layout.scss`. They are not theme tokens. Themes own only colour (`--c-*`), type (`--f-*`, `--fs-*`), shape (`--r-*`), and depth (`--sh-*`):
   - `styles/_theme-classic.scss`, scoped to `html[data-theme="classic"]`: `mat.theme()` with the azure palette and `theme-type: dark`, `typography: Inter`, plus `--c-*` / `--r-*` / `--sh-*` values that express the current look (primary as accent, 6px card radius, Material surfaces).
   - `styles/_theme-marquee.scss`, scoped to `html[data-theme="marquee"]`: the colour, type, shape, and shadow blocks from the prototype's `:root`, plus a `mat.theme()` call with a palette generated offline by `ng generate @angular/material:theme-color` from the Marquee seeds (primary `#e5a00d`, neutral `#1e1e1e`), then the handful of spec-exact pins (`--mat-sys-surface: #1e1e1e`, the three text tiers, the font tokens). The generated palette gives the dozens of derived Material tokens for free; the hand-written bridge stays around ten lines.
   - Component SCSS references tokens only. `--mat-sys-*` remains the semantic layer the 33 existing component stylesheets already use (242 uses of `on-surface-variant`, 174 of `on-surface`, 73 of `primary`); new shared components use `--c-*` where the spec names a token and `--mat-sys-*` otherwise. Hex literals that encode brand colour are swept to tokens; semantically fixed badges (red "unmatched", green "wish ready") stay literal.
   - Shape overrides that Material can't take from a variable (pill buttons, 8px cards, the spec's 40px chip) go through `mat.button-overrides(...)`, `mat.chips-overrides(...)` and friends inside each theme file. No `!important`.

2. **Fonts stay first-party.** Vendor Inter (400, 600, 700) and Figtree (800), latin subset, woff2, into `web-app/public/vendor/fonts/` with `inter.LICENSE` and `figtree.LICENSE` (both SIL OFL 1.1), declare them in `fonts.css`, and document both in `THIRD_PARTY_LICENSES.md` under "Web-app vendored fonts" the same way Roboto is today. Remove `roboto-latin.woff2`, `roboto.LICENSE`, its `@font-face`, its preload in `index.html` and its entry in `THIRD_PARTY_LICENSES.md` (S5). Source the files from the `@fontsource/inter` and `@fontsource/figtree` npm packages (version 5.3.0) that already sit in the prototype's `node_modules`: `files/inter-latin-{400,600,700}-normal.woff2` and `files/figtree-latin-800-normal.woff2`, plus each package's `LICENSE`. Copy the files; do not add the packages to the SPA's `package.json`. Preload the Inter 400 face (it is on every page); do not preload Figtree, which only the Marquee skin uses, because `@font-face` is lazy and classic users would otherwise download it for nothing. `font-src 'self'` is untouched. Check `/csp-report` stays quiet after deploy.

   **SIL OFL 1.1 compliance checklist** (both fonts; neither declares a Reserved Font Name in the Fontsource license headers, which removes the renaming clause from consideration):
   - Ship the full license text with the font files: `inter.LICENSE` and `figtree.LICENSE`, verbatim from the packages, copyright line included. This satisfies "each copy contains the copyright notice and this license" for the Docker image, the GitHub repo, and the browser download alike.
   - Add both to `THIRD_PARTY_LICENSES.md` with file names, source (Fontsource 5.3.0, upstream repo), license, license-text path, and scope (latin subset, specific weights).
   - The fonts remain under OFL regardless of the repo's MIT license. OFL explicitly allows bundling with software under any license; MIT covers our code, not the font files. Say so in `THIRD_PARTY_LICENSES.md`.
   - Never distribute the font files by themselves; they ship only inside the app.
   - Don't further subset, re-hint, or rename the files. Redistributing Fontsource's output as-is keeps us out of "Modified Version" territory.
   - Don't use "Inter", "Figtree", or their authors' names to promote the app. Naming them in the license file and the bake-off rationale is acknowledgement, which is permitted.

3. **Theme choice is a per-user server preference with a local cache.**
   - Flyway `V101__user_web_theme.sql`: `ALTER TABLE app_user ADD COLUMN web_theme VARCHAR(32) DEFAULT NULL;` Nullable on purpose: `NULL` is "unset, follow the built-in default".
   - `AppUser.web_theme: String? = null`.
   - Kotlin `enum class WebTheme(val id: String)` with `fromId(): WebTheme?` that rejects unknown ids, and `companion val DEFAULT = CLASSIC`, the one place the built-in default lives. Don't store free text.
   - `ProfileHttpService`: the `GET /api/v2/profile` JSON gains `web_theme` (the pinned id or `null`) and `default_theme` (`WebTheme.DEFAULT.id`), so the client can show "Default (currently Classic)" without hard-coding the default. `POST /api/v2/profile/theme {"theme": "marquee" | null}` pins or clears, mirroring `tv-quality`. The SPA's profile page is REST-based today, so no proto change is needed; the iOS / Android TV apps don't theme the web. Extend `ProfileHttpServiceTest` (pin, clear, unknown id rejected, unauthenticated).
   - SPA `core/theme.service.ts` (`providedIn: 'root'`): `pinned = signal<ThemeId | null>`, `serverDefault = signal<ThemeId>`, `effective = computed(() => pinned() ?? serverDefault())`. An `effect` writes `effective()` to `document.documentElement.dataset['theme']` and mirrors it to `localStorage['mm.theme.effective']`. The profile load (or `AuthService` after login) calls `theme.applyFromServer(profile.web_theme, profile.default_theme)` so the server wins over the cache. Login, setup, terms, and change-password pages render with whatever the cache says, so a returning user never sees the wrong theme flash. If the built-in default changes in a release, an unpinned user sees the old theme for one page load and the new one thereafter; acceptable.
   - First paint: `web-app/src/main.ts` sets `data-theme` from `localStorage` before `bootstrapApplication`, falling back to a compile-time copy of the default. It must be done there and not in an inline `<script>` in `index.html`, because `script-src 'self'` forbids inline scripts. `index.html` carries `<html lang="en" data-theme="classic">` as the no-JS fallback; a test asserts that attribute matches `WebTheme.DEFAULT` so the two can't drift.
   - Profile page: a "Theme" section with a radio group of three: "Default (currently Classic)", "Classic", "Marquee". Labels from a `THEMES` table in `theme.service.ts`. Selecting posts to the server and sets the signal immediately so the page re-skins without reload.

4. **No theme-conditional structure.** `ThemeService` exposes ids and labels; nothing else. Templates never read the theme. If a future theme needs different structure, that is the moment to revisit S6, not to add an `@if` on the theme id.

5. **Shared presentational components replace per-page poster markup.** Home, title grid, actor, author, artist, series, collection, and search views each carry their own `.poster-card` / `.poster-wrapper` CSS today. Introduce under `web-app/src/app/shared/media/`:
   - `section-head` (title, optional tag, optional "see all" link, optional subline; spec 3.1),
   - `media-row` (horizontal scroll-snap row with the bleed-right margin, hover arrows on pointer devices, `aria-label`; ports `wireRows` from `main.ts` as a directive with `ResizeObserver` plus a scroll listener),
   - `poster-card` (2:3 or square, progress pill, corner badge, caption with `.accent`; spec 3.2),
   - `wide-card` (16:9, scrim, overlay title, badge; spec 3.3),
   - `hero-banner` (spec 3.4) and `hero-carousel` (dots plus scroll sync),
   - `person-card` (spec 3.6), `chip-row` (3.5), `category-tile` (3.7), `faq-accordion` (3.9), `placeholder-art` (gradient plus initial for missing artwork, ports `art()`).

   Each is `OnPush`, signal inputs, no `any`, token variables only. Sizes come from the unscoped layout tokens, so both themes render identical geometry.

6. **Server additions for the home composition.** The spec's section 5 maps rows to the REST v2 feed the prototype scraped; the SPA actually loads home over gRPC (`CatalogService/HomeFeed`), so additions go on `HomeFeedResponse` and are mirrored in `HomeFeedHttpService` for Roku.
   - `repeated Title featured = 11;` recently added titles that have a cached backdrop, newest first, max 6. `Title` already carries `description`, `backdrop_url`, `content_rating`, `year`. Add `optional string backdrop_tint` to `Title` as `#rrggbb`: the saturated average colour of the backdrop at about 20% lightness (spec 3.4). Pure function `BackdropTint.compute(BufferedImage): String` with a unit test on synthetic images.
   - `optional Title spotlight = 12;` one show from `missing_seasons` with a backdrop, for the single hero.
   - `repeated ArtistCard recent_artists = 13;` distinct artists across `recently_added_albums` with album and track counts and headshot URL; reuse `ArtistService` list shapes.
   - Live TV row reuses `LiveService/ListTvChannels` from the SPA; no feed change.
   - Cast names on the hero meta line are optional; omit rather than add a cast fetch per hero.
   - Update `adaptProtoHomeFeed` in `catalog.service.ts`, the typed fixtures in `web-app/tests/fixtures-typed/home-feed.fixture.ts`, and the gRPC server tests.

   **Tint storage and backfill (must tolerate interruption).** Store the tint in two places: a new nullable `title.backdrop_tint VARCHAR(7)` column (Flyway `V102`) that the feed reads, and the backdrop's `ImageMetadata` sidecar (new nullable `tint` field) for disaster recovery. New backdrops get both written by `InternetImageStore` at cache time. Existing backdrops are backfilled by a background job started from `Bootstrap` after migrations, on one thread (H2 rule: sequential queries only), designed so that killing the process at any instant leaves a consistent state and the next start resumes where it stopped:
   - The work set is a query, not a cursor file: `SELECT id FROM title WHERE backdrop_path IS NOT NULL AND backdrop_tint IS NULL`. The column is the checkpoint, so there is no separate progress table to get out of sync.
   - Per title, one unit of work: read the cached backdrop file, compute the tint, write the sidecar via temp file + `ATOMIC_MOVE` (the pattern `ImageMetadata` already uses), then `UPDATE title SET backdrop_tint = ? WHERE id = ? AND backdrop_tint IS NULL` in its own transaction. Sidecar first, DB last, so a crash between the two leaves the row unclaimed and the sidecar write is simply repeated (idempotent).
   - A backdrop that is missing on disk or fails to decode gets the neutral fallback `#2a2a2a` written to the column so the job converges instead of retrying forever; log at WARN with the title id. Re-caching the backdrop later recomputes it.
   - Small batches (50 ids per query), `clock.sleep` between batches so the request path isn't starved, progress logged every batch, and a `--skip_tint_backfill` CLI flag for debugging. Not a `SchemaUpdater`: the updater framework records "applied" once and is not restartable mid-way, which is exactly the property we need to avoid.
   - Test: an integration test that seeds titles, runs the job, cancels it cooperatively after N rows, asserts rows are either fully done or untouched, restarts it, and asserts completion with no double work.

7. **Icons.** Keep `mat-icon` with the vendored Material Icons font; the spec only asks for 20px / 24px sizes. Do not add the prototype's inline SVG set.

8. **Budgets.** `angular.json` caps any component stylesheet at 32kB (warn at 16kB). The shared components keep each stylesheet small; `home.scss` shrinks because card CSS moves out. Global token and layout files live under `styles.scss`, which is not under the per-component cap.

## Phases

Each phase ends green on `./gradlew test`, `cd web-app && node tests/harness.mjs`, and `ng build`. Commit per phase; no pushes until end-to-end verification.

### Phase 0: simplify (S1 to S5), no theming yet
The app comes out of this phase looking like the spec's layout in today's Material azure colours, dark only, in Inter, with no second theme. Everything after this phase is smaller because of it. Order inside the phase matters; each step is its own commit.

1. **Fonts (S5 prerequisite).** Vendor Inter 400/600/700 and Figtree 800 with licenses; remove Roboto; `fonts.css`, `index.html` preload, `THIRD_PARTY_LICENSES.md`, `docs/THIRD_PARTY_AUDIT.md`. Point `mat.theme()`'s `typography` at Inter. Verify: `ng build`; no request for Figtree on any page yet; no CSP reports.
2. **Dark only (S2).** `theme-type: dark` in `mat.theme()`, `color-scheme: dark` on `html`, delete the three `!important` overrides and the `mat.form-field-overrides` label block in `styles.scss` (re-add any that axe still demands, as token overrides). `run-axe.ts`: `SCHEMES = ['dark']`; remove the per-spec `emulateMedia` calls in axe specs 08, 10, 14, 16, 17. Expect the axe suite to run about twice as fast.
3. **Breakpoints (S4).** Replace the 768px query in `ShellComponent` with 1023px; introduce `styles/_layout.scss` with the spec's layout tokens and their 1023px / 599px overrides; migrate any component media queries to the same two values.
4. **Shell (S3), in eleven small commits.** The Material shell is replaced piece by piece. Steps 4a to 4c are pure refactors with no visual change; 4d to 4j each swap one Material wrapper for the spec's structure; 4k is cleanup. Each step is its own commit, green on all suites, and the app is fully usable after every one. Colours come from `--mat-sys-*` throughout; the Marquee palette arrives in Phase 1.

   | Step | Change | What stays | Tests touched |
   |------|--------|------------|---------------|
   | 4a | Extract the nav list (everything inside `mat-nav-list`, including feature-flag gating, admin group, badges, the Purchases and Transcodes collapsibles) into `core/shell/nav-list.ts` as `app-nav-list`. Template moves verbatim. | `mat-sidenav`, `mat-nav-list` | none |
   | 4b | Extract the search input, clear button, advanced button, debounce, and suggestions dropdown into `core/shell/search-box.ts` as `app-search-box`, with its own signals. `ShellComponent` loses the search code. | `mat-toolbar` around it | none |
   | 4c | Extract the profile `mat-menu` and its items into `core/shell/account-menu.ts` as `app-account-menu`, emitting `reportProblem` and `signOut`. | `mat-menu` | none |
   | 4d | Replace `mat-toolbar` with `header.app-header > .header-inner` (64px, sticky, max-width 1536px, brand word). Add the `is-scrolled` frosted class driven by the scroll container (see note). Same children: toggle button, brand, search box, spacer, account menu. | `mat-sidenav-container` | 09 selectors |
   | 4e | Restyle `app-search-box` as the spec's 40px pill (`--c-surface`, icon, 14/600); below 599px it collapses to an icon that reveals the input. CSS and a `collapsed` signal only. | everything else | 09 |
   | 4f | Replace `app-account-menu`'s `mat-menu` with the user sheet: `<dialog class="sheet">` opened by `showModal()`, white account pill with avatar initial and username in the header, links for Profile, Help, Report a Problem, Sign Out, close on link click, backdrop click, and Escape. Delete `MatMenuModule` from the shell. | nav, container | 09 (menu interactions), axe 05 |
   | 4g | Convert `app-nav-list` from `mat-nav-list` to plain `nav > a.nav-item` pills with `routerLinkActive` plus `ariaCurrentWhenActive="page"`; active state is the outline plus accent icon; group header styled per spec `.side-nav h3`; collapsible groups keep their `button` plus `aria-expanded`. Delete the `color-mix` active-link rule and the `!important` nested padding. Still rendered inside `mat-sidenav`. | `mat-sidenav-container` | 10 selectors |
   | 4h | Replace `mat-sidenav-container` with `aside.side-nav` (fixed, 240px, visible at 1024px and up) plus `main.app-main`. Below 1024px the same `app-nav-list` renders inside `<dialog class="sheet left">` opened by the header toggle and closed on `NavigationEnd`. `drawerOpen` and the `matchMedia` listener are replaced by the dialog plus a CSS media query. The audio player moves back inside normal flow; delete the `with-player` padding compensation and the `player-spacer`. Delete `MatSidenavModule`, `MatListModule`, `MatToolbarModule` imports. | | 10 (drawer open/close), 12 (audio bar placement), axe 05 at 390px |
   | 4i | Add `nav.tabbar` (fixed bottom, 64px, below 1024px): Home, Live TV when enabled, Movies & TV, Discover when enabled; active tab in accent. `main` and the audio player get bottom offsets equal to `--tabbar-h`. | | new functional spec for the tab bar at 390px; axe 05 |
   | 4j | Add `.page-glow` behind the header and set a `data-page` attribute on `body` from the activated route (`home`, `discover`, otherwise unset) so the gradient varies per section as in the prototype. Purely decorative, `aria-hidden`. | | axe (contrast over the glow) |
   | 4k | Cleanup: remove dead rules from `shell.scss`, confirm the bundle no longer contains sidenav, toolbar, list or menu Material code (`ng build --stats-json` and compare the initial bundle size before and after step 4a), and update `CLAUDE.md`'s shell description. | | none |

   **Scroll container note (decide at 4d, implement at 4h).** Today `main.page-content` is the scroller (`overflow: auto`, `tabindex="0"` for axe's scrollable-region rule). The prototype scrolls the document, with a sticky header and a fixed nav. Recommended: switch to document scrolling at 4h, because it removes the `tabindex` workaround, makes `position: fixed` for the nav, tab bar and audio player trivial, and lets `is-scrolled` listen on `window`. At 4d, implement `is-scrolled` against whichever element scrolls and keep it behind one small `ScrollSource` helper so 4h changes a single line. The router has no scroll-restoration option configured (`provideRouter(routes)` only), so nothing depends on the current scroller.

   **Why this order.** 4a to 4c make the later swaps local to one small component each. 4f before 4g and 4h because the sheet pattern (native `<dialog>`, `showModal()`, close rules) is proven on the smallest surface first and then reused for the phone nav. 4h is last among the structural swaps so that when the container goes, the only thing moving is the container.
5. **Shared components (S1).** Build the components in decision 5. Migrate home, `title-grid`, actor, author, artist, series, collection, search and wishlist card markup onto them and delete the private `.poster-card` CSS in each. Posters take the spec's sizes from the layout tokens. Functional spec 05 keeps passing (preserve `.carousel-section`, `.poster-card`, `.poster-wrapper`, `a.poster-title` as classes on the new components, or update selectors in the same commit).

   Exit criterion: one layout, Material colours, dark, Inter, all suites green, screenshots at 1440 and 390 in `data/screenshots/` for the record.

### Phase 1: theme plumbing
- Flyway V101, `AppUser.web_theme`, `WebTheme` enum, `ProfileHttpService` GET fields plus POST endpoint, `ProfileHttpServiceTest` cases.
- `styles/` split: `_layout.scss` (from Phase 0), `_theme-classic.scss`, `_theme-marquee.scss` (generated palette plus pins), `data-theme` default in `index.html`, `main.ts` pre-bootstrap read.
- `ThemeService` plus vitest spec (initial from storage, server wins, effect writes the attribute).
- Profile page Theme section; `web-app/tests/fixtures/profile/profile.json` gets `web_theme` and `default_theme`; functional test 07 covers pin, clear, and the POST body; axe test 07 covers the new controls.
- Sweep brand-colour hex literals in component SCSS to tokens.
- Harness: `MM_THEME` env var honoured by `tests/helpers/test-fixture.ts` via `page.addInitScript` seeding `localStorage['mm.theme.effective']`; `tests/harness.mjs` runs the `axe` directory once per theme and labels the runs in `summary.txt`. The functional suite runs once; layout is identical across themes, so there is nothing theme-specific to exercise beyond spec 07.
- Exit criterion: Marquee is the spec's colours, pills, radii, shadows, and Figtree headings on the shared layout; classic is unchanged from the end of Phase 0; both pass axe.

### Phase 2: home composition (universal)
- Server: decision 6 (featured, tint plus backfill job, spotlight, recent_artists), tests, fixtures.
- Home per spec section 4, in order: welcome (h1 "Welcome back, {username}", poster mosaic from `recently_added` plus `recently_watched`), featured hero carousel, Browse Your Library chips (one per nav feature flag), Continue Watching wide cards (plus All Channels tile when live TV is on), Recently Added, Spotlight hero, Resume Reading, Recently Added Books, Continue Listening, Artists, Recently Added Albums, New Seasons Available (accent `NEW S3` badge), On Live TV, FAQ. Rows with no data are omitted. Keep both dismiss buttons.
- FAQ copy lives in a TS constant; wording follows the user-facing copy rule (plain, no reassurance, no implementation detail).
- Tests: functional 05 extended for hero Play / Wish List, chips, artist card, live TV tile; axe 05 in both themes.

### Phase 3: other views
- Movies / TV / Books / Music grids: `.grid` from the spec (auto-fill, 192px min at desktop, 160 / 104 at the breakpoints) via `title-grid`; filter chips become the spec's 40px pills.
- Search page: centred search box (spec `.search-box`) and results grid.
- Live TV: `live-top` player plus info layout and guide rows (prototype `liveTvPage`); layout only, the HLS player component is unchanged.
- Discover: the prototype's `trendingPage` (hero plus promo plus rows) using existing recommendation RPCs.
- Title detail: tinted hero header using `backdrop_tint`.
- Each view: axe in both themes; functional specs updated where selectors moved.

### Phase 4: docs and polish
- `docs/USER_GUIDE.md` "Personalizing Your Library" gains a "Theme" subsection; `CLAUDE.md` Architecture gets a "Theming" paragraph (token layers, `data-theme`, universal layout rule, where fonts live, hermetic rule, dark only); `docs/ADMIN_GUIDE.md` notes the tint backfill and `--skip_tint_backfill`; `docs/FEATURES.md` completed table row; `README.md` screenshot refresh. Done 2026-11-10 except the README screenshots, which wait on a decision about artwork (TMDB posters and backdrops are not ours to redistribute; the likely path is screenshots of the mocked app with generated placeholder art).
- Memory note for future sessions: theme ids are persisted; never rename without a migration; layout is universal, themes are tokens only.

## Cloud authoring (approved 2026-11-07)

Phase 0 and Phase 1 are built unattended by a two-hourly cloud routine running as
`jbstewart-agent`, as a chain of stacked PRs. The self-contained brief the
routine reads is `docs/plans/marquee-agent-brief.md`; this section records
the decisions behind it.

- **Identity and rules.** Cloud work runs as `jbstewart-agent` under its existing restrictions: branches under `agent/`, PRs only, `verify` must pass, the operator merges. The standing "no pushes until verified end to end" rule is replaced for this chain by: every pushed PR is green on Gradle tests, both Playwright suites, and CI before the next step starts.
- **Local prerequisites, done before the first routine.** (1) Commit the four reference files under `docs/plans/marquee-reference/` after a leak scrub, without the prototype's `library.json` / `home-feed.json` snapshots. (2) Vendor the fonts locally (Phase 0 step 1), because the woff2 files live in the prototype's `node_modules` on this machine. (3) Add `web-app/tests/ci-run.sh`: start `ng serve`, wait for port 4200, run the harness with the given args, tear down. The harness does not start the dev server itself. (4) Add `web-app/tests/visual/capture.mjs` (below). (5) Write the brief.
- **Dry run first.** A one-shot routine that clones, builds, installs Chromium, runs Gradle tests and both suites through `ci-run.sh`, pushes a throwaway `agent/dry-run` branch, opens and closes a draft PR, and reports. No code changes. It proves the sandbox can install the browser and that the identity satisfies the rulesets. The chain routine is created only after the dry run is clean.
- **Dry-run findings (2026-11-08) folded into the repo:** `tests/ci-run.sh` uses the sandbox's own Chromium instead of downloading one; `gradle.properties` carries UTF-8 JVM settings (POSIX locale broke test reports on em-dash test names) and 8 retries with doubling backoff for a rate-limited Maven Central; the harness sizes concurrency to the CPU count (6 processes on 4 cores produced 56 timeout failures). The sandbox blocks GraphQL `gh` commands, so the brief uses REST. A full verification cycle is about 45 minutes, so the routine cadence is every two hours, not hourly.
- **Second dry run (2026-11-08) findings:** the JVM file-name encoding follows the locale and ignores `-Dsun.jnu.encoding`, so the nine test method names containing an em dash were renamed (Gradle writes one report page per test method); `tests/ci-run.sh` sets a 90 s per-test timeout and the harness runs two specs at a time below 8 cores, because 3 specs on 4 cores still produced 42 timeouts; the sandbox denies `git push --force`, so every branch name is unique and fix runs add commits; the agent account is from 2026 and only the ID-based noreply address links commits to it; a stop hook asks for commits to be re-authored as Claude, which the brief tells the run to ignore.
- **Resumable routine, every two hours.** Each run starts with no context. It reconstructs state from the open `agent/marquee-*` PRs and `docs/plans/marquee-progress.md` on the chain tip, performs exactly one step, verifies, pushes, opens the stacked PR, updates the progress file, and stops. A run that cannot get green stops and records why instead of building on a broken base. Model for the chain: Opus 5.5 or Fable, not the Sonnet default; the shell rewrite deserves it.
- **PR chain for Phase 0 (one PR per row).** `agent/marquee-01-dark` (S2), `-02-breakpoints` (S4 plus `_layout.scss`), `-03-shell-extract` (4a to 4c), `-04-header` (4d, 4e), `-05-user-sheet` (4f), `-06-nav-pills` (4g), `-07-container` (4h), `-08-tabbar-glow` (4i to 4k), `-09-shared-components` (step 5). Phase 1 follows as `-10-theme-server` and `-11-theme-client`. Each PR's base is the previous branch; GitHub retargets to `main` when a base branch is deleted after merge, so review is bottom-up with no manual retargeting.
- **Evaluation content.** No demo content is committed or seeded. The suites are fully request-mocked and need nothing. For visual review, `tests/visual/capture.mjs` logs in against the existing fixtures and screenshots home, a title grid, title detail, profile, and live TV at 1440 and 390 in each available theme. Two upgrades to the mock layer make the captures meaningful: the image stub gains a `generatedArt` mode that answers each poster, backdrop, and headshot URL with a deterministic SVG (hue from the id, correct aspect ratio, large label, hairline border) instead of the 1x1 grey PNG, and, once Phase 2 adds the field, the home-feed fixture carries `backdrop_tint` values so heroes render tinted without server computation. Real-artwork judgement is deferred to the mop-up: deploy the chain tip to the NAS and look.
- **Screenshots in PRs (superseded 2026-11-09 by Argos, below).** GitHub's API cannot upload image attachments, so screenshots are committed to a dedicated orphan branch `agent/marquee-screenshots` (never merged, no PR, inside the bot's namespace), one folder per row named by row number (`agent/marquee-10`), and referenced in the PR body by commit SHA via `raw.githubusercontent.com` so later pushes cannot move them. The body uses HTML `<img>` tags with short URLs, because the cloud sandbox's HTTPS proxy rewrites image references on the way to GitHub, on every send path and on resend, once the URL passes a length limit: about 150 characters for a markdown image and about 157 for an `<img>` tag, measured with the 40-character SHA (probe on the closed dry-run PR and the row 09 run, 2026-11-08). Row-number folders keep every URL at 142 characters or fewer. The mop-up deletes the branch; links 404 and `main`'s history never carries the PNGs.
- **Mop-up after the chain** (expected, not a failure): delete `agent/marquee-screenshots` and `docs/plans/marquee-progress.md`; deploy and review with real artwork; fix what the generated art could not show; refresh `docs/images/screenshots/`.
- **Phase 3 in two parallel chains (2026-11-09).** The brief was generalized into `docs/plans/chain-agent-brief.md`, which reads a chain file for the name, branch prefix, progress file, screenshots branch, and rows. Two routines run it two-hourly, an hour apart: `docs/plans/chain-grids.md` (`agent/grids-*`: grids, filter pills, people grids, search, wish list, collections and tags) and `docs/plans/chain-live.md` (`agent/live-*`: Live TV layout and guide, Discover frame and rows). They touch disjoint views; both may append adapters to `catalog.service.ts`, each in its own block at the end of the file. The rest of Phase 2 (home composition) is built locally alongside them.
- **Visual review moved to Argos (2026-11-09).** Committed screenshots showed every view on every PR and never the difference. The `visual` job in `.github/workflows/ci.yml` now runs `tests/visual/capture.mjs` (which captures through `argosScreenshot`, with a Discover view added) and uploads to Argos over GitHub OIDC, no secret. Argos diffs against the merge-base build, so a stacked PR shows only its own row, and posts the `argos` status plus a comment on the PR. The chain brief, both chain files, and the routine prompts dropped the screenshot steps once the last Grids row (06) had started; the mop-up deletes the three screenshots branches and the progress files.
- **Chains complete (2026-11-10).** Grids rows 01 to 06 (retired-repo PRs #157, #160, #162, #164, #168, #170) and Live rows 01 to 04 (retired-repo PRs #156, #158, #161, #163) merged; both routines disabled; the three screenshots branches and both progress files deleted. The progress files' "Notes for later" are carried below.
- **Classic retired (2026-11-10).** One theme ships: Marquee is the only `WebTheme` and the default; migration V103 clears `classic` from `app_user.web_theme` so those users follow the default; the profile page no longer offers a theme choice; the Argos capture runs in Marquee only, halving the screenshot count. Everything else from decision 1 and Phase 1 stays (per-user pin, `data-theme`, `ThemeService`, per-theme axe passes) so a second theme is an additive change.

## Left over from the Phase 3 chains (2026-11-10)

Items the chain runs noticed and left for a design call or a later PR, merged from the two progress files.

- **Family (`personal-videos`) is not on `app-title-grid`.** It keeps its own wide card (description, people, tags). Moving it onto `poster-card` in the `.grid` would drop that detail; needs a design call.
- **Series, actor, author, and artist pages still carry private `.poster-grid` CSS.** They can switch to the global `.grid` the way collections and tags did in Grids row 06; author and artist detail were in no row.
- **Wish list lifecycle colors are gone.** The lifecycle meta is accent or muted only; the old per-stage colors (green ready, red not feasible or won't order, orange needs assistance) could return as a status chip in the caption. The page's own TMDB search bar is still the old input and button, not the spec `.search-box`.
- **Collection detail at 390px.** On an unowned part without a poster, the "Not Owned" label runs under the wish heart (104px columns; it did with the old 140px grid too). Move the label to the caption or shorten it; design call.
- **Capture set gaps.** `tests/visual/capture.spec.ts` has no search, wish list, collections, or tags view, so Argos never saw Grids rows 04 to 06. Discover was added with the Argos job.
- **Live TV omissions, by necessity.** No Favorite pill (no channel-favorite RPC); no guide-times header or elapsed fill (no programme data); the Discover hero is the feed spotlight or the first missing-seasons show on the neutral tint (`MissingSeason` has no backdrop field), and there is one spotlight hero, not the prototype's two.
- **Build-badge axe flake.** The corner badge's fade let axe sample half-faded text on slow pages; it hit every chain on random specs. Fixed by moving the label to the Help page with no animation.


## Accessibility checkpoints (axe decides, but watch these)
- `--c-text-3` (`#ffffff99`) on `--c-surface` over the page: about 4.9:1, passes AA at 12px/600 but has no margin. Do not lighten the surface.
- Amber `--c-accent` text on `#1e1e1e` (the `.m.accent` meta line, tag chevron): about 7:1, fine. Black text on amber badges: fine. Never put white text on amber.
- Overlay titles on wide cards rely on the scrim; keep the `text-shadow` and the 50% scrim height.
- Hero meta over `--tint`: tint is capped at about 20% lightness, so white text passes. Clamp lightness in `BackdropTint.compute` and add a test asserting the result's relative luminance stays under a fixed bound.
- Row arrows are hidden on `(hover: none)`; rows remain keyboard scrollable because cards are focusable links.
- `prefers-reduced-motion`: disable the hover scale, sheet slide, and smooth scrolling.
- Classic's accent is Material azure on dark surfaces; check the progress pill and active-nav icon contrast once in Phase 0 step 5.

## Risks
- **Phase 0 step 4 (shell rewrite)** touches search, nav gating, admin badges, the audio player placement, and the mobile drawer. It is split into eleven commits (4a to 4k) so that no single commit changes more than one wrapper; the container swap (4h) happens only after everything inside it has already been converted. If a step misbehaves, revert that one commit.
- **Material system-variable bridge.** Overriding `--mat-sys-*` under a scope is supported, but a few Material internals read SCSS-time values. The generated-palette approach in decision 1 avoids most of this; if a component still ignores the bridge, use its `mat.<component>-overrides()` mixin rather than `!important`.
- **Tint backfill.** Interruption-safe by construction (decision 6). Cost is one JPEG decode per cached backdrop, once.
- **Harness time.** Axe per theme, dark only: roughly today's duration.
