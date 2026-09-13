import Foundation
import UserNotifications

/// Schedules the prayer-time alerts.
///
/// iOS gives an app no reliable moment to reschedule in the background, and prayer times move every
/// day, so a repeating trigger cannot express them. The answer is a long rolling window: every
/// enabled prayer for the next [windowDays] is scheduled as its own dated trigger, and the whole
/// window is rebuilt each time the app comes to the foreground. iOS keeps only the 64 soonest
/// pending notifications, which is what caps the window.
enum PrayerNotificationScheduler {

    /// The bundled adhan, thirty seconds of it.
    ///
    /// Thirty because that is iOS's hard ceiling for a notification sound: past it the system
    /// silently substitutes the default tone, which would be worse than choosing a length. The
    /// Android app plays the whole two and a half minutes through a foreground service, and iOS
    /// gives an app no equivalent — an app that is not running cannot play audio at all, so the
    /// notification's own sound is the entire mechanism available.
    static let adhanSoundFile = "adhan.caf"

    /// Six prayers a day — five called plus the warning — against iOS's ceiling of 64 pending
    /// notifications. Seven days leaves room for the pre-adhan warnings to have their own slots.
    private static let windowDays = 7

    private static let center = UNUserNotificationCenter.current()

    /// Asks for permission. Returns whether alerts may now be posted.
    static func requestAuthorization() async -> Bool {
        do {
            return try await center.requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            return false
        }
    }

    static func authorizationGranted() async -> Bool {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }

    /// Rebuilds the whole window from the current settings. Safe to call on every foreground.
    static func reschedule(with preferences: PreferencesStore) async {
        center.removeAllPendingNotificationRequests()

        guard preferences.notificationsEnabled,
              !preferences.notifiedPrayers.isEmpty,
              let coordinates = preferences.place?.coordinates,
              await authorizationGranted()
        else { return }

        let calendar = Calendar.current
        let parameters = preferences.calculationParameters
        let now = Date()

        for dayOffset in 0..<windowDays {
            guard let day = calendar.date(byAdding: .day, value: dayOffset, to: now) else { continue }
            let parts = calendar.dateComponents([.year, .month, .day], from: day)
            guard let year = parts.year, let month = parts.month, let dayOfMonth = parts.day,
                  let times = try? PrayerTimes.calculate(
                    coordinates: coordinates,
                    year: year, month: month, day: dayOfMonth,
                    parameters: parameters
                  )
            else { continue }

            for prayer in Prayer.allCases where preferences.notifiedPrayers.contains(prayer) {
                let at = times.time(for: prayer)
                if at > now {
                    await add(
                        prayer: prayer, at: at, kind: .adhan,
                        placeName: preferences.place?.name,
                        withSound: preferences.adhanSound,
                        calendar: calendar
                    )
                }

                // The warning before the time, for whoever wants to reach the mosque rather than
                // be told they are already late. Sunrise gets none: nobody is being called to it.
                let minutesBefore = preferences.preAdhanMinutes
                if minutesBefore > 0, prayer != .sunrise {
                    let warnAt = at.addingTimeInterval(-Double(minutesBefore) * 60)
                    if warnAt > now {
                        await add(
                            prayer: prayer, at: warnAt, kind: .warning(minutes: minutesBefore),
                            placeName: preferences.place?.name,
                            // The warning is a nudge, not the call. Raising the adhan for it would
                            // make the actual adhan the second time the user heard it.
                            withSound: false,
                            calendar: calendar
                        )
                    }
                }
            }
        }
    }

    static func cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    /// What an alert is for: the call itself, or the warning that it is coming.
    private enum Kind {
        case adhan
        case warning(minutes: Int)

        var slug: String {
            switch self {
            case .adhan: return "adhan"
            case .warning: return "warning"
            }
        }
    }

    private static func add(
        prayer: Prayer,
        at date: Date,
        kind: Kind,
        placeName: String?,
        withSound: Bool,
        calendar: Calendar
    ) async {
        let content = UNMutableNotificationContent()
        switch kind {
        case .adhan:
            content.title = "حان الآن وقت صلاة \(prayer.arabicName)"
            content.body = [Formatting.time(date), placeName]
                .compactMap { $0 }
                .joined(separator: " — ")
        case .warning(let minutes):
            content.title = "اقترب وقت \(prayer.arabicName)"
            content.body = "بعد \(minutes) دقيقة — \(Formatting.time(date.addingTimeInterval(Double(minutes) * 60)))"
        }

        // Sunrise is never the adhan: it ends Fajr rather than calling anyone to prayer, so it
        // arrives as a plain alert however the sound setting is left.
        content.sound = withSound && prayer != .sunrise
            ? UNNotificationSound(named: UNNotificationSoundName(adhanSoundFile))
            : nil

        let components = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: date
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        // The identifier is the prayer, the kind and the day, so rebuilding the window replaces
        // rather than duplicates a notification the user has already been promised — and the
        // warning does not overwrite the call it is warning about.
        let identifier = [
            "prayer", prayer.rawValue, kind.slug,
            "\(components.year ?? 0)-\(components.month ?? 0)-\(components.day ?? 0)",
        ].joined(separator: "-")

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        try? await center.add(request)
    }
}
