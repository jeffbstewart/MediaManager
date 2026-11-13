# iOS Marquee look: the web rebrand on the iOS app

Status: first pass landed 2026-11-13. Builds clean; the sign-in flow was
checked in the simulator, the signed-in screens were not (see "Verify").

The web app's Marquee theme (`docs/plans/web-theming.md`, spec at
`docs/plans/marquee-reference/STYLE-SPEC.md`) is expressed as CSS tokens
and DOM shapes. This note records how each part maps onto SwiftUI, what
was adapted on purpose, and what a later pass should pick up.

## Where it lives

| File | What |
|------|------|
| `ios/MediaManager/MediaManager/Theme/MarqueeTheme.swift` | Tokens: colour (`--c-*`), type (display face + roles), radius, spacing, card sizes; `MarqueeLayout` (the two breakpoints as the horizontal size class); `install()` for the UIKit appearance proxies; page modifiers `mmPage()`, `mmGlowPage()`, `mmListChrome()`, `mmRow()`. |
| `Theme/MarqueeControls.swift` | Pill button styles (`.mmPrimary` white, `.mmGhost` surface, `.mmAccent` amber, `MMPillButtonStyle(prominent:)`), `MMChip`, `MMTag`, `MMRatingBox`, `.mmPill` text field, `MMBrandWordmark`, `MMPageGlow`. |
| `Theme/MediaCards.swift` | Spec section 3 as views: `MMSectionHead`, `MMRow`, `MMArtFrame`, `MMPlaceholderArt`, `MMProgressPill`, `MMCornerBadge`, `MMArtBadge`, `MMCaption`, `MMPosterTile`, `MMWideCard`, `MMHeroBanner`, `MMPersonCard`, `MMCategoryTile`, `MMNotice`. |
| `Theme/BackdropTint.swift` | Hero tint derived on device from the cached backdrop (`CIAreaAverage`, then saturate and set lightness to 20%). |
| `Resources/Fonts/Figtree-ExtraBold.ttf` + `Figtree.LICENSE` | The display face, registered in `Info.plist` (`UIAppFonts`). |
| `Assets.xcassets/AccentColor` | `#e5a00d`, so every system control that reads the tint is amber. |

Views restyled in this pass: `MediaManagerApp` (install, dark only,
tint), `ContentView` (sidebar: wordmark, pill rows, section headers,
glow; `BrowseRoute` destinations), `HomeView` (spec home composition
and `PosterCard`), `TitleDetailView`, `CatalogView`, `MiniPlayerBar`,
`LoginView`, `ServerSetupView`, `SetupAccountView`,
`TermsAgreementView`, `AppPolicyAgreementView`, `ProfileView` and
`AboutView` (list chrome only).

## Mapping decisions

- **Tokens, not literals.** Every colour, font role, radius and size
  the spec names is a static on `MarqueeTheme`. Views reference those;
  the only hex values outside the theme file are tag colours the
  server sends.
- **Breakpoints become the size class.** The web switches at 1023px
  and 599px. On iOS `MarqueeLayout.isWide` is `horizontalSizeClass ==
  .regular`: compact gets the spec's 390px sizes (114pt posters, 16pt
  gutters, 8pt card gap), regular gets the 768px ones (175pt posters,
  12pt gap) with a 32pt gutter rather than the desktop's 64. Read it
  with `@Environment(\.marquee)`.
- **Body text is San Francisco, not Inter.** The spec's body stack
  lists `-apple-system` right after Inter. On iOS the system face
  brings Dynamic Type, optical sizing and the full weight range; Inter
  would need `UIFontMetrics` plumbing for a near-identical result.
  Display text is Figtree 800 everywhere the web uses it (page and row
  titles, hero and wide-card titles, the wordmark, navigation bar
  titles). Only the 800 face ships, as on the web.
- **Dark only.** `UIUserInterfaceStyle = Dark` in Info.plist plus
  `.preferredColorScheme(.dark)` at the root.
