import Foundation

/// Where the sun is, seen from a point on the ground.
///
/// The prayer times need the sun's *altitude* at a given hour angle, which `Astronomical` already
/// provides; this adds the azimuth, which is what turns the sun into a compass. Its accuracy is
/// that of the same Meeus series the timings rest on — a small fraction of a degree — and unlike a
/// magnetometer there is nothing in a room that can bend it.
struct SolarPosition: Equatable, Sendable {

    /// Degrees clockwise from true north, `[0, 360)`.
    let azimuth: Double

    /// Degrees above the horizon; negative when the sun has set.
    let altitude: Double

    var isAboveHorizon: Bool { altitude > 0 }

    /// The sun's apparent position at `moment`, seen from `coordinates`.
    static func at(_ coordinates: Coordinates, _ moment: Date) -> SolarPosition {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        let parts = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: moment
        )
        let hours = Double(parts.hour ?? 0)
            + Double(parts.minute ?? 0) / 60.0
            + Double(parts.second ?? 0) / 3600.0

        let julianDay = Astronomical.julianDay(
            year: parts.year ?? 2000,
            month: parts.month ?? 1,
            day: parts.day ?? 1,
            hours: hours
        )
        let solar = SolarCoordinates(julianDay: julianDay)

        // Local hour angle: how far west of the observer's meridian the sun has travelled.
        let hourAngle = (solar.apparentSiderealTime + coordinates.longitude - solar.rightAscension)
            .closestAngle

        let phi = coordinates.latitude.degreesToRadians
        let delta = solar.declination.degreesToRadians
        let h = hourAngle.degreesToRadians

        // Measured from south and positive westward, which is the convention the formula falls out
        // of; turned to the compass convention on the way out.
        let fromSouth = atan2(sin(h), cos(h) * sin(phi) - tan(delta) * cos(phi)).radiansToDegrees

        return SolarPosition(
            azimuth: (fromSouth + 180.0).unwoundAngle,
            altitude: Astronomical.altitudeOfCelestialBody(
                observerLatitude: coordinates.latitude,
                declination: solar.declination,
                localHourAngle: hourAngle
            )
        )
    }
}
