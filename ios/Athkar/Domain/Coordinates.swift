import Foundation

/// A geographic position in signed decimal degrees. North and east are positive.
struct Coordinates: Equatable, Codable, Sendable {
    let latitude: Double
    let longitude: Double

    /// Returns nil rather than trapping: these values often arrive from storage or a sensor, and a
    /// bad pair should send the user back to the picker, not crash the app.
    init?(latitude: Double, longitude: Double) {
        guard latitude.isFinite, longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude)
        else { return nil }
        self.latitude = latitude
        self.longitude = longitude
    }
}
