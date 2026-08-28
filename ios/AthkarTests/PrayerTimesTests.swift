import XCTest
@testable import Athkar

/// The same reference values as the Android suite, so a divergence between the two ports shows up
/// as a failing test rather than as two apps quietly disagreeing about when Fajr is.
final class PrayerTimesTests: XCTestCase {

    private func localTime(_ date: Date, zone: String) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    private var raleigh: Coordinates { Coordinates(latitude: 35.7750, longitude: -78.6336)! }
    private var makkah: Coordinates { Coordinates(latitude: 21.4225, longitude: 39.8262)! }

    func testMatchesPublishedTimesForRaleighUnderISNAAndHanafi() throws {
        var parameters = CalculationMethod.northAmerica.parameters
        parameters.madhab = .hanafi

        let times = try PrayerTimes.calculate(
            coordinates: raleigh, year: 2015, month: 7, day: 12, parameters: parameters
        )
        let zone = "America/New_York"

        XCTAssertEqual(localTime(times.fajr, zone: zone), "04:42")
        XCTAssertEqual(localTime(times.sunrise, zone: zone), "06:08")
        XCTAssertEqual(localTime(times.dhuhr, zone: zone), "13:21")
        XCTAssertEqual(localTime(times.asr, zone: zone), "18:22")
        XCTAssertEqual(localTime(times.maghrib, zone: zone), "20:32")
        XCTAssertEqual(localTime(times.isha, zone: zone), "21:57")
    }

    func testHanafiAsrFallsAfterShafiAsr() throws {
        var shafiParameters = CalculationMethod.northAmerica.parameters
        shafiParameters.madhab = .shafi
        var hanafiParameters = CalculationMethod.northAmerica.parameters
        hanafiParameters.madhab = .hanafi

        let shafi = try PrayerTimes.calculate(
            coordinates: raleigh, year: 2015, month: 7, day: 12, parameters: shafiParameters
        )
        let hanafi = try PrayerTimes.calculate(
            coordinates: raleigh, year: 2015, month: 7, day: 12, parameters: hanafiParameters
        )

        XCTAssertEqual(localTime(hanafi.asr, zone: "America/New_York"), "18:22")
        XCTAssertGreaterThan(hanafi.asr, shafi.asr)
        // Only Asr moves with the madhab; the other five are fixed by the sun alone.
        XCTAssertEqual(shafi.fajr, hanafi.fajr)
        XCTAssertEqual(shafi.maghrib, hanafi.maghrib)
    }

    func testUmmAlQuraPlacesIshaExactlyNinetyMinutesAfterMaghrib() throws {
        let times = try PrayerTimes.calculate(
            coordinates: makkah, year: 2024, month: 3, day: 15,
            parameters: CalculationMethod.ummAlQura.parameters
        )
        XCTAssertEqual(times.isha.timeIntervalSince(times.maghrib), 90 * 60, accuracy: 1)
    }

    func testTimesAreStrictlyOrderedThroughTheYear() throws {
        let riyadh = Coordinates(latitude: 24.7136, longitude: 46.6753)!
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var date = calendar.date(from: DateComponents(year: 2024, month: 1, day: 1))!

        for _ in 0..<366 {
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            let times = try PrayerTimes.calculate(
                coordinates: riyadh,
                year: parts.year!, month: parts.month!, day: parts.day!,
                parameters: CalculationMethod.ummAlQura.parameters
            )
            XCTAssertLessThan(times.fajr, times.sunrise, "fajr before sunrise on \(parts)")
            XCTAssertLessThan(times.sunrise, times.dhuhr, "sunrise before dhuhr on \(parts)")
            XCTAssertLessThan(times.dhuhr, times.asr, "dhuhr before asr on \(parts)")
            XCTAssertLessThan(times.asr, times.maghrib, "asr before maghrib on \(parts)")
            XCTAssertLessThan(times.maghrib, times.isha, "maghrib before isha on \(parts)")
            date = calendar.date(byAdding: .day, value: 1, to: date)!
        }
    }

    func testHighLatitudeTwilightThatNeverOccursIsClamped() throws {
        // Oslo in June: the sun sets, but never descends 18 degrees below the horizon, so the
        // angle-based Fajr and Isha do not exist and the night-portion rule has to supply them.
        var parameters = CalculationMethod.muslimWorldLeague.parameters
        parameters.highLatitudeRule = .twilightAngle

        let times = try PrayerTimes.calculate(
            coordinates: Coordinates(latitude: 59.9139, longitude: 10.7522)!,
            year: 2024, month: 6, day: 21,
            parameters: parameters
        )
        XCTAssertLessThan(times.fajr, times.sunrise)
        XCTAssertGreaterThan(times.isha, times.maghrib)
    }

    func testPolarDayIsReportedRatherThanSilentlyWrong() {
        XCTAssertThrowsError(
            try PrayerTimes.calculate(
                coordinates: Coordinates(latitude: 78.2232, longitude: 15.6267)!, // Longyearbyen
                year: 2024, month: 6, day: 21,
                parameters: CalculationMethod.muslimWorldLeague.parameters
            )
        )
    }

    func testCurrentAndNextPrayerTrackTheClock() throws {
        let times = try PrayerTimes.calculate(
            coordinates: makkah, year: 2024, month: 3, day: 15,
            parameters: CalculationMethod.ummAlQura.parameters
        )

        XCTAssertNil(times.currentPrayer(at: times.fajr.addingTimeInterval(-1)))
        XCTAssertEqual(times.nextPrayer(at: times.fajr.addingTimeInterval(-1)), .fajr)
        XCTAssertEqual(times.currentPrayer(at: times.fajr), .fajr)
        XCTAssertEqual(times.currentPrayer(at: times.asr.addingTimeInterval(-1)), .dhuhr)
        XCTAssertEqual(times.currentPrayer(at: times.isha.addingTimeInterval(1)), .isha)
        XCTAssertNil(times.nextPrayer(at: times.isha.addingTimeInterval(1)))
    }

    func testElevationBringsSunriseForwardAndPushesSunsetBack() throws {
        let atSeaLevel = try PrayerTimes.calculate(
            coordinates: makkah, year: 2024, month: 3, day: 15,
            parameters: CalculationMethod.ummAlQura.parameters, elevationMeters: 0
        )
        let onAMountain = try PrayerTimes.calculate(
            coordinates: makkah, year: 2024, month: 3, day: 15,
            parameters: CalculationMethod.ummAlQura.parameters, elevationMeters: 2000
        )
        XCTAssertLessThan(onAMountain.sunrise, atSeaLevel.sunrise)
        XCTAssertGreaterThan(onAMountain.maghrib, atSeaLevel.maghrib)
    }
}
