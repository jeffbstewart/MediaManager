import SwiftUI
import UIKit
import CoreImage
import MediaManagerCore
import MediaManagerProtos

/// The hero banner's tint (spec 3.4): each backdrop's saturated
/// average colour at about 20% lightness. The server computes the
/// same value into `Title.backdrop_tint` for the web; the iOS proto
/// bindings predate that field, so this derives it on the device
/// from the cached backdrop image with a single `CIAreaAverage`.
/// Results are memoised per title for the process lifetime.
@MainActor
enum BackdropTint {
    private static var cache: [Int64: Color] = [:]
    private static let context = CIContext(options: [.workingColorSpace: NSNull()])

    /// Neutral fallback while the image loads or when it can't be read.
    static let fallback = MarqueeTheme.Colors.grey2

    static func tint(forTitleId titleId: Int64, imageProvider: ImageProvider) async -> Color {
        if let hit = cache[titleId] { return hit }
        guard let image = await imageProvider.image(for: .backdrop(titleId: titleId)) else {
            return fallback
        }
        let colour = compute(from: image) ?? fallback
        cache[titleId] = colour
        return colour
    }

    /// Average the image, then push the hue to a saturated, dark
    /// value: saturation at least 0.55, lightness 20%. Mirrors the
    /// server's `BackdropTint.compute`.
    static func compute(from image: UIImage) -> Color? {
        guard let cg = image.cgImage else { return nil }
        let ci = CIImage(cgImage: cg)
        guard let filter = CIFilter(name: "CIAreaAverage") else { return nil }
        filter.setValue(ci, forKey: kCIInputImageKey)
        filter.setValue(CIVector(cgRect: ci.extent), forKey: kCIInputExtentKey)
        guard let out = filter.outputImage else { return nil }
        var px = [UInt8](repeating: 0, count: 4)
        context.render(
            out, toBitmap: &px, rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        let r = Double(px[0]) / 255, g = Double(px[1]) / 255, b = Double(px[2]) / 255
        let (h, s, _) = hsl(r: r, g: g, b: b)
        // A grey backdrop has no hue worth amplifying; keep it grey.
        let sat = s < 0.08 ? 0.0 : max(s, 0.55)
        let (rr, gg, bb) = rgb(h: h, s: sat, l: 0.20)
        return Color(.sRGB, red: rr, green: gg, blue: bb, opacity: 1)
    }

    private static func hsl(r: Double, g: Double, b: Double) -> (Double, Double, Double) {
        let mx = max(r, g, b), mn = min(r, g, b)
        let l = (mx + mn) / 2
        guard mx != mn else { return (0, 0, l) }
        let d = mx - mn
        let s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn)
        var h: Double
        if mx == r { h = (g - b) / d + (g < b ? 6 : 0) }
        else if mx == g { h = (b - r) / d + 2 }
        else { h = (r - g) / d + 4 }
        h /= 6
        return (h, s, l)
    }

    private static func rgb(h: Double, s: Double, l: Double) -> (Double, Double, Double) {
        guard s > 0 else { return (l, l, l) }
        let q = l < 0.5 ? l * (1 + s) : l + s - l * s
        let p = 2 * l - q
        func channel(_ t0: Double) -> Double {
            var t = t0
            if t < 0 { t += 1 }
            if t > 1 { t -= 1 }
            if t < 1 / 6 { return p + (q - p) * 6 * t }
            if t < 1 / 2 { return q }
            if t < 2 / 3 { return p + (q - p) * (2 / 3 - t) * 6 }
            return p
        }
        return (channel(h + 1 / 3), channel(h), channel(h - 1 / 3))
    }
}
