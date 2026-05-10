# iOS Screenshot Pipeline — Handoff for a Mac Claude session

Status: **not yet built.** This document collects the context, constraints,
and decisions accumulated on the Windows side so a Mac-based session can
implement the App Store screenshot pipeline without re-deriving them.

The web equivalent is fully shipped — see `docs/SCREENSHOT_PROCEDURES.md`,
`lifecycle/capture-web-screenshots.sh`, and
`web-app/tests/scripts/capture-docs-screenshots.mjs`. Mirror the **shape**
of that pipeline on iOS: a small, repeatable script, manifest-driven, no
manual clicking.

## Goal

Produce **functional** screenshots of the iOS app for App Store Connect's
required device-class matrix. No marketing-overlay frames (`frameit`
output) — the user explicitly said skip those for now.

Apple's current required device classes (verify against App Store Connect
when you start; this changes):

- iPhone 6.9" — iPhone 16 Pro Max
- iPhone 6.7" / 6.5" — older Pro Max generation
- iPad 13" — iPad Pro

5–10 screenshots per device class is the usual target. The same set of
*screens* should be captured on each device.

## Pre-flight checklist

Before writing any code:

1. The demo server is up at `https://appstoredemo.15mcmahon.net:8443` and
   has all the fixtures applied (movies, TV series, books, albums, users,
   wishes). The Windows side already ran `seed-users` and `seed-wishes`
   against it. Verify with a browser load of `/app/login` from the Mac.
2. `app_store_demo_setup/secrets/.env` is **gitignored** — it does not
   exist on a fresh Mac checkout. The user must copy from
   `app_store_demo_setup/secrets/example.env` and fill in the seven
   passwords. The seven `DEMO_*` env vars are sourced the same way as
   on Windows (`set -a; . secrets/.env; set +a`).
3. The iOS app builds and runs on the Mac. See `docs/MAC_SETUP.md` for
   the Xcode + signing + provisioning flow. Multicast networking
   entitlement is documented in `docs/TODO_IOS.md` — needed for SSDP
   discovery but **not** required for the demo-server screenshot pipeline
   (we hard-code the demo URL).
4. The `MediaManagerUITests` target already exists with `ReaderUITests.swift`
   as the existing UI test seed. Add new snapshot tests alongside it.

## Account roster — DO NOT MIX UP

| Account | Role | Used by | Purpose |
|---|---|---|---|
| `<DEMO_ADMIN_USER>` (typically `demo-admin`) | admin | us | Our admin for scripts. Never for screenshots — admin chrome wouldn't be on a customer-facing App Store screenshot anyway. |
| `viewer` | viewer | **us (iOS screenshot pipeline goes here)** | Use this for the iOS shots. |
| `kid` | viewer (rating ceiling = 4 / PG-13) | us | If a screenshot needs to demonstrate the rating gate. |
| `empty` | viewer | us | Fresh-account / empty-state screenshots. |
| `reviewer-admin` | admin | **APPLE — DO NOT LOG IN** | Pasted into App Store Connect's Sign-In Information. |
| `reviewer` | viewer | **APPLE — DO NOT LOG IN** | Same — for the non-admin reviewer flow. |
| `reviewer-kid` | viewer (TV-Y ceiling) | **APPLE — DO NOT LOG IN** | Demonstrates rating gate at max restriction. |

The separation is by design — a credential rotation on either side (us or
Apple) must not break the other. The web pipeline enforces this: it only
ever logs in as `viewer`/`kid`/`empty`/`admin`. The iOS pipeline must do
the same.

If you find yourself wanting to log in as `reviewer` to capture a
screenshot, **stop** — use `viewer` instead.

## Demo-server state — what's predictable

The demo catalog is curated and deterministic (`app_store_demo_setup`
fetches public-domain content from archive.org / Standard Ebooks / Musopen).
Stable title IDs to target in your snapshot tests:

| ID | Title | Type | Notes |
|---|---|---|---|
| 27 | Night of the Living Dead | MOVIE | 1968. Used by the web `player.png` shot. |
| 30 | The General | MOVIE | 1926. Silent film — exercises the optional audio-stream map in the transcode buddy. |
| 33 | Sherlock Holmes | TV | 1954. 39 episodes, single season. Best TV-detail shot. |
| (varies) | Various Standard Ebooks | BOOK | Pride and Prejudice, A Study in Scarlet, etc. |
| (varies) | Various PD albums | ALBUM | Bach Brandenburg Concertos, 1920s 78rpm, etc. |

The viewer's wish list has five entries (seeded by `seed-wishes`):

- The Princess Bride — Ordered
- Star Wars — Wished for
- Casablanca — Won't order
- Star Trek (TV) — Not feasible
- Better Call Saul (TV) — Wished for

All have valid TMDB poster paths (recently fixed via
`TmdbPosterPathResolver` — `b67f949`). If you find a wish tile showing
text instead of a poster, that's a regression; check the resolver test.

## First-login state — expect a ToS dialog

The four screenshot accounts (`viewer`/`kid`/`empty`/`admin`) all have
`terms_of_use_accepted_at = NULL` at the database level. The web side
already accepted ToS for them on first capture run. The iOS session is
per-device, so **iOS will independently show the ToS dialog on the first
login** for each account on the simulator/device you run snapshots on.

Two options:

