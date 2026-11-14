import SwiftUI

// Pill controls from the spec (section 1 "Shape": `--r-pill`; 3.4
// `.btn` / `.btn.ghost`; 3.5 chips). All are 40pt tall, 14/600 text,
// with 16pt side padding.

/// Primary call to action: white pill, black text (`.btn`). Used for
/// Play, Sign In, Connect and the one-per-screen decisive action.
struct MMPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(MarqueeTheme.Fonts.control)
            .foregroundStyle(MarqueeTheme.Colors.ink)
            .padding(.horizontal, 20)
            .frame(minHeight: MarqueeTheme.Sizes.pillHeight)
            .background(Color.white.opacity(configuration.isPressed ? 0.85 : 1), in: Capsule())
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Capsule())
    }
}

/// Secondary action: translucent surface pill, white text (`.btn.ghost`).
struct MMGhostButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(MarqueeTheme.Fonts.control)
            .foregroundStyle(MarqueeTheme.Colors.text)
            .padding(.horizontal, 20)
            .frame(minHeight: MarqueeTheme.Sizes.pillHeight)
            .background(
                configuration.isPressed
                    ? MarqueeTheme.Colors.surfaceRaised
                    : MarqueeTheme.Colors.surface,
                in: Capsule())
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Capsule())
    }
}

/// Accent action: accent-filled pill, black text. The one place the accent
/// is used as a fill rather than a highlight (e.g. a wish list add).
struct MMAccentButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(MarqueeTheme.Fonts.control)
            .foregroundStyle(MarqueeTheme.Colors.ink)
            .padding(.horizontal, 20)
            .frame(minHeight: MarqueeTheme.Sizes.pillHeight)
            .background(MarqueeTheme.Colors.accent.opacity(configuration.isPressed ? 0.85 : 1), in: Capsule())
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Capsule())
    }
}

/// Primary or ghost pill chosen at runtime (`prominent`), for a
/// button whose role depends on state.
struct MMPillButtonStyle: ButtonStyle {
    var prominent: Bool
    func makeBody(configuration: Configuration) -> some View {
        if prominent {
            MMPrimaryButtonStyle().makeBody(configuration: configuration)
        } else {
            MMGhostButtonStyle().makeBody(configuration: configuration)
        }
    }
}

extension ButtonStyle where Self == MMPrimaryButtonStyle {
    static var mmPrimary: MMPrimaryButtonStyle { MMPrimaryButtonStyle() }
}
extension ButtonStyle where Self == MMGhostButtonStyle {
    static var mmGhost: MMGhostButtonStyle { MMGhostButtonStyle() }
}
extension ButtonStyle where Self == MMAccentButtonStyle {
    static var mmAccent: MMAccentButtonStyle { MMAccentButtonStyle() }
}

/// A chip (spec 3.5): 40pt surface pill with an optional 16pt icon.
/// Not a button itself so it can wrap a `NavigationLink` label or
/// plain text alike.
struct MMChip: View {
    let label: String
    var systemImage: String? = nil
    var selected: Bool = false

    var body: some View {
        HStack(spacing: 8) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
            }
            Text(label)
        }
        .font(MarqueeTheme.Fonts.control)
        .foregroundStyle(selected ? MarqueeTheme.Colors.ink : MarqueeTheme.Colors.text)
        .padding(.horizontal, 16)
        .frame(height: MarqueeTheme.Sizes.pillHeight)
        .background(selected ? Color.white : MarqueeTheme.Colors.surface, in: Capsule())
        .contentShape(Capsule())
    }
}

/// A small tag chip (genres, family members): the same pill at a
/// compact 28pt height for inline flow layouts.
struct MMTag: View {
    let label: String
    var systemImage: String? = nil
    var tint: Color? = nil

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 12, weight: .semibold))
            }
            Text(label)
        }
        .font(MarqueeTheme.Fonts.meta)
        .foregroundStyle(tint ?? MarqueeTheme.Colors.text)
        .padding(.horizontal, 10)
        .frame(height: 28)
        .background((tint ?? Color.white).opacity(tint == nil ? 0.10 : 0.20), in: Capsule())
    }
}

/// The content-rating box (spec 3.4 `.rating`): 4pt radius outline.
struct MMRatingBox: View {
    let rating: String

