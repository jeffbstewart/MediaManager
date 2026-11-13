import SwiftUI
import MediaManagerCore
import MediaManagerProtos

// Building blocks from the spec (section 3): section head, poster
// art, wide card, hero banner, placeholder art, progress pill and
// corner badge. The web app's `shared/media/*` components are the
// same set; keep names aligned when adding one.

// MARK: - 3.1 Section head

/// Row title in the display face, optionally with a "see all"
/// chevron. The title lines up with the row's left edge.
struct MMSectionHead<Trailing: View>: View {
    @Environment(\.marquee) private var layout
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var trailing: () -> Trailing

    init(_ title: String, subtitle: String? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Leading-aligned so a chevron hugs the title; a caller that
            // wants something at the far edge puts a Spacer first in
            // `trailing`.
            HStack(alignment: .center) {
                Text(title)
                    .font(layout.h2)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(1)
                trailing()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let subtitle {
                Text(subtitle)
                    .font(MarqueeTheme.Fonts.body)
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .lineLimit(2)
            }
        }
        .padding(.horizontal, layout.pageX)
        .accessibilityAddTraits(.isHeader)
    }
}

extension MMSectionHead where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil) {
        self.init(title, subtitle: subtitle) { EmptyView() }
    }
}

/// A "see all" chevron for a section head (`section-head a svg`).
struct MMSeeAllChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(MarqueeTheme.Colors.text3)
    }
}

// MARK: - Horizontal row

/// A scrolling row with the spec's card gap and page padding; the
/// trailing padding lets the last card sit flush when scrolled and
/// the leading padding lines the first card up with the title.
struct MMRow<Content: View>: View {
    @Environment(\.marquee) private var layout
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: layout.cardGap) {
                content()
            }
            .padding(.horizontal, layout.pageX)
            // Room for the card shadow below the art.
            .padding(.bottom, 6)
        }
        .scrollClipDisabled()
    }
}

// MARK: - 3.2 Art frame

/// The artwork surface every card shares: 8pt radius, the card
/// shadow, a 1px inner hairline, and the placeholder grey behind a
/// transparent or missing image.
struct MMArtFrame<Content: View>: View {
    var radius: CGFloat = MarqueeTheme.Radius.card
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .background(MarqueeTheme.Colors.grey1)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.2), radius: 4, x: 0, y: 4)
    }
}

/// Missing artwork (spec 3.2): a gradient placeholder with a large
/// faint initial. Uses the same seeded hue as `BookCoverView` and
/// the server's SVG placeholder so a title looks alike everywhere.
struct MMPlaceholderArt: View {
    let name: String
    var systemImage: String? = nil
    /// False draws only the gradient (for art that carries its own
    /// overlay, such as a play button).
    var showsGlyph: Bool = true

