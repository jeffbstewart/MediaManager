# Changelog

History of user-visible changes per platform. Server changes are released continuously via Docker / Watchtower and aren't versioned individually — only the client apps are entered here.

For developer-facing change history, use `git log`.

PR and issue numbers below refer to the retired repository's history and do not resolve here; they are kept so the owner can look them up in the private retired copy.

## Release-cut convention

Each iOS TestFlight / App Store build is git-tagged immediately after submission so the next build's diff can be derived without manual hash bookkeeping.

- **Tag format:** `ios/<marketing>-<build>` — e.g. `ios/1.2-10` for `MARKETING_VERSION=1.2`, `CURRENT_PROJECT_VERSION=10`.
- **Tag prefix:** `ios/` so these don't fire the server release workflow (which triggers on `v*` tags).
- **How to tag:** run `lifecycle/tag-ios-build.sh` right after submitting to TestFlight. It reads the marketing + build versions from the pbxproj and pushes the tag.
- **Deriving the next changelog:** `git log ios/<previous-tag>..HEAD -- ios/MediaManager/`.

Server / Android TV / Roku get their own tag schemes (or none — the server is rolling).

---

## iOS 1.3 (build 15) — 2026-11-13

**TestFlight build, intended for the App Store.** Follows build 14 by a few hours. The app gets its own identity: a new icon, a shorter name on the home screen, and a teal accent in place of the gold that build 14 carried over from the web app. The first-launch agreement screen is reworked so the button is always next to the documents it refers to.

### Icon and name

- New app icon: a cream house with a disc in front, on a deep teal tile. The artwork is tuned per size, with heavier outlines on the disc at home-screen sizes so it stays legible when small
- The name under the icon is now "Disc Keeper", which fits without truncating; build 14 showed "HouseholdDisc..."
- The App Store name and the in-app wordmark remain "Household Disc Keeper"

### Accent colour

- Bright teal replaces gold everywhere the accent appears: progress bars, "% read" labels, the wordmark's slashes, the sort control, active sidebar icons, links, toggles and other system controls
- Contrast against the page is higher than before (9.0:1, up from 7.4:1)

### First-launch agreement screen

- "I Agree" and its acknowledgement now sit directly under the Privacy Policy and Terms of Service links; the note about server terms and the source link follow
- The whole page scrolls when it does not fit. Previously the acknowledgement and the source link could sit below the fold with nothing to show they were there
- At the accessibility text sizes the introduction and the button grow with the text, and the two document links stack so "Terms of Service" no longer breaks mid-word

### Wordmark

- The wordmark no longer truncates at large text sizes, and when it has to shrink it scales as one piece (previously only the longest word shrank). This applies everywhere it appears: sidebar, sign-in, server setup and the terms screens

---

## iOS 1.3 (build 14) — 2026-11-13

**TestFlight build, intended for the App Store.** The app takes on the Marquee look the web app already has: a dark charcoal page, an amber accent, pill buttons, heavy display headings, and poster-first layouts. Every screen was restyled; navigation and features are unchanged, so everything is where it was.

### Look and feel

- Dark only: charcoal page, amber accent on every control that takes the tint, and a soft glow behind the top of each page
- Headings, page titles, and the wordmark are set in Figtree ExtraBold; body text stays San Francisco, so Dynamic Type works as before
- The decisive action on each screen (Play, Sign In, Connect, Continue, I Agree) is a white pill; secondary actions are quieter surface pills
- Poster and album art share one card style (rounded corners, hairline edge, shadow), with progress and badges drawn on the art
- Navigation bar is transparent at the top of a page and frosts over once content scrolls under it

### Home

- Featured carousel at the top, each slide tinted from its own backdrop
- Welcome block and browse chips for jumping straight into a section
- Continue Watching as wide backdrop cards with progress; poster rows, Continue Reading, and New Seasons below

### Sidebar

- Wordmark at the top, taken from the app's display name
- Rows are pills: the current section gets a white outline and an amber icon
- Small-caps section headers, red counters, and the connection status as a footer row

### Title pages

- Backdrop runs under the navigation bar and fades into the page, with the title sitting on the fade
- Play, favorite, and download actions are pills that wrap on narrow screens
- Tags are chips; cast photos are round

### Everything else

- Movies, TV, Books, and Music grids fill the page width on both iPhone and iPad, with square tiles for music
- Live TV and Cameras are wide-card grids with a red Live badge
- Music, Books, Seasons and Episodes, Actor, Collections, Genres, Tags, Search, Wish List, Downloads, Sessions, Family, Profile, About, the sign-in flow, the terms and password gates, and the admin screens all use the same colours, type, and controls

### Not changed

- The video player and the book reader keep their own chrome (the reader still has its light, sepia, and dark themes)
- iPhone navigation is still the sidebar list, not a bottom tab bar
- Episode rows remain text rows

Build 13 (1.2) was a TestFlight expiry refresh with no changes and has no entry.

---

## iOS 1.2 (build 12) — 2026-08-15

