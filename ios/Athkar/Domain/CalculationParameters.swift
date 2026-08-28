import Foundation

/// Which shadow length marks the start of Asr.
enum Madhab: String, CaseIterable, Codable, Sendable {
    /// Shafi'i, Maliki, Hanbali: one shadow length.
    case shafi
    /// Hanafi: two shadow lengths.
    case hanafi

    var shadowLength: Int { self == .shafi ? 1 : 2 }

    var arabicName: String { self == .shafi ? "الجمهور" : "الحنفي" }
}

/// How to place Fajr and Isha where the sun never descends far enough below the horizon for the
/// defining twilight angle to occur — the "abnormal period" of high latitudes.
enum HighLatitudeRule: String, CaseIterable, Codable, Sendable {
    /// Night is split in half; Fajr and Isha may not be closer to midnight than that.
    case middleOfTheNight
    /// Fajr no earlier than 1/7 of the night before sunrise, Isha no later than 1/7 after sunset.
    case seventhOfTheNight
    /// The night fraction is derived from the method's own twilight angle.
    case twilightAngle

    /// The rule conventionally applied at a given latitude: the angle-based rule is closest to
    /// observation, but below roughly 48° the three barely differ.
    static func recommended(for coordinates: Coordinates) -> HighLatitudeRule {
        abs(coordinates.latitude) > 48 ? .twilightAngle : .middleOfTheNight
    }
}

/// Minute offsets applied to each computed time. Methods carry their own conventional rounding here
/// (several publish Dhuhr a minute or more after true transit).
struct PrayerAdjustments: Equatable, Sendable {
    var fajr = 0
    var sunrise = 0
    var dhuhr = 0
    var asr = 0
    var maghrib = 0
    var isha = 0

    static func + (lhs: PrayerAdjustments, rhs: PrayerAdjustments) -> PrayerAdjustments {
        PrayerAdjustments(
            fajr: lhs.fajr + rhs.fajr,
            sunrise: lhs.sunrise + rhs.sunrise,
            dhuhr: lhs.dhuhr + rhs.dhuhr,
            asr: lhs.asr + rhs.asr,
            maghrib: lhs.maghrib + rhs.maghrib,
            isha: lhs.isha + rhs.isha
        )
    }
}

/// The full parameter set a prayer-time calculation needs.
struct CalculationParameters: Equatable, Sendable {
    /// Sun's depression below the horizon at Fajr, degrees.
    var fajrAngle: Double
    /// Sun's depression at Isha, degrees. Ignored when `ishaInterval` is set.
    var ishaAngle: Double = 0
    /// Fixed minutes after Maghrib, used by methods that define Isha that way. Zero means use the angle.
    var ishaInterval: Int = 0
    /// Sun's depression at Maghrib for the methods that do not use sunset.
    var maghribAngle: Double = 0
    var madhab: Madhab = .shafi
    var highLatitudeRule: HighLatitudeRule = .middleOfTheNight
    var adjustments = PrayerAdjustments()
    var methodAdjustments = PrayerAdjustments()

    /// Portion of the night after which Fajr/Isha are clamped when the twilight angle never occurs.
    var nightPortions: (fajr: Double, isha: Double) {
        switch highLatitudeRule {
        case .middleOfTheNight: return (0.5, 0.5)
        case .seventhOfTheNight: return (1.0 / 7.0, 1.0 / 7.0)
        case .twilightAngle: return (fajrAngle / 60.0, ishaAngle / 60.0)
        }
    }
}

/// Published parameter sets. Choosing the method your local authority uses matters more than any
/// refinement in the astronomy: the spread between methods reaches ~20 minutes at Fajr and Isha.
enum CalculationMethod: String, CaseIterable, Codable, Identifiable, Sendable {
    case ummAlQura
    case muslimWorldLeague
    case egyptian
    case karachi
    case northAmerica
    case dubai
    case qatar
    case kuwait
    case singapore
    case turkey
    case tehran

    var id: String { rawValue }

    var arabicName: String {
        switch self {
        case .ummAlQura: return "أم القرى — مكة المكرمة"
        case .muslimWorldLeague: return "رابطة العالم الإسلامي"
        case .egyptian: return "الهيئة المصرية العامة للمساحة"
        case .karachi: return "جامعة العلوم الإسلامية — كراتشي"
        case .northAmerica: return "الجمعية الإسلامية لأمريكا الشمالية"
        case .dubai: return "دبي — الإمارات"
        case .qatar: return "دار التقويم القطرية"
        case .kuwait: return "الكويت"
        case .singapore: return "سنغافورة"
        case .turkey: return "ديانت — تركيا"
        case .tehran: return "جامعة طهران"
        }
    }

    /// The parameter set for this method, before user adjustments are applied.
    var parameters: CalculationParameters {
        switch self {
        case .ummAlQura:
            return CalculationParameters(fajrAngle: 18.5, ishaInterval: 90)
        case .muslimWorldLeague:
            return CalculationParameters(
                fajrAngle: 18, ishaAngle: 17,
                methodAdjustments: PrayerAdjustments(dhuhr: 1)
            )
        case .egyptian:
            return CalculationParameters(
                fajrAngle: 19.5, ishaAngle: 17.5,
                methodAdjustments: PrayerAdjustments(dhuhr: 1)
            )
        case .karachi:
            return CalculationParameters(
                fajrAngle: 18, ishaAngle: 18,
                methodAdjustments: PrayerAdjustments(dhuhr: 1)
            )
        case .northAmerica:
            return CalculationParameters(
                fajrAngle: 15, ishaAngle: 15,
                methodAdjustments: PrayerAdjustments(dhuhr: 1)
            )
        case .dubai:
            return CalculationParameters(
                fajrAngle: 18.2, ishaAngle: 18.2,
                methodAdjustments: PrayerAdjustments(sunrise: -3, dhuhr: 3, asr: 3, maghrib: 3)
            )
        case .qatar:
            return CalculationParameters(fajrAngle: 18, ishaInterval: 90)
        case .kuwait:
            return CalculationParameters(fajrAngle: 18, ishaAngle: 17.5)
        case .singapore:
            return CalculationParameters(
                fajrAngle: 20, ishaAngle: 18,
                methodAdjustments: PrayerAdjustments(dhuhr: 1)
            )
        case .turkey:
            return CalculationParameters(
                fajrAngle: 18, ishaAngle: 17,
                methodAdjustments: PrayerAdjustments(sunrise: -7, dhuhr: 5, asr: 4, maghrib: 7)
            )
        case .tehran:
            return CalculationParameters(fajrAngle: 17.7, ishaAngle: 14, maghribAngle: 4.5)
        }
    }
}
