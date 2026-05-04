import SwiftUI

/// Book cover with a synthesised fallback. The real cover (if any) loads
/// on top of a hashed-hue colour swatch with the title's first letter,
/// so missing covers stay visually distinct instead of showing the
/// generic "film" placeholder.
///
/// Algorithm intentionally mirrors `servePlaceholder` in
/// `ImageHttpServices.kt`: the same seed produces the same hue and
/// initial across iOS and the web SVG path, so a title looks the same
/// everywhere it renders without a real cover.
struct BookCoverView: View {
    let ref: MMImageRef?
    let seed: String
    var cornerRadius: CGFloat = 6

    var body: some View {
        ZStack {
            synthCover
            if let ref {
                CachedImage(
                    ref: ref,
                    cornerRadius: cornerRadius,
                    transparentPlaceholder: true)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }

    @ViewBuilder
    private var synthCover: some View {
        let hue = Self.hue(for: seed)
        // Server uses HSL(hue, 35%, 28%); converted to HSB this lands at
        // (hue, 51.9%, 37.8%) — close enough that side-by-side iOS / web
        // cards on the same title aren't noticeably different shades.
        Color(hue: hue, saturation: 0.519, brightness: 0.378)
            .overlay {
                GeometryReader { geo in
                    Text(Self.initial(for: seed))
                        .font(.system(
                            size: min(geo.size.width, geo.size.height) * 0.56,
                            weight: .bold))
                        .foregroundStyle(.white.opacity(0.78))
                        .minimumScaleFactor(0.3)
                        .frame(width: geo.size.width, height: geo.size.height)
                }
            }
    }

    /// Hue in [0, 1] derived from a Java-style 31-multiplier hash of the
    /// trimmed seed, matching the server's algorithm.
    private static func hue(for seed: String) -> Double {
        let trimmed = seed.trimmingCharacters(in: .whitespaces)
        let basis = trimmed.isEmpty ? "?" : trimmed
        // String.utf16 mirrors Kotlin's Char.code (UTF-16 code unit).
        let hash = basis.utf16.reduce(0) { acc, c in acc &* 31 &+ Int(c) }
        return Double(((hash % 360) + 360) % 360) / 360.0
    }

    /// First alphanumeric character of the seed, uppercased. Falls back
    /// to "?" so empty / punctuation-only seeds don't render blank.
    private static func initial(for seed: String) -> String {
        let trimmed = seed.trimmingCharacters(in: .whitespaces)
        let basis = trimmed.isEmpty ? "?" : trimmed
        if let first = basis.first(where: { $0.isLetter || $0.isNumber }) {
            return String(first).uppercased()
        }
        return "?"
    }
}
