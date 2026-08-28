import SwiftUI

/// Within this many degrees the phone is treated as facing the qibla.
private let alignmentToleranceDegrees = 4.0

/// The qibla as a fixed bearing plus a live compass.
///
/// The bearing alone is useful without a magnetometer, so the two are independent: losing the
/// sensor degrades the screen instead of emptying it.
struct QiblaView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @EnvironmentObject private var location: LocationProvider
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        NavigationStack {
            Group {
                if let coordinates = preferences.place?.coordinates {
                    content(for: coordinates)
                } else {
                    ContentUnavailableViewCompat(
                        title: "حدّد موقعك أولًا",
                        message: "اتجاه القبلة يُحسب من موقعك — حدّده من شاشة «الصلاة»."
                    )
                }
            }
            .background(Theme.background(scheme))
            .navigationTitle("القبلة")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { location.startCompass() }
        .onDisappear { location.stopCompass() }
    }

    private func content(for coordinates: Coordinates) -> some View {
        let bearing = Qibla.direction(from: coordinates)
        let distance = Qibla.distanceKm(from: coordinates)
        let heading = location.heading
        let offAngle = heading.map { shortestDelta(to: bearing, from: $0) }
        let isAligned = offAngle.map { abs($0) <= alignmentToleranceDegrees } ?? false

        return ScrollView {
            VStack(spacing: 16) {
                Text(preferences.place?.name ?? "")
                    .font(.headline)
                    .foregroundStyle(.secondary)

                CompassDial(
                    // The dial turns to keep true north pointing north, so the marker sits at the
                    // qibla bearing within it and the fixed pointer reads the phone's own heading.
                    rotation: heading.map { -$0 } ?? 0,
                    qiblaBearing: bearing,
                    isAligned: isAligned
                )
                .frame(maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .animation(.easeOut(duration: 0.18), value: heading)

                VStack(spacing: 8) {
                    Text(Formatting.bearing(bearing))
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(Theme.primary(scheme))
                    Text("اتجاه القبلة من الشمال الحقيقي")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("المسافة إلى الكعبة: \(Formatting.distanceKm(distance))")
                        .font(.callout)

                    Text(statusText(heading: heading, isAligned: isAligned))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(isAligned ? Theme.gold(scheme) : Color.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)

                    if location.needsCalibration && location.hasCompass {
                        Text("دقّة البوصلة منخفضة — حرّك الجهاز على شكل الرقم 8 لمعايرته.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 22)
                        .fill(isAligned ? Theme.primary(scheme).opacity(0.15) : Theme.surface(scheme))
                )
            }
            .padding(16)
        }
    }

    private func statusText(heading: Double?, isAligned: Bool) -> String {
        if !location.hasCompass {
            return "لا يوجد حسّاس بوصلة في هذا الجهاز — استعمل الزاوية أعلاه مع بوصلة أخرى."
        }
        if heading == nil { return "جارٍ قراءة البوصلة…" }
        return isAligned ? "أنت تواجه القبلة" : "أدِر الجهاز حتى تنطبق العلامة الذهبية على المؤشر"
    }

    /// Signed difference mapped onto (-180, 180].
    private func shortestDelta(to target: Double, from current: Double) -> Double {
        (target - current).truncatingRemainder(dividingBy: 360).closestAngle
    }
}

/// A labelled point on the dial. A struct rather than a tuple because SwiftUI's `ForEach` needs an
/// identity, and Swift has no key paths into tuple elements.
private struct Cardinal: Identifiable {
    let label: String
    let angle: Double

    var id: String { label }

    static let all = [
        Cardinal(label: "ش", angle: 0),
        Cardinal(label: "ق", angle: 90),
        Cardinal(label: "ج", angle: 180),
        Cardinal(label: "غ", angle: 270),
    ]
}

private struct CompassDial: View {

    let rotation: Double
    let qiblaBearing: Double
    let isAligned: Bool

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let radius = size / 2
            let ringRadius = radius * 0.86
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)

            ZStack {
                Circle()
                    .stroke(Color.secondary.opacity(0.3), lineWidth: 2)
                    .frame(width: ringRadius * 2, height: ringRadius * 2)

                // Fixed pointer at the top: the direction the phone itself is facing.
                Path { path in
                    path.move(to: CGPoint(x: center.x, y: center.y - radius * 0.96))
                    path.addLine(to: CGPoint(x: center.x, y: center.y - radius * 0.72))
                }
                .stroke(Theme.primary(scheme), style: StrokeStyle(lineWidth: 4, lineCap: .round))

                Circle()
                    .fill(Theme.primary(scheme))
                    .frame(width: 8, height: 8)
                    .position(center)

                ZStack {
                    ForEach(0..<72, id: \.self) { index in
                        let angle = Double(index) * 5
                        let isMajor = index % 18 == 0
                        let isMinor = index % 6 == 0
                        if isMajor || isMinor {
                            Path { path in
                                let length = isMajor ? radius * 0.10 : radius * 0.05
                                path.move(to: point(center, angle, ringRadius))
                                path.addLine(to: point(center, angle, ringRadius - length))
                            }
                            .stroke(
                                Color.secondary.opacity(isMajor ? 0.9 : 0.45),
                                lineWidth: isMajor ? 3 : 1.5
                            )
                        }
                    }

                    ForEach(Cardinal.all) { cardinal in
                        Text(cardinal.label)
                            .font(.headline)
                            .foregroundStyle(.secondary)
                            .position(point(center, cardinal.angle, ringRadius - radius * 0.20))
                    }

                    Path { path in
                        path.move(to: center)
                        path.addLine(to: point(center, qiblaBearing, ringRadius * 0.78))
                    }
                    .stroke(markerColor, style: StrokeStyle(lineWidth: 6, lineCap: .round))

                    Circle()
                        .fill(markerColor)
                        .frame(width: 20, height: 20)
                        .position(point(center, qiblaBearing, ringRadius * 0.94))
                }
                .rotationEffect(.degrees(rotation), anchor: .center)
            }
        }
    }

    private var markerColor: Color {
        isAligned ? Theme.gold(scheme) : Theme.primary(scheme).opacity(0.7)
    }

    /// Point at `degrees` clockwise from screen-up — the compass convention, which is 90 degrees
    /// off the mathematical one and clockwise rather than anticlockwise.
    private func point(_ center: CGPoint, _ degrees: Double, _ distance: Double) -> CGPoint {
        let radians = (degrees - 90) * .pi / 180
        return CGPoint(
            x: center.x + distance * cos(radians),
            y: center.y + distance * sin(radians)
        )
    }
}
