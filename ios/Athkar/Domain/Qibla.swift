import Foundation

/// Direction of the Kaaba as a great-circle initial bearing.
///
/// A rhumb line — the "straight line on a flat map" people expect — is the wrong answer: it is not
/// the shortest path on a sphere, and its error grows to tens of degrees at long range.
enum Qibla {

    /// The Kaaba, Al-Masjid al-Haram, Makkah.
    static let makkah = Coordinates(latitude: 21.4225241, longitude: 39.8261818)!

    /// Mean Earth radius, kilometres (IUGG).
    private static let earthRadiusKm = 6371.0088

    /// Initial great-circle bearing to the Kaaba, degrees clockwise from true north, `[0, 360)`.
    static func direction(from origin: Coordinates) -> Double {
        let phi1 = origin.latitude.degreesToRadians
        let phi2 = makkah.latitude.degreesToRadians
        let deltaLambda = (makkah.longitude - origin.longitude).degreesToRadians

        let y = sin(deltaLambda)
        let x = cos(phi1) * tan(phi2) - sin(phi1) * cos(deltaLambda)
        return atan2(y, x).radiansToDegrees.unwoundAngle
    }

    /// Great-circle distance to the Kaaba, kilometres.
    static func distanceKm(from origin: Coordinates) -> Double {
        let phi1 = origin.latitude.degreesToRadians
        let phi2 = makkah.latitude.degreesToRadians
        let deltaPhi = (makkah.latitude - origin.latitude).degreesToRadians
        let deltaLambda = (makkah.longitude - origin.longitude).degreesToRadians

        // Haversine: numerically stable for the short distances a rearranged cosine rule loses.
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2)
            + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * earthRadiusKm * asin(min(sqrt(a), 1.0))
    }
}
