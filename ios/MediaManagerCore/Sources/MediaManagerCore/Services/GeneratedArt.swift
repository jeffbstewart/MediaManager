import UIKit
import MediaManagerProtos

/// Deterministic abstract artwork that stands in for every poster,
/// backdrop, cover and headshot in screenshot mode (`ScreenshotMode`).
///
/// A Swift port of `artFor` in `web-app/tests/helpers/image-stub.ts`,
/// which draws the pictures in the web app's published screenshots: a
/// two-stop gradient, a handful of translucent circles, rings,
/// polygons and bands in hues related to the base, and a dot grain.
/// The same image reference always draws the same picture, and nothing
/// in it resembles a real poster, so the result can be published.
///
/// The drawing follows the web version step for step, but the two are
/// seeded from different keys (a URL path there, an image reference
/// here), so a given title does not get the same picture on both.
public enum GeneratedArt {

    /// Artwork for an image reference, at that kind of image's natural
    /// shape. Drawn once per reference and kept in memory.
    @MainActor
    public static func image(for ref: MMImageRef) -> UIImage {
        let key = Self.key(for: ref)
        if let hit = cache.object(forKey: key.text as NSString) { return hit }
        let image = draw(key: key.text, id: key.id, size: Self.size(for: ref.type))
        cache.setObject(image, forKey: key.text as NSString)
        return image
    }

    /// Artwork for an image loaded by server path (the few places that
    /// do not use an image reference).
    @MainActor
    public static func image(forPath path: String) -> UIImage {
        if let hit = cache.object(forKey: path as NSString) { return hit }
        let clean = path.split(separator: "?").first.map(String.init) ?? path
        let digits = clean.split(whereSeparator: { !$0.isNumber }).last.flatMap { Int64($0) }
        let wide = clean.contains("backdrop") || clean.contains("/cam/")
        let square = clean.contains("headshot") || clean.contains("album-art")
        let size = wide ? CGSize(width: 1280, height: 720)
            : square ? CGSize(width: 400, height: 400) : CGSize(width: 400, height: 600)
        let image = draw(key: clean, id: digits, size: size)
        cache.setObject(image, forKey: path as NSString)
        return image
    }

