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

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        method = Self.decode(CalculationMethod.self, from: defaults, key: Keys.method) ?? .ummAlQura
        madhab = Self.decode(Madhab.self, from: defaults, key: Keys.madhab) ?? .shafi
        place = Self.decode(Place.self, from: defaults, key: Keys.place)
        notificationsEnabled = defaults.bool(forKey: Keys.notificationsEnabled)
        favourites = Set(defaults.stringArray(forKey: Keys.favourites) ?? [])

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
    }
}
