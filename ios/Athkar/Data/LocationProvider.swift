import Foundation
import CoreLocation
import Combine

/// One-shot position fixes and a live compass heading.
///
/// `CLHeading.trueHeading` is used rather than `magneticHeading`: magnetic north is up to ~25
/// degrees from true north depending on where you are, which is far more than a qibla tolerates,
/// and CoreLocation already applies the local declination for us. It is only available while
/// location updates are authorised, which is why the heading and the fix share one manager.
final class LocationProvider: NSObject, ObservableObject {

    @Published private(set) var isLocating = false
    @Published private(set) var errorMessage: String?
    /// Degrees clockwise from true north, or nil when there is no usable compass yet.
    @Published private(set) var heading: Double?
    /// True when the device reports its heading accuracy as unusable.
    @Published private(set) var needsCalibration = false

    private let manager = CLLocationManager()
    private var onFix: ((Place) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        manager.headingFilter = 1
    }

    var hasCompass: Bool { CLLocationManager.headingAvailable() }

    /// Asks for a single fix, prompting for permission the first time.
    func requestPlace(completion: @escaping (Place) -> Void) {
        onFix = completion
        errorMessage = nil
        isLocating = true

        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            fail("تعذّر تحديد موقعك: صلاحية الموقع مرفوضة. فعّلها من الإعدادات، أو اختر مدينتك يدويًا.")
        default:
            manager.requestLocation()
        }
    }

    func startCompass() {
        guard hasCompass else { return }
        manager.startUpdatingHeading()
    }

    func stopCompass() {
        guard hasCompass else { return }
        manager.stopUpdatingHeading()
    }

    private func fail(_ message: String) {
        isLocating = false
        onFix = nil
        errorMessage = message
    }

    func dismissError() { errorMessage = nil }
}

extension LocationProvider: CLLocationManagerDelegate {

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            // The prompt is answered asynchronously, so the pending request resumes here.
            if isLocating { manager.requestLocation() }
        case .denied, .restricted:
            if isLocating {
                fail("تعذّر تحديد موقعك: صلاحية الموقع مرفوضة. فعّلها من الإعدادات، أو اختر مدينتك يدويًا.")
            }
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last,
              let coordinates = Coordinates(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
              )
        else {
            fail("تعذّر تحديد موقعك. حاول مرة أخرى، أو اختر مدينتك يدويًا.")
            return
        }

        isLocating = false
        let place = Place(
            latitude: coordinates.latitude,
            longitude: coordinates.longitude,
            name: Cities.name(for: coordinates),
            isAutomatic: true
        )
        onFix?(place)
        onFix = nil
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        fail("تعذّر تحديد موقعك. تأكد من تفعيل خدمة الموقع، أو اختر مدينتك يدويًا.")
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // A negative accuracy means the reading is unusable until the device is re-calibrated.
        needsCalibration = newHeading.headingAccuracy < 0
        heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : nil
    }
}
