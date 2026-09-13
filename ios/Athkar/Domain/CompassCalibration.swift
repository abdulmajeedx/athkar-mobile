import Foundation

/// How the user told the app where true north really is.
///
/// Both work the same way — the app knows the sun's true azimuth to a fraction of a degree from the
/// date, the time and the coordinates, so any sighting of the sun is a sighting of an absolute
/// bearing — and they differ only in what the user aims with.
enum SightingMethod: String, CaseIterable, Codable, Identifiable, Sendable {

    /// The shadow of anything upright, which points exactly away from the sun.
    ///
    /// The recommended one, and by a distance. A shadow's edge is a sharp line lying on the ground
    /// that can be matched to within a degree or so, the phone can rest flat while it is done, and
    /// nobody has to look anywhere near the sun to do it.
    case shadow

    /// The sun itself, for when there is no shadow to use — a hazier sighting, and a harsher one.
    case sun

    var id: String { rawValue }

    var arabicName: String {
        switch self {
        case .shadow: return "بالظل"
        case .sun: return "بالشمس"
        }
    }

    /// How far a heading corrected by this method can still be out.
    ///
    /// Stated rather than hidden, because the whole feature is a claim about accuracy, and a claim
    /// about accuracy without its own error bar is marketing. The computed solar azimuth
    /// contributes a fraction of a degree to these; all the rest is the hand that did the aiming.
    var expectedErrorDegrees: Double {
        switch self {
        case .shadow: return 2
        case .sun: return 5
        }
    }

    /// The true bearing the top of the phone points along when the sighting is made.
    ///
    /// The shadow falls on the anti-solar bearing, so aligning the phone with a shadow that runs
    /// away from the observer means the phone points at the sun's azimuth plus a half turn.
    func trueBearing(sunAzimuth: Double) -> Double {
        switch self {
        case .sun: return sunAzimuth.unwoundAngle
        case .shadow: return (sunAzimuth + 180).unwoundAngle
        }
    }
}

/// A correction to the magnetic compass, measured against the sun.
///
/// `CLHeading.trueHeading` already applies the local declination, which is the *modelled* part of
/// the error. It cannot touch the local part: a steel balcony rail, a car door, a magnetic case or a
/// laptop half a metre away bends the field the sensor is reading, and CoreLocation reports the bent
/// bearing with exactly the same confidence as a clean one.
///
/// The sun is outside all of it. One sighting says what the compass *should* have read, and the
/// difference is this offset — which then corrects every later reading.
struct CompassCalibration: Equatable, Codable, Sendable {

    /// Degrees to add to a raw heading to get the true one, on `[0, 360)`.
    let offsetDegrees: Double
    let takenAt: Date
    /// Where it was taken — a correction is local to the metal that made it necessary.
    let latitude: Double
    let longitude: Double
    let method: SightingMethod

    /// Long enough to cover a stay somewhere, short enough that it cannot survive the journey home.
    static let maxAge: TimeInterval = 6 * 60 * 60

    /// Past this the user is somewhere else, whatever the clock says.
    static let maxDriftKm = 20.0

    /// A sighting past this altitude is refused: near the zenith the sun's azimuth sweeps through a
    /// large angle in minutes, shadows shrink to nothing, and a degree of aiming error in the hand
    /// becomes many degrees in the answer.
    static let maxUsableAltitude = 70.0

    /// Below this the sun is too close to the horizon to be seen or to cast a defined edge.
    static let minUsableAltitude = 1.0

    /// The correction as a signed turn, which is how it is worth reading: "+7°", "−23°".
    var signedOffset: Double { offsetDegrees.closestAngle }

    /// A raw magnetic heading, corrected.
    func correct(_ rawHeading: Double) -> Double { (rawHeading + offsetDegrees).unwoundAngle }

    /// True once this correction has stopped describing the user's surroundings.
    ///
    /// Both limits are about the same thing. The offset cancels the iron that was around the phone
    /// at the moment of the sighting; out of that car it does not merely lose accuracy, it adds an
    /// error that was not in the raw reading. Better to expire and say so.
    func isStale(at moment: Date, near coordinates: Coordinates?) -> Bool {
        if moment.timeIntervalSince(takenAt) >= Self.maxAge { return true }
        guard let coordinates else { return false }
        return Self.distanceKm(
            latitude, longitude, coordinates.latitude, coordinates.longitude
        ) > Self.maxDriftKm
    }

    /// Builds the correction from one sighting.
    ///
    /// - Parameters:
    ///   - sunAzimuth: the sun's true azimuth at `takenAt`, degrees clockwise from true north.
    ///   - rawHeading: what the compass said the phone was pointing at, uncorrected.
    static func fromSighting(
        method: SightingMethod,
        sunAzimuth: Double,
        rawHeading: Double,
        takenAt: Date,
        at coordinates: Coordinates
    ) -> CompassCalibration {
        CompassCalibration(
            offsetDegrees: (method.trueBearing(sunAzimuth: sunAzimuth) - rawHeading).unwoundAngle,
            takenAt: takenAt,
            latitude: coordinates.latitude,
            longitude: coordinates.longitude,
            method: method
        )
    }

    /// Whether the sun is usable for a sighting at this altitude.
    static func canSight(sunAltitude: Double) -> Bool {
        sunAltitude >= minUsableAltitude && sunAltitude <= maxUsableAltitude
    }

    private static let earthRadiusKm = 6371.0088

    private static func distanceKm(
        _ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double
    ) -> Double {
        let phi1 = lat1.degreesToRadians
        let phi2 = lat2.degreesToRadians
        let deltaPhi = (lat2 - lat1).degreesToRadians
        let deltaLambda = (lon2 - lon1).degreesToRadians
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2)
            + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * earthRadiusKm * asin(min(sqrt(a), 1))
    }
}
