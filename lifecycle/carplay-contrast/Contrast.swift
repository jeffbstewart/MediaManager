// Measures the contrast of every line of text in a screenshot against
// the background behind it, the way WCAG defines contrast. Built for
// CarPlay screenshots from lifecycle/carplay-shot.sh, but knows nothing
// about CarPlay: it works on any PNG.
//
//   carplay-contrast <png> [x0 y0 x1 y1] [--min RATIO]
//
// The optional rectangle (pixel coordinates) limits the check, for
// example to the car screen inside the simulator window. Exits 1 when
// any line is below --min (default 4.5, WCAG AA for normal text) or no
// text is found at all, 0 when every line passes, 2 on bad input.
//
// Text is found with the Vision framework's on-device text recognition
// (no permissions, nothing leaves the Mac). Within each line's box the
// background is the most common colour and the text colour is the
// pixel furthest from it in luminance: the core of the glyph strokes,
// not their antialiased edges. Every line is printed with what was
// read, so a line Vision missed is visible by its absence.

import CoreGraphics
import Foundation
import ImageIO
import Vision

struct RGB: Hashable {
    let r: UInt8, g: UInt8, b: UInt8
    var hex: String { String(format: "#%02x%02x%02x", r, g, b) }
    var luminance: Double {
        func lin(_ v: UInt8) -> Double {
            let c = Double(v) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }
}

func contrast(_ a: Double, _ b: Double) -> Double {
    (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("carplay-contrast: \(message)\n".utf8))
    exit(2)
}

var args = Array(CommandLine.arguments.dropFirst())
var minimum = 4.5
if let i = args.firstIndex(of: "--min") {
    guard i + 1 < args.count, let v = Double(args[i + 1]) else { fail("--min needs a number") }
    minimum = v
    args.removeSubrange(i...(i + 1))
}
guard args.count == 1 || args.count == 5 else {
    fail("usage: carplay-contrast <png> [x0 y0 x1 y1] [--min RATIO]")
}

let url = URL(fileURLWithPath: args[0]) as CFURL
guard let source = CGImageSourceCreateWithURL(url, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fail("cannot read \(args[0])") }
let width = image.width, height = image.height

var region = CGRect(x: 0, y: 0, width: width, height: height)
if args.count == 5 {
    guard let a = Int(args[1]), let b = Int(args[2]), let c = Int(args[3]), let d = Int(args[4]),
          a >= 0, b >= 0, c <= width, d <= height, a < c, b < d else {
        fail("rectangle must lie inside the \(width)x\(height) image")
    }
    region = CGRect(x: a, y: b, width: c - a, height: d - b)
}
guard let cropped = image.cropping(to: region) else { fail("cannot crop") }
let cw = cropped.width, ch = cropped.height

var pixels = [UInt8](repeating: 0, count: cw * ch * 4)
guard let space = CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(data: &pixels, width: cw, height: ch, bitsPerComponent: 8,
                              bytesPerRow: cw * 4, space: space,
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { fail("no context") }
context.draw(cropped, in: CGRect(x: 0, y: 0, width: cw, height: ch))

func pixel(_ x: Int, _ y: Int) -> RGB {
    let i = (y * cw + x) * 4
    return RGB(r: pixels[i], g: pixels[i + 1], b: pixels[i + 2])
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
do {
    try VNImageRequestHandler(cgImage: cropped).perform([request])
} catch {
    fail("text recognition failed: \(error)")
}

struct Line { let text: String, x: Int, y: Int, ink: RGB, background: RGB, ratio: Double }
var lines: [Line] = []
for observation in request.results ?? [] {
    guard let text = observation.topCandidates(1).first?.string else { continue }
    // Vision's box is normalised with the origin at the bottom left.
    let box = VNImageRectForNormalizedRect(observation.boundingBox, cw, ch)
    let bx0 = max(0, Int(box.minX)), bx1 = min(cw, Int(box.maxX.rounded(.up)))
    let by0 = max(0, ch - Int(box.maxY.rounded(.up))), by1 = min(ch, ch - Int(box.minY))
    guard bx1 > bx0, by1 > by0 else { continue }

    var counts: [RGB: Int] = [:]
    for y in by0..<by1 { for x in bx0..<bx1 { counts[pixel(x, y), default: 0] += 1 } }
    guard let background = counts.max(by: { $0.value < $1.value })?.key else { continue }
    let bgLum = background.luminance
    var ink = background, ratio = 1.0
    for color in counts.keys {
        let r = contrast(color.luminance, bgLum)
        if r > ratio { (ink, ratio) = (color, r) }
    }
    lines.append(Line(text: text, x: bx0 + Int(region.minX), y: by0 + Int(region.minY),
                      ink: ink, background: background, ratio: ratio))
}

lines.sort { ($0.y, $0.x) < ($1.y, $1.x) }
print(String(format: "%d lines of text in %d,%d %dx%d; minimum %.2f:1",
             lines.count, Int(region.minX), Int(region.minY), cw, ch, minimum))
for line in lines {
    let verdict = line.ratio < minimum ? "FAIL" : "ok  "
    print(String(format: "  %@ %5.2f:1  %@ on %@  at %4d,%-4d  %@", verdict, line.ratio,
                 line.ink.hex, line.background.hex, line.x, line.y, line.text))
}
if lines.isEmpty {
    print("FAIL: no text found")
    exit(1)
}
let failing = lines.filter { $0.ratio < minimum }
if failing.isEmpty {
    print("PASS")
    exit(0)
}
print("FAIL: \(failing.count) of \(lines.count) lines below \(String(format: "%.2f", minimum)):1")
exit(1)
