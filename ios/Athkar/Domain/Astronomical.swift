import Foundation

/// Solar position from Jean Meeus, *Astronomical Algorithms* (2nd ed.), chapters 12, 22, 25 and 15.
///
/// Accuracy is what separates a prayer-time app from a rough estimate, so this is the full apparent
/// position — equation of the centre, nutation in longitude and obliquity, and the apparent sidereal
/// time — rather than the low-precision series. Rise/set/twilight instants are then refined by
/// interpolating the sun's coordinates across the previous, current and next day (Meeus ch. 15),
/// which removes the error a single-day model accumulates near the solstices and at high latitude.
enum Astronomical {

    /// Julian Day for a Gregorian calendar date; `hours` is UT expressed as a fraction of a day.
    static func julianDay(year: Int, month: Int, day: Int, hours: Double = 0.0) -> Double {
        let y = month > 2 ? year : year - 1
        let m = month > 2 ? month : month + 12
        let d = Double(day) + (hours / 24.0)
        let a = y / 100
        let b = 2 - a + (a / 4)
        return (365.25 * Double(y + 4716)).rounded(.down)
            + (30.6001 * Double(m + 1)).rounded(.down)
            + d + Double(b) - 1524.5
    }

    /// Julian centuries since J2000.0 — the time argument of every series below.
    static func julianCentury(_ julianDay: Double) -> Double { (julianDay - 2451545.0) / 36525.0 }

    /// Geometric mean longitude of the sun, degrees (Meeus 25.2).
    static func meanSolarLongitude(_ t: Double) -> Double {
        (280.4664567 + 36000.76983 * t + 0.0003032 * t * t).unwoundAngle
    }

    /// Geometric mean longitude of the moon, degrees — only needed for the nutation terms.
    static func meanLunarLongitude(_ t: Double) -> Double { (218.3165 + 481267.8813 * t).unwoundAngle }

    /// Longitude of the ascending lunar node, degrees (Meeus 47.7).
    static func ascendingLunarNodeLongitude(_ t: Double) -> Double {
        (125.04452 - 1934.136261 * t + 0.0020708 * t * t + (t * t * t) / 450000.0).unwoundAngle
    }

    /// Mean anomaly of the sun, degrees (Meeus 25.3).
    static func meanSolarAnomaly(_ t: Double) -> Double {
        (357.52911 + 35999.05029 * t - 0.0001537 * t * t).unwoundAngle
    }

    /// Equation of the centre, degrees (Meeus p. 164).
    static func solarEquationOfTheCenter(_ t: Double, meanAnomaly: Double) -> Double {
        let m = meanAnomaly.degreesToRadians
        return sin(m) * (1.914602 - 0.004817 * t - 0.000014 * t * t)
            + sin(2 * m) * (0.019993 - 0.000101 * t)
            + sin(3 * m) * 0.000289
    }

    /// Apparent longitude of the sun, degrees — true longitude corrected for aberration and nutation.
    static func apparentSolarLongitude(_ t: Double, meanLongitude: Double) -> Double {
        let trueLongitude = meanLongitude + solarEquationOfTheCenter(t, meanAnomaly: meanSolarAnomaly(t))
        let omega = (125.04 - 1934.136 * t).degreesToRadians
        return (trueLongitude - 0.00569 - 0.00478 * sin(omega)).unwoundAngle
    }

    /// Mean obliquity of the ecliptic, degrees (Meeus 22.2).
    static func meanObliquityOfTheEcliptic(_ t: Double) -> Double {
        23.439291 - 0.013004167 * t - 0.0000001639 * t * t + 0.0000005036 * t * t * t
    }

    /// Obliquity corrected for the leading nutation term, degrees (Meeus p. 165).
    static func apparentObliquityOfTheEcliptic(_ t: Double, meanObliquity: Double) -> Double {
        meanObliquity + 0.00256 * cos((125.04 - 1934.136 * t).degreesToRadians)
    }

