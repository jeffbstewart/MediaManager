import SwiftUI
import UIKit

/// The Marquee look (docs/plans/marquee-reference/STYLE-SPEC.md,
/// section 1) adapted to SwiftUI. The web app expresses the same
/// design as CSS custom properties under `html[data-theme="marquee"]`
/// (`web-app/src/styles/_theme-marquee.scss`); this file is the iOS
/// counterpart, one token per spec value, so the two clients drift
/// only when someone edits one and not the other.
///
/// What is adapted rather than copied:
/// - Body text stays San Francisco. The spec's body stack is Inter
///   with `-apple-system` as the fallback; on iOS the system face
///   gets Dynamic Type, optical sizes and the system weights for
///   free, and Inter was chosen on the web only because the browser
///   had nothing comparable built in. Display headings use Figtree
///   800, the brand signature, exactly as on the web.
/// - Pixel sizes are points. The spec's 390px column is an iPhone
///   width, so its phone sizes map one to one.
/// - The app is dark only (`MarqueeTheme.install()` forces the
///   interface style), as is the web.
enum MarqueeTheme {

    // MARK: Color (spec --c-*)

    enum Colors {
        /// `--c-page` page background.
        static let page = Color(mmHex: 0x1e1e1e)
        /// `--c-text` headings, card titles.
        static let text = Color.white
        /// `--c-text-2` body copy, descriptions.
        static let text2 = Color.white.opacity(0.80)
        /// `--c-text-3` meta lines ("2025 • Movie", "23m left").
        static let text3 = Color.white.opacity(0.60)
        /// `--c-ink` the primary pill button's text and the sheet fill.
        static let ink = Color.black
        /// `--c-accent` progress fill, active nav icon, accent meta.
        /// Bright teal, from the app icon's family. The web theme this
        /// was adapted from uses a gold accent; the iOS app does not.
        static let accent = Color(mmHex: 0x2dd4bf)
        /// `--c-surface` chips, search pill, ghost buttons, person cards.
        static let surface = Color.white.opacity(0.10)
        /// A second step up from `surface` for pressed / hovered pills.
        static let surfaceRaised = Color.white.opacity(0.16)
        /// `--c-scrim` translucent panels (nav, FAQ rows).
        static let scrim = Color.black.opacity(0.30)
        /// `--c-scrim-strong` chips sitting on artwork.
        static let scrimStrong = Color.black.opacity(0.70)
        /// `--c-footer` footer band.
        static let footer = Color.black.opacity(0.60)
        /// `--c-live` LIVE badge, nav counters.
        static let live = Color(mmHex: 0xdc3004)
        /// Hairline drawn inside every piece of artwork (`#ffffff14`).
        static let hairline = Color.white.opacity(0.08)
        /// Active nav pill outline (`#ffffffb3`).
        static let navOutline = Color.white.opacity(0.70)
        /// Progress track under the accent fill (`#ffffff4d`).
        static let progressTrack = Color.white.opacity(0.30)

        // Secondary palette: category tiles, avatar fills.
        static let blue = Color(mmHex: 0x1b90dc)
        static let pink = Color(mmHex: 0xf74366)
        static let plum = Color(mmHex: 0x823165)
        static let green = Color(mmHex: 0x45934b)
        static let orange = Color(mmHex: 0xf37b38)

        // Greys: image placeholders.
        static let grey1 = Color(mmHex: 0x282a2d)
        static let grey2 = Color(mmHex: 0x3f4245)
        static let grey3 = Color(mmHex: 0x575b61)
        static let grey4 = Color(mmHex: 0x868c96)

        /// `--g-top`: the glow behind the header and the welcome block.
        static let glowTop: [Color] = [
            Color(mmHex: 0x6d0f4f), Color(mmHex: 0x3a1f86), Color(mmHex: 0x04289a),
        ]
        /// `--g-discover`: the glow on browse / discover pages.
        static let glowDiscover: [Color] = [
            Color(mmHex: 0x0b2a6e), Color(mmHex: 0x0e3a96), Color(mmHex: 0x0a3fae),
        ]
        /// `--g-plain`: a quiet grey glow for utility pages.
        static let glowPlain: [Color] = [Color(mmHex: 0x2a2a2a), page]
    }

    // MARK: Type

    enum Fonts {
        /// PostScript name of the one Figtree face we ship
        /// (`Resources/Fonts/Figtree-ExtraBold.ttf`, SIL OFL 1.1).
        /// Only the 800 weight ships, as on the web, so every display
        /// rule renders at 800.
        static let displayName = "Figtree-ExtraBold"