    var body: some View {
        let hue = MMPlaceholderArt.hue(for: name)
        ZStack {
            LinearGradient(
                colors: [
                    Color(hue: hue, saturation: 0.52, brightness: 0.42),
                    Color(hue: hue, saturation: 0.55, brightness: 0.22),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing)
            GeometryReader { geo in
                let side = min(geo.size.width, geo.size.height)
                VStack(spacing: 4) {
                    if !showsGlyph {
                        EmptyView()
                    } else if let systemImage {
                        Image(systemName: systemImage)
                            .font(.system(size: side * 0.34, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.55))
                    } else {
                        Text(MMPlaceholderArt.initial(for: name))
                            .font(MarqueeTheme.Fonts.display(side * 0.56, relativeTo: .largeTitle))
                            .foregroundStyle(.white.opacity(0.35))
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
        .accessibilityHidden(true)
    }

    static func hue(for seed: String) -> Double {
        let trimmed = seed.trimmingCharacters(in: .whitespaces)
        let basis = trimmed.isEmpty ? "?" : trimmed
        let hash = basis.utf16.reduce(0) { acc, c in acc &* 31 &+ Int(c) }
        return Double(((hash % 360) + 360) % 360) / 360.0
    }

    static func initial(for seed: String) -> String {
        let trimmed = seed.trimmingCharacters(in: .whitespaces)
        let basis = trimmed.isEmpty ? "?" : trimmed
        if let first = basis.first(where: { $0.isLetter || $0.isNumber }) {
            return String(first).uppercased()
        }
        return "?"
    }
}

/// Progress pill over artwork (spec 3.2): 4pt tall, 8pt in from the
/// art's edges, translucent track, amber fill.
struct MMProgressPill: View {
    let fraction: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(MarqueeTheme.Colors.progressTrack)
                Capsule()
                    .fill(MarqueeTheme.Colors.accent)
                    .frame(width: max(4, geo.size.width * min(max(fraction, 0), 1)))
            }
        }
        .frame(height: 4)
        .padding(8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Progress")
        .accessibilityValue("\(Int((fraction * 100).rounded())) percent")
    }
}

/// Corner badge at the top-right of art (spec 3.3): caps, 12/700,
/// radius `0 0 0 8`. Red for LIVE, amber for NEW, scrim otherwise.
struct MMCornerBadge: View {
    enum Tone { case live, accent, scrim }
    let text: String
    var tone: Tone = .scrim

    var body: some View {
        Text(text.uppercased())
            .font(MarqueeTheme.Fonts.badge)
            .foregroundStyle(tone == .scrim ? MarqueeTheme.Colors.text : MarqueeTheme.Colors.ink)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(fill)
            .clipShape(UnevenRoundedRectangle(
                topLeadingRadius: 0, bottomLeadingRadius: MarqueeTheme.Radius.card,
                bottomTrailingRadius: 0, topTrailingRadius: MarqueeTheme.Radius.card))
    }

    private var fill: Color {
        switch tone {
        case .live: MarqueeTheme.Colors.live
        case .accent: MarqueeTheme.Colors.accent
        case .scrim: MarqueeTheme.Colors.scrimStrong
        }
    }
}

/// A small round scrim badge over art (downloaded marker, etc.).
struct MMArtBadge: View {
    let systemImage: String
    let label: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(.white)
            .padding(5)
            .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
            .padding(6)
            .accessibilityLabel(label)
    }
}

// MARK: - Caption

/// The card caption: title (14 regular, white) and up to two meta
/// lines (12 semibold, muted; `accent` turns one amber).
struct MMCaption: View {
    let title: String
    var meta: String? = nil
    var metaAccent: Bool = false
    var credit: String? = nil
    var width: CGFloat? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(MarqueeTheme.Fonts.cardTitle)
                .foregroundStyle(MarqueeTheme.Colors.text)
                .lineLimit(2)
            if let credit {
                Text(credit)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
                    .lineLimit(1)
            }
            if let meta {
                Text(meta)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(metaAccent ? MarqueeTheme.Colors.accent : MarqueeTheme.Colors.text3)
                    .lineLimit(1)
            }
        }
        .frame(width: width, alignment: .leading)
        .padding(.top, 8)
    }
}

// MARK: - 3.2 Poster card (generic)

/// Poster or square card over any image reference. `PosterCard`
/// (HomeView.swift) is the `ApiTitle`-aware wrapper that adds the
/// download marker, playable dimming and resume lines on top.
struct MMPosterTile: View {
    @Environment(\.marquee) private var layout
    let name: String
    let ref: MMImageRef?
    var square: Bool = false
    var width: CGFloat? = nil
    var progress: Double? = nil
    var badge: String? = nil
    var badgeTone: MMCornerBadge.Tone = .scrim
    var placeholderIcon: String? = nil
    var dimmed: Bool = false

    private var w: CGFloat { width ?? layout.posterWidth }
    private var h: CGFloat { square ? w : (w * 3 / 2).rounded() }

    var body: some View {
        MMArtFrame {
            ZStack(alignment: .bottom) {
                MMPlaceholderArt(name: name, systemImage: placeholderIcon)
                if let ref {
                    CachedImage(ref: ref, cornerRadius: 0, transparentPlaceholder: true)
                }
            }
            .frame(width: w, height: h)
            .overlay {
                if dimmed {
                    MarqueeTheme.Colors.page.opacity(0.55)
                }
            }
            .overlay(alignment: .bottom) {
                if let progress, progress > 0 {
                    MMProgressPill(fraction: progress)
                }
            }
            .overlay(alignment: .topTrailing) {
                if let badge {
                    MMCornerBadge(text: badge, tone: badgeTone)
                }
            }
        }
        .frame(width: w, height: h)
    }
}

