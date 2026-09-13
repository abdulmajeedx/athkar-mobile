import Foundation
import Combine

/// Everything the user has chosen, persisted in `UserDefaults`.
///
/// `UserDefaults` rather than a database because all of it is a handful of scalars and a set of
/// ids: the corpus itself is read-only bundled content, so there is nothing here worth the cost of
/// a store.
final class PreferencesStore: ObservableObject {

    @Published private(set) var method: CalculationMethod
    @Published private(set) var madhab: Madhab
    @Published private(set) var place: Place?
    @Published private(set) var notificationsEnabled: Bool
    @Published private(set) var notifiedPrayers: Set<Prayer>
    @Published private(set) var favourites: Set<String>
    /// Minutes before the adhan to give a warning, or zero for none.
    @Published private(set) var preAdhanMinutes: Int
    /// Minutes after each adhan that the congregation stands, keyed by prayer.
    @Published private(set) var iqamaMinutes: [Prayer: Int]
    /// Whether the alert carries the adhan or arrives silent.
    @Published private(set) var adhanSound: Bool
    @Published private(set) var theme: AppTheme
    /// The tasbih, which outlives the screen it is counted on.
    @Published private(set) var tasbih: TasbihState
    /// The solar correction in force, if the user has taken a sighting.
    @Published private(set) var calibration: CompassCalibration?

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        method = Self.decode(CalculationMethod.self, from: defaults, key: Keys.method) ?? .ummAlQura
        madhab = Self.decode(Madhab.self, from: defaults, key: Keys.madhab) ?? .shafi
        place = Self.decode(Place.self, from: defaults, key: Keys.place)
        notificationsEnabled = defaults.bool(forKey: Keys.notificationsEnabled)
        favourites = Set(defaults.stringArray(forKey: Keys.favourites) ?? [])
        preAdhanMinutes = defaults.integer(forKey: Keys.preAdhanMinutes)
        // `object(forKey:)` rather than `bool(forKey:)`: an absent key reads as false, and the
        // adhan is meant to be on until someone turns it off.
        adhanSound = defaults.object(forKey: Keys.adhanSound) as? Bool ?? true
        theme = AppTheme.from(name: defaults.string(forKey: Keys.theme))

        let storedIqama = defaults.dictionary(forKey: Keys.iqamaMinutes) as? [String: Int] ?? [:]
        iqamaMinutes = storedIqama.reduce(into: [:]) { result, entry in
            if let prayer = Prayer(rawValue: entry.key) { result[prayer] = entry.value }
        }

        let dhikr = TasbihPhrase.from(name: defaults.string(forKey: Keys.tasbihDhikr))
        tasbih = TasbihState(
            dhikr: dhikr,
            // A stored target of zero — however it got there — would make every tap complete a
            // round, so it falls back to the phrase's own.
            target: max(1, defaults.object(forKey: Keys.tasbihTarget) as? Int ?? dhikr.defaultTarget),
            count: max(0, defaults.integer(forKey: Keys.tasbihCount)),
            rounds: max(0, defaults.integer(forKey: Keys.tasbihRounds))
        )
        calibration = Self.decode(CompassCalibration.self, from: defaults, key: Keys.calibration)

