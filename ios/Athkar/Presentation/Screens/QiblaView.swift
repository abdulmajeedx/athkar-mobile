import SwiftUI

/// Within this many degrees the phone is treated as pointing at the qibla.
private let alignmentToleranceDegrees = 4.0

/// The qibla as a fixed bearing, a live compass, a spirit level, and a way to check the compass
/// against the sun.
///
/// The bearing alone is useful without a magnetometer, so the parts are independent: losing the
/// sensor degrades the screen instead of emptying it.
struct QiblaView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @EnvironmentObject private var location: LocationProvider
    @StateObject private var motion = MotionProvider()
    @Environment(\.colorScheme) private var scheme
    @Environment(\.skyPhase) private var sky

    @State private var showSighting = false
    @State private var now = Date()

    /// The sun moves about a degree every four minutes; twenty seconds is far finer than needed.
    private let clock = Timer.publish(every: 20, on: .main, in: .common).autoconnect()

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
        .onAppear {
            location.startCompass()
            motion.start()
        }
        .onDisappear {
            location.stopCompass()
            motion.stop()
        }
        .onReceive(clock) { now = $0 }
        .sheet(isPresented: $showSighting) {
            if let coordinates = preferences.place?.coordinates {
                SightingSheet(
                    coordinates: coordinates,
                    isLevel: motion.isLevel,
                    rawHeading: location.heading
                ) { calibration in
                    preferences.setCalibration(calibration)
                    showSighting = false
                }
            }
        }
    }

    // MARK: - Derived state

    /// The correction in force, or nil when the compass is running raw.
    ///
    /// A correction that has expired, or that was taken somewhere else, is worse than none — it
    /// adds an error the raw reading did not have — so it is dropped here rather than where it is
    /// applied, and the screen simply shows an uncalibrated compass.
    private func calibration(at coordinates: Coordinates) -> CompassCalibration? {
        guard let stored = preferences.calibration else { return nil }
        return stored.isStale(at: now, near: coordinates) ? nil : stored
    }

    private func content(for coordinates: Coordinates) -> some View {
        let bearing = Qibla.direction(from: coordinates)
        let correction = calibration(at: coordinates)
        let heading = location.heading.map { correction?.correct($0) ?? $0 }
        let offAngle = heading.map { shortestDelta(to: bearing, from: $0) }
        let isPointing = offAngle.map { abs($0) <= alignmentToleranceDegrees } ?? false
        // Pointing the right way is only half of it: the reading itself is only dependable while
        // the phone is flat, so the qibla is confirmed only when the level agrees.
        let isAligned = isPointing && motion.isLevel

        return ScrollView {
            VStack(spacing: 16) {
                Text(preferences.place?.name ?? "")
                    .font(.headline)
                    .foregroundStyle(.secondary)

                CompassDial(
                    rotation: heading.map { -$0 } ?? 0,
                    qiblaBearing: bearing,
                    isAligned: isAligned,
                    isLevel: motion.isLevel,
                    showsLevel: motion.isAvailable,
                    pitch: motion.pitchDegrees,
                    roll: motion.rollDegrees,
                    sky: sky
                )
                .frame(maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .animation(.easeOut(duration: 0.18), value: heading)

                bearingCard(
                    coordinates: coordinates,
                    bearing: bearing,
                    heading: heading,
                    offAngle: offAngle,
                    isPointing: isPointing,
                    isAligned: isAligned
                )

                calibrationCard(coordinates: coordinates, correction: correction)
            }
            .padding(16)
        }
    }

    private func bearingCard(
        coordinates: Coordinates,
        bearing: Double,
        heading: Double?,
        offAngle: Double?,
        isPointing: Bool,
        isAligned: Bool
    ) -> some View {
        VStack(spacing: 8) {
            Text(Formatting.bearing(bearing))
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(Theme.primary(scheme))
            Text("اتجاه القبلة من الشمال الحقيقي")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("المسافة إلى الكعبة: \(Formatting.distanceKm(Qibla.distanceKm(from: coordinates)))")
                .font(.callout)

            Text(statusText(heading: heading, offAngle: offAngle, isPointing: isPointing, isAligned: isAligned))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(isAligned ? Theme.gold(scheme) : Color.primary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                .accessibilityAddTraits(.updatesFrequently)

            if location.hasCompass, heading != nil {
                accuracyRow
            }

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

    /// The conditions a magnetic bearing depends on, stated rather than assumed.
    ///
    /// A compass that is wrong looks exactly like a compass that is right — it has no way to know.
    /// These are the checks the app *can* run, so it shows them and their verdicts instead of a
    /// single "accuracy" word that means nothing to the person holding the phone.
    private var accuracyRow: some View {
        HStack(spacing: 14) {
            if motion.isAvailable {
                check("مستوٍ", satisfied: motion.isLevel)
            }
            check("مُعايَر", satisfied: !location.needsCalibration)
            if preferences.calibration != nil {
                check("مضبوطة بالشمس", satisfied: true, gold: true)
            }
        }
        .padding(.top, 4)
    }

    private func check(_ label: String, satisfied: Bool, gold: Bool = false) -> some View {
        let colour = gold ? Theme.gold(scheme) : (satisfied ? Theme.primary(scheme) : Color.red)
        return HStack(spacing: 3) {
            Image(systemName: satisfied ? "checkmark" : "xmark")
                .font(.caption2.bold())
            Text(label).font(.caption)
        }
        .foregroundStyle(colour)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(satisfied ? "متحقق" : "غير متحقق")")
    }

    /// The solar correction: the one thing on this screen that can make the compass *right* rather
    /// than merely report how wrong it might be.
    private func calibrationCard(
        coordinates: Coordinates,
        correction: CompassCalibration?
    ) -> some View {
        let sun = SolarPosition.at(coordinates, now)
        let canSight = CompassCalibration.canSight(sunAltitude: sun.altitude)

        return VStack(alignment: .leading, spacing: 8) {
            if let correction {
                Text("البوصلة مضبوطة \(correction.method.arabicName)")
                    .font(.headline)
                    .foregroundStyle(Theme.gold(scheme))
                Text(
                    "التصحيح \(Formatting.signedDegrees(correction.signedOffset)) · "
                        + Formatting.ago(now.timeIntervalSince(correction.takenAt))
                )
                .font(.callout)
                .monospacedDigit()
                Text(
                    "الدقة المتوقعة نحو \(Int(correction.method.expectedErrorDegrees)) درجات. "
                        + "ينتهي الضبط بعد ست ساعات أو إذا انتقلت إلى مكان آخر."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

                HStack {
                    Button("إعادة الضبط") { showSighting = true }
                        .buttonStyle(.bordered)
                        .disabled(!canSight)
                    Button("إلغاء الضبط") { preferences.setCalibration(nil) }
                        .buttonStyle(.borderless)
                }
            } else {
                Text("اضبط البوصلة بالشمس")
                    .font(.headline)
                    .foregroundStyle(Theme.primary(scheme))
                Text(
                    "الحديد والمغناطيس حولك يزيحان البوصلة عشرات الدرجات وهي تخبرك بالزاوية واثقة. "
                        + "سَمْت الشمس يُحسب فلكيًا لا يزيغ، فقياسٌ واحد عليه يكشف خطأ بوصلتك ويصحّحه."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

                Button("ابدأ الضبط") { showSighting = true }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(!canSight || location.heading == nil)

                if let reason = unavailableReason(sun: sun) {
                    Text(reason)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 22)
                .fill(correction == nil ? Theme.surface(scheme) : Theme.gold(scheme).opacity(0.12))
        )
    }

    /// Why the button is dead, when it is — never a disabled control with no explanation beside it.
    private func unavailableReason(sun: SolarPosition) -> String? {
        if location.heading == nil { return "جارٍ قراءة البوصلة…" }
        if sun.altitude < CompassCalibration.minUsableAltitude {
            return "الشمس تحت الأفق الآن — الضبط متاح في النهار."
        }
        if sun.altitude > CompassCalibration.maxUsableAltitude {
            return "الشمس قريبة من كبد السماء، والظل أقصر من أن يُقاس عليه. "
                + "جرّب بعد ساعتين أو قبل الغروب."
        }
        return nil
    }

    private func statusText(
        heading: Double?,
        offAngle: Double?,
        isPointing: Bool,
        isAligned: Bool
    ) -> String {
        if !location.hasCompass {
            return "لا يوجد حسّاس بوصلة في هذا الجهاز — استعمل الزاوية أعلاه مع بوصلة أخرى."
        }
        if heading == nil { return "جارٍ قراءة البوصلة…" }
        if isAligned { return "أنت تواجه القبلة" }
        // Pointing the right way but leaning: naming that as the one remaining step is the
        // difference between a user who levels the phone and one who decides the app cannot make
        // up its mind.
        if isPointing { return "اتجاهك صحيح — سوِّ الجهاز أفقيًا ليثبت" }
        guard let offAngle else { return "أدِر الجهاز حتى تنطبق العلامة الذهبية على المؤشر" }
        // Which way, and how far: "turn until the marks line up" describes the screen rather than
        // instructing anyone, and leaves the user to discover the direction by turning wrongly.
        let away = Int(abs(offAngle).rounded())
        return "أدِر الجهاز \(offAngle > 0 ? "يمينًا" : "يسارًا") \(degreesLabel(away))"
    }

    /// Signed difference mapped onto (-180, 180].
    private func shortestDelta(to target: Double, from current: Double) -> Double {
        (target - current).truncatingRemainder(dividingBy: 360).closestAngle
    }
}

/// Arabic counts degrees in three forms; "23 درجة" and "3 درجة" are not both right.
func degreesLabel(_ degrees: Int) -> String {
    switch degrees {
    case 1: return "درجة"
    case 2: return "درجتين"
    case 3...10: return "\(degrees) درجات"
    default: return "\(degrees) درجة"
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
    let isLevel: Bool
    let showsLevel: Bool
    let pitch: Double
    let roll: Double
    let sky: SkyPhase

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let radius = size / 2
            let ringRadius = radius * 0.86
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)

            ZStack {
                Circle()
                    .fill(sky.gradient)
                    .frame(width: ringRadius * 2.1, height: ringRadius * 2.1)

                Circle()
                    .stroke(Color.white.opacity(0.25), lineWidth: 2)
                    .frame(width: ringRadius * 2, height: ringRadius * 2)

                // Fixed pointer at the top: the direction the phone itself is facing.
                Path { path in
                    path.move(to: CGPoint(x: center.x, y: center.y - radius * 0.99))
                    path.addLine(to: CGPoint(x: center.x, y: center.y - radius * 0.88))
                }
                .stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))

                dial(center: center, radius: radius, ringRadius: ringRadius)
                    .rotationEffect(.degrees(rotation), anchor: .center)

                if showsLevel {
                    level(center: center, radius: radius)
                }
            }
        }
    }

    private func dial(center: CGPoint, radius: CGFloat, ringRadius: CGFloat) -> some View {
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
                        Color.white.opacity(isMajor ? 0.75 : 0.35),
                        lineWidth: isMajor ? 3 : 1.5
                    )
                }
            }

            ForEach(Cardinal.all) { cardinal in
                Text(cardinal.label)
                    .font(.headline)
                    .foregroundStyle(cardinal.angle == 0 ? Color(hex: 0xFF8A80) : .white.opacity(0.9))
                    .position(point(center, cardinal.angle, ringRadius - radius * 0.20))
            }

            // The needle, tapered to a point: a line of even width reads as a radius rather than
            // as a pointer.
            Path { path in
                let tip = point(center, qiblaBearing, ringRadius * 0.88)
                let left = point(center, qiblaBearing + 90, radius * 0.05)
                let right = point(center, qiblaBearing - 90, radius * 0.05)
                path.move(to: tip)
                path.addLine(to: left)
                path.addLine(to: right)
                path.closeSubpath()
            }
            .fill(markerColor)

            // The Kaaba at the end of the needle: the dial already says where, this says what is
            // there, which is the only reason anyone is holding the phone out.
            kaaba
                .frame(width: radius * 0.13, height: radius * 0.13)
                .position(point(center, qiblaBearing, ringRadius * 0.99))
        }
    }

    private var kaaba: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 2).fill(Color(hex: 0x14110E))
            Rectangle()
                .fill(markerColor)
                .frame(height: 3)
                .offset(y: -2)
        }
    }

    /// A spirit level where the hub of the compass used to be.
    ///
    /// The hub was a dot that said nothing. This is the one thing a magnetic compass needs from its
    /// user and never asks for — almost nobody has been told that a tilted phone reads a tilted
    /// field — and a bubble does not have to be explained.
    ///
    /// The bubble floats to the *raised* side, as one in glass would: a positive pitch means the
    /// top edge has come up, so the bubble runs toward the top of the screen, and a positive roll
    /// drops the right edge, so it runs right to left.
    private func level(center: CGPoint, radius: CGFloat) -> some View {
        let well = radius * 0.17
        let bubbleRadius = radius * 0.06
        let travel = well - bubbleRadius
        let scale = MotionProvider.fullScaleDegrees
        let clampedPitch = max(-scale, min(scale, pitch))
        let clampedRoll = max(-scale, min(scale, roll))

        return ZStack {
            Circle()
                .fill(Color.black.opacity(0.35))
                .frame(width: well * 2, height: well * 2)
            Circle()
                .stroke(isLevel ? sky.accent : Color.white.opacity(0.35), lineWidth: radius * 0.012)
                .frame(width: well * 2, height: well * 2)
            Circle()
                .fill(isLevel ? sky.accent : Color.white.opacity(0.75))
                .frame(width: bubbleRadius * 2, height: bubbleRadius * 2)
                .offset(
                    x: -(clampedRoll / scale) * travel,
                    y: -(clampedPitch / scale) * travel
                )
                .animation(.easeOut(duration: 0.16), value: pitch)
                .animation(.easeOut(duration: 0.16), value: roll)
        }
        .position(center)
    }

    private var markerColor: Color {
        isAligned ? Theme.goldBright : sky.accent
    }

    /// Point at `degrees` clockwise from screen-up — the compass convention, which is 90 degrees
    /// off the mathematical one and clockwise rather than anticlockwise.
    private func point(_ center: CGPoint, _ degrees: Double, _ distance: CGFloat) -> CGPoint {
        let radians = (degrees - 90) * .pi / 180
        return CGPoint(
            x: center.x + distance * cos(radians),
            y: center.y + distance * sin(radians)
        )
    }
}