// MARK: - 3.3 Wide card

/// 16:9 card with a bottom scrim and the title over the art in the
/// display face, plus an optional corner badge and progress pill.
/// Caption lines below are optional (`Show • S2 • E5` / `23m left`).
struct MMWideCard: View {
    @Environment(\.marquee) private var layout
    let name: String
    let ref: MMImageRef?
    var caption: String? = nil
    var meta: String? = nil
    var progress: Double? = nil
    var badge: String? = nil
    var badgeTone: MMCornerBadge.Tone = .scrim
    var showPlay: Bool = false
    var width: CGFloat? = nil

    private var w: CGFloat { width ?? layout.wideWidth }
    private var h: CGFloat { (w * 9 / 16).rounded() }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MMArtFrame {
                ZStack(alignment: .bottomLeading) {
                    // The play overlay already marks the card as video;
                    // a film glyph under it would just collide.
                    MMPlaceholderArt(name: name, systemImage: "film", showsGlyph: !showPlay)
                    if let ref {
                        CachedImage(ref: ref, cornerRadius: 0, transparentPlaceholder: true)
                    }
                    LinearGradient(
                        colors: [.clear, .black.opacity(0.75)],
                        startPoint: .center, endPoint: .bottom)
                    Text(name)
                        .font(MarqueeTheme.Fonts.cardOverlay)
                        .foregroundStyle(MarqueeTheme.Colors.text)
                        .lineLimit(2)
                        .padding(.horizontal, 10)
                        .padding(.bottom, progress != nil ? 18 : 10)
                        .shadow(color: .black.opacity(0.6), radius: 2, y: 1)
                }
                .frame(width: w, height: h)
                .overlay(alignment: .bottom) {
                    if let progress, progress > 0 {
                        MMProgressPill(fraction: progress)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if let badge {
                        MMCornerBadge(text: badge, tone: badgeTone)
                    }
                }
                // Play sits in the top-left corner, opposite the badge,
                // so a two-line title along the bottom never runs
                // under it.
                .overlay(alignment: .topLeading) {
                    if showPlay {
                        Image(systemName: "play.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(10)
                            .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                            .padding(8)
                            .accessibilityHidden(true)
                    }
                }
            }
            .frame(width: w, height: h)

            if caption != nil || meta != nil {
                VStack(alignment: .leading, spacing: 2) {
                    if let caption {
                        Text(caption)
                            .font(MarqueeTheme.Fonts.cardTitle)
                            .foregroundStyle(MarqueeTheme.Colors.text)
                            .lineLimit(1)
                    }
                    if let meta {
                        Text(meta)
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                            .lineLimit(1)
                    }
                }
                .padding(.top, 8)
            }
        }
        .frame(width: w, alignment: .leading)
    }
}

// MARK: - 3.4 Hero banner

