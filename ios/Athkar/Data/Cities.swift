import Foundation

/// A place the user computes times for, and how it was obtained.
struct Place: Equatable, Codable, Identifiable, Sendable {
    let latitude: Double
    let longitude: Double
    let name: String
    /// True when it came from the device, false when picked from the list. A manual choice must
    /// survive a failed location fix.
    let isAutomatic: Bool

    var id: String { "\(name)-\(latitude)-\(longitude)" }

    var coordinates: Coordinates? { Coordinates(latitude: latitude, longitude: longitude) }
}

/// Offline fallback for when location permission is refused or no fix is available — which, for an
/// app whose whole point is working without a network, has to be a first-class path rather than an
/// error state. Coordinates are city centres; a few kilometres of error moves a prayer time by well
/// under a minute.
enum Cities {

    static let all: [Place] = [
        place("مكة المكرمة", 21.3891, 39.8579),
        place("المدينة المنورة", 24.4672, 39.6111),
        place("الرياض", 24.7136, 46.6753),
        place("جدة", 21.4858, 39.1925),
        place("الدمام", 26.4207, 50.0888),
        place("أبها", 18.2164, 42.5053),
        place("تبوك", 28.3835, 36.5662),
        place("بريدة", 26.3260, 43.9750),
        place("القاهرة", 30.0444, 31.2357),
        place("الإسكندرية", 31.2001, 29.9187),
        place("الخرطوم", 15.5007, 32.5599),
        place("عمّان", 31.9539, 35.9106),
        place("القدس", 31.7683, 35.2137),
        place("غزة", 31.5017, 34.4668),
        place("بيروت", 33.8938, 35.5018),
        place("دمشق", 33.5138, 36.2765),
        place("بغداد", 33.3152, 44.3661),
        place("الكويت", 29.3759, 47.9774),
        place("الدوحة", 25.2854, 51.5310),
        place("المنامة", 26.2285, 50.5860),
        place("أبوظبي", 24.4539, 54.3773),
        place("دبي", 25.2048, 55.2708),
        place("مسقط", 23.5880, 58.3829),
        place("صنعاء", 15.3694, 44.1910),
        place("الرباط", 34.0209, -6.8416),
        place("الدار البيضاء", 33.5731, -7.5898),
        place("الجزائر", 36.7538, 3.0588),
        place("تونس", 36.8065, 10.1815),
        place("طرابلس", 32.8872, 13.1913),
        place("نواكشوط", 18.0735, -15.9582),
        place("مقديشو", 2.0469, 45.3182),
        place("إسطنبول", 41.0082, 28.9784),
        place("كوالالمبور", 3.1390, 101.6869),
        place("جاكرتا", -6.2088, 106.8456),
        place("إسلام آباد", 33.6844, 73.0479),
        place("لندن", 51.5074, -0.1278),
        place("باريس", 48.8566, 2.3522),
        place("برلين", 52.5200, 13.4050),
        place("نيويورك", 40.7128, -74.0060),
        place("تورونتو", 43.6532, -79.3832),
    ]

    static func search(_ query: String) -> [Place] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return all }
        return all.filter { $0.name.contains(trimmed) }
    }

    /// Names a fix after the nearest bundled city, or falls back to the coordinates themselves —
    /// no reverse geocoder, because a geocoder needs the network this app is built to work without.
    static func name(for coordinates: Coordinates, matchRadiusKm: Double = 40) -> String {
        let nearest = all.min { lhs, rhs in
            distanceKm(coordinates, lhs) < distanceKm(coordinates, rhs)
        }
        if let nearest, distanceKm(coordinates, nearest) <= matchRadiusKm {
            return nearest.name
        }
        return String(format: "%.3f، %.3f", coordinates.latitude, coordinates.longitude)
    }

    private static func distanceKm(_ coordinates: Coordinates, _ place: Place) -> Double {
        guard let target = place.coordinates else { return .greatestFiniteMagnitude }
        let phi1 = coordinates.latitude.degreesToRadians
        let phi2 = target.latitude.degreesToRadians
        let deltaPhi = (target.latitude - coordinates.latitude).degreesToRadians
        let deltaLambda = (target.longitude - coordinates.longitude).degreesToRadians
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2)
            + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * 6371.0088 * asin(min(sqrt(a), 1.0))
    }

    private static func place(_ name: String, _ latitude: Double, _ longitude: Double) -> Place {
        Place(latitude: latitude, longitude: longitude, name: name, isAutomatic: false)
    }
}