**TestFlight release.** Recovery from the 2026-08 server endpoint migration (fixes retired-repo issue #99), mirroring what a PR in the retired repository (#98 there) did for Roku and Android TV. Installs carrying the old address come up at server setup instead of wedged, and every escape hatch works with the server unreachable.

### Server endpoint

- Server config version (now 2) stamped into the Keychain with the endpoint; anything older is discarded on launch and the app starts at server setup
- Uninstall + reinstall now clears the Keychain, so a reinstall genuinely forgets the server and account (previously both survived app deletion)
- One stored endpoint instead of two — Armeria serves gRPC, HTTP, SPA, and streaming on a single port, so images and video no longer track a second address that could rot independently
- The stored address is Discover's canonical `secure_url`, re-read every launch, so a server that moves is followed automatically while the old address still answers
- Plaintext default port 9090 (was 8080); TLS default stays 443

### Recovery UX

- Server Unreachable prompt offers Retry / Go Offline / Change Server / Sign Out, all functional with no network
- Change Server drops only the address and keeps the session — tokens are server-issued, not address-bound, so re-pointing resumes without re-entering credentials
- Profile renders Change Server + Sign Out even when the profile fetch fails; previously the whole screen was blank, hiding Sign Out exactly when it was needed
- Sign-out no longer waits on the revoke round-trip, so it's instant with the server dark
- Terms gate gains "Use a different server"
- Connection failures surface the gRPC status as readable text instead of "GRPCCore.RPCError error 1"

### Admin + misc

- Settings field follows the server's `public_base_url` rename, labelled "Public Server URL"
- Removed a leftover `INSearchForMediaIntent` declaration that wasn't wired to anything (build 12)
- Siri video playback (movies + TV resume) and car detection (build 11, never tagged)

---

## iOS 1.2 (build 10) — 2026-06-07

**TestFlight release.** First build that's fully usable offline for movies, TV episodes, and books on the same device; close-button responsiveness on Live TV / Camera streams is fixed; mini-player no longer flexes mid-navigation. CarPlay can cold-launch without unlocking the phone.

### Offline mode

- Offline Mode toggle moved from Profile to the top of the sidebar — reachable without any RPC (fixes retired-repo issue #73)
- Every gRPC is gated at the client layer when offline, not just calls routed through the offline data model
- Tab views (Movies, TV, Books, Music) hold their spinner stably during loads instead of flashing an error/empty state
- Auto-clear of the offline flag on logout so a stale session preference can't strand a fresh login
- Hidden offline: Search, Collections, Tags, Family, Cameras, Live TV, Wish List, all admin tabs

### Offline resume (fixes retired-repo issue #72)

- Per-device shadow store for playback + reading positions; survives an online→offline transition on the same device
- Movies / TV episodes resume at the right offset offline
- Books resume at the right locator offline
- Server stays canonical for cross-device sync; local shadow fills the gap when the server isn't reachable

### Mini-player

- Bar extends through the bottom safe area — no system-surface gap under it on devices with a home indicator (fixes retired-repo issue #75)
- Stops flexing to ~half the screen during navigation transitions / page loads
- Browse Artists in Music + the search bar in Authors are now reachable above the mini-player instead of hidden behind it (fixes retired-repo issue #78)
- Custom press-feedback button style replaces `.plain` so the close button on Live TV / Camera streams shows a dim + scale on tap
- Camera view shows the mini-player overlay so audio is controllable without leaving the camera feed
- Tags / collections that contain music CDs no longer show "Not Playable" on the album posters (fixes retired-repo issue #76)

### Live TV / Cameras

- Close button on Live TV / Camera streams responds instantly (fixes retired-repo issue #77). Was 2–7 seconds because the AVPlayer status observer iterated the entire HLS access log on every status change on the main thread; that loop is gone and the close-handler uses an explicit `onClose` closure rather than `@Environment(\.dismiss)` (which was flaky on item-based `fullScreenCover`).
- Press animation + medium haptic on the close button
- Live TV pauses the music queue when you tune a channel (same hand-off pattern movies already had)
- AVPlayer teardown deferred so the dismiss animation isn't blocked
- LiveStreamView body pinned to fill the available area — kills the mini-player half-screen flex that used to fire during the Connecting → playing branch transition

### Ebook reader

- Font size persists across reader sessions (fixes retired-repo issue #67)
- Scroll position preserved when changing font size (fixes retired-repo issue #69)
- Internal links + TOC entries actually navigate now (fixes retired-repo issue #70)
- TOC dropdown shows entries on first open (was empty until you re-opened)
- TOC sheet + mini-player tint to match the reader's light / sepia / dark theme
- WebView respects the bottom safe area (fixes retired-repo issue #68)
- Reader repaginates on resize + accounts for the mini-player when audio is playing

### CarPlay (retired-repo issue #74 — verification pending)

- AppServices populated at process boot so the CarPlay scene's `whenReady` resolves immediately on first connect — no more "open the iOS app on the phone first"
- Auto-recovers from stale HTTP/2 connections (AirPods / lock-screen mini-player benefits from the same fix)
- Shuffle Library row added
- Dropped the dead-end Search delegate

### About page

- New About entry in the sidebar (next to Profile)
- Houses build info + App Legal + Server Legal — moved out of Profile and out of the sidebar's bottom toolbar
- Profile is now cleaner; the build-number chip is no longer the most prominent thing at the bottom of the sidebar

### Other

- gRPC client detects dead HTTP/2 streams + reconnects transparently — fewer "stuck app, need to log out and back in" reports
- Forced-password-change flow asks for the current password (was unsafe — a borrowed phone could rotate the password)
- Downloads view split into per-category sub-pages with a "Delete All" typed-confirmation
- Sign Out consolidated to Profile (was duplicated in the sidebar bottom bar)
- Search promoted to the top of the sidebar (most-frequent jump-anywhere entry)
- HomeView no longer paints empty when offline — feed carousels are populated from the local catalog caches
- Empty state for Music's "For You" rendered only when there are recommendations