/// Featured banner: a tinted card, the backdrop on the right fading
/// into the tint, title in the display face, rating + year meta, an
/// optional kicker, a three-line description and pill actions. On
/// compact widths it stacks: 16:9 image on top, then the text.
struct MMHeroBanner<Actions: View>: View {
    @Environment(\.marquee) private var layout
    let title: String
    let ref: MMImageRef?
    var tint: Color = MarqueeTheme.Colors.grey2
    var contentRating: String? = nil
    var year: Int? = nil
    var kind: String? = nil
    var kicker: String? = nil
    var description: String? = nil
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        Group {
            if layout.isWide {
                sideBySide
            } else {
                stacked
            }
        }
        .background(tint)
        .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous)
                .strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.25), radius: 15, x: 6, y: 6)
    }

    /// The backdrop as an overlay on a sized base, so the resizable
    /// image fills the slot rather than growing the stack to the
    /// bitmap's own size.
    private var image: some View {
        Color.clear
            .overlay {
                ZStack {
                    MMPlaceholderArt(name: title, systemImage: "film")
                    if let ref {
                        CachedImage(ref: ref, cornerRadius: 0, transparentPlaceholder: true)
                    }
                }
            }
            .clipped()
    }

    private var stacked: some View {
        VStack(alignment: .leading, spacing: 0) {
            image
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .overlay(
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0.55),
                            .init(color: tint, location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom))
            text
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 20)
        }
    }

    private var sideBySide: some View {
        ZStack(alignment: .leading) {
            HStack(spacing: 0) {
                Color.clear.frame(maxWidth: .infinity)
                image
                    .frame(maxWidth: .infinity)
                    .overlay(
                        LinearGradient(
                            stops: [
                                .init(color: tint, location: 0),
                                .init(color: tint.opacity(0.55), location: 0.3),
                                .init(color: .clear, location: 0.7),
                            ],
                            startPoint: .leading, endPoint: .trailing))
            }
            text
                .frame(maxWidth: 560, alignment: .leading)
                .padding(.horizontal, 40)
                .padding(.vertical, 32)
        }
        .frame(minHeight: 300)
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(layout.hero)
                .foregroundStyle(MarqueeTheme.Colors.text)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            HStack(spacing: 10) {
                if let contentRating, !contentRating.isEmpty {
                    MMRatingBox(rating: contentRating)
                }
                if let year {
                    Text(String(year))
                }
                if let kind {
                    Text(kind)
                }
            }
            .font(MarqueeTheme.Fonts.meta)
            .foregroundStyle(MarqueeTheme.Colors.text2)
            if let kicker {
                Text(kicker)
                    .font(MarqueeTheme.Fonts.kicker)
                    .foregroundStyle(MarqueeTheme.Colors.text)
            }
            if let description, !description.isEmpty {
                Text(description)
                    .font(MarqueeTheme.Fonts.body)
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .lineLimit(3)
            }
            HStack(spacing: 12) {
                actions()
            }
            .padding(.top, 4)
        }
    }
}

// MARK: - 3.6 Person card

/// Person / artist card: surface fill, round photo on the left, name
/// and meta lines on the right.
struct MMPersonCard: View {
    @Environment(\.marquee) private var layout
    let name: String
    let ref: MMImageRef?
    var fallbackRef: MMImageRef? = nil
    var lines: [String] = []
    var width: CGFloat? = nil

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                MMPlaceholderArt(name: name, systemImage: "person.fill")
                if let fallbackRef {
                    CachedImage(ref: fallbackRef, cornerRadius: 0, transparentPlaceholder: true)
                }
                if let ref {
                    CachedImage(ref: ref, cornerRadius: 0, transparentPlaceholder: true)
                }
            }
            .frame(width: 100, height: 100)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))

            VStack(alignment: .leading, spacing: 4) {
                Text(name)
                    .font(MarqueeTheme.Fonts.body)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(2)
                ForEach(lines, id: \.self) { line in
                    Text(line)
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(width: width ?? layout.personWidth, height: 132, alignment: .leading)
        .background(MarqueeTheme.Colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
    }
}

// MARK: - 3.7 Category tile

/// Poster-width tile with a saturated two-stop gradient and a
/// centred label (the "Browse Your Library" shortcuts).
struct MMCategoryTile: View {
    @Environment(\.marquee) private var layout
    let label: String
    let systemImage: String
    let colors: [Color]

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .semibold))
            Text(label)
                .font(MarqueeTheme.Fonts.body.weight(.semibold))
        }
        .foregroundStyle(.white)
        .frame(width: layout.posterWidth, height: 116)
        .background(
            ZStack {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                RadialGradient(
                    colors: [.white.opacity(0.22), .clear],
                    center: .topTrailing, startRadius: 0, endRadius: 120)
            })
        .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 4, x: 0, y: 4)
    }
}

// MARK: - Info banner

/// A quiet surface banner with a leading icon (replaces the tinted
/// "not available yet" boxes on the detail pages).
struct MMNotice: View {
    let text: String
    var systemImage: String = "info.circle"
    var tint: Color = MarqueeTheme.Colors.accent

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
            Text(text)
                .font(MarqueeTheme.Fonts.cardTitle)
                .foregroundStyle(MarqueeTheme.Colors.text2)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(MarqueeTheme.Colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
    }
}