    /// Mean sidereal time at Greenwich, degrees (Meeus 12.4).
    static func meanSiderealTime(_ t: Double) -> Double {
        let jd = t * 36525.0 + 2451545.0
        let theta = 280.46061837 + 360.98564736629 * (jd - 2451545.0)
            + 0.000387933 * t * t - (t * t * t) / 38710000.0
        return theta.unwoundAngle
    }

    /// Nutation in longitude, degrees — leading terms only (Meeus ch. 22).
    static func nutationInLongitude(
        solarLongitude: Double, lunarLongitude: Double, ascendingNode: Double
    ) -> Double {
        let l0 = solarLongitude.degreesToRadians
        let lp = lunarLongitude.degreesToRadians
        let omega = ascendingNode.degreesToRadians
        return (-17.2 / 3600) * sin(omega)
            - (1.32 / 3600) * sin(2 * l0)
            - (0.23 / 3600) * sin(2 * lp)
            + (0.21 / 3600) * sin(2 * omega)
    }

    /// Nutation in obliquity, degrees — leading terms only (Meeus ch. 22).
    static func nutationInObliquity(
        solarLongitude: Double, lunarLongitude: Double, ascendingNode: Double
    ) -> Double {
        let l0 = solarLongitude.degreesToRadians
        let lp = lunarLongitude.degreesToRadians
        let omega = ascendingNode.degreesToRadians
        return (9.2 / 3600) * cos(omega)
            + (0.57 / 3600) * cos(2 * l0)
            + (0.10 / 3600) * cos(2 * lp)
            - (0.09 / 3600) * cos(2 * omega)
    }

    /// Altitude of a body above the horizon, degrees, from its declination and local hour angle.
    static func altitudeOfCelestialBody(
        observerLatitude: Double, declination: Double, localHourAngle: Double
    ) -> Double {
        let phi = observerLatitude.degreesToRadians
        let delta = declination.degreesToRadians
        let h = localHourAngle.degreesToRadians
        return asin(sin(phi) * sin(delta) + cos(phi) * cos(delta) * cos(h)).radiansToDegrees
    }

    /// First approximation of the transit (solar noon) as a fraction of the day (Meeus 15.1).
    static func approximateTransit(
        longitude: Double, siderealTime: Double, rightAscension: Double
    ) -> Double {
        let westLongitude = -longitude
        return ((rightAscension + westLongitude - siderealTime) / 360.0).normalized(withBound: 1.0)
    }

    /// Transit refined by interpolating right ascension across three days; returns hours UT.
    static func correctedTransit(
        approximateTransit m0: Double,
        longitude: Double,
        siderealTime: Double,
        rightAscension: Double,
        previousRightAscension: Double,
        nextRightAscension: Double
    ) -> Double {
        let westLongitude = -longitude
        let theta = (siderealTime + 360.985647 * m0).unwoundAngle
        let alpha = interpolateAngles(
            y2: rightAscension, y1: previousRightAscension, y3: nextRightAscension, n: m0
        ).unwoundAngle
        let hourAngle = (theta - westLongitude - alpha).closestAngle
        return (m0 + (hourAngle / -360.0)) * 24.0
    }