        if let stored = defaults.stringArray(forKey: Keys.notifiedPrayers) {
            notifiedPrayers = Set(stored.compactMap(Prayer.init(rawValue:)))
        } else {
            // Sunrise is excluded by default: it ends Fajr rather than beginning a prayer.
            notifiedPrayers = [.fajr, .dhuhr, .asr, .maghrib, .isha]
        }
    }

    /// The parameter set to hand the calculator: the method's published values, with the user's
    /// madhab and the high-latitude rule appropriate to their latitude.
    var calculationParameters: CalculationParameters {
        var parameters = method.parameters
        parameters.madhab = madhab
        if let coordinates = place?.coordinates {
            parameters.highLatitudeRule = HighLatitudeRule.recommended(for: coordinates)
        }
        return parameters
    }

    func setMethod(_ value: CalculationMethod) {
        method = value
        Self.encode(value, into: defaults, key: Keys.method)
    }

    func setMadhab(_ value: Madhab) {
        madhab = value
        Self.encode(value, into: defaults, key: Keys.madhab)
    }

    func setPlace(_ value: Place) {
        place = value
        Self.encode(value, into: defaults, key: Keys.place)
    }

    func setNotificationsEnabled(_ value: Bool) {
        notificationsEnabled = value
        defaults.set(value, forKey: Keys.notificationsEnabled)
    }

    func toggleNotified(_ prayer: Prayer) {
        if notifiedPrayers.contains(prayer) {
            notifiedPrayers.remove(prayer)
        } else {
            notifiedPrayers.insert(prayer)
        }
        defaults.set(notifiedPrayers.map(\.rawValue), forKey: Keys.notifiedPrayers)
    }

    func toggleFavourite(_ id: String) {
        if favourites.contains(id) {
            favourites.remove(id)
        } else {
            favourites.insert(id)
        }
        defaults.set(Array(favourites), forKey: Keys.favourites)
    }

    func isFavourite(_ id: String) -> Bool { favourites.contains(id) }

    func setPreAdhanMinutes(_ value: Int) {
        preAdhanMinutes = max(0, value)
        defaults.set(preAdhanMinutes, forKey: Keys.preAdhanMinutes)
    }

    func setIqamaMinutes(_ value: Int, for prayer: Prayer) {
        iqamaMinutes[prayer] = max(0, value)
        defaults.set(
            iqamaMinutes.reduce(into: [String: Int]()) { $0[$1.key.rawValue] = $1.value },
            forKey: Keys.iqamaMinutes
        )
    }

    func setAdhanSound(_ value: Bool) {
        adhanSound = value
        defaults.set(value, forKey: Keys.adhanSound)
    }

    func setTheme(_ value: AppTheme) {
        theme = value
        defaults.set(value.rawValue, forKey: Keys.theme)
    }

    /// The tasbih is written on every single tap, so it is stored as four scalars rather than as an
    /// encoded blob: this runs on the main thread between a finger and the next frame.
    func setTasbih(_ value: TasbihState) {
        tasbih = value
        defaults.set(value.dhikr.rawValue, forKey: Keys.tasbihDhikr)
        defaults.set(value.target, forKey: Keys.tasbihTarget)
        defaults.set(value.count, forKey: Keys.tasbihCount)
        defaults.set(value.rounds, forKey: Keys.tasbihRounds)
    }

    func setCalibration(_ value: CompassCalibration?) {
        calibration = value
        guard let value else { return defaults.removeObject(forKey: Keys.calibration) }
        Self.encode(value, into: defaults, key: Keys.calibration)
    }

    // MARK: - Codable helpers

    /// A value written by another version of the app must not crash this one; anything unreadable
    /// falls back to the default exactly as an absent value does.
    private static func decode<T: Decodable>(_ type: T.Type, from defaults: UserDefaults, key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private static func encode<T: Encodable>(_ value: T, into defaults: UserDefaults, key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }

    private enum Keys {
        static let method = "method"
        static let madhab = "madhab"
        static let place = "place"
        static let notificationsEnabled = "notifications_enabled"
        static let notifiedPrayers = "notified_prayers"
        static let favourites = "favourites"
        static let preAdhanMinutes = "pre_adhan_minutes"
        static let iqamaMinutes = "iqama_minutes"
        static let adhanSound = "adhan_sound"
        static let theme = "theme"
        static let tasbihDhikr = "tasbih_dhikr"
        static let tasbihTarget = "tasbih_target"
        static let tasbihCount = "tasbih_count"
        static let tasbihRounds = "tasbih_rounds"
        static let calibration = "compass_calibration"
    }
}
