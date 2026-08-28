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

    /// Ten days of five prayers stays under the 64-notification ceiling with room to spare.
    private static let windowDays = 10

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
                guard at > now else { continue }
                await add(prayer: prayer, at: at, placeName: preferences.place?.name, calendar: calendar)
            }
        }
    }

    static func cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    private static func add(prayer: Prayer, at date: Date, placeName: String?, calendar: Calendar) async {
        let content = UNMutableNotificationContent()
        content.title = "حان الآن وقت صلاة \(prayer.arabicName)"
        content.body = [Formatting.time(date), placeName].compactMap { $0 }.joined(separator: " — ")
        content.sound = .default

        let components = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: date
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        // The identifier is the prayer and its day, so rebuilding the window replaces rather than
        // duplicates a notification the user has already been promised.
        let identifier = "prayer-\(prayer.rawValue)-\(components.year ?? 0)-\(components.month ?? 0)-\(components.day ?? 0)"

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        try? await center.add(request)
    }
}