    /// Instant, in hours UT, at which the sun reaches `angleAboveHorizon`. `afterTransit` picks the
    /// afternoon/evening branch. Returns nil when the sun never reaches that altitude on this day —
    /// the polar-night / midnight-sun case the caller must resolve with a high-latitude rule.
    static func correctedHourAngle(
        approximateTransit m0: Double,
        angleAboveHorizon: Double,
        coordinates: Coordinates,
        afterTransit: Bool,
        siderealTime: Double,
        rightAscension: Double,
        previousRightAscension: Double,
        nextRightAscension: Double,
        declination: Double,
        previousDeclination: Double,
        nextDeclination: Double
    ) -> Double? {
        let westLongitude = -coordinates.longitude
        let phi = coordinates.latitude.degreesToRadians
        let term1 = sin(angleAboveHorizon.degreesToRadians) - sin(phi) * sin(declination.degreesToRadians)
        let term2 = cos(phi) * cos(declination.degreesToRadians)
        let ratio = term1 / term2
        guard ratio.isFinite, ratio <= 1.0, ratio >= -1.0 else { return nil }

        let h0 = acos(ratio).radiansToDegrees
        let m = afterTransit ? m0 + h0 / 360.0 : m0 - h0 / 360.0
        let theta = (siderealTime + 360.985647 * m).unwoundAngle
        let alpha = interpolateAngles(
            y2: rightAscension, y1: previousRightAscension, y3: nextRightAscension, n: m
        ).unwoundAngle
        let delta = interpolate(
            y2: declination, y1: previousDeclination, y3: nextDeclination, n: m
        )
        let hourAngle = theta - westLongitude - alpha
        let altitude = altitudeOfCelestialBody(
            observerLatitude: coordinates.latitude, declination: delta, localHourAngle: hourAngle
        )
        let denominator = 360.0 * cos(delta.degreesToRadians) * cos(phi) * sin(hourAngle.degreesToRadians)
        let deltaM = (altitude - angleAboveHorizon) / denominator
        return (m + deltaM) * 24.0
    }

    /// Three-point interpolation (Meeus 3.3).
    static func interpolate(y2: Double, y1: Double, y3: Double, n: Double) -> Double {
        let a = y2 - y1
        let b = y3 - y2
        let c = b - a
        return y2 + (n / 2) * (a + b + n * c)
    }

    /// Three-point interpolation for angles, taking the shortest way round the circle.
    static func interpolateAngles(y2: Double, y1: Double, y3: Double, n: Double) -> Double {
        let a = (y2 - y1).unwoundAngle
        let b = (y3 - y2).unwoundAngle
        let c = b - a
        return y2 + (n / 2) * (a + b + n * c)
    }
}

/// Apparent solar coordinates for one Julian Day.
struct SolarCoordinates {

    /// Apparent declination of the sun, degrees.
    let declination: Double

    /// Apparent right ascension of the sun, degrees, normalised to `[0, 360)`.
    let rightAscension: Double

    /// Apparent sidereal time at Greenwich, degrees.
    let apparentSiderealTime: Double

    init(julianDay: Double) {
        let t = Astronomical.julianCentury(julianDay)
        let meanLongitude = Astronomical.meanSolarLongitude(t)
        let meanLunarLongitude = Astronomical.meanLunarLongitude(t)
        let ascendingNode = Astronomical.ascendingLunarNodeLongitude(t)
        let apparentLongitude = Astronomical
            .apparentSolarLongitude(t, meanLongitude: meanLongitude).degreesToRadians
        let siderealTime = Astronomical.meanSiderealTime(t)
        let nutationLongitude = Astronomical.nutationInLongitude(
            solarLongitude: meanLongitude, lunarLongitude: meanLunarLongitude, ascendingNode: ascendingNode
        )
        let nutationObliquity = Astronomical.nutationInObliquity(
            solarLongitude: meanLongitude, lunarLongitude: meanLunarLongitude, ascendingNode: ascendingNode
        )
        let meanObliquity = Astronomical.meanObliquityOfTheEcliptic(t)
        let apparentObliquity = Astronomical
            .apparentObliquityOfTheEcliptic(t, meanObliquity: meanObliquity).degreesToRadians

        declination = asin(sin(apparentObliquity) * sin(apparentLongitude)).radiansToDegrees
        rightAscension = atan2(
            cos(apparentObliquity) * sin(apparentLongitude),
            cos(apparentLongitude)
        ).radiansToDegrees.unwoundAngle
        apparentSiderealTime = siderealTime
            + (nutationLongitude * 3600 * cos((meanObliquity + nutationObliquity).degreesToRadians)) / 3600
    }
}
