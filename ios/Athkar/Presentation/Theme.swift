import SwiftUI

/// The app's own palette rather than the system default: an athkar app is read slowly and often at
/// night, so the surfaces are warm and low-contrast by day and genuinely dark — not grey — after
/// sunset, with a single gold accent reserved for the element that matters on each screen.
enum Theme {

    static let emeraldDeep = Color(red: 0.043, green: 0.365, blue: 0.290)
    static let emeraldBright = Color(red: 0.373, green: 0.827, blue: 0.690)
    static let goldDeep = Color(red: 0.604, green: 0.482, blue: 0.071)
    static let goldBright = Color(red: 0.898, green: 0.757, blue: 0.345)

    static func primary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? emeraldBright : emeraldDeep
    }

    static func gold(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? goldBright : goldDeep
    }

    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 0.051, green: 0.078, blue: 0.067)
            : Color(red: 0.984, green: 0.973, blue: 0.945)
    }

    static func surface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0.086, green: 0.125, blue: 0.110) : .white
    }

    static func heroGradient(_ scheme: ColorScheme) -> LinearGradient {
        LinearGradient(
            colors: scheme == .dark
                ? [Color(red: 0.067, green: 0.275, blue: 0.227), Color(red: 0.039, green: 0.149, blue: 0.125)]
                : [emeraldDeep, Color(red: 0.039, green: 0.267, blue: 0.220)],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

/// Arabic with full tashkeel needs far more vertical room than Latin text: the marks sit above and
/// below the baseline and collide between lines at the system's default leading.
extension View {
    func arabicBody() -> some View {
        font(.system(size: 19))
            .lineSpacing(12)
            .multilineTextAlignment(.leading)
    }
}