        /// Display face at a fixed size. Scales with Dynamic Type
        /// relative to the given text style.
        static func display(_ size: CGFloat, relativeTo style: Font.TextStyle = .title2) -> Font {
            .custom(displayName, size: size, relativeTo: style)
        }

        /// Page title (h1): 28pt on phones, 32 on wide layouts.
        static let h1 = display(28, relativeTo: .largeTitle)
        static let h1Wide = display(32, relativeTo: .largeTitle)
        /// Row title (h2): 18pt on phones, 21 on wide layouts.
        static let h2 = display(18, relativeTo: .title2)
        static let h2Wide = display(21, relativeTo: .title2)
        /// Hero title: 34pt on phones, 40 on tablets.
        static let hero = display(34, relativeTo: .largeTitle)
        static let heroWide = display(40, relativeTo: .largeTitle)
        /// Wide-card overlay title and FAQ questions: 18 / 16.
        static let cardOverlay = display(18, relativeTo: .headline)
        static let h3 = display(16, relativeTo: .headline)
        /// The brand wordmark in the sidebar / login screens.
        static let brand = display(26, relativeTo: .title)

        /// Body / description: 16pt regular.
        static let body = Font.system(size: 16)
        /// Card title: 14pt regular.
        static let cardTitle = Font.system(size: 14)
        /// Meta line: 12pt semibold.
        static let meta = Font.system(size: 12, weight: .semibold)
        /// Buttons, chips, nav: 14pt semibold.
        static let control = Font.system(size: 14, weight: .semibold)
        /// Hero kicker ("Just added to your library"): 12pt bold.
        static let kicker = Font.system(size: 12, weight: .bold)
        /// Corner badge: 12pt bold caps.
        static let badge = Font.system(size: 12, weight: .bold)
    }

    // MARK: Shape and depth

    enum Radius {
        /// `--r-card` posters, wide cards, person cards.
        static let card: CGFloat = 8
        /// `--r-hero` hero banners, welcome card, sheets.
        static let hero: CGFloat = 16
        /// `--r-badge` rating box, tags.
        static let badge: CGFloat = 4
    }

    enum Spacing {
        /// Horizontal page padding on phones (spec: 16 at 390 / 768).
        static let pageX: CGFloat = 16
        /// Horizontal page padding on wide layouts (spec: 64 at 1440,
        /// scaled down for the iPad detail column).
        static let pageXWide: CGFloat = 32
        /// Gap between sections: 24 on phones, 32 wide.
        static let section: CGFloat = 24
        static let sectionWide: CGFloat = 32
        /// Row title to row.
        static let headToRow: CGFloat = 12
        /// Gap between cards: 8 on phones, 12 on tablets.
        static let card: CGFloat = 8
        static let cardWide: CGFloat = 12
    }

    enum Sizes {
        /// Poster (2:3): 114 × 171 on phones, 175 × 262 on tablets.
        static let posterWidth: CGFloat = 114
        static let posterWidthWide: CGFloat = 175
        /// Wide card (16:9): 175 × 99 on phones, 220 × 124 on tablets.
        static let wideWidth: CGFloat = 175
        static let wideWidthWide: CGFloat = 220
        /// Person card: 300 × 132 on phones, 350 × 160 wide.
        static let personWidth: CGFloat = 300
        static let personWidthWide: CGFloat = 350
        /// Pill controls are 40pt tall everywhere.
        static let pillHeight: CGFloat = 40
        /// Nav icons are 20pt.
        static let navIcon: CGFloat = 20
    }

    // MARK: Install

    /// Wires the parts of the look that SwiftUI can't express per
    /// view: the interface style, the navigation bar, the backing
    /// collection views behind `List` / `Form`, and the tint. Called
    /// once from `MediaManagerApp.init()`.
    @MainActor
    static func install() {
        let page = UIColor(Colors.page)
        let accent = UIColor(Colors.accent)

        // Navigation bars (spec `header.app-header`: transparent at
        // the top of the page, frosted once content scrolls under
        // it). Titles use the display face.
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .foregroundColor: UIColor.white,
            .font: UIFont(name: Fonts.displayName, size: 18) ?? UIFont.boldSystemFont(ofSize: 18),
        ]
        let largeTitleAttrs: [NSAttributedString.Key: Any] = [
            .foregroundColor: UIColor.white,
            .font: UIFont(name: Fonts.displayName, size: 32) ?? UIFont.boldSystemFont(ofSize: 32),
        ]
        let atTop = UINavigationBarAppearance()
        atTop.configureWithTransparentBackground()
        atTop.titleTextAttributes = titleAttrs
        atTop.largeTitleTextAttributes = largeTitleAttrs