/// Taking the sighting.
///
/// The shadow is offered first and recommended, and not only because it is the more accurate of the
/// two: it is the one that does not ask anybody to point a phone at the sun and look along it. The
/// warning on the other method is there for the same reason, and is not decoration.
private struct SightingSheet: View {

    let coordinates: Coordinates
    let isLevel: Bool
    let rawHeading: Double?
    let onSight: (CompassCalibration) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var method: SightingMethod = .shadow
    @State private var now = Date()

    private let clock = Timer.publish(every: 10, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("الطريقة", selection: $method) {
                        Text("بالظل (موصى به)").tag(SightingMethod.shadow)
                        Text("بالشمس").tag(SightingMethod.sun)
                    }
                    .pickerStyle(.segmented)
                }

                Section("الخطوات") {
                    Text(instructions)
                    if method == .sun {
                        Text("لا تنظر إلى الشمس مباشرة. وجّه الجهاز نحو جهتها دون أن ترمقها ببصرك.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    let sun = SolarPosition.at(coordinates, now)
                    LabeledContent("سَمْت الشمس الآن", value: Formatting.degrees(sun.azimuth))
                    LabeledContent("ارتفاعها عن الأفق", value: Formatting.degrees(sun.altitude))
                    Text("الدقة المتوقعة بعد الضبط: نحو \(degreesLabel(Int(method.expectedErrorDegrees))).")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if !isLevel {
                        Text("الجهاز مائل الآن — سوِّه حتى تستقرّ فقاعة الميزان، ثم ثبّت.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button("ثبّت الآن", action: sight)
                        .frame(maxWidth: .infinity)
                        .disabled(!canSight)
                }
            }
            .navigationTitle("ضبط البوصلة بالشمس")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
            .onReceive(clock) { now = $0 }
        }
    }

    private var instructions: String {
        switch method {
        case .shadow:
            return """
            ١) ضع الجهاز على أرض مستوية تصلها الشمس.
            ٢) أقِم شيئًا مستقيمًا بجانبه: قلمًا أو عصًا.
            ٣) أدِر الجهاز حتى ينطبق طرفه الأعلى على الظلّ الممتدّ بعيدًا عن الشمس.
            ٤) اضغط «ثبّت الآن» وأنت ممسك به ثابتًا.
            """
        case .sun:
            return """
            ١) أمسك الجهاز مستويًا أفقيًا كالصينية.
            ٢) أدِر طرفه الأعلى نحو جهة الشمس — جهتها على الأرض، لا قرصها في السماء.
            ٣) اضغط «ثبّت الآن» وهو ثابت.
            """
        }
    }

    private var canSight: Bool {
        guard rawHeading != nil, isLevel else { return false }
        return CompassCalibration.canSight(sunAltitude: SolarPosition.at(coordinates, Date()).altitude)
    }

    /// Everything hangs on the instant, so the sun's azimuth is recomputed here rather than read
    /// from the ticking readout — the reference has to be the moment of the tap — and the heading
    /// used is the raw one, or a previous correction would be measured into the new one.
    private func sight() {
        guard let rawHeading else { return }
        let moment = Date()
        let sun = SolarPosition.at(coordinates, moment)
        guard CompassCalibration.canSight(sunAltitude: sun.altitude) else { return }
        onSight(
            CompassCalibration.fromSighting(
                method: method,
                sunAzimuth: sun.azimuth,
                rawHeading: rawHeading,
                takenAt: moment,
                at: coordinates
            )
        )
    }
}
