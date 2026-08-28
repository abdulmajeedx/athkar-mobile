import Foundation

/// Arabic date and time formatting done explicitly rather than through the device locale, so the
/// output is identical whatever language the phone is set to — the app's UI is Arabic regardless.
enum Formatting {

    private static let hijriMonths = [
        "محرّم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوّال", "ذو القعدة", "ذو الحجة",
    ]

    private static let gregorianMonths = [
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    ]

    /// Indexed by `Calendar`'s weekday, which starts at 1 = Sunday.
    private static let weekdays = [
        "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت",
    ]

    /// Clock time as `h:mm ص/م`.
    static func time(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let hour24 = parts.hour ?? 0
        let minute = parts.minute ?? 0
        let hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12
        return String(format: "%d:%02d %@", hour12, minute, hour24 < 12 ? "ص" : "م")
    }

    /// A countdown as `h:mm:ss`, or `mm:ss` under an hour.
    static func countdown(_ interval: TimeInterval) -> String {
        let total = Int(max(interval, 0))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%02d:%02d", minutes, seconds)
    }

    /// `الجمعة، 5 رمضان 1446 هـ`, on the Umm al-Qura calendar Saudi Arabia publishes.
    static func hijriDate(_ date: Date) -> String {
        var hijri = Calendar(identifier: .islamicUmmAlQura)
        hijri.timeZone = .current
        let parts = hijri.dateComponents([.day, .month, .year], from: date)
        let weekday = Calendar.current.component(.weekday, from: date)
        let month = (parts.month ?? 1) - 1
        let monthName = hijriMonths.indices.contains(month) ? hijriMonths[month] : ""
        let weekdayName = weekdays.indices.contains(weekday - 1) ? weekdays[weekday - 1] : ""
        return "\(weekdayName)، \(parts.day ?? 0) \(monthName) \(parts.year ?? 0) هـ"
    }

    /// `28 أغسطس 2026 م`.
    static func gregorianDate(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.day, .month, .year], from: date)
        let month = (parts.month ?? 1) - 1
        let monthName = gregorianMonths.indices.contains(month) ? gregorianMonths[month] : ""
        return "\(parts.day ?? 0) \(monthName) \(parts.year ?? 0) م"
    }

    /// Distance in whole kilometres, with a thousands separator.
    static func distanceKm(_ km: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        let value = formatter.string(from: NSNumber(value: km)) ?? String(Int(km))
        return "\(value) كم"
    }

    /// A compass bearing to one decimal place.
    static func bearing(_ degrees: Double) -> String { String(format: "%.1f°", degrees) }
}
