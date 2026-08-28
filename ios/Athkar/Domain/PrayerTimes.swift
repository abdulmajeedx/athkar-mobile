import Foundation

/// The five obligatory prayers plus sunrise, which bounds the end of Fajr.
enum Prayer: String, CaseIterable, Identifiable, Codable, Sendable {
    case fajr, sunrise, dhuhr, asr, maghrib, isha

    var id: String { rawValue }

    var arabicName: String {
        switch self {
        case .fajr: return "الفجر"
        case .sunrise: return "الشروق"
        case .dhuhr: return "الظهر"
        case .asr: return "العصر"
        case .maghrib: return "المغرب"
        case .isha: return "العشاء"
        }
    }

    /// Order within the day, used to fade prayers that have already passed.
    var order: Int { Prayer.allCases.firstIndex(of: self) ?? 0 }
}

/// Night-portion times used for qiyam.
struct SunnahTimes {
    let middleOfTheNight: Date
    let lastThirdOfTheNight: Date
}

/// The sun does not cross the horizon on the requested day, so sunrise and sunset — and everything
/// derived from them — are undefined. Above the polar circles for part of the year.
struct PolarDayError: Error {
    let coordinates: Coordinates
    let date: DateComponents
}

/// Prayer times for one calendar day at one location.
///
/// Times are `Date`s — unambiguous points in time — so the caller renders them in whatever zone it
/// likes without this type reasoning about offsets or DST. The calculation itself is zone-free: the
/// date is only the local calendar day whose transit is being solved for.
struct PrayerTimes {

    let coordinates: Coordinates
    let fajr: Date
    let sunrise: Date
    let dhuhr: Date
    let asr: Date
    let maghrib: Date
    let isha: Date

    /// Sun's altitude at apparent sunrise/sunset: 16' of semi-diameter plus 34' of standard
    /// atmospheric refraction, below the horizon.
    private static let sunriseSunsetAltitude = -0.833

    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    func time(for prayer: Prayer) -> Date {
        switch prayer {
        case .fajr: return fajr
        case .sunrise: return sunrise
        case .dhuhr: return dhuhr
        case .asr: return asr
        case .maghrib: return maghrib
        case .isha: return isha
        }
    }

    /// The prayer whose time has most recently passed, or nil before Fajr.
    func currentPrayer(at moment: Date) -> Prayer? {
        Prayer.allCases.last { time(for: $0) <= moment }
    }

    /// The next prayer to come, or nil once Isha has passed.
    func nextPrayer(at moment: Date) -> Prayer? {
        Prayer.allCases.first { time(for: $0) > moment }
    }

    /// Splits the night — Maghrib to the *following* day's Fajr — into halves and thirds. Pass the
    /// next day's times; using today's Fajr would measure the night backwards.
    func sunnahTimes(tomorrow: PrayerTimes) -> SunnahTimes {
        let night = tomorrow.fajr.timeIntervalSince(maghrib)
        return SunnahTimes(
            middleOfTheNight: maghrib.addingTimeInterval(night / 2),
            lastThirdOfTheNight: maghrib.addingTimeInterval(night * 2 / 3)
        )
    }

