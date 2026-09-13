import SwiftUI

@main
struct AthkarApp: App {

    @StateObject private var preferences = PreferencesStore()
    @StateObject private var location = LocationProvider()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(preferences)
                .environmentObject(location)
                // The UI is Arabic on every screen, so the direction is pinned rather than
                // inherited: a phone set to English would otherwise mirror the layout away from
                // the text it is laying out.
                .environment(\.layoutDirection, .rightToLeft)
        }
        .onChange(of: scenePhase) { phase in
            // iOS gives an app no reliable background moment, so the rolling notification window is
            // rebuilt whenever the app is in front of the user.
            guard phase == .active else { return }
            Task { await PrayerNotificationScheduler.reschedule(with: preferences) }
        }
    }
}

/// The five tabs, and the hour they are all painted in.
///
/// The sky is computed once here rather than in each screen, from the user's own prayer times, so
/// the header of the adhkar, the face of the compass and the ground of the tasbih are the same hour
/// and all of them turn together at Maghrib.
struct RootView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @State private var now = Date()

    /// The sky moves at the prayers; a minute is finer than it can ever need.
    private let clock = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            AthkarView()
                .tabItem { Label("الأذكار", systemImage: "list.bullet") }

            TasbihView()
                .tabItem { Label("المسبحة", systemImage: "circle.circle") }

            PrayerTimesView()
                .tabItem { Label("الصلاة", systemImage: "clock") }

            QiblaView()
                .tabItem { Label("القبلة", systemImage: "location.north.line") }

            SettingsView()
                .tabItem { Label("الإعدادات", systemImage: "slider.horizontal.3") }
        }
        .environment(\.skyPhase, sky)
        .preferredColorScheme(preferences.theme.colorScheme(sky: sky))
        .onReceive(clock) { now = $0 }
    }

    private var sky: SkyPhase {
        SkyPhase.current(
            coordinates: preferences.place?.coordinates,
            parameters: preferences.calculationParameters,
            at: now
        )
    }
}
