import XCTest
@testable import Athkar

/// Reference bearings are the great-circle values for each city. A rhumb-line implementation passes
/// for Cairo and is ~40 degrees wrong for Jakarta, so the suite spans both hemispheres.
final class QiblaTests: XCTestCase {

    private let tolerance = 0.01

    private func assertBearing(_ expected: Double, _ latitude: Double, _ longitude: Double, _ name: String) {
        guard let coordinates = Coordinates(latitude: latitude, longitude: longitude) else {
            return XCTFail("\(name): invalid coordinates")
        }
        XCTAssertEqual(Qibla.direction(from: coordinates), expected, accuracy: tolerance, name)
    }

    func testBearingsMatchPublishedGreatCircleValues() {
        assertBearing(56.560, 38.9072, -77.0369, "Washington DC")
        assertBearing(58.481, 40.7128, -74.0059, "New York")
        assertBearing(18.843, 37.7749, -122.4194, "San Francisco")
        assertBearing(350.883, 61.2181, -149.9003, "Anchorage")
        assertBearing(277.499, -33.8688, 151.2093, "Sydney")
        assertBearing(118.987, 51.5074, -0.1278, "London")
        assertBearing(119.163, 48.8566, 2.3522, "Paris")
        assertBearing(139.027, 59.9139, 10.7522, "Oslo")
        assertBearing(255.881, 33.7294, 73.0931, "Islamabad")
        assertBearing(293.020, 35.6895, 139.6917, "Tokyo")
        assertBearing(295.144, -6.18233995, 106.84287, "Jakarta")
    }

    func testPointsDueNorthAndSouthOfTheKaabaReadAsDueSouthAndNorth() {
        let north = Coordinates(latitude: Qibla.makkah.latitude + 10, longitude: Qibla.makkah.longitude)!
        let south = Coordinates(latitude: Qibla.makkah.latitude - 10, longitude: Qibla.makkah.longitude)!
        XCTAssertEqual(Qibla.direction(from: north), 180.0, accuracy: tolerance)
        XCTAssertEqual(Qibla.direction(from: south), 0.0, accuracy: tolerance)
    }

    func testDistanceIsZeroAtTheKaabaAndMatchesKnownSeparations() {
        XCTAssertEqual(Qibla.distanceKm(from: Qibla.makkah), 0.0, accuracy: 0.001)
        XCTAssertEqual(
            Qibla.distanceKm(from: Coordinates(latitude: 24.4672, longitude: 39.6111)!),
            339.0, accuracy: 5.0
        )
        XCTAssertEqual(
            Qibla.distanceKm(from: Coordinates(latitude: 30.0444, longitude: 31.2357)!),
            1288.0, accuracy: 15.0
        )
    }

    func testEveryBearingIsAValidCompassHeading() {
        for latitude in stride(from: -80.0, through: 80.0, by: 10.0) {
            for longitude in stride(from: -180.0, to: 180.0, by: 10.0) {
                guard let coordinates = Coordinates(latitude: latitude, longitude: longitude) else { continue }
                let bearing = Qibla.direction(from: coordinates)
                XCTAssertTrue(bearing >= 0 && bearing < 360, "bearing \(bearing) at \(latitude)/\(longitude)")
            }
        }
    }
}

/// The corpus must actually reach the app bundle; a packaging slip would otherwise show as an empty
/// screen at runtime rather than a failure here.
final class AdhkarLibraryTests: XCTestCase {

    func testBundledCorpusLoads() {
        let library = AdhkarLibrary.shared
        // The same counts the Android suite asserts, against the same file. They moved when the
        // combined morning/evening chapter was split in two, and the iOS bundle kept the old copy
        // for three weeks without anything noticing — hence the byte-for-byte check in CI.
        XCTAssertEqual(library.chapters.count, 133)
        XCTAssertEqual(library.chapters.reduce(0) { $0 + $1.items.count }, 287)
        XCTAssertFalse(library.chapters.contains { $0.title.isEmpty })
        XCTAssertFalse(library.chapters.flatMap(\.items).contains { $0.text.isEmpty })
    }
}