- **Navigation bar.** Transparent at the top of a page, frosted page
  colour once content scrolls under it (spec `header.app-header`),
  with display-face titles. Done once through `UINavigationBarAppearance`.
- **Lists.** `List` and `Form` are collection views; the appearance
  proxy paints their background the page colour everywhere. Rows keep
  the system grouped fill unless a screen opts into `mmListChrome()` /
  `mmRow()` for the surface token (Profile and About do).
- **Sidebar.** `SidebarRow` draws the spec's nav pill itself (40pt,
  20pt icon, 14/600 label; active row gets the white outline and an
  amber icon). `listRowBackground(.clear)` removes the system
  selection highlight so the pill is the only state cue. Counters use
  the `--c-live` red like the web nav badges.
- **Hero tint.** The server stores `Title.backdrop_tint` for the web,
  but the iOS proto bindings predate that field (and `featured` /
  `spotlight` on `HomeFeedResponse`). Rather than regenerate the
  bindings in a rebrand change, `BackdropTint` computes the same value
  on device from the backdrop the image cache already holds, and Home
  derives the featured set from the "Recently Added" carousel. When
  the bindings are regenerated, switch both to the typed fields.
- **Buttons.** The spec's primary action is a white pill with black
  text; `.borderedProminent` would give an amber one. The decisive
  action per screen (Play, Sign In, Connect, Continue, I Agree) uses
  `.mmPrimary`; everything else on those screens is `.mmGhost`.
  Admin forms were left on the system styles, which now tint amber.
- **Phone navigation.** The spec puts a 4-tab bottom bar on phones.
  The app's `NavigationSplitView` collapses to the sidebar list as the
  root on iPhone; that structure is unchanged in this pass, only
  restyled. A tab bar is a navigation change, not a theme change.

## Verify

Build: from `ios/MediaManager`,

```bash
xcodebuild -project MediaManager.xcodeproj -scheme MediaManager \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Every viewer-facing screen was reviewed in the iPhone 17 Pro Max
simulator against the live server, driven with `lifecycle/sim-drive.sh`
(idb): sign-in, Home, sidebar (online and offline), Movies, TV Shows,
title detail, Seasons, Episodes, Books, author and book detail, Music,
album and artist detail, Artists, Collections and a collection, Tags
and a tag, Family, Live TV, Cameras, Search, Wish List, Downloads,
Profile. Fixes from that review: grids fill the page width
(`MarqueeLayout.gridCellWidth`, measured per pushed destination), the
title page's backdrop runs under the navigation bar with the title on
its fade, the hero image sizes from a clear base, square music tiles
have their own width tier, the sidebar's status line is a list row,
section headers are the small caps meta style, and the bulk download
row is a quiet amber row everywhere.

## Second pass (same day)

Every remaining user-facing screen was restyled against the same
token API: Music (landing, artists, artist and album detail,
playlists), Books (authors, author / book / series detail, reader
chapter sheet), Seasons and Episodes, Actor, Collections, Genres,
Tags, Search, Wish List, Live TV and Cameras (wide-card grids with
the red `Live` badge), Downloads, Sessions, Family Members, the admin
screens, the gate screens in `RootView` (connecting, forced password
change, fingerprint mismatch), Profile and About rows.

The wordmark now derives from `CFBundleDisplayName`, so the branded
build reads `household/disc/keeper` and the open-source build
`media/manager`.

## Not done yet

- Episode rows stay text rows: `ApiEpisode` carries no image, so
  there is nothing to put in a 16:9 card.
- Home's Artists row and On Live TV row (spec rows 10 and 13) await
  the regenerated proto bindings / the live channel list on Home.
- `CustomPlayerView` and `BookReaderView` keep their own chrome on
  purpose; the reader has its own light / sepia / dark themes.
- A bottom tab bar on iPhone (spec section 2), if wanted.
