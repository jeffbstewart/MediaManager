import Foundation

extension FormatStyle where Self == FloatingPointFormatStyle<Double>.Percent {
    /// A fraction (0…1) as a whole percentage in the user's locale:
    /// where the % goes and how it's spaced vary ("50 %", "%50").
    /// Rounds down, so nothing reads 100% until it is.
    static var wholePercent: Self {
        .percent.precision(.fractionLength(0)).rounded(rule: .towardZero)
    }
}
