import SwiftUI

/// The day's schedule and the countdown to the next prayer.
///
/// The settings that move both used to sit under the list, so reaching the times meant scrolling
/// past five of them; they are in the settings tab now and this screen shows the day and nothing
/// else.
struct PrayerTimesView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @EnvironmentObject private var location: LocationProvider
    @Environment(\.colorScheme) private var scheme
    @Environment(\.skyPhase) private var sky

    @State private var now = Date()
    @State private var showCityPicker = false

    /// One second is the resolution of the countdown; nothing else on the screen depends on it.
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Group {
                if preferences.place == nil {
                    placePrompt
                } else {
                    schedule
                }
            }
            .background(Theme.background(scheme))
            .navigationTitle("مواقيت الصلاة")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onReceive(ticker) { now = $0 }
        .sheet(isPresented: $showCityPicker) {
            CityPickerView { place in
                preferences.setPlace(place)
                showCityPicker = false
            }
        }
        .alert(
            "تعذّر تحديد الموقع",
            isPresented: Binding(
                get: { location.errorMessage != nil },
                set: { if !$0 { location.dismissError() } }
            )
        ) {
            Button("حسنًا", role: .cancel) { location.dismissError() }
        } message: {
            Text(location.errorMessage ?? "")
        }
    }

    // MARK: - States

    private var placePrompt: some View {
        VStack(spacing: 16) {
            Image(systemName: "location.circle")
                .font(.system(size: 44))
                .foregroundStyle(Theme.primary(scheme))
            Text("أين أنت؟").font(.title2.bold())
            Text("مواقيت الصلاة واتجاه القبلة يُحسبان من موقعك. لن تُعرض مواقيت قبل تحديده حتى لا تكون خاطئة.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button {
                location.requestPlace { preferences.setPlace($0) }
            } label: {
                if location.isLocating {
                    ProgressView()
                } else {
                    Label("تحديد موقعي", systemImage: "location.fill")
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(location.isLocating)

            Button("اختيار مدينة") { showCityPicker = true }
                .buttonStyle(.bordered)
        }
        .padding(32)
    }

    private var schedule: some View {
        ScrollView {
            VStack(spacing: 16) {
                hero
                if let times = todaysTimes {
                    prayerList(times)
                } else {
                    Text("الشمس لا تشرق ولا تغرب في هذا الموقع اليوم، فلا يمكن حساب المواقيت. اختر أقرب مدينة تحتها بخط عرض أدنى.")
                        .font(.callout)
                        .foregroundStyle(.red)
                        .padding(16)
                }
            }
            .padding(16)
        }
    }

    private var hero: some View {
        VStack(spacing: 6) {
            Button {
                showCityPicker = true
            } label: {
                Label(preferences.place?.name ?? "اختر موقعًا", systemImage: "mappin.and.ellipse")
                    .font(.headline)
                    .foregroundStyle(.white)
            }

            Text(Formatting.hijriDate(now)).font(.title3.bold()).foregroundStyle(.white)
            Text(Formatting.gregorianDate(now)).font(.caption).foregroundStyle(.white.opacity(0.7))

            Divider().overlay(Color.white.opacity(0.2)).padding(.vertical, 12)

            if let next = nextPrayer {
                Text("الصلاة القادمة").font(.caption).foregroundStyle(.white.opacity(0.7))
                Text(next.prayer.arabicName)
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(sky.accent)
                Text(Formatting.time(next.at)).font(.headline).foregroundStyle(.white)
                Text(Formatting.countdown(next.at.timeIntervalSince(now)))
                    .font(.title3)
                    .foregroundStyle(.white.opacity(0.9))
                    .monospacedDigit()
            } else {
                Text("انقضت صلوات اليوم").font(.headline).foregroundStyle(.white)
            }

            currentPrayerBand
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        // The sky of the prayer that has begun. An app that tells the time by the sun and stays
        // one colour all day is throwing away the most obvious thing it knows.
        .background(sky.gradient)
        .clipShape(RoundedRectangle(cornerRadius: 28))
        .animation(.easeInOut(duration: 0.9), value: sky)
    }

    /// What is happening *now*, under the countdown to what is next.
    ///
    /// Between the adhan and the iqama this is the only number that matters, so it takes the
    /// emphasis; once the iqama has passed it becomes the time elapsed since the call, which is the
    /// difference between "I still have a moment" and "I have missed the congregation".
    @ViewBuilder
    private var currentPrayerBand: some View {
        if let times = todaysTimes, let current = times.currentPrayer(at: now) {
            let calledAt = times.time(for: current)
            let iqamaMinutes = current == .sunrise ? 0 : (preferences.iqamaMinutes[current] ?? 0)
            let iqamaAt = calledAt.addingTimeInterval(Double(iqamaMinutes) * 60)

            VStack(spacing: 2) {
                Divider().overlay(Color.white.opacity(0.2)).padding(.vertical, 12)

                if iqamaMinutes > 0, iqamaAt > now {
                    Text("إقامة \(current.arabicName) بعد")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.75))
                    Text(Formatting.countdown(iqamaAt.timeIntervalSince(now)))
                        .font(.title2.bold())
                        .foregroundStyle(sky.accent)
                        .monospacedDigit()
                } else {
                    Text("منذ أذان \(current.arabicName)")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.75))
                    Text(Formatting.countdown(now.timeIntervalSince(calledAt)))
                        .font(.title3)
                        .foregroundStyle(.white.opacity(0.9))
                        .monospacedDigit()
                }
            }
        }
    }

    private func prayerList(_ times: PrayerTimes) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(Prayer.allCases.enumerated()), id: \.element) { index, prayer in
                let at = times.time(for: prayer)
                let isNext = nextPrayer?.prayer == prayer
                let isPast = at <= now

                HStack {
                    Circle()
                        .fill(isNext ? Theme.gold(scheme) : Color.secondary.opacity(0.4))
                        .frame(width: 8, height: 8)
                    Text(prayer.arabicName)
                        .font(isNext ? .headline : .body)
                    Spacer()
                    Text(Formatting.time(at))
                        .font(.headline)
                        .monospacedDigit()
                }
                // Past prayers fade rather than disappear: the schedule stays readable as a whole day.
                .opacity(isPast && !isNext ? 0.45 : 1)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(isNext ? Theme.primary(scheme).opacity(0.12) : .clear)

                if index != Prayer.allCases.count - 1 {
                    Divider().padding(.horizontal, 16)
                }
            }
        }
        .background(Theme.surface(scheme))
        .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    // MARK: - Derived values

    private var todaysTimes: PrayerTimes? {
        guard let coordinates = preferences.place?.coordinates else { return nil }
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: now)
        guard let year = parts.year, let month = parts.month, let day = parts.day else { return nil }
        return try? PrayerTimes.calculate(
            coordinates: coordinates,
            year: year, month: month, day: day,
            parameters: preferences.calculationParameters
        )
    }

    private var nextPrayer: (prayer: Prayer, at: Date)? {
        guard let times = todaysTimes, let prayer = times.nextPrayer(at: now) else { return nil }
        return (prayer, times.time(for: prayer))
    }
}

/// Searchable list of the bundled cities, plus a shortcut to the device's own location.
struct CityPickerView: View {

    let onSelect: (Place) -> Void

    @EnvironmentObject private var location: LocationProvider
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        location.requestPlace { place in
                            onSelect(place)
                            dismiss()
                        }
                    } label: {
                        Label("استخدام موقعي الحالي", systemImage: "location.fill")
                    }
                    .disabled(location.isLocating)
                }

                Section {
                    ForEach(Cities.search(query)) { place in
                        Button(place.name) { onSelect(place) }
                    }
                }
            }
            .searchable(text: $query, prompt: "ابحث عن مدينة")
            .navigationTitle("اختر مدينتك")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إغلاق") { dismiss() }
                }
            }
        }
    }
}