    /// Computes the six times for the given local calendar day.
    ///
    /// - Parameter elevationMeters: observer height above the local horizon; it advances sunrise and
    ///   delays sunset by roughly one minute per 100 m and is otherwise ignored.
    static func calculate(
        coordinates: Coordinates,
        year: Int,
        month: Int,
        day: Int,
        parameters: CalculationParameters,
        elevationMeters: Double = 0
    ) throws -> PrayerTimes {
        let dateComponents = DateComponents(year: year, month: month, day: day)
        let jd = Astronomical.julianDay(year: year, month: month, day: day)
        let solar = SolarCoordinates(julianDay: jd)
        let previousSolar = SolarCoordinates(julianDay: jd - 1)
        let nextSolar = SolarCoordinates(julianDay: jd + 1)

        let m0 = Astronomical.approximateTransit(
            longitude: coordinates.longitude,
            siderealTime: solar.apparentSiderealTime,
            rightAscension: solar.rightAscension
        )
        let horizonAltitude = sunriseSunsetAltitude - 0.0347 * sqrt(max(elevationMeters, 0))

        func hourAngle(_ angle: Double, afterTransit: Bool) -> Double? {
            Astronomical.correctedHourAngle(
                approximateTransit: m0,
                angleAboveHorizon: angle,
                coordinates: coordinates,
                afterTransit: afterTransit,
                siderealTime: solar.apparentSiderealTime,
                rightAscension: solar.rightAscension,
                previousRightAscension: previousSolar.rightAscension,
                nextRightAscension: nextSolar.rightAscension,
                declination: solar.declination,
                previousDeclination: previousSolar.declination,
                nextDeclination: nextSolar.declination
            )
        }

        let transitHours = Astronomical.correctedTransit(
            approximateTransit: m0,
            longitude: coordinates.longitude,
            siderealTime: solar.apparentSiderealTime,
            rightAscension: solar.rightAscension,
            previousRightAscension: previousSolar.rightAscension,
            nextRightAscension: nextSolar.rightAscension
        )

        // A polar day or night leaves the sun permanently above or below the horizon; without a
        // sunrise there is no night to divide, so no rule can rescue the day.
        guard let sunriseHours = hourAngle(horizonAltitude, afterTransit: false),
              let sunsetHours = hourAngle(horizonAltitude, afterTransit: true)
        else { throw PolarDayError(coordinates: coordinates, date: dateComponents) }

        let asrAngle = asrAltitude(
            shadowLength: parameters.madhab.shadowLength,
            latitude: coordinates.latitude,
            declination: solar.declination
        )
        guard let asrHours = hourAngle(asrAngle, afterTransit: true) else {
            throw PolarDayError(coordinates: coordinates, date: dateComponents)
        }

        guard let midnight = utc.date(from: dateComponents) else {
            throw PolarDayError(coordinates: coordinates, date: dateComponents)
        }

        let dhuhrTime = try instant(from: midnight, hoursUT: transitHours, dateComponents, coordinates)
        let sunriseTime = try instant(from: midnight, hoursUT: sunriseHours, dateComponents, coordinates)
        let sunsetTime = try instant(from: midnight, hoursUT: sunsetHours, dateComponents, coordinates)
        let asrTime = try instant(from: midnight, hoursUT: asrHours, dateComponents, coordinates)

        // The night Fajr and Isha are clamped against runs from today's sunset to *tomorrow's*
        // sunrise, not to today's.
        let tomorrowSunriseTime = try tomorrowSunrise(
            coordinates: coordinates, from: dateComponents, horizonAltitude: horizonAltitude
        )
        let night = tomorrowSunriseTime.timeIntervalSince(sunsetTime)
        let portions = parameters.nightPortions

        let fajrTime: Date = {
            let safe = sunriseTime.addingTimeInterval(-portions.fajr * night)
            guard let hours = hourAngle(-parameters.fajrAngle, afterTransit: false),
                  let computed = try? instant(from: midnight, hoursUT: hours, dateComponents, coordinates)
            else { return safe }
            return computed < safe ? safe : computed
        }()

        let maghribTime: Date = {
            guard parameters.maghribAngle > 0,
                  let hours = hourAngle(-parameters.maghribAngle, afterTransit: true),
                  let computed = try? instant(from: midnight, hoursUT: hours, dateComponents, coordinates)
            else { return sunsetTime }
            return computed
        }()

        let ishaTime: Date = {
            if parameters.ishaInterval > 0 {
                return maghribTime.addingTimeInterval(Double(parameters.ishaInterval) * 60)
            }
            let safe = sunsetTime.addingTimeInterval(portions.isha * night)
            guard let hours = hourAngle(-parameters.ishaAngle, afterTransit: true),
                  let computed = try? instant(from: midnight, hoursUT: hours, dateComponents, coordinates)
            else { return safe }
            return computed > safe ? safe : computed
        }()

        let offsets = parameters.adjustments + parameters.methodAdjustments
        return PrayerTimes(
            coordinates: coordinates,
            fajr: fajrTime.shifted(byMinutes: offsets.fajr),
            sunrise: sunriseTime.shifted(byMinutes: offsets.sunrise),
            dhuhr: dhuhrTime.shifted(byMinutes: offsets.dhuhr),
            asr: asrTime.shifted(byMinutes: offsets.asr),
            maghrib: maghribTime.shifted(byMinutes: offsets.maghrib),
            isha: ishaTime.shifted(byMinutes: offsets.isha)
        )
    }