        let scrolled = UINavigationBarAppearance()
        scrolled.configureWithDefaultBackground()
        scrolled.backgroundColor = page.withAlphaComponent(0.72)
        scrolled.shadowColor = .clear
        scrolled.titleTextAttributes = titleAttrs
        scrolled.largeTitleTextAttributes = largeTitleAttrs

        let navBar = UINavigationBar.appearance()
        navBar.standardAppearance = scrolled
        navBar.compactAppearance = scrolled
        navBar.scrollEdgeAppearance = atTop
        navBar.compactScrollEdgeAppearance = atTop
        navBar.tintColor = accent

        // Lists and forms are collection views underneath; painting
        // their background here gives every `List` the page colour
        // without touching 59 call sites. Cells keep the system
        // secondary grouped fill, which on a `#1e1e1e` page is
        // slightly darker than the page; `mmListChrome()` lifts the
        // rows to the surface token on the screens that matter.
        UICollectionView.appearance().backgroundColor = page
        UITableView.appearance().backgroundColor = page

        // Search fields in the navigation bar pick up the surface pill.
        UISearchBar.appearance().tintColor = accent

        // Toolbars (the sidebar's bottom status bar).
        let tool = UIToolbarAppearance()
        tool.configureWithOpaqueBackground()
        tool.backgroundColor = page
        tool.shadowColor = .clear
        UIToolbar.appearance().standardAppearance = tool
        UIToolbar.appearance().scrollEdgeAppearance = tool

        UIWindow.appearance().tintColor = accent
    }
}

// MARK: - Color helpers

extension Color {
    /// `Color(mmHex: 0x2dd4bf)`. Named to avoid colliding with the
    /// string-based `Color(hex:)` initialiser the tag views use.
    init(mmHex hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255,
            opacity: opacity)
    }
}

extension ShapeStyle where Self == Color {
    /// `--c-page`
    static var mmPage: Color { MarqueeTheme.Colors.page }
    /// `--c-text-2`
    static var mmText2: Color { MarqueeTheme.Colors.text2 }
    /// `--c-text-3`
    static var mmText3: Color { MarqueeTheme.Colors.text3 }
    /// `--c-accent`
    static var mmAccent: Color { MarqueeTheme.Colors.accent }
    /// `--c-surface`
    static var mmSurface: Color { MarqueeTheme.Colors.surface }
    /// `--c-scrim-strong`
    static var mmScrimStrong: Color { MarqueeTheme.Colors.scrimStrong }
    /// `--c-live`
    static var mmLive: Color { MarqueeTheme.Colors.live }
}

// MARK: - Layout environment

/// Which of the spec's two breakpoints a view is laid out for. The
/// web switches at 1023px (nav collapses) and 599px (phone sizes);
/// on iOS the equivalent split is the horizontal size class: compact
/// gets the 390px column's sizes, regular gets the 768px ones.
struct MarqueeLayout {
    let isWide: Bool
    /// Width of the page the view sits in, when a `mmPage()` /
    /// `mmGlowPage()` ancestor has measured it. Grids use it to fill
    /// the row instead of leaving a dead gutter on the right.
    var contentWidth: CGFloat? = nil