1. **Have the snapshot test handle the dialog explicitly** — locate the
   two checkboxes ("I agree to the Privacy Policy", "I agree to the
   Terms of Use"), tap each, tap "Accept & Continue". The web equivalent
   is in `web-app/tests/scripts/capture-docs-screenshots.mjs` under the
   `if (page.url().includes('/app/terms'))` block — mirror that flow
   in XCUITest.
2. **Pre-accept once manually** and let the snapshot test skip the
   dialog. Brittle on a fresh simulator — option 1 is better.

The Apple-shared `reviewer*` accounts intentionally keep
`terms_of_use_accepted_at = NULL` so Apple reviewers see the ToS dialog
when they sign in. Don't accidentally accept-then-leak that state.

## Recommended tooling shape

The natural choice on macOS:

- **fastlane snapshot** wrapping XCUITest. Standard for App Store
  screenshots. Output goes to a per-device-class directory.
- `ios/MediaManager/fastlane/Snapfile` — declares the device matrix
  (`devices` list), languages (`["en-US"]` to start), and the workspace
  + scheme.
- `ios/MediaManager/fastlane/Fastfile` — wraps `snapshot` as a lane
  (e.g. `lane :screenshots`).
- New XCUITest file: `MediaManagerUITests/SnapshotTests.swift`. One test
  per screen, each calling `snapshot("01-login")`, `snapshot("02-home")`,
  etc. fastlane gathers these per device.
- `lifecycle/capture-ios-screenshots.sh` — Mac-only wrapper. Sources
  `app_store_demo_setup/secrets/.env`, runs `bundle exec fastlane
  screenshots` from the workspace dir.

## Suggested screenshot manifest

Mirror the web tier-1 plus iOS-specific surfaces. Start small (4–6) and
grow:

| File | Account | Path / target screen |
|---|---|---|
| `01-server-setup.png` | (none) | Server URL entry on first launch (`ServerSetupView.swift`) |
| `02-login.png` | (none) | Sign-in form after entering the demo URL |
| `03-home.png` | viewer | Home with carousels |
| `04-catalog-movies.png` | viewer | Movies tab |
| `05-catalog-tv.png` | viewer | TV Shows tab (Sherlock tile front-and-center) |
| `06-title-detail.png` | viewer | The General or Night of the Living Dead |
| `07-title-detail-tv.png` | viewer | Sherlock Holmes with season + episode list |
| `08-player.png` | viewer | In-app playback (the iOS native player chrome) |
| `09-search.png` | viewer | `sherlock` query — mixed-type results |
| `10-wishlist.png` | viewer | All 5 fixture wishes, mixed statuses |

`empty` and `kid` accounts give you bonus shots (empty-state, rating gate
in action) if the App Store listing benefits from those.

## Reference: how the web pipeline solved similar problems

Worth reading before you start. The same gotchas will hit iOS.

- **Manifest + grouped contexts** —
  `web-app/tests/scripts/capture-docs-screenshots.mjs`. One context per
  account, all screens for that account in sequence, to avoid the 10/min
  login rate limiter.
- **mat-checkbox quirk** — Same idea will hit you if SwiftUI / UIKit
  has any custom-rendered controls: target the *underlying* control
  element, not the host. The web fix was `setChecked(true)` on the
  inner `<input>`. iOS equivalent: tap the cell, not the chrome.
- **Player controls fade-out** — HTML5 video hides controls after a
  delay; the web fix mouse-moves over the video right before capture.
  iOS AVPlayerViewController behaves similarly — you'll likely need a
  tap or hover-equivalent to keep controls visible during snapshot.
- **TMDB poster cross-type collision** — Fixed server-side in
  `TmdbPosterPathResolver` (`b67f949`). iOS doesn't need to do anything;
  posters now resolve correctly.

## Done criteria

- `lifecycle/capture-ios-screenshots.sh` runs to completion on a clean
  Mac with `secrets/.env` populated.
- PNGs land under `ios/MediaManager/fastlane/screenshots/<lang>/<device>/`
  for each required device class.
- The 10 shots above all render their intended content (no ToS dialogs,
  no empty placeholders, video controls visible on the player shot).
- Wrap with a commit and (after end-to-end verification) push.
- Apple-facing artifacts (`fastlane/screenshots/`) go in `.gitignore` —
  they're large binaries we'd rather regenerate than version. (Mirror the
  web's `data/screenshots/` ignore pattern.)

## Things to add to existing docs once iOS is done

- `docs/SCREENSHOT_PROCEDURES.md` — add an "iOS screenshots" section
  pointing at `lifecycle/capture-ios-screenshots.sh` and the device
  matrix.
- `docs/MAC_SETUP.md` — link to this file from the iOS section.
- `claude.log` — append a session entry summarising what was built.

## Conventions to honor (per CLAUDE.md / project memory)

- Use **American spelling** in code, comments, and docs (`artifacts`,
  not `artefacts`).
- Don't push WIP — local commits during iteration are fine; push only
  after the full pipeline runs end-to-end.
- Commit messages: imperative first line, body explains what + why,
  trailer `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- No infrastructure leaks in source: the demo URL
  `appstoredemo.15mcmahon.net:8443` is already committed and public, so
  it can appear in code. Other internal hostnames / IPs / UUIDs
  should not.
- Repeatable tests over ad-hoc probes — the snapshot pipeline IS a
  committed test, not a one-off run.
