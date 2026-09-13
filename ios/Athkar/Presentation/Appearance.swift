import SwiftUI

/// Which palette the app wears.
///
/// `byTime` is the default and the reason the others exist as an escape from it: it follows the sky
/// the user is actually standing under, so the app is a light page through the morning and a dark
/// one after Maghrib, changing at the prayers rather than at midnight. That is right for most people
/// and wrong for anyone who reads adhkar in bed with the lights off, so the fixed choices stay.
enum AppTheme: String, CaseIterable, Codable, Identifiable, Sendable {
    case byTime
    case light
    case dark
    case system

    var id: String { rawValue }

    static let `default` = AppTheme.byTime

    /// A theme written by a later version of the app reads as absent rather than as a crash.
    static func from(name: String?) -> AppTheme {
        guard let name, let theme = AppTheme(rawValue: name) else { return .default }
        return theme
    }

    var label: String {
        switch self {
        case .byTime: return "حسب وقت الصلاة"
        case .light: return "فاتح"
        case .dark: return "داكن"
        case .system: return "حسب النظام"
        }
    }

    var explanation: String {
        switch self {
        case .byTime: return "يتغيّر لون التطبيق مع الوقت: للفجر لون وللظهر آخر، ويُظلم بعد المغرب."
        case .light: return "صفحة فاتحة في كل الأوقات."
        case .dark: return "خلفية داكنة في كل الأوقات، أريح للعين ليلًا."
        case .system: return "يتبع الوضع الداكن في إعدادات الجهاز."
        }
    }

    /// What to force on the view tree, or nil to leave the system's own choice alone.
    func colorScheme(sky: SkyPhase) -> ColorScheme? {
        switch self {
        case .byTime: return sky.isNight ? .dark : .light
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }
}

extension SkyPhase {

    /// Which sky it is here, now.
    ///
    /// From the user's own prayer times when a place is known, because that is the whole point —
    /// the app turns to its evening colours when Maghrib arrives where the user is standing, not at
    /// a clock hour that means sunset in one country and full daylight in another. Before a place
    /// is chosen, and if the day cannot be computed at all, it falls back to the clock: a rough sky
    /// is still a truer picture than one fixed colour.
    static func current(
        coordinates: Coordinates?,
        parameters: CalculationParameters,
        at moment: Date,
        calendar: Calendar = .current
    ) -> SkyPhase {
        let hour = calendar.component(.hour, from: moment)
        guard let coordinates else { return forHour(hour) }

        let parts = calendar.dateComponents([.year, .month, .day], from: moment)
        guard let year = parts.year, let month = parts.month, let day = parts.day,
              let times = try? PrayerTimes.calculate(
                coordinates: coordinates, year: year, month: month, day: day, parameters: parameters
              )
        else { return forHour(hour) }

        if let current = times.currentPrayer(at: moment) { return forPrayer(current) }

        // Before today's Fajr the standing prayer is yesterday's Isha, so it is still night — until
        // the hour before dawn, which has a sky of its own and is when this app is most often open.
        let untilFajr = times.time(for: .fajr).timeIntervalSince(moment)
        return untilFajr <= 90 * 60 ? .nightEnd : .night
    }
}

/// The hour the app is painted in, read by every screen rather than derived again in each.
private struct SkyPhaseKey: EnvironmentKey {
    static let defaultValue = SkyPhase.night
}

extension EnvironmentValues {
    var skyPhase: SkyPhase {
        get { self[SkyPhaseKey.self] }
        set { self[SkyPhaseKey.self] = newValue }
    }
}

/// A surface carrying the hour's gradient, which is how the sky reaches the screens.
///
/// Android lays a girih pattern over the same gradient; that tile is an Android drawable and is not
/// bundled here, so this is the gradient alone rather than a second, different-looking ornament
/// invented for iOS.
struct SkySurface<Content: View>: View {

    @Environment(\.skyPhase) private var sky
    private let corner: CGFloat
    private let content: Content

    init(corner: CGFloat = 28, @ViewBuilder content: () -> Content) {
        self.corner = corner
        self.content = content()
    }

    var body: some View {
        content
            .background(sky.gradient)
            .clipShape(RoundedRectangle(cornerRadius: corner))
            // Long enough to read as the light changing rather than as a repaint.
            .animation(.easeInOut(duration: 0.9), value: sky)
    }
}