    var pageX: CGFloat { isWide ? MarqueeTheme.Spacing.pageXWide : MarqueeTheme.Spacing.pageX }
    var sectionGap: CGFloat { isWide ? MarqueeTheme.Spacing.sectionWide : MarqueeTheme.Spacing.section }
    var cardGap: CGFloat { isWide ? MarqueeTheme.Spacing.cardWide : MarqueeTheme.Spacing.card }
    var posterWidth: CGFloat { isWide ? MarqueeTheme.Sizes.posterWidthWide : MarqueeTheme.Sizes.posterWidth }
    var posterHeight: CGFloat { (posterWidth * 3 / 2).rounded() }
    /// Square art (albums, playlists) in a horizontal row. Wider than
    /// a poster so a 1:1 cover doesn't read as a stubby poster; the
    /// web keeps posters and squares the same width, but at phone
    /// sizes the square needs the extra room.
    var squareWidth: CGFloat { (posterWidth * 1.35).rounded() }
    /// Cell width for a poster grid: as many spec-width columns as fit
    /// the page (after the gutters), each widened so the row fills
    /// the width exactly. Falls back to `posterWidth` when the page
    /// hasn't been measured.
    var gridCellWidth: CGFloat {
        guard let contentWidth, contentWidth > 0 else { return posterWidth }
        let available = contentWidth - 2 * pageX
        let columns = max(1, floor((available + cardGap) / (posterWidth + cardGap)))
        return floor((available - (columns - 1) * cardGap) / columns)
    }
    /// Grid columns for `gridCellWidth` cells at the spec's card gap.
    var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: gridCellWidth, maximum: gridCellWidth), spacing: cardGap, alignment: .top)]
    }
    /// The same fill for 16:9 cards (Live TV, Cameras).
    var wideGridCellWidth: CGFloat {
        guard let contentWidth, contentWidth > 0 else { return wideWidth }
        let available = contentWidth - 2 * pageX
        let columns = max(1, floor((available + cardGap) / (wideWidth + cardGap)))
        return floor((available - (columns - 1) * cardGap) / columns)
    }
    var wideGridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: wideGridCellWidth, maximum: wideGridCellWidth), spacing: cardGap, alignment: .top)]
    }
    var wideWidth: CGFloat { isWide ? MarqueeTheme.Sizes.wideWidthWide : MarqueeTheme.Sizes.wideWidth }
    var wideHeight: CGFloat { (wideWidth * 9 / 16).rounded() }
    var personWidth: CGFloat { isWide ? MarqueeTheme.Sizes.personWidthWide : MarqueeTheme.Sizes.personWidth }
    var h1: Font { isWide ? MarqueeTheme.Fonts.h1Wide : MarqueeTheme.Fonts.h1 }
    var h2: Font { isWide ? MarqueeTheme.Fonts.h2Wide : MarqueeTheme.Fonts.h2 }
    var hero: Font { isWide ? MarqueeTheme.Fonts.heroWide : MarqueeTheme.Fonts.hero }
}

extension EnvironmentValues {
    /// Derived from the horizontal size class, so a view asks for
    /// `@Environment(\.marquee)` and reads `pageX`, `posterWidth`,
    /// `h2` and friends without each view re-deriving the split.
    var marquee: MarqueeLayout {
        MarqueeLayout(isWide: horizontalSizeClass == .regular, contentWidth: mmContentWidth)
    }

    /// Set by `mmPage()` / `mmGlowPage()` from the page's measured width.
    @Entry var mmContentWidth: CGFloat? = nil
}

/// Measures the page width and publishes it to descendants as
/// `mmContentWidth`, so `MarqueeLayout.gridCellWidth` can fill rows.
/// Applied by `ContentView` to the detail column: a view cannot read
/// an environment value it sets on its own body, so the measurement
/// has to sit above every page that lays out a grid.
struct PageWidthModifier: ViewModifier {
    @State private var width: CGFloat? = nil
    func body(content: Content) -> some View {
        content
            .environment(\.mmContentWidth, width)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}

// MARK: - Page chrome modifiers

extension View {
    /// Page background for scrolling content: the page colour under
    /// everything, extended into the safe areas so the navigation bar
    /// and the home-indicator strip read as the same slab.
    func mmPage() -> some View {
        background(MarqueeTheme.Colors.page.ignoresSafeArea())
    }

    /// Publishes this view's width to descendants (`mmContentWidth`).
    func mmMeasureWidth() -> some View {
        modifier(PageWidthModifier())
    }

    /// For `List` / `Form`: hides the system grouped background so
    /// the page colour shows, and lifts rows onto the surface token.
    /// Apply to the List itself; `mmRow()` goes on the rows (or a
    /// Section) when the surface fill is wanted.
    func mmListChrome() -> some View {
        scrollContentBackground(.hidden)
            .background(MarqueeTheme.Colors.page.ignoresSafeArea())
    }

    /// Row surface for a List section (spec `--c-surface`).
    func mmRow() -> some View {
        listRowBackground(MarqueeTheme.Colors.surface)
    }

    /// Section heading text in the display face.
    func mmSectionTitle() -> some View {
        modifier(SectionTitleModifier())
    }
}

private struct SectionTitleModifier: ViewModifier {
    @Environment(\.marquee) private var layout
    func body(content: Content) -> some View {
        content
            .font(layout.h2)
            .foregroundStyle(MarqueeTheme.Colors.text)
    }
}
