import SwiftUI

/// The colours of the sky at each prayer.
///
/// A prayer-times app whose surfaces stay one fixed colour all day is telling the user nothing.
/// These are the hours the sky itself is named after, so the app takes its colour from the one the
/// user is standing in: indigo before dawn, amber at sunrise, a high clear blue at noon, gold
/// through the afternoon, the orange-to-violet collapse at sunset, and night after that.
///
/// The values are the same six as the Android app's, to the byte, so the two ports cannot drift
/// into being different-looking apps with the same name.
enum SkyPhase: String, CaseIterable, Identifiable, Sendable {

    /// Before dawn: the deep blue that has not started to warm.
    case nightEnd
    /// Fajr: indigo giving way at the horizon.
    case dawn
    /// Sunrise: the amber hour.
    case sunrise
    /// Dhuhr: high sun, the clearest blue of the day.
    case noon
    /// Asr: the light has turned and gone warm.
    case afternoon
    /// Maghrib: orange collapsing into violet.
    case sunset
    /// Isha: full night.
    case night

    var id: String { rawValue }

    var top: Color {
        switch self {
        case .nightEnd: return Color(hex: 0x141A3A)
        case .dawn: return Color(hex: 0x2A2A63)
        case .sunrise: return Color(hex: 0xB5602F)
        case .noon: return Color(hex: 0x1D6FA8)
        case .afternoon: return Color(hex: 0x1F6B63)
        case .sunset: return Color(hex: 0x8A3A2E)
        case .night: return Color(hex: 0x10203A)
        }
    }

    var bottom: Color {
        switch self {
        case .nightEnd: return Color(hex: 0x0B0F26)
        case .dawn: return Color(hex: 0x6B3F6B)
        case .sunrise: return Color(hex: 0x7A3B2E)
        case .noon: return Color(hex: 0x124A73)
        case .afternoon: return Color(hex: 0x124A4A)
        case .sunset: return Color(hex: 0x3E2350)
        case .night: return Color(hex: 0x070C1A)
        }
    }

    /// Accent that stays legible on this sky — the countdown, the next prayer, the qibla marker.
    var accent: Color {
        switch self {
        case .nightEnd: return Color(hex: 0xB8C4FF)
        case .dawn: return Color(hex: 0xFFC9A3)
        case .sunrise: return Color(hex: 0xFFE0A3)
        case .noon: return Color(hex: 0xFFE9A8)
        case .afternoon: return Color(hex: 0xFFD98A)
        case .sunset: return Color(hex: 0xFFCF8F)
        case .night: return Color(hex: 0xE5C158)
        }
    }

    /// True when the sun is down.
    ///
    /// Every one of these gradients is deep enough to need white text on it; this is about the
    /// world outside the phone, and decides whether the rest of the app is a light page or a dark
    /// one.
    var isNight: Bool {
        switch self {
        case .sunrise, .noon, .afternoon: return false
        case .nightEnd, .dawn, .sunset, .night: return true
        }
    }

    /// What to call this hour when the user is choosing a theme and wants to see what they get.
    var arabicName: String {
        switch self {
        case .nightEnd: return "السَّحَر"
        case .dawn: return "الفجر"
        case .sunrise: return "الشروق"
        case .noon: return "الظهر"
        case .afternoon: return "العصر"
        case .sunset: return "المغرب"
        case .night: return "العشاء"
        }
    }

    var gradient: LinearGradient {
        LinearGradient(colors: [top, bottom], startPoint: .top, endPoint: .bottom)
    }

    /// The sky for a prayer that has begun. Sunrise is its own phase rather than part of Fajr: it
    /// is the moment the colour actually changes, and the app is showing it anyway.
    static func forPrayer(_ prayer: Prayer?) -> SkyPhase {
        // Before the day's first prayer there is no current one, and that hour has its own sky.
        guard let prayer else { return .nightEnd }
        switch prayer {
        case .fajr: return .dawn
        case .sunrise: return .sunrise
        case .dhuhr: return .noon
        case .asr: return .afternoon
        case .maghrib: return .sunset
        case .isha: return .night
        }
    }

    /// The sky by the clock, for before a place is known.
    ///
    /// A rough stand-in and admittedly so — real prayer times move by hours across a year and a
    /// continent. It exists because the alternative on first launch is a single fixed colour, and
    /// an app that is midnight-blue at noon is more wrong than one an hour early into Asr.
    static func forHour(_ hour: Int) -> SkyPhase {
        switch hour {
        case 0...3: return .night
        case 4: return .nightEnd
        case 5: return .dawn
        case 6...7: return .sunrise
        case 8...14: return .noon
        case 15...17: return .afternoon
        case 18...19: return .sunset
        default: return .night
        }
    }
}

extension Color {
    /// `0xRRGGBB`, so the palette can be written as the same hex the Android side uses.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
