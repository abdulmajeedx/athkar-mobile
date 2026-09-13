import XCTest
@testable import Athkar

/// The arithmetic behind the solar correction, carrying the same cases as the Android suite.
///
/// Every one of these is a way the app could confidently point someone at the wrong wall, which is
/// the only kind of bug a qibla feature really has. The angles wrap at 360, and the errors that live
/// there — a correction applied across north, a sighting taken facing south — do not announce
/// themselves on screen: they look like a perfectly ordinary bearing.
final class CompassCalibrationTests: XCTestCase {

    private let riyadh = Coordinates(latitude: 24.7136, longitude: 46.6753)!
    private let moment = Date(timeIntervalSince1970: 1_789_000_000)

    private func sighting(
        _ method: SightingMethod = .shadow,
        sunAzimuth: Double,
        rawHeading: Double,
        at coordinates: Coordinates? = nil,
        takenAt: Date? = nil
    ) -> CompassCalibration {
        CompassCalibration.fromSighting(
            method: method,
            sunAzimuth: sunAzimuth,
            rawHeading: rawHeading,
            takenAt: takenAt ?? moment,
            at: coordinates ?? riyadh
        )
    }

    func testACompassReadingLowIsCorrectedUpwardByTheDifference() {
        // The sun is truly at 200; aimed at it, the phone claimed 190. Everything it says is ten
        // degrees light.
        let calibration = sighting(.sun, sunAzimuth: 200, rawHeading: 190)
        XCTAssertEqual(calibration.offsetDegrees, 10, accuracy: 0.001)
        XCTAssertEqual(calibration.correct(190), 200, accuracy: 0.001)
        XCTAssertEqual(calibration.correct(0), 10, accuracy: 0.001)
    }

    func testTheShadowPointsTheOppositeWayFromTheSun() {
        // Sun in the south-east; the shadow of an upright thing runs to the north-west.
        let perfect = sighting(.shadow, sunAzimuth: 120, rawHeading: 300)
        XCTAssertEqual(perfect.offsetDegrees, 0, accuracy: 0.001, "a perfect compass needs no correction")

        let off = sighting(.shadow, sunAzimuth: 120, rawHeading: 290)
        XCTAssertEqual(off.offsetDegrees, 10, accuracy: 0.001)
    }

    func testACorrectionTakenAcrossNorthDoesNotSendTheNeedleTheLongWayRound() {
        // True 5, compass says 355: the offset is ten degrees, not three hundred and fifty.
        let calibration = sighting(.sun, sunAzimuth: 5, rawHeading: 355)
        XCTAssertEqual(calibration.offsetDegrees, 10, accuracy: 0.001)
        XCTAssertEqual(calibration.signedOffset, 10, accuracy: 0.001)
        XCTAssertEqual(calibration.correct(355), 5, accuracy: 0.001)
    }

    func testACompassReadingHighIsReportedAsANegativeTurn() {
        // True 350, compass says 10 — it reads twenty degrees heavy, and saying so as "+340" would
        // be arithmetically right and useless to read.
        let calibration = sighting(.sun, sunAzimuth: 350, rawHeading: 10)
        XCTAssertEqual(calibration.offsetDegrees, 340, accuracy: 0.001)
        XCTAssertEqual(calibration.signedOffset, -20, accuracy: 0.001)
        XCTAssertEqual(calibration.correct(10), 350, accuracy: 0.001)
    }

    func testEveryCorrectedHeadingStaysABearing() {
        let calibration = sighting(.sun, sunAzimuth: 350, rawHeading: 10)
        for raw in 0..<360 {
            let corrected = calibration.correct(Double(raw))
            XCTAssertTrue(corrected >= 0 && corrected < 360, "\(raw) corrected to \(corrected)")
        }
    }

    func testACorrectionExpiresWithTheAfternoon() {
        let calibration = sighting(.shadow, sunAzimuth: 120, rawHeading: 300)
        XCTAssertFalse(calibration.isStale(at: moment.addingTimeInterval(5 * 3600), near: riyadh))
        XCTAssertTrue(calibration.isStale(at: moment.addingTimeInterval(7 * 3600), near: riyadh))
    }

    func testACorrectionDoesNotTravelWithTheUser() {
        let calibration = sighting(.shadow, sunAzimuth: 120, rawHeading: 300)
        // Across the city is still the same magnetic environment as far as this is concerned.
        XCTAssertFalse(
            calibration.isStale(at: moment, near: Coordinates(latitude: 24.78, longitude: 46.72)!)
        )
        // Another city is not. The iron it cancelled is hundreds of kilometres behind.
        XCTAssertTrue(
            calibration.isStale(at: moment, near: Coordinates(latitude: 26.4207, longitude: 50.0888)!)
        )
    }

    func testAnUnknownPositionCannotMakeAFreshCorrectionStale() {
        // The place is still loading; that is not evidence the user has moved.
        let calibration = sighting(.shadow, sunAzimuth: 120, rawHeading: 300)
        XCTAssertFalse(calibration.isStale(at: moment, near: nil))
    }

    func testTheSunIsRefusedWhenItIsDownAndWhenItIsOverhead() {
        XCTAssertFalse(CompassCalibration.canSight(sunAltitude: -4))
        XCTAssertFalse(CompassCalibration.canSight(sunAltitude: 0.5))
        XCTAssertTrue(CompassCalibration.canSight(sunAltitude: 20))
        XCTAssertTrue(CompassCalibration.canSight(sunAltitude: 65))
        XCTAssertFalse(CompassCalibration.canSight(sunAltitude: 78))
    }

    func testTheShadowIsClaimedToBeTheBetterSightingAndTheNumbersSaySo() {
        XCTAssertLessThan(
            SightingMethod.shadow.expectedErrorDegrees,
            SightingMethod.sun.expectedErrorDegrees
        )
    }

    /// The sun's own azimuth, which is the reference everything above is measured against.
    ///
    /// Checked against the prayer times the same model already produces rather than against a
    /// published almanac: at sunrise in Riyadh the sun is by definition in the east, at solar noon
    /// it is due south from a northern-hemisphere latitude, and at sunset it is in the west. A sign
    /// error or a south-versus-north convention slip — the two ways this function can be wrong —
    /// fails all three at once.
    func testSolarAzimuthAgreesWithTheDaysOwnTimings() throws {
        let times = try PrayerTimes.calculate(
            coordinates: riyadh, year: 2026, month: 3, day: 21,
            parameters: CalculationMethod.ummAlQura.parameters
        )

        let atSunrise = SolarPosition.at(riyadh, times.time(for: .sunrise))
        XCTAssertEqual(atSunrise.azimuth, 90, accuracy: 2, "the equinox sun rises due east")
        XCTAssertEqual(atSunrise.altitude, 0, accuracy: 1.5, "and is on the horizon as it does")

        let atNoon = SolarPosition.at(riyadh, times.time(for: .dhuhr))
        XCTAssertEqual(atNoon.azimuth, 180, accuracy: 2, "at midday it stands due south of Riyadh")
        XCTAssertTrue(atNoon.altitude > 50, "high, but nowhere near the zenith at this latitude")

        let atMaghrib = SolarPosition.at(riyadh, times.time(for: .maghrib))
        XCTAssertEqual(atMaghrib.azimuth, 270, accuracy: 2.5, "and sets due west")
    }

    func testTheSunIsBelowTheHorizonAtNight() {
        let midnight = Date(timeIntervalSince1970: 1_774_400_000) // 2026-03-25 00:53 UTC
        XCTAssertFalse(SolarPosition.at(riyadh, midnight).isAboveHorizon)
    }
}
