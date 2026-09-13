import SwiftUI

/// Everything adjustable in the app, in one place.
///
/// It was split: the calculation method and the alerts lived at the bottom of the prayer screen, so
/// reaching the day's times meant scrolling past five settings and reaching the settings meant
/// scrolling past the times, and neither is what either reader came for. The theme had nowhere at
/// all. A tab costs one slot in the bar and ends the hunt.
struct SettingsView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @Environment(\.skyPhase) private var sky
    @Environment(\.colorScheme) private var scheme
    @State private var notificationsDenied = false

    var body: some View {
        NavigationStack {
            Form {
                appearanceSection
                alertsSection
                if preferences.notificationsEnabled {
                    prayersSection
                    timingSection
                }
                calculationSection
                aboutSection
            }
            .navigationTitle("الإعدادات")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - Appearance

    private var appearanceSection: some View {
        Section("المظهر") {
            Picker("المظهر", selection: themeBinding) {
                ForEach(AppTheme.allCases) { theme in
                    Text(theme.label).tag(theme)
                }
            }
            .pickerStyle(.menu)

            Text(preferences.theme.explanation)
                .font(.footnote)
                .foregroundStyle(.secondary)

            if preferences.theme == .byTime {
                skyStrip
                Text("الآن: \(sky.arabicName)")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Theme.primary(scheme))
            }
        }
    }

    /// The day's skies in a row, the one in force marked with its own accent — the theme previewed
    /// rather than described, since "by time" means nothing as a sentence.
    private var skyStrip: some View {
        HStack(spacing: 4) {
            ForEach(SkyPhase.allCases) { phase in
                VStack(spacing: 4) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8).fill(phase.gradient)
                        if phase == sky {
                            Circle().fill(phase.accent).frame(width: 8, height: 8)
                        }
                    }
                    .frame(height: 34)

                    Text(phase.arabicName)
                        .font(.system(size: 9))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(phase == sky ? Color.primary : Color.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - Alerts

    private var alertsSection: some View {
        Section("التنبيهات") {
            Toggle("تنبيه عند دخول الوقت", isOn: notificationsBinding)

            if preferences.notificationsEnabled {
                Toggle(
                    "صوت الأذان",
                    isOn: Binding(
                        get: { preferences.adhanSound },
                        set: { value in
                            preferences.setAdhanSound(value)
                            reschedule()
                        }
                    )
                )
                Text(
                    preferences.adhanSound
                        ? "يُرفع الأذان مع التنبيه. ويقتصر على ثلاثين ثانية، وهو أقصى ما يسمح به "
                            + "نظام iOS لصوت الإشعار."
                        : "يظهر التنبيه بلا صوت."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            if notificationsDenied {
                Text("الإشعارات مرفوضة لهذا التطبيق. فعّلها من إعدادات النظام ليصلك التنبيه.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private var prayersSection: some View {
        Section("الصلوات المنبَّه لها") {
            ForEach(Prayer.allCases) { prayer in
                Toggle(
                    prayer.arabicName,
                    isOn: Binding(
                        get: { preferences.notifiedPrayers.contains(prayer) },
                        set: { _ in
                            preferences.toggleNotified(prayer)
                            reschedule()
                        }
                    )
                )
            }
        }
    }

    private var timingSection: some View {
        Section("قبل الأذان وبعده") {
            Picker("تنبيه قبل الوقت", selection: preAdhanBinding) {
                ForEach(preAdhanChoices, id: \.self) { minutes in
                    Text(minutes == 0 ? "بدون" : "\(minutes) دقيقة").tag(minutes)
                }
            }
            Text("تنبيه خفيف قبل دخول الوقت، للاستعداد أو للحاق بالمسجد.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            // Sunrise has no congregation and so no iqama; offering one would put a countdown on
            // the screen for a prayer nobody is being called to.
            ForEach(Prayer.allCases.filter { $0 != .sunrise }) { prayer in
                Stepper(
                    iqamaLabel(for: prayer),
                    value: Binding(
                        get: { preferences.iqamaMinutes[prayer] ?? 0 },
                        set: { preferences.setIqamaMinutes($0, for: prayer) }
                    ),
                    in: 0...60,
                    step: 5
                )
            }
        }
    }

    private func iqamaLabel(for prayer: Prayer) -> String {
        let minutes = preferences.iqamaMinutes[prayer] ?? 0
        return minutes == 0
            ? "إقامة \(prayer.arabicName): غير محددة"
            : "إقامة \(prayer.arabicName): \(minutes) دقيقة"
    }

    private var preAdhanChoices: [Int] { [0, 5, 10, 15, 20, 30] }

    // MARK: - Calculation

    private var calculationSection: some View {
        Section("حساب المواقيت") {
            Picker("طريقة الحساب", selection: methodBinding) {
                ForEach(CalculationMethod.allCases) { method in
                    Text(method.arabicName).tag(method)
                }
            }
            Picker("وقت العصر", selection: madhabBinding) {
                ForEach(Madhab.allCases, id: \.self) { madhab in
                    Text(madhab.arabicName).tag(madhab)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var aboutSection: some View {
        Section("عن أذكاري") {
            LabeledContent("الإصدار", value: Self.version)
            Text(
                "يعمل التطبيق دون إنترنت: المواقيت والقبلة تُحسبان على جهازك، والأذكار مخزّنة فيه، "
                    + "ولا يُرسل موقعك إلى أي خادم."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }

    private static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    // MARK: - Bindings

    private var themeBinding: Binding<AppTheme> {
        Binding(get: { preferences.theme }, set: { preferences.setTheme($0) })
    }

    private var preAdhanBinding: Binding<Int> {
        Binding(
            get: { preferences.preAdhanMinutes },
            set: { value in
                preferences.setPreAdhanMinutes(value)
                reschedule()
            }
        )
    }

    private var methodBinding: Binding<CalculationMethod> {
        Binding(
            get: { preferences.method },
            set: { value in
                preferences.setMethod(value)
                reschedule()
            }
        )
    }

    private var madhabBinding: Binding<Madhab> {
        Binding(
            get: { preferences.madhab },
            set: { value in
                preferences.setMadhab(value)
                reschedule()
            }
        )
    }

    private var notificationsBinding: Binding<Bool> {
        Binding(
            get: { preferences.notificationsEnabled },
            set: { wanted in
                guard wanted else {
                    preferences.setNotificationsEnabled(false)
                    PrayerNotificationScheduler.cancelAll()
                    return
                }
                Task {
                    // Enabling only on a grant keeps the switch honest: it is never on while the
                    // system is silently dropping every alert.
                    let granted = await PrayerNotificationScheduler.requestAuthorization()
                    await MainActor.run {
                        notificationsDenied = !granted
                        preferences.setNotificationsEnabled(granted)
                    }
                    if granted {
                        await PrayerNotificationScheduler.reschedule(with: preferences)
                    }
                }
            }
        )
    }

    private func reschedule() {
        Task { await PrayerNotificationScheduler.reschedule(with: preferences) }
    }
}