    @MainActor private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 600
        return c
    }()

    // MARK: Keys and shapes

    private static func key(for ref: MMImageRef) -> (text: String, id: Int64?) {
        let kind: String
        switch ref.type {
        // Thumbnail and full poster of a title share one picture.
        case .posterThumbnail, .posterFull: kind = "poster"
        case .backdrop: kind = "backdrop"
        case .headshot: kind = "headshot"
        case .collectionPoster: kind = "collection"
        case .localImage: kind = "local"
        case .ownershipPhoto: kind = "ownership"
        case .cameraSnapshot: kind = "camera"
        case .tmdbPoster: kind = "tmdb"
        case .artistHeadshot: kind = "artist"
        case .authorHeadshot: kind = "author"
        case .caaReleaseGroup: kind = "release-group"
        case .openlibraryCover: kind = "openlibrary"
        default: kind = "image"
        }
        let numeric: Int64? = [
            ref.titleID, Int64(ref.tmdbPersonID), Int64(ref.tmdbCollectionID), ref.cameraID,
            ref.artistID, ref.authorID, ref.hasTmdbMedia ? Int64(ref.tmdbMedia.tmdbID) : 0,
        ].first { $0 != 0 }
        if let numeric { return ("\(kind)/\(numeric)", numeric) }
        let text = [ref.uuid, ref.musicbrainzReleaseGroupID, ref.openlibraryWorkID].first { !$0.isEmpty } ?? ""
        return ("\(kind)/\(text)", nil)
    }

    private static func size(for type: MMImageType) -> CGSize {
        switch type {
        case .backdrop, .cameraSnapshot: return CGSize(width: 1280, height: 720)
        case .headshot, .artistHeadshot, .authorHeadshot: return CGSize(width: 400, height: 400)
        case .caaReleaseGroup: return CGSize(width: 600, height: 600)
        default: return CGSize(width: 400, height: 600)
        }
    }

    // MARK: Drawing

    private static func draw(key: String, id: Int64?, size: CGSize) -> UIImage {
        let w = size.width, h = size.height
        var rng = Mulberry32(seed: fnv1a(key))
        // The id sets the base hue, as on the web, so neighbours differ.
        let hue: Double = id.map { Double(($0 &* 47) % 360).magnitude } ?? floor(rng.next() * 360)
        func pick(_ lo: Double, _ hi: Double) -> Double { lo + rng.next() * (hi - lo) }
        func tone(_ shift: Double, _ s: Double, _ l: Double, _ a: Double) -> UIColor {
            hsl((hue + shift + 360).truncatingRemainder(dividingBy: 360), s / 100, l / 100, a)
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { rendererContext in
            let ctx = rendererContext.cgContext
            let bounds = CGRect(origin: .zero, size: size)
            ctx.clip(to: bounds)

            // Background: a two-stop gradient across the frame at a
            // random angle.
            let angle = floor(pick(0, 360)) * .pi / 180
            let dx = cos(angle) / 2, dy = sin(angle) / 2
            let colors = [tone(0, 42, 34, 1).cgColor, tone(40, 48, 15, 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                ctx.drawLinearGradient(
                    gradient,
                    start: CGPoint(x: (0.5 - dx) * w, y: (0.5 - dy) * h),
                    end: CGPoint(x: (0.5 + dx) * w, y: (0.5 + dy) * h),
                    options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            }

            // Five to eight translucent shapes.
            let shifts: [Double] = [0, 30, -30, 150, 180, 210]
            let count = 5 + Int(rng.next() * 4)
            for _ in 0..<count {
                let kind = rng.next()
                let cx = pick(-0.1, 1.1) * w
                let cy = pick(-0.1, 1.1) * h
                let r = pick(0.12, 0.42) * min(w, h)
                let shift = shifts[Int(rng.next() * 6) % shifts.count]
                let color = tone(shift, floor(pick(45, 70)), floor(pick(35, 62)), pick(0.14, 0.34))
                if kind < 0.3 {
                    ctx.setFillColor(color.cgColor)
                    ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
                } else if kind < 0.5 {
                    ctx.setStrokeColor(color.cgColor)
                    ctx.setLineWidth(pick(0.05, 0.14) * r)
                    ctx.strokeEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
                } else if kind < 0.8 {
                    let sides = 3 + Int(rng.next() * 4)
                    let rotation = pick(0, .pi * 2)
                    let path = CGMutablePath()
                    for k in 0..<sides {
                        let a = rotation + Double(k) / Double(sides) * .pi * 2
                        let point = CGPoint(x: cx + cos(a) * r, y: cy + sin(a) * r)
                        if k == 0 { path.move(to: point) } else { path.addLine(to: point) }
                    }
                    path.closeSubpath()
                    ctx.addPath(path)
                    ctx.setFillColor(color.cgColor)
                    ctx.fillPath()
                } else {
                    let band = pick(0.06, 0.18) * max(w, h)
                    let rotation = floor(pick(-60, 60)) * .pi / 180
                    ctx.saveGState()
                    ctx.translateBy(x: cx, y: cy)
                    ctx.rotate(by: rotation)
                    ctx.setFillColor(color.cgColor)
                    ctx.fill(CGRect(x: -w, y: -band / 2, width: w * 2, height: band))
                    ctx.restoreGState()
                }
            }

            // Dot grain, then a hairline edge.
            let dot = max(2, (min(w, h) / 120).rounded())
            ctx.setFillColor(UIColor(white: 1, alpha: 0.05).cgColor)
            var y = dot
            while y < h {
                var x = dot
                while x < w {
                    ctx.fillEllipse(in: CGRect(x: x - dot / 2, y: y - dot / 2, width: dot, height: dot))
                    x += dot * 3
                }
                y += dot * 3
            }
            ctx.setStrokeColor(UIColor(white: 1, alpha: 0.12).cgColor)
            ctx.setLineWidth(1)
            ctx.stroke(bounds.insetBy(dx: 0.5, dy: 0.5))
        }
    }

    private static func hsl(_ hDegrees: Double, _ s: Double, _ l: Double, _ a: Double) -> UIColor {
        // HSL to HSB, which UIColor takes.
        let v = l + s * min(l, 1 - l)
        let sb = v == 0 ? 0 : 2 * (1 - l / v)
        return UIColor(hue: hDegrees / 360, saturation: sb, brightness: v, alpha: a)
    }

    private static func fnv1a(_ s: String) -> UInt32 {
        var h: UInt32 = 0x811c9dc5
        for byte in s.utf8 {
            h ^= UInt32(byte)
            h = h &* 0x01000193
        }
        return h
    }

    /// mulberry32: a small deterministic generator, uniform in [0, 1).
    private struct Mulberry32 {
        var state: UInt32
        init(seed: UInt32) { state = seed }
        mutating func next() -> Double {
            state = state &+ 0x6d2b79f5
            var t = state
            t = (t ^ (t >> 15)) &* (t | 1)
            t ^= t &+ ((t ^ (t >> 7)) &* (t | 61))
            return Double(t ^ (t >> 14)) / 4294967296
        }
    }
}
