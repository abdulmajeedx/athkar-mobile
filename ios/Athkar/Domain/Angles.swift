import Foundation

/// Degree/radian plumbing shared by the solar model. Callers work in degrees, the trigonometry
/// works in radians, and every angle that feeds a `cos`/`sin` is normalised exactly once at the
/// boundary so no formula has to guess which convention it received.
extension Double {

    var degreesToRadians: Double { self * (.pi / 180.0) }

    var radiansToDegrees: Double { self * (180.0 / .pi) }

    /// Normalises an angle to `[0, 360)`.
    var unwoundAngle: Double { normalized(withBound: 360.0) }

    /// Normalises to `[0, max)`; the sign-safe modulo the astronomical series expect.
    func normalized(withBound max: Double) -> Double { self - max * (self / max).rounded(.down) }

    /// Maps an angle onto `(-180, 180]` — the shortest signed way round the circle.
    var closestAngle: Double {
        if self >= -180.0 && self <= 180.0 { return self }
        return self - 360.0 * (self / 360.0).rounded()
    }
}