    /// Sunrise on the day after the given date, needed to measure the length of tonight.
    private static func tomorrowSunrise(
        coordinates: Coordinates, from dateComponents: DateComponents, horizonAltitude: Double
    ) throws -> Date {
        guard let today = utc.date(from: dateComponents),
              let tomorrow = utc.date(byAdding: .day, value: 1, to: today)
        else { throw PolarDayError(coordinates: coordinates, date: dateComponents) }
        let parts = utc.dateComponents([.year, .month, .day], from: tomorrow)
        guard let year = parts.year, let month = parts.month, let day = parts.day else {
            throw PolarDayError(coordinates: coordinates, date: dateComponents)
        }

        let jd = Astronomical.julianDay(year: year, month: month, day: day)
        let solar = SolarCoordinates(julianDay: jd)
        let previousSolar = SolarCoordinates(julianDay: jd - 1)
        let nextSolar = SolarCoordinates(julianDay: jd + 1)
        let m0 = Astronomical.approximateTransit(
            longitude: coordinates.longitude,
            siderealTime: solar.apparentSiderealTime,
            rightAscension: solar.rightAscension
        )
        guard let hours = Astronomical.correctedHourAngle(
            approximateTransit: m0,
            angleAboveHorizon: horizonAltitude,
            coordinates: coordinates,
            afterTransit: false,
            siderealTime: solar.apparentSiderealTime,
            rightAscension: solar.rightAscension,
            previousRightAscension: previousSolar.rightAscension,
            nextRightAscension: nextSolar.rightAscension,
            declination: solar.declination,
            previousDeclination: previousSolar.declination,
            nextDeclination: nextSolar.declination
        ) else { throw PolarDayError(coordinates: coordinates, date: parts) }

        return try instant(from: tomorrow, hoursUT: hours, parts, coordinates)
    }

    /// Sun's altitude when an object's shadow has grown by `shadowLength` times its own height
    /// beyond the shadow it cast at transit.
    private static func asrAltitude(shadowLength: Int, latitude: Double, declination: Double) -> Double {
        let noonShadowAngle = abs(latitude - declination)
        return atan(1.0 / (Double(shadowLength) + tan(noonShadowAngle.degreesToRadians))).radiansToDegrees
    }

    /// Converts hours-from-midnight-UT into an instant, rounded to the nearest minute — the
    /// precision at which prayer times are published. Hours outside `0...24` roll into the
    /// neighbouring day, which is normal far from a zone meridian.
    private static func instant(
        from midnightUT: Date,
        hoursUT: Double,
        _ dateComponents: DateComponents,
        _ coordinates: Coordinates
    ) throws -> Date {
        guard hoursUT.isFinite else {
            throw PolarDayError(coordinates: coordinates, date: dateComponents)
        }
        let minutes = (hoursUT * 60.0).rounded()
        return midnightUT.addingTimeInterval(minutes * 60)
    }
}

private extension Date {
    func shifted(byMinutes minutes: Int) -> Date {
        minutes == 0 ? self : addingTimeInterval(Double(minutes) * 60)
    }
}
