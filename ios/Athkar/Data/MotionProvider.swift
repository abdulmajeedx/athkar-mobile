import Combine
import CoreMotion
import Foundation

/// How far the phone is from flat.
///
/// `CLHeading` says nothing about attitude, and attitude is what decides whether the heading it
/// reports is worth anything: a magnetometer reads the horizontal component of the field, so a
/// phone held at a reading angle projects that field onto a plane that is not the ground and
/// returns a bearing that is confidently wrong. CoreMotion is the only source of the missing half.
///
/// No permission is involved — the accelerometer and gyroscope are not gated on iOS — and nothing
/// here is recorded or sent anywhere.
final class MotionProvider: NSObject, ObservableObject {

    /// Degrees the top edge of the screen is raised from flat; negative when it is dipped.
    @Published private(set) var pitchDegrees: Double = 0
    /// Degrees the right edge is dipped from flat; negative when it is raised.
    @Published private(set) var rollDegrees: Double = 0

    /// Within this much of flat the heading is as good as the sensor can give.
    ///
    /// Ten degrees rather than two: it has to be a band a hand can hold at arm's length, not a
    /// knife edge that flickers in and out while someone stands as still as a person can.
    static let levelToleranceDegrees = 10.0

    /// The tilt that pushes the level's bubble all the way to the edge of its ring. Twenty rather
    /// than ninety, so the bubble visibly moves for the corrections that actually matter.
    static let fullScaleDegrees = 20.0

    private let manager = CMMotionManager()

    /// True when this device can answer at all.
    ///
    /// Every iPhone can; the simulator cannot, which is the case this exists for. Where the answer
    /// is unavailable the app treats the phone as level rather than blocking the compass behind a
    /// sensor that will never report — an unanswerable question is not a failed check.
    var isAvailable: Bool { manager.isDeviceMotionAvailable }

    /// How far from flat, in any direction.
    var tiltDegrees: Double { (pitchDegrees * pitchDegrees + rollDegrees * rollDegrees).squareRoot() }

    var isLevel: Bool { !isAvailable || tiltDegrees <= Self.levelToleranceDegrees }

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 20.0
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let self, let attitude = motion?.attitude else { return }
            // Rotation about +x (which points right along the screen) takes the top of the device
            // up, and rotation about +y (which points up the screen) takes the right edge down.
            // Both are reported in radians from flat.
            self.pitchDegrees = attitude.pitch * 180 / .pi
            self.rollDegrees = attitude.roll * 180 / .pi
        }
    }

    func stop() {
        guard manager.isDeviceMotionActive else { return }
        manager.stopDeviceMotionUpdates()
    }
}