    var body: some View {
        Text(rating)
            .font(MarqueeTheme.Fonts.meta)
            .foregroundStyle(MarqueeTheme.Colors.text)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay(
                RoundedRectangle(cornerRadius: MarqueeTheme.Radius.badge)
                    .stroke(MarqueeTheme.Colors.text3, lineWidth: 1))
    }
}

/// Text field styled as a 40pt surface pill (the spec's search pill).
struct MMFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .font(MarqueeTheme.Fonts.body)
            .foregroundStyle(MarqueeTheme.Colors.text)
            .padding(.horizontal, 16)
            .frame(minHeight: 44)
            .background(MarqueeTheme.Colors.surface, in: Capsule())
    }
}

extension TextFieldStyle where Self == MMFieldStyle {
    static var mmPill: MMFieldStyle { MMFieldStyle() }
}

/// The brand wordmark used in the sidebar and the sign-in screens
/// (web shell `.brand-word`): the app's brand name, lowercased,
/// with each word break drawn as a slash in the accent. The name
/// comes from `MMBrandName` (`APP_BRAND_NAME` in
/// `Branding.xcconfig`), so the open-source build reads
/// `media/manager` and a branded build reads its own name, e.g.
/// `household/disc/keeper`. It is separate from the display name
/// because the home screen truncates long names: a branded build
/// can show a short name under its icon and the full one here.
/// Without a brand name the display name is used.
struct MMBrandWordmark: View {
    var size: CGFloat = 26

    /// Words of the brand name; `Media Manager` when nothing is set.
    static var words: [String] {
        let info = Bundle.main.infoDictionary
        // An unset build setting expands to an empty string, not nil.
        let name = ["MMBrandName", "CFBundleDisplayName", "CFBundleName"]
            .compactMap { info?[$0] as? String }
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            ?? "Media Manager"
        let parts = name.split(whereSeparator: { $0.isWhitespace || $0 == "/" || $0 == "-" })
            .map { $0.lowercased() }
        return parts.isEmpty ? ["media", "manager"] : parts
    }

    var body: some View {
        let words = Self.words
        // One Text, not a row of them: when the name has to shrink to
        // fit, the whole wordmark scales together. As separate views
        // only the longest word shrank.
        let slash = Text("/").foregroundStyle(MarqueeTheme.Colors.accent)
        words.dropFirst().reduce(Text(words[0])) { mark, word in
            Text("\(mark)\(slash)\(Text(word))")
        }
        .font(MarqueeTheme.Fonts.display(size, relativeTo: .title))
        .foregroundStyle(MarqueeTheme.Colors.text)
        .tracking(-1)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        // A logo, not copy: past this size a three-word name no longer
        // fits a phone's width even scaled down, and it truncated to
        // `hous.../disc/kee...`. VoiceOver reads the label below.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(words.map(\.capitalized).joined(separator: " "))
    }
}

/// List / form section header in the spec's meta style: 12pt
/// semibold, uppercase, muted. Use `Section(header: MMSectionHeader("Account"))`
/// in place of `Section("Account")`.
struct MMSectionHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title.uppercased())
            .font(MarqueeTheme.Fonts.meta)
            .foregroundStyle(MarqueeTheme.Colors.text3)
            .tracking(0.6)
            .textCase(nil)
    }
}

/// The page glow (spec `.page-glow`): a horizontal three-stop
/// gradient behind the header that fades to the page colour over the
/// first ~660pt. Sits behind scrolling content and does not scroll.
struct MMPageGlow: View {
    var colors: [Color] = MarqueeTheme.Colors.glowTop
    var height: CGFloat = 480

    var body: some View {
        LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing)
            .mask(
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.9), location: 0),
                        .init(color: .black.opacity(0.35), location: 0.45),
                        .init(color: .clear, location: 1),
                    ],
                    startPoint: .top, endPoint: .bottom))
            .frame(height: height)
            .frame(maxHeight: .infinity, alignment: .top)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

extension View {
    /// Page colour plus the glow behind the top of the page.
    func mmGlowPage(_ colors: [Color] = MarqueeTheme.Colors.glowTop) -> some View {
        background {
            ZStack(alignment: .top) {
                MarqueeTheme.Colors.page.ignoresSafeArea()
                MMPageGlow(colors: colors)
            }
        }
    }
}
